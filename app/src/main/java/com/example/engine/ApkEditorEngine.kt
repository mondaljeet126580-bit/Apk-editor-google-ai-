package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ApkEntryItem(
  val path: String,
  val size: Long,
  val compressedSize: Long,
  val isDirectory: Boolean,
  val isModified: Boolean = false,
  val isDeleted: Boolean = false,
)

data class ApkEditRequest(
  val manifestPatch: ManifestPatchSpec? = null,
  val newLauncherIcon: Bitmap? = null,
  val fileReplacements: Map<String, ByteArray> = emptyMap(),
  val deletedEntries: Set<String> = emptySet(),
  val outputBaseName: String = "edited_app",
)

data class SavedApkResult(
  val fileName: String,
  val displayPath: String,
  val installableFile: File,
  val installUri: Uri,
  val mediaStoreUri: Uri?,
  val fileSizeBytes: Long,
  val timestampMillis: Long,
)

data class SavedProjectItem(
  val fileName: String,
  val displayPath: String,
  val fileSizeBytes: Long,
  val lastModifiedMillis: Long,
  val localFile: File?,
  val contentUri: Uri,
  val appName: String,
  val packageName: String,
  val icon: Bitmap?,
)

/**
 * Full-featured native Kotlin APK editing, Split APK merging (arm64-v8a / DPI / language splits),
 * resource replacement, strict 4-byte (`resources.arsc`) and 16384-byte (`.so`) ZIP alignment,
 * V1/V2/V3 signing via apksig, and ZArchiver-ready public Download/ApkEditor export engine.
 */
object ApkEditorEngine {
  const val OUTPUT_SUBFOLDER = "ApkEditor"
  const val PUBLIC_DISPLAY_DIR = "Download/ApkEditor"
  private const val RESOURCE_ALIGNMENT_BYTES = 4
  private const val NATIVE_LIB_ALIGNMENT_BYTES = 16384 // 16KB page alignment (satisfies 4KB & 16KB)

  private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var bytesWritten: Long = 0L
      private set

    override fun write(b: Int) {
      out.write(b)
      bytesWritten += 1L
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      bytesWritten += len.toLong()
    }
  }

  /**
   * Parses binary AndroidManifest.xml directly from an APK file and merges
   * resolved resources (like `@string/app_name` and launcher icon) via PackageManager.
   */
  suspend fun inspectApk(
    context: Context,
    apkFile: File,
  ): Pair<ParsedManifestInfo, Bitmap?> =
    withContext(Dispatchers.IO) {
      val manifestBytes =
        ZipFile(apkFile).use { zip ->
          val entry =
            zip.getEntry("AndroidManifest.xml")
              ?: throw IllegalStateException("APK does not contain AndroidManifest.xml")
          zip.getInputStream(entry).use { it.readBytes() }
        }

      var parsed = AxmlParserAndPatcher.parseManifestInfo(manifestBytes)
      var resolvedIcon: Bitmap? = null

      try {
        val pm = context.packageManager
        val pkgInfo =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
              apkFile.absolutePath,
              PackageManager.PackageInfoFlags.of(0),
            )
          } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
          }

        val appInfo = pkgInfo?.applicationInfo
        if (appInfo != null) {
          appInfo.sourceDir = apkFile.absolutePath
          appInfo.publicSourceDir = apkFile.absolutePath
          val resolvedLabel = pm.getApplicationLabel(appInfo).toString()
          if (resolvedLabel.isNotBlank() && (parsed.isLabelResourceRef || parsed.appLabel.isBlank())) {
            parsed = parsed.copy(appLabel = resolvedLabel)
          }
          val drawable = pm.getApplicationIcon(appInfo)
          resolvedIcon = drawableToBitmap(drawable, 192)
        }
      } catch (_: Exception) {
        // Fallback cleanly to AXML values if archive info cannot resolve resources
      }

      if (parsed.appLabel.isBlank()) {
        parsed = parsed.copy(appLabel = parsed.packageName.substringAfterLast('.').ifBlank { "App" })
      }
      parsed to resolvedIcon
    }

  suspend fun listApkEntries(
    apkFile: File,
    splitApkFiles: List<File> = emptyList(),
  ): List<ApkEntryItem> =
    withContext(Dispatchers.IO) {
      val entriesByPath = LinkedHashMap<String, ApkEntryItem>()
      ZipFile(apkFile).use { zip ->
        zip.entries().asSequence().forEach { entry ->
          entriesByPath[entry.name] =
            ApkEntryItem(
              path = entry.name,
              size = entry.size.coerceAtLeast(0L),
              compressedSize = entry.compressedSize.coerceAtLeast(0L),
              isDirectory = entry.isDirectory,
            )
        }
      }
      for (splitFile in splitApkFiles) {
        if (!splitFile.exists() || !splitFile.canRead()) continue
        try {
          ZipFile(splitFile).use { splitZip ->
            splitZip.entries().asSequence().forEach { entry ->
              val name = entry.name
              if (
                name == "AndroidManifest.xml" ||
                  name == "resources.arsc" ||
                  isSignatureEntry(name) ||
                  name in entriesByPath
              ) {
                return@forEach
              }
              entriesByPath[name] =
                ApkEntryItem(
                  path = name,
                  size = entry.size.coerceAtLeast(0L),
                  compressedSize = entry.compressedSize.coerceAtLeast(0L),
                  isDirectory = entry.isDirectory,
                )
            }
          }
        } catch (_: Exception) {
        }
      }
      entriesByPath.values.sortedWith(compareBy<ApkEntryItem> { it.path.lowercase(Locale.ROOT) })
    }

  suspend fun extractEntryBytes(
    apkFile: File,
    entryPath: String,
    splitApkFiles: List<File> = emptyList(),
  ): ByteArray? =
    withContext(Dispatchers.IO) {
      ZipFile(apkFile).use { zip ->
        val entry = zip.getEntry(entryPath)
        if (entry != null) return@withContext zip.getInputStream(entry).use { it.readBytes() }
      }
      for (splitFile in splitApkFiles) {
        if (!splitFile.exists() || !splitFile.canRead()) continue
        try {
          ZipFile(splitFile).use { zip ->
            val entry = zip.getEntry(entryPath)
            if (entry != null) return@withContext zip.getInputStream(entry).use { it.readBytes() }
          }
        } catch (_: Exception) {
        }
      }
      null
    }

  /**
   * Merges Split APKs (arm64-v8a libraries + DPI/language/feature resources),
   * patches AndroidManifest.xml (cloning authorities/permissions & disabling split flags),
   * replaces launcher icons & custom files, strips old signatures, aligns resources.arsc (4B)
   * and all `.so` native libraries (16KB / 16384B) as STORED entries, signs with V1+V2+V3
   * via apksig strictly after alignment, and saves to Download/ApkEditor/.
   */
  suspend fun buildSignAndExportApk(
    context: Context,
    sourceApkFile: File,
    splitApkFiles: List<File> = emptyList(),
    request: ApkEditRequest,
    signingOptions: SigningOptions = SigningOptions(),
    onProgress: (String) -> Unit = {},
  ): SavedApkResult =
    withContext(Dispatchers.IO) {
      val workDir = File(context.cacheDir, "apk_build_work").apply { mkdirs() }
      val unsignedAlignedApk = File(workDir, "unsigned_aligned_${System.currentTimeMillis()}.apk")
      val signedTempApk = File(workDir, "signed_${System.currentTimeMillis()}.apk")

      try {
        val validSplits = splitApkFiles.filter { it.exists() && it.canRead() && it.absolutePath != sourceApkFile.absolutePath }
        if (validSplits.isNotEmpty()) {
          onProgress("Merging base APK with ${validSplits.size} split APK(s) & arm64-v8a libraries...")
        } else {
          onProgress("Patching AndroidManifest.xml & aligning resources...")
        }

        repackAndAlignApk(
          sourceApkFile = sourceApkFile,
          splitApkFiles = validSplits,
          outputUnsignedApk = unsignedAlignedApk,
          request = request,
          onProgress = onProgress,
        )

        onProgress("Signing aligned APK (V1 + V2 + V3 via apksig)...")
        ApkSignerHelper.signApk(
          context = context,
          inputUnsignedApk = unsignedAlignedApk,
          outputSignedApk = signedTempApk,
          options = signingOptions,
        )

        onProgress("Saving to $PUBLIC_DISPLAY_DIR/...")
        val safeBase =
          request.outputBaseName
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "ApkEditor" }
            .take(36)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalFileName = "${safeBase}_edited_${stamp}.apk"

        // Save an app-accessible installable copy for reliable FileProvider Install/Share on Vivo Funtouch OS & all OEMs
        val projectsDir = File(context.getExternalFilesDir("projects"), "").apply { mkdirs() }
        val localCopy = File(projectsDir, finalFileName)
        signedTempApk.copyTo(localCopy, overwrite = true)

        // Export directly to public Download/ApkEditor/ so ZArchiver sees it immediately
        val mediaUri = exportToPublicDownloads(context, signedTempApk, finalFileName)
        val installUri =
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localCopy)

        SavedApkResult(
          fileName = finalFileName,
          displayPath = "$PUBLIC_DISPLAY_DIR/$finalFileName",
          installableFile = localCopy,
          installUri = installUri,
          mediaStoreUri = mediaUri,
          fileSizeBytes = localCopy.length(),
          timestampMillis = System.currentTimeMillis(),
        )
      } finally {
        unsignedAlignedApk.delete()
        signedTempApk.delete()
      }
    }

  internal fun repackAndAlignApk(
    sourceApkFile: File,
    splitApkFiles: List<File>,
    outputUnsignedApk: File,
    request: ApkEditRequest,
    onProgress: (String) -> Unit = {},
  ) {
    val iconCache = mutableMapOf<Int, ByteArray>()

    fun encodeScaledIconPng(targetPx: Int): ByteArray {
      return iconCache.getOrPut(targetPx) {
        val src = request.newLauncherIcon!!
        val scaled = Bitmap.createScaledBitmap(src, targetPx, targetPx, true)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        if (scaled !== src) scaled.recycle()
        baos.toByteArray()
      }
    }

    // Detect if any native .so libraries exist across base + split APKs
    var hasNativeLibs = false
    val existingDexIndices = HashSet<Int>()

    ZipFile(sourceApkFile).use { baseZip ->
      val baseEntries = baseZip.entries().toList()
      for (e in baseEntries) {
        if (e.name.endsWith(".so", ignoreCase = true)) hasNativeLibs = true
        parseDexIndex(e.name)?.let { existingDexIndices.add(it) }
      }
      if (!hasNativeLibs) {
        for (sf in splitApkFiles) {
          try {
            ZipFile(sf).use { sz ->
              if (sz.entries().asSequence().any { it.name.endsWith(".so", ignoreCase = true) }) {
                hasNativeLibs = true
              }
            }
          } catch (_: Exception) {
          }
        }
      }

      // Always strip split flags when merging splits or when any manifest patch is requested
      val effectiveManifestPatch: ManifestPatchSpec? =
        when {
          request.manifestPatch != null ->
            request.manifestPatch.copy(
              stripSplitFlags = true,
              ensureExtractNativeLibsFalse = hasNativeLibs,
            )
          splitApkFiles.isNotEmpty() ->
            ManifestPatchSpec(
              stripSplitFlags = true,
              ensureExtractNativeLibsFalse = hasNativeLibs,
            )
          else -> null
        }

      val countingStream =
        CountingOutputStream(BufferedOutputStream(FileOutputStream(outputUnsignedApk), 64 * 1024))
      ZipOutputStream(countingStream).use { zos ->
        zos.setLevel(6)
        val writtenPaths = HashSet<String>()

        // 1. Write all entries from base.apk (stripping old signatures & applying patches/replacements)
        for ((index, entry) in baseEntries.withIndex()) {
          val path = entry.name
          if (index % 40 == 0) {
            onProgress("Repacking base APK (${index + 1}/${baseEntries.size})...")
          }

          if (isSignatureEntry(path)) continue
          if (path in request.deletedEntries) continue

          if (entry.isDirectory) {
            if (writtenPaths.add(path)) {
              zos.putNextEntry(ZipEntry(path).apply { time = 0L })
              zos.closeEntry()
            }
            continue
          }

          val replacedBytes: ByteArray? =
            when {
              path in request.fileReplacements -> request.fileReplacements[path]
              path == "AndroidManifest.xml" && effectiveManifestPatch != null -> {
                val origManifest = baseZip.getInputStream(entry).use { it.readBytes() }
                AxmlParserAndPatcher.patch(origManifest, effectiveManifestPatch)
              }
              request.newLauncherIcon != null && isLauncherIconRasterEntry(path) -> {
                encodeScaledIconPng(targetIconSizeForPath(path))
              }
              else -> null
            }

          writtenPaths.add(path)
          writeZipEntryAligned(
            zos = zos,
            countingStream = countingStream,
            path = path,
            originalEntry = entry,
            overrideBytes = replacedBytes,
            readOriginalBytes = { baseZip.getInputStream(entry).use { it.readBytes() } },
            streamOriginalToZos = { baseZip.getInputStream(entry).use { input -> input.copyTo(zos, 32 * 1024) } },
          )
        }

        // 2. Merge all split APKs (arm64-v8a / ABI native libs, DPI & language resources, assets, feature dexes)
        // Prioritize ABI splits first (so arm64-v8a native libs are guaranteed to be included)
        val orderedSplits =
          splitApkFiles.sortedBy { file ->
            val n = file.name.lowercase(Locale.ROOT)
            when {
              n.contains("arm64") -> 0
              n.contains("armeabi") || n.contains("x86") -> 1
              n.contains("dpi") -> 2
              else -> 3
            }
          }

        for ((splitIdx, splitFile) in orderedSplits.withIndex()) {
          onProgress("Merging split ${splitIdx + 1}/${orderedSplits.size}: ${splitFile.name}...")
          ZipFile(splitFile).use { splitZip ->
            val splitEntries = splitZip.entries().toList()
            for (entry in splitEntries) {
              val rawPath = entry.name
              if (isSignatureEntry(rawPath)) continue
              if (rawPath == "AndroidManifest.xml" || rawPath == "resources.arsc") continue
              if (entry.isDirectory) continue

              // Assign next free classesN.dex name if a feature split ships its own DEX
              val targetPath =
                if (rawPath.matches(Regex("^classes\\d*\\.dex$")) && rawPath in writtenPaths) {
                  val nextIdx = allocateNextDexIndex(existingDexIndices)
                  if (nextIdx == 1) "classes.dex" else "classes$nextIdx.dex"
                } else {
                  rawPath
                }

              if (targetPath in request.deletedEntries) continue
              if (targetPath in writtenPaths) continue

              parseDexIndex(targetPath)?.let { existingDexIndices.add(it) }

              val replacedBytes: ByteArray? =
                when {
                  targetPath in request.fileReplacements -> request.fileReplacements[targetPath]
                  request.newLauncherIcon != null && isLauncherIconRasterEntry(targetPath) -> {
                    encodeScaledIconPng(targetIconSizeForPath(targetPath))
                  }
                  else -> null
                }

              writtenPaths.add(targetPath)
              writeZipEntryAligned(
                zos = zos,
                countingStream = countingStream,
                path = targetPath,
                originalEntry = entry,
                overrideBytes = replacedBytes,
                readOriginalBytes = { splitZip.getInputStream(entry).use { it.readBytes() } },
                streamOriginalToZos = { splitZip.getInputStream(entry).use { input -> input.copyTo(zos, 32 * 1024) } },
              )
            }
          }
        }

        // 3. Write any brand-new replacement files not originally present in base or splits
        for ((newPath, newBytes) in request.fileReplacements) {
          if (newPath !in writtenPaths && newPath !in request.deletedEntries && !isSignatureEntry(newPath)) {
            writtenPaths.add(newPath)
            val isResourcesArsc = newPath == "resources.arsc"
            val isNativeSo = newPath.endsWith(".so", ignoreCase = true)
            if (isResourcesArsc || isNativeSo) {
              val alignment = if (isNativeSo) NATIVE_LIB_ALIGNMENT_BYTES else RESOURCE_ALIGNMENT_BYTES
              writeAlignedStoredEntry(zos, countingStream, newPath, newBytes, alignment)
            } else {
              val outEntry =
                ZipEntry(newPath).apply {
                  method = ZipEntry.DEFLATED
                  time = 0L
                }
              zos.putNextEntry(outEntry)
              zos.write(newBytes)
              zos.closeEntry()
            }
          }
        }
      }
    }
  }

  private fun writeZipEntryAligned(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    path: String,
    originalEntry: ZipEntry,
    overrideBytes: ByteArray?,
    readOriginalBytes: () -> ByteArray,
    streamOriginalToZos: () -> Unit,
  ) {
    val isResourcesArsc = path == "resources.arsc"
    val isNativeSo = path.endsWith(".so", ignoreCase = true)
    val mustStoreAligned = isResourcesArsc || isNativeSo || originalEntry.method == ZipEntry.STORED

    if (mustStoreAligned) {
      val dataBytes = overrideBytes ?: readOriginalBytes()
      val alignment = if (isNativeSo) NATIVE_LIB_ALIGNMENT_BYTES else RESOURCE_ALIGNMENT_BYTES
      writeAlignedStoredEntry(zos, countingStream, path, dataBytes, alignment)
    } else {
      val outEntry =
        ZipEntry(path).apply {
          method = ZipEntry.DEFLATED
          time = 0L
        }
      zos.putNextEntry(outEntry)
      if (overrideBytes != null) {
        zos.write(overrideBytes)
      } else {
        streamOriginalToZos()
      }
      zos.closeEntry()
    }
  }

  private fun writeAlignedStoredEntry(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    entryName: String,
    data: ByteArray,
    alignment: Int,
  ) {
    zos.flush()
    val currentOffset = countingStream.bytesWritten
    val nameBytesLen = entryName.toByteArray(Charsets.UTF_8).size
    // Local file header fixed size is 30 bytes + nameLen + extraLen
    val headerBase = currentOffset + 30L + nameBytesLen.toLong()
    val remainder = (headerBase % alignment).toInt()
    val paddingLen = if (remainder == 0) 0 else (alignment - remainder)

    val crc = CRC32().apply { update(data) }.value
    val storedEntry =
      ZipEntry(entryName).apply {
        method = ZipEntry.STORED
        time = 0L
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        this.crc = crc
        if (paddingLen > 0) {
          extra = ByteArray(paddingLen)
        }
      }

    zos.putNextEntry(storedEntry)
    zos.write(data)
    zos.closeEntry()
    zos.flush()
  }

  private fun parseDexIndex(path: String): Int? {
    if (path == "classes.dex") return 1
    val match = Regex("^classes(\\d+)\\.dex$").matchEntire(path) ?: return null
    return match.groupValues[1].toIntOrNull()
  }

  private fun allocateNextDexIndex(existing: MutableSet<Int>): Int {
    var idx = 2
    while (idx in existing) idx++
    existing.add(idx)
    return idx
  }

  internal fun isSignatureEntry(path: String): Boolean {
    if (path.equals("stamp-cert-sha256", ignoreCase = true)) return true
    if (!path.startsWith("META-INF/", ignoreCase = true)) return false
    val fileName = path.substringAfterLast('/')
    if (fileName.equals("MANIFEST.MF", ignoreCase = true)) return true
    if (fileName.endsWith(".SF", ignoreCase = true)) return true
    if (fileName.endsWith(".RSA", ignoreCase = true)) return true
    if (fileName.endsWith(".DSA", ignoreCase = true)) return true
    if (fileName.endsWith(".EC", ignoreCase = true)) return true
    if (fileName.startsWith("SIG-", ignoreCase = true)) return true
    return false
  }

  private fun isLauncherIconRasterEntry(path: String): Boolean {
    val lower = path.lowercase(Locale.ROOT)
    val isRaster = lower.endsWith(".png") || lower.endsWith(".webp")
    if (!isRaster) return false
    val isResDir = lower.startsWith("res/mipmap") || lower.startsWith("res/drawable")
    if (!isResDir) return false
    val fileName = lower.substringAfterLast('/')
    return fileName.contains("ic_launcher") ||
      fileName.contains("app_icon") ||
      fileName == "icon.png" ||
      fileName == "icon.webp"
  }

  private fun targetIconSizeForPath(path: String): Int {
    val lower = path.lowercase(Locale.ROOT)
    return when {
      lower.contains("xxxhdpi") -> 192
      lower.contains("xxhdpi") -> 144
      lower.contains("xhdpi") -> 96
      lower.contains("hdpi") -> 72
      lower.contains("mdpi") -> 48
      lower.contains("ldpi") -> 36
      else -> 192
    }
  }

  private fun exportToPublicDownloads(context: Context, signedApk: File, fileName: String): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, fileName)
          put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
          put(
            MediaStore.Downloads.RELATIVE_PATH,
            "${Environment.DIRECTORY_DOWNLOADS}/$OUTPUT_SUBFOLDER",
          )
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
      val resolver = context.contentResolver
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      if (uri != null) {
        resolver.openOutputStream(uri)?.use { out ->
          FileInputStream(signedApk).use { input -> input.copyTo(out, 64 * 1024) }
        }
        val completeValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, completeValues, null, null)
      }
      uri
    } else {
      @Suppress("DEPRECATION")
      val downloadsRoot =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      val outDir = File(downloadsRoot, OUTPUT_SUBFOLDER).apply { mkdirs() }
      val destFile = File(outDir, fileName)
      signedApk.copyTo(destFile, overwrite = true)
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
    }
  }

  /**
   * Scans saved edited APKs from Download/ApkEditor (via MediaStore on API 29+ or direct file on API < 29)
   * merged with app's projects directory so Install & Share always work seamlessly.
   */
  suspend fun listSavedProjects(context: Context): List<SavedProjectItem> =
    withContext(Dispatchers.IO) {
      val itemsByName = LinkedHashMap<String, SavedProjectItem>()

      val localDir = File(context.getExternalFilesDir("projects"), "")
      if (localDir.exists()) {
        localDir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
          ?.sortedByDescending { it.lastModified() }
          ?.forEach { file ->
            val (label, pkg, icon) = readQuickApkInfo(context, file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            itemsByName[file.name] =
              SavedProjectItem(
                fileName = file.name,
                displayPath = "$PUBLIC_DISPLAY_DIR/${file.name}",
                fileSizeBytes = file.length(),
                lastModifiedMillis = file.lastModified(),
                localFile = file,
                contentUri = uri,
                appName = label,
                packageName = pkg,
                icon = icon,
              )
          }
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
          val projection =
            arrayOf(
              MediaStore.Downloads._ID,
              MediaStore.Downloads.DISPLAY_NAME,
              MediaStore.Downloads.SIZE,
              MediaStore.Downloads.DATE_MODIFIED,
              MediaStore.Downloads.RELATIVE_PATH,
            )
          val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
          val selectionArgs = arrayOf("%$OUTPUT_SUBFOLDER%")
          context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
          )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

            while (cursor.moveToNext()) {
              val name = cursor.getString(nameCol) ?: continue
              if (!name.endsWith(".apk", ignoreCase = true)) continue
              if (name in itemsByName) continue
              val id = cursor.getLong(idCol)
              val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
              val size = cursor.getLong(sizeCol)
              val modMs = cursor.getLong(modCol) * 1000L
              itemsByName[name] =
                SavedProjectItem(
                  fileName = name,
                  displayPath = "$PUBLIC_DISPLAY_DIR/$name",
                  fileSizeBytes = size,
                  lastModifiedMillis = modMs,
                  localFile = null,
                  contentUri = uri,
                  appName = name.removeSuffix(".apk"),
                  packageName = "Download/ApkEditor",
                  icon = null,
                )
            }
          }
        } catch (_: Exception) {
        }
      } else {
        @Suppress("DEPRECATION")
        val legacyDir =
          File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OUTPUT_SUBFOLDER)
        legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }
          ?.forEach { file ->
            if (file.name !in itemsByName) {
              val (label, pkg, icon) = readQuickApkInfo(context, file)
              val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
              itemsByName[file.name] =
                SavedProjectItem(
                  fileName = file.name,
                  displayPath = "$PUBLIC_DISPLAY_DIR/${file.name}",
                  fileSizeBytes = file.length(),
                  lastModifiedMillis = file.lastModified(),
                  localFile = file,
                  contentUri = uri,
                  appName = label,
                  packageName = pkg,
                  icon = icon,
                )
            }
          }
      }

      itemsByName.values.sortedByDescending { it.lastModifiedMillis }
    }

  suspend fun deleteSavedProject(context: Context, item: SavedProjectItem): Boolean =
    withContext(Dispatchers.IO) {
      var deleted = false
      item.localFile?.let { if (it.exists()) deleted = it.delete() || deleted }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
          val rows =
            context.contentResolver.delete(
              MediaStore.Downloads.EXTERNAL_CONTENT_URI,
              "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
              arrayOf(item.fileName, "%$OUTPUT_SUBFOLDER%"),
            )
          if (rows > 0) deleted = true
        } catch (_: Exception) {
        }
      } else {
        @Suppress("DEPRECATION")
        val legacyFile =
          File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OUTPUT_SUBFOLDER),
            item.fileName,
          )
        if (legacyFile.exists()) deleted = legacyFile.delete() || deleted
      }
      deleted
    }

  private fun readQuickApkInfo(context: Context, apkFile: File): Triple<String, String, Bitmap?> {
    return try {
      val pm = context.packageManager
      val pkgInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION")
          pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
      val appInfo = pkgInfo?.applicationInfo
      if (appInfo != null) {
        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath
        val label = pm.getApplicationLabel(appInfo).toString()
        val icon = drawableToBitmap(pm.getApplicationIcon(appInfo), 128)
        Triple(label, pkgInfo.packageName, icon)
      } else {
        Triple(apkFile.nameWithoutExtension, "APK Package", null)
      }
    } catch (_: Exception) {
      Triple(apkFile.nameWithoutExtension, "APK Package", null)
    }
  }

  fun drawableToBitmap(drawable: Drawable, targetSizePx: Int = 128): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
      return drawable.bitmap
    }
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetSizePx
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetSizePx
    val w = width.coerceAtMost(targetSizePx)
    val h = height.coerceAtMost(targetSizePx)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
  }
}

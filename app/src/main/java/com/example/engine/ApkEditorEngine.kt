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
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
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
 * size-optimized repacking (Deflater.BEST_COMPRESSION level 9 for DEX/assets/XML/native .so
 * when android:extractNativeLibs="true" is verified, with safe 16KB STORED fallback),
 * strict 4-byte aligned STORED resources.arsc, unused ABI stripping, V1/V2/V3 signing via apksig,
 * and ZArchiver-ready public Download/ApkEditor export engine.
 */
object ApkEditorEngine {
  const val OUTPUT_SUBFOLDER = "ApkEditor"
  const val PUBLIC_DISPLAY_DIR = "Download/ApkEditor"
  private const val RESOURCE_ALIGNMENT_BYTES = 4
  private const val NATIVE_LIB_ALIGNMENT_BYTES = 16384 // 16KB page alignment fallback

  private val ALREADY_COMPRESSED_MEDIA_EXTENSIONS =
    setOf(
      "webp",
      "jpg",
      "jpeg",
      "mp4",
      "ogg",
      "mp3",
      "wav",
      "m4a",
      "aac",
      "flac",
      "webm",
      "mkv",
      "gif",
      "gz",
      "zip",
      "xz",
      "br",
      "7z",
    )

  private val UNUSED_NON_ARM64_ABIS =
    setOf(
      "armeabi-v7a",
      "armeabi",
      "x86",
      "x86_64",
      "mips",
      "mips64",
      "riscv64",
    )

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
        if (isUnusedAbiSplitFile(splitFile)) continue
        try {
          ZipFile(splitFile).use { splitZip ->
            splitZip.entries().asSequence().forEach { entry ->
              val name = entry.name
              if (
                name == "AndroidManifest.xml" ||
                  name == "resources.arsc" ||
                  isExcludedMetadataOrSignature(name) ||
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
   * Merges Split APKs (keeping arm64-v8a libraries + DPI/language/feature resources and dropping
   * unused CPU architectures), patches AndroidManifest.xml (cloning authorities/permissions,
   * disabling split flags, and safely setting android:extractNativeLibs="true"), compresses all
   * compressible entries including DEX/assets/XML and `.so` libraries at Deflater.BEST_COMPRESSION
   * (level 9), keeps resources.arsc STORED + 4-byte aligned, signs with V1+V2+V3 via apksig
   * strictly after repacking, and saves to Download/ApkEditor/.
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
        val validSplits =
          splitApkFiles.filter {
            it.exists() &&
              it.canRead() &&
              it.absolutePath != sourceApkFile.absolutePath &&
              !isUnusedAbiSplitFile(it)
          }
        if (validSplits.isNotEmpty()) {
          onProgress("Merging base APK with ${validSplits.size} arm64/resource split(s)...")
        } else {
          onProgress("Patching AndroidManifest.xml & optimizing compression...")
        }

        repackAndAlignApk(
          sourceApkFile = sourceApkFile,
          splitApkFiles = validSplits,
          outputUnsignedApk = unsignedAlignedApk,
          request = request,
          onProgress = onProgress,
        )

        onProgress("Signing optimized APK (V1 + V2 + V3 via apksig)...")
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

    // Filter out split APKs that exclusively target unused non-arm64 CPU architectures
    val keptSplits =
      splitApkFiles.filter {
        it.exists() && it.canRead() && it.absolutePath != sourceApkFile.absolutePath && !isUnusedAbiSplitFile(it)
      }

    // Pre-scan base + splits to detect native libraries, arm64-v8a availability, and DEX indices
    var hasNativeLibs = false
    var hasArm64Libs = false
    val existingDexIndices = HashSet<Int>()

    ZipFile(sourceApkFile).use { baseZip ->
      val baseEntries = baseZip.entries().toList()
      for (e in baseEntries) {
        val name = e.name
        if (name.endsWith(".so", ignoreCase = true)) {
          hasNativeLibs = true
          if (name.startsWith("lib/arm64-v8a/", ignoreCase = true)) hasArm64Libs = true
        }
        parseDexIndex(name)?.let { existingDexIndices.add(it) }
      }
      for (sf in keptSplits) {
        try {
          ZipFile(sf).use { sz ->
            for (e in sz.entries().asSequence()) {
              val name = e.name
              if (name.endsWith(".so", ignoreCase = true)) {
                hasNativeLibs = true
                if (name.startsWith("lib/arm64-v8a/", ignoreCase = true)) hasArm64Libs = true
              }
            }
          }
        } catch (_: Exception) {
        }
      }

      // Prepare & verify patched AndroidManifest.xml BEFORE writing entries so we know with 100%
      // certainty whether android:extractNativeLibs="true" is safely applied (enabling Level 9 .so compression)
      val manifestEntry = baseZip.getEntry("AndroidManifest.xml")
      val rawOriginalManifest =
        request.fileReplacements["AndroidManifest.xml"]
          ?: manifestEntry?.let { baseZip.getInputStream(it).use { s -> s.readBytes() } }

      var finalManifestBytes: ByteArray? = rawOriginalManifest
      var canCompressNativeLibs = false

      if (rawOriginalManifest != null) {
        val baseSpec =
          (request.manifestPatch ?: ManifestPatchSpec()).copy(
            stripSplitFlags = true,
            stripUseEmbeddedDex = true,
          )
        if (hasNativeLibs) {
          // Attempt to safely set android:extractNativeLibs="true" and verify roundtrip integrity
          val candidateResult =
            runCatching {
              val origParsed = AxmlParserAndPatcher.parseManifestInfo(rawOriginalManifest)
              val patched =
                AxmlParserAndPatcher.patch(
                  rawOriginalManifest,
                  baseSpec.copy(extractNativeLibsOverride = true),
                )
              val reparsed = AxmlParserAndPatcher.parseManifestInfo(patched)
              val expectedPkg = baseSpec.packageName?.trim()?.ifBlank { null } ?: origParsed.packageName
              val intact =
                reparsed.extractNativeLibs == true &&
                  (expectedPkg.isBlank() || reparsed.packageName == expectedPkg)
              if (intact) patched else null
            }.getOrNull()

          if (candidateResult != null) {
            finalManifestBytes = candidateResult
            canCompressNativeLibs = true
          } else {
            // Safe fallback: keep .so STORED with 16KB alignment and apply user's manifest edits only
            canCompressNativeLibs = false
            finalManifestBytes =
              if (request.manifestPatch != null || keptSplits.isNotEmpty()) {
                AxmlParserAndPatcher.patch(
                  rawOriginalManifest,
                  baseSpec.copy(extractNativeLibsOverride = null),
                )
              } else {
                rawOriginalManifest
              }
          }
        } else if (request.manifestPatch != null || keptSplits.isNotEmpty()) {
          finalManifestBytes = AxmlParserAndPatcher.patch(rawOriginalManifest, baseSpec)
        }
      }

      val countingStream =
        CountingOutputStream(BufferedOutputStream(FileOutputStream(outputUnsignedApk), 64 * 1024))
      ZipOutputStream(countingStream).use { zos ->
        zos.setLevel(Deflater.BEST_COMPRESSION)
        val writtenPaths = HashSet<String>()

        // 1. Write all entries from base.apk
        for ((index, entry) in baseEntries.withIndex()) {
          val path = entry.name
          if (index % 40 == 0) {
            onProgress("Optimizing base APK (${index + 1}/${baseEntries.size})...")
          }

          if (isExcludedMetadataOrSignature(path)) continue
          if (path in request.deletedEntries) continue
          if (shouldSkipUnusedAbiEntry(path, hasArm64Libs)) continue

          if (entry.isDirectory) continue

          val replacedBytes: ByteArray? =
            when {
              path == "AndroidManifest.xml" -> finalManifestBytes
              path in request.fileReplacements -> request.fileReplacements[path]
              request.newLauncherIcon != null && isLauncherIconRasterEntry(path) -> {
                encodeScaledIconPng(targetIconSizeForPath(path))
              }
              else -> null
            }

          if (!writtenPaths.add(path)) continue
          writeOptimizedZipEntry(
            zos = zos,
            countingStream = countingStream,
            path = path,
            originalEntry = entry,
            overrideBytes = replacedBytes,
            canCompressNativeLibs = canCompressNativeLibs,
            openOriginalStream = { baseZip.getInputStream(entry) },
          )
        }

        // 2. Merge kept split APKs (arm64-v8a native libs, DPI & language resources, assets, feature dexes)
        val orderedSplits =
          keptSplits.sortedBy { file ->
            val n = file.name.lowercase(Locale.ROOT)
            when {
              n.contains("arm64") -> 0
              n.contains("dpi") -> 1
              else -> 2
            }
          }

        for ((splitIdx, splitFile) in orderedSplits.withIndex()) {
          onProgress("Merging split ${splitIdx + 1}/${orderedSplits.size}: ${splitFile.name}...")
          ZipFile(splitFile).use { splitZip ->
            val splitEntries = splitZip.entries().toList()
            for (entry in splitEntries) {
              val rawPath = entry.name
              if (isExcludedMetadataOrSignature(rawPath)) continue
              if (rawPath == "AndroidManifest.xml" || rawPath == "resources.arsc") continue
              if (entry.isDirectory) continue
              if (shouldSkipUnusedAbiEntry(rawPath, hasArm64Libs)) continue

              val targetPath =
                if (rawPath.matches(Regex("^classes\\d*\\.dex$")) && rawPath in writtenPaths) {
                  val nextIdx = allocateNextDexIndex(existingDexIndices)
                  if (nextIdx == 1) "classes.dex" else "classes$nextIdx.dex"
                } else {
                  rawPath
                }

              if (targetPath in request.deletedEntries) continue
              if (!writtenPaths.add(targetPath)) continue

              parseDexIndex(targetPath)?.let { existingDexIndices.add(it) }

              val replacedBytes: ByteArray? =
                when {
                  targetPath in request.fileReplacements -> request.fileReplacements[targetPath]
                  request.newLauncherIcon != null && isLauncherIconRasterEntry(targetPath) -> {
                    encodeScaledIconPng(targetIconSizeForPath(targetPath))
                  }
                  else -> null
                }

              writeOptimizedZipEntry(
                zos = zos,
                countingStream = countingStream,
                path = targetPath,
                originalEntry = entry,
                overrideBytes = replacedBytes,
                canCompressNativeLibs = canCompressNativeLibs,
                openOriginalStream = { splitZip.getInputStream(entry) },
              )
            }
          }
        }

        // 3. Write any brand-new replacement files not originally present in base or splits
        for ((newPath, newBytes) in request.fileReplacements) {
          if (
            newPath !in writtenPaths &&
              newPath !in request.deletedEntries &&
              !isExcludedMetadataOrSignature(newPath) &&
              !shouldSkipUnusedAbiEntry(newPath, hasArm64Libs)
          ) {
            writtenPaths.add(newPath)
            val isResourcesArsc = newPath == "resources.arsc"
            val isNativeSo = newPath.endsWith(".so", ignoreCase = true)
            val isMedia = isAlreadyCompressedMedia(newPath)
            if (isResourcesArsc || isMedia || (isNativeSo && !canCompressNativeLibs)) {
              val alignment = if (isNativeSo) NATIVE_LIB_ALIGNMENT_BYTES else RESOURCE_ALIGNMENT_BYTES
              writeAlignedStoredBytes(zos, countingStream, newPath, newBytes, alignment)
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

  private fun writeOptimizedZipEntry(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    path: String,
    originalEntry: ZipEntry,
    overrideBytes: ByteArray?,
    canCompressNativeLibs: Boolean,
    openOriginalStream: () -> InputStream,
  ) {
    val isResourcesArsc = path == "resources.arsc"
    val isNativeSo = path.endsWith(".so", ignoreCase = true)
    val isMedia = isAlreadyCompressedMedia(path)
    val isAlreadyCompressedPng =
      path.endsWith(".png", ignoreCase = true) && originalEntry.method == ZipEntry.STORED

    val shouldStoreUncompressed =
      when {
        isResourcesArsc -> true
        isNativeSo -> !canCompressNativeLibs
        isMedia -> true
        isAlreadyCompressedPng -> true
        else -> false
      }

    if (shouldStoreUncompressed) {
      val alignment = if (isNativeSo) NATIVE_LIB_ALIGNMENT_BYTES else RESOURCE_ALIGNMENT_BYTES
      if (overrideBytes != null) {
        writeAlignedStoredBytes(zos, countingStream, path, overrideBytes, alignment)
      } else {
        writeAlignedStoredStream(
          zos = zos,
          countingStream = countingStream,
          entryName = path,
          originalEntry = originalEntry,
          alignment = alignment,
          openStream = openOriginalStream,
        )
      }
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
        openOriginalStream().use { input -> input.copyTo(zos, 64 * 1024) }
      }
      zos.closeEntry()
    }
  }

  private fun writeAlignedStoredBytes(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    entryName: String,
    data: ByteArray,
    alignment: Int,
  ) {
    val crc = CRC32().apply { update(data) }.value
    putAlignedStoredEntryHeader(
      zos = zos,
      countingStream = countingStream,
      entryName = entryName,
      size = data.size.toLong(),
      crc = crc,
      alignment = alignment,
    )
    zos.write(data)
    zos.closeEntry()
    zos.flush()
  }

  /**
   * Streams large STORED entries (such as resources.arsc, media files, or fallback .so libraries)
   * without buffering the entire payload in heap memory.
   */
  private fun writeAlignedStoredStream(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    entryName: String,
    originalEntry: ZipEntry,
    alignment: Int,
    openStream: () -> InputStream,
  ) {
    val size: Long
    val crc: Long
    if (originalEntry.size >= 0L && originalEntry.crc >= 0L) {
      size = originalEntry.size
      crc = originalEntry.crc
    } else {
      val crcCalc = CRC32()
      var total = 0L
      val buf = ByteArray(64 * 1024)
      openStream().use { input ->
        while (true) {
          val read = input.read(buf)
          if (read <= 0) break
          crcCalc.update(buf, 0, read)
          total += read.toLong()
        }
      }
      size = total
      crc = crcCalc.value
    }

    putAlignedStoredEntryHeader(
      zos = zos,
      countingStream = countingStream,
      entryName = entryName,
      size = size,
      crc = crc,
      alignment = alignment,
    )
    openStream().use { input -> input.copyTo(zos, 64 * 1024) }
    zos.closeEntry()
    zos.flush()
  }

  private fun putAlignedStoredEntryHeader(
    zos: ZipOutputStream,
    countingStream: CountingOutputStream,
    entryName: String,
    size: Long,
    crc: Long,
    alignment: Int,
  ) {
    zos.flush()
    val currentOffset = countingStream.bytesWritten
    val nameBytesLen = entryName.toByteArray(Charsets.UTF_8).size
    // Local file header fixed size is 30 bytes + nameLen + extraLen
    val headerBase = currentOffset + 30L + nameBytesLen.toLong()
    val remainder = (headerBase % alignment).toInt()
    val paddingLen = if (remainder == 0) 0 else (alignment - remainder)

    val storedEntry =
      ZipEntry(entryName).apply {
        method = ZipEntry.STORED
        time = 0L
        this.size = size
        this.compressedSize = size
        this.crc = crc
        if (paddingLen > 0) {
          extra = ByteArray(paddingLen)
        }
      }
    zos.putNextEntry(storedEntry)
  }

  internal fun isAlreadyCompressedMedia(path: String): Boolean {
    val dotIdx = path.lastIndexOf('.')
    if (dotIdx < 0 || dotIdx == path.lastIndex) return false
    val ext = path.substring(dotIdx + 1).lowercase(Locale.ROOT)
    return ext in ALREADY_COMPRESSED_MEDIA_EXTENSIONS
  }

  internal fun isUnusedAbiSplitFile(file: File): Boolean {
    val name = file.name.lowercase(Locale.ROOT)
    if (name.contains("arm64")) return false
    return name.contains("armeabi") ||
      name.contains("x86_64") ||
      name.contains("x86") ||
      name.contains("mips") ||
      name.contains("riscv")
  }

  internal fun shouldSkipUnusedAbiEntry(path: String, hasArm64Libs: Boolean): Boolean {
    if (!path.startsWith("lib/", ignoreCase = true)) return false
    val rest = path.substring(4)
    val slashIdx = rest.indexOf('/')
    if (slashIdx <= 0) return false
    val abi = rest.substring(0, slashIdx).lowercase(Locale.ROOT)
    if (abi == "arm64-v8a") return false
    // Always drop non-ARM architectures (x86, x86_64, mips, riscv)
    if (abi in UNUSED_NON_ARM64_ABIS && abi != "armeabi-v7a" && abi != "armeabi") return true
    // Drop 32-bit ARM when arm64-v8a native libraries exist (all modern apps on Vivo T2x 5G)
    if (hasArm64Libs && (abi == "armeabi-v7a" || abi == "armeabi")) return true
    return false
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

  internal fun isExcludedMetadataOrSignature(path: String): Boolean {
    if (path.equals("stamp-cert-sha256", ignoreCase = true)) return true
    if (path.startsWith("META-INF/com/android/build/", ignoreCase = true)) return true
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

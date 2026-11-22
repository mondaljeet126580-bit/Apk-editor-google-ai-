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
 * Full-featured native Kotlin APK editing, resource replacement,
 * 4-byte/4096-byte ZIP alignment, V1/V2/V3 signing, and ZArchiver-ready
 * public Download/ApkEditor export engine.
 */
object ApkEditorEngine {
  const val OUTPUT_SUBFOLDER = "ApkEditor"
  const val PUBLIC_DISPLAY_DIR = "Download/ApkEditor"

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

  suspend fun listApkEntries(apkFile: File): List<ApkEntryItem> =
    withContext(Dispatchers.IO) {
      ZipFile(apkFile).use { zip ->
        zip.entries().asSequence().map { entry ->
          ApkEntryItem(
            path = entry.name,
            size = entry.size.coerceAtLeast(0L),
            compressedSize = entry.compressedSize.coerceAtLeast(0L),
            isDirectory = entry.isDirectory,
          )
        }.sortedWith(compareBy<ApkEntryItem> { it.path.lowercase(Locale.ROOT) }).toList()
      }
    }

  suspend fun extractEntryBytes(apkFile: File, entryPath: String): ByteArray? =
    withContext(Dispatchers.IO) {
      ZipFile(apkFile).use { zip ->
        val entry = zip.getEntry(entryPath) ?: return@withContext null
        zip.getInputStream(entry).use { it.readBytes() }
      }
    }

  /**
   * Patches AndroidManifest.xml, replaces launcher icons & custom files,
   * strips old signatures, aligns resources.arsc (4B) and native .so libs (4096B)
   * as STORED entries, signs with V1+V2+V3 via apksig, and saves to Download/ApkEditor/.
   */
  suspend fun buildSignAndExportApk(
    context: Context,
    sourceApkFile: File,
    request: ApkEditRequest,
    signingOptions: SigningOptions = SigningOptions(),
    onProgress: (String) -> Unit = {},
  ): SavedApkResult =
    withContext(Dispatchers.IO) {
      val workDir = File(context.cacheDir, "apk_build_work").apply { mkdirs() }
      val unsignedApk = File(workDir, "unsigned_${System.currentTimeMillis()}.apk")
      val signedTempApk = File(workDir, "signed_${System.currentTimeMillis()}.apk")

      try {
        onProgress("Patching AndroidManifest.xml & resources...")
        repackAndAlignApk(
          sourceApkFile = sourceApkFile,
          outputUnsignedApk = unsignedApk,
          request = request,
          onProgress = onProgress,
        )

        onProgress("Signing APK (V1 + V2 + V3)...")
        ApkSignerHelper.signApk(
          context = context,
          inputUnsignedApk = unsignedApk,
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

        // Save an app-accessible installable copy for reliable FileProvider Install/Share across all OEMs
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
        unsignedApk.delete()
        signedTempApk.delete()
      }
    }

  private fun repackAndAlignApk(
    sourceApkFile: File,
    outputUnsignedApk: File,
    request: ApkEditRequest,
    onProgress: (String) -> Unit,
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

    ZipFile(sourceApkFile).use { sourceZip ->
      val countingStream = CountingOutputStream(BufferedOutputStream(FileOutputStream(outputUnsignedApk), 64 * 1024))
      ZipOutputStream(countingStream).use { zos ->
        zos.setLevel(6)
        val entries = sourceZip.entries().toList()
        val writtenPaths = HashSet<String>()

        for ((index, entry) in entries.withIndex()) {
          val path = entry.name
          if (index % 40 == 0) {
            onProgress("Repacking archive (${index + 1}/${entries.size})...")
          }

          // Strip old signature files so apksig produces clean V1+V2+V3 signatures
          if (isSignatureEntry(path)) continue
          if (path in request.deletedEntries) continue

          if (entry.isDirectory) {
            writtenPaths.add(path)
            zos.putNextEntry(ZipEntry(path))
            zos.closeEntry()
            continue
          }

          val replacedBytes: ByteArray? =
            when {
              path in request.fileReplacements -> request.fileReplacements[path]
              path == "AndroidManifest.xml" && request.manifestPatch != null -> {
                val origManifest = sourceZip.getInputStream(entry).use { it.readBytes() }
                AxmlParserAndPatcher.patch(origManifest, request.manifestPatch)
              }
              request.newLauncherIcon != null && isLauncherIconRasterEntry(path) -> {
                encodeScaledIconPng(targetIconSizeForPath(path))
              }
              else -> null
            }

          val isResourcesArsc = path == "resources.arsc"
          val isNativeSo = path.endsWith(".so", ignoreCase = true)
          val mustStoreAligned = isResourcesArsc || isNativeSo || entry.method == ZipEntry.STORED

          writtenPaths.add(path)

          if (mustStoreAligned) {
            val dataBytes =
              replacedBytes ?: sourceZip.getInputStream(entry).use { it.readBytes() }
            val alignment = if (isNativeSo) 4096 else 4
            writeAlignedStoredEntry(zos, countingStream, path, dataBytes, alignment)
          } else {
            val outEntry = ZipEntry(path).apply { method = ZipEntry.DEFLATED }
            zos.putNextEntry(outEntry)
            if (replacedBytes != null) {
              zos.write(replacedBytes)
            } else {
              sourceZip.getInputStream(entry).use { input -> input.copyTo(zos, 32 * 1024) }
            }
            zos.closeEntry()
          }
        }

        // Write any brand-new replacement files that were not present in original APK
        for ((newPath, newBytes) in request.fileReplacements) {
          if (newPath !in writtenPaths && newPath !in request.deletedEntries && !isSignatureEntry(newPath)) {
            if (newPath == "resources.arsc" || newPath.endsWith(".so", ignoreCase = true)) {
              val alignment = if (newPath.endsWith(".so", ignoreCase = true)) 4096 else 4
              writeAlignedStoredEntry(zos, countingStream, newPath, newBytes, alignment)
            } else {
              val outEntry = ZipEntry(newPath).apply { method = ZipEntry.DEFLATED }
              zos.putNextEntry(outEntry)
              zos.write(newBytes)
              zos.closeEntry()
            }
          }
        }
      }
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

  private fun isSignatureEntry(path: String): Boolean {
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

      // 1. Check app's local mirror of saved projects first (always has FileProvider access)
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

      // 2. Also scan public Download/ApkEditor via MediaStore on API 29+ for any externally placed APKs
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
          // Ignore MediaStore permission edge cases gracefully
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

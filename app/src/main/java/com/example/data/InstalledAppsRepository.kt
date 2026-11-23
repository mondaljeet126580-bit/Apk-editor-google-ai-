package com.example.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.engine.ApkEditorEngine
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppItem(
  val appName: String,
  val packageName: String,
  val sourceApkPath: String,
  val splitApkPaths: List<String> = emptyList(),
  val isSystemApp: Boolean,
  val versionName: String,
  val versionCode: Long,
  val minSdk: Int,
  val targetSdk: Int,
)

class InstalledAppsRepository(private val context: Context) {

  suspend fun loadInstalledApps(): List<InstalledAppItem> =
    withContext(Dispatchers.IO) {
      val pm = context.packageManager
      val packages =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION")
          pm.getInstalledPackages(0)
        }

      packages.mapNotNull { pkgInfo ->
        val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
        val sourceDir = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return@mapNotNull null
        val splitDirs =
          (appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs)?.toList().orEmpty()
        val label =
          try {
            pm.getApplicationLabel(appInfo).toString().ifBlank { pkgInfo.packageName }
          } catch (_: Exception) {
            pkgInfo.packageName
          }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val vCode =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
          } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
          }
        InstalledAppItem(
          appName = label,
          packageName = pkgInfo.packageName,
          sourceApkPath = sourceDir,
          splitApkPaths = splitDirs,
          isSystemApp = isSystem,
          versionName = pkgInfo.versionName ?: "1.0",
          versionCode = vCode,
          minSdk = appInfo.minSdkVersion,
          targetSdk = appInfo.targetSdkVersion,
        )
      }.sortedBy { it.appName.lowercase(Locale.ROOT) }
    }

  suspend fun loadAppIcon(packageName: String): Bitmap? =
    withContext(Dispatchers.IO) {
      try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        ApkEditorEngine.drawableToBitmap(drawable, 128)
      } catch (_: Exception) {
        null
      }
    }

  suspend fun copyExternalApkToCache(uri: Uri): File =
    withContext(Dispatchers.IO) {
      val importDir = File(context.cacheDir, "imported_apks").apply { mkdirs() }
      val displayName = queryDisplayName(uri) ?: "selected_${System.currentTimeMillis()}.apk"
      val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
      val outFile = File(importDir, safeName)
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output -> input.copyTo(output, 64 * 1024) }
      } ?: throw IllegalStateException("Unable to read selected APK file")
      outFile
    }

  private fun queryDisplayName(uri: Uri): String? {
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
      null
    }
  }
}

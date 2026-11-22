package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ApkInstallHelper {

  fun installApk(context: Context, apkFile: File?, fallbackUri: Uri) {
    val installUri =
      if (apkFile != null && apkFile.exists()) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
      } else {
        fallbackUri
      }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
      Toast.makeText(
        context,
        "Please enable 'Install unknown apps' for APK Editor GM, then tap Install again.",
        Toast.LENGTH_LONG,
      ).show()
      val settingsIntent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
          data = Uri.parse("package:${context.packageName}")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      try {
        context.startActivity(settingsIntent)
        return
      } catch (_: Exception) {
        // Proceed to install intent fallback
      }
    }

    val intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(installUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Toast.makeText(context, "Unable to launch package installer: ${e.message}", Toast.LENGTH_LONG)
        .show()
    }
  }

  fun shareApk(context: Context, apkFile: File?, fallbackUri: Uri, fileName: String) {
    val shareUri =
      if (apkFile != null && apkFile.exists()) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
      } else {
        fallbackUri
      }

    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    try {
      val chooser = Intent.createChooser(intent, "Share $fileName").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(chooser)
    } catch (e: Exception) {
      Toast.makeText(context, "Unable to share APK: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
  }
}

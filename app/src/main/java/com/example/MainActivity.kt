package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.BuildOperationDialogs
import com.example.ui.components.EditModeDialog
import com.example.ui.components.TargetLoadOverlay
import com.example.ui.screens.CommonEditScreen
import com.example.ui.screens.CurrentActivityScreen
import com.example.ui.screens.FullEditScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SelectAppScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SimpleEditScreen
import com.example.ui.screens.WebViewScreen
import com.example.ui.screens.XmlEditScreen
import com.example.ui.theme.ApkEditorTheme
import com.example.util.ApkInstallHelper
import com.example.viewmodel.MainViewModel

object AppRoutes {
  const val ROUTE_HOME = "home"
  const val ROUTE_SELECT_APP = "select_app"
  const val ROUTE_COMMON_EDIT = "common_edit"
  const val ROUTE_FULL_EDIT = "full_edit"
  const val ROUTE_SIMPLE_EDIT = "simple_edit"
  const val ROUTE_XML_EDIT = "xml_edit"
  const val ROUTE_PROJECTS = "projects"
  const val ROUTE_SETTINGS = "settings"
  const val ROUTE_WEBVIEW = "webview"
  const val ROUTE_CURRENT_ACTIVITY = "current_activity"
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
      navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
    )
    setContent {
      ApkEditorTheme {
        ApkEditorApp()
      }
    }
  }
}

@Composable
fun ApkEditorApp(
  viewModel: MainViewModel = viewModel(),
) {
  val context = LocalContext.current
  val navController = rememberNavController()

  val activeTarget by viewModel.activeTarget.collectAsStateWithLifecycle()
  val showEditModeDialog by viewModel.showEditModeDialog.collectAsStateWithLifecycle()
  val targetLoadState by viewModel.targetLoadState.collectAsStateWithLifecycle()
  val buildState by viewModel.buildState.collectAsStateWithLifecycle()

  val apkFilePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.selectExternalApkUri(uri)
      }
    }

  NavHost(
    navController = navController,
    startDestination = AppRoutes.ROUTE_HOME,
  ) {
    composable(AppRoutes.ROUTE_HOME) {
      HomeScreen(
        onPickApkFile = {
          apkFilePickerLauncher.launch(
            arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")
          )
        },
        onSelectInstalledApp = { navController.navigate(AppRoutes.ROUTE_SELECT_APP) },
        onOpenProjects = { navController.navigate(AppRoutes.ROUTE_PROJECTS) },
        onOpenSettings = { navController.navigate(AppRoutes.ROUTE_SETTINGS) },
        onOpenWebView = { navController.navigate(AppRoutes.ROUTE_WEBVIEW) },
        onOpenCurrentActivity = { navController.navigate(AppRoutes.ROUTE_CURRENT_ACTIVITY) },
        onExitApp = {
          (context as? Activity)?.finishAndRemoveTask()
        },
      )
    }

    composable(AppRoutes.ROUTE_SELECT_APP) {
      SelectAppScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_COMMON_EDIT) {
      CommonEditScreen(
        viewModel = viewModel,
        onClose = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_FULL_EDIT) {
      FullEditScreen(
        viewModel = viewModel,
        onClose = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_SIMPLE_EDIT) {
      SimpleEditScreen(
        viewModel = viewModel,
        onClose = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_XML_EDIT) {
      XmlEditScreen(
        viewModel = viewModel,
        onClose = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_PROJECTS) {
      ProjectsScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_SETTINGS) {
      SettingsScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_WEBVIEW) {
      WebViewScreen(
        onBack = { navController.popBackStack() },
      )
    }

    composable(AppRoutes.ROUTE_CURRENT_ACTIVITY) {
      CurrentActivityScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
      )
    }
  }

  // Screen 3: Edit Mode Popup Dialog (accessible from Home APK file picker, Select App, and Projects)
  if (showEditModeDialog && activeTarget != null) {
    EditModeDialog(
      appName = activeTarget?.displayName.orEmpty(),
      onDismiss = { viewModel.dismissEditModeDialog() },
      onFullEdit = {
        viewModel.dismissEditModeDialog()
        navController.navigate(AppRoutes.ROUTE_FULL_EDIT)
      },
      onSimpleEdit = {
        viewModel.dismissEditModeDialog()
        navController.navigate(AppRoutes.ROUTE_SIMPLE_EDIT)
      },
      onCommonEdit = {
        viewModel.dismissEditModeDialog()
        navController.navigate(AppRoutes.ROUTE_COMMON_EDIT)
      },
      onXmlEdit = {
        viewModel.dismissEditModeDialog()
        navController.navigate(AppRoutes.ROUTE_XML_EDIT)
      },
    )
  }

  TargetLoadOverlay(
    state = targetLoadState,
    onDismissError = { viewModel.clearTargetLoadError() },
  )

  BuildOperationDialogs(
    buildState = buildState,
    onDismiss = { viewModel.dismissBuildState() },
    onInstall = { result ->
      ApkInstallHelper.installApk(context, result.installableFile, result.installUri)
    },
    onShare = { result ->
      ApkInstallHelper.shareApk(context, result.installableFile, result.installUri, result.fileName)
    },
  )
}

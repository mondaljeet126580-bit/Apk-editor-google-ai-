package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.AppIconView
import com.example.ui.components.EditorBottomActionBar
import com.example.ui.theme.PureWhite
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Full Edit Screen: Tabbed editor combining Common Info + APK ZIP File Explorer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullEditScreen(
  viewModel: MainViewModel,
  onClose: () -> Unit,
) {
  val target by viewModel.activeTarget.collectAsStateWithLifecycle()
  val active = target
  if (active == null) {
    onClose()
    return
  }

  val scope = rememberCoroutineScope()
  val manifest = active.manifestInfo
  val replacements by viewModel.fileReplacements.collectAsStateWithLifecycle()
  val deleted by viewModel.deletedEntries.collectAsStateWithLifecycle()

  var selectedTab by remember { mutableIntStateOf(0) }
  var appName by remember(active.sourceApkFile.absolutePath) { mutableStateOf(manifest.appLabel) }
  var packageName by remember(active.sourceApkFile.absolutePath) { mutableStateOf(manifest.packageName) }
  var installLocation by remember(active.sourceApkFile.absolutePath) {
    mutableIntStateOf(manifest.installLocation.coerceIn(-1, 2))
  }
  var versionCode by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.versionCode.toString())
  }
  var versionName by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.versionName)
  }
  var minSdk by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.minSdkVersion.toString())
  }
  var targetSdk by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.targetSdkVersion.toString())
  }
  var replacementIcon by remember(active.sourceApkFile.absolutePath) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(active.sourceApkFile.absolutePath) {
    viewModel.loadZipEntriesForActiveTarget()
  }

  val photoPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        scope.launch {
          replacementIcon = viewModel.decodeImageUriToBitmap(uri)
        }
      }
    }

  Scaffold(
    containerColor = PureWhite,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = PureWhite,
            titleContentColor = TextPrimaryDark,
            navigationIconContentColor = TextPrimaryDark,
          ),
        navigationIcon = {
          IconButton(onClick = onClose, modifier = Modifier.testTag("full_edit_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconView(
              bitmap = replacementIcon ?: active.icon,
              size = 28.dp,
              contentDescription = active.displayName,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = appName.ifBlank { active.displayName },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = stringResource(R.string.title_full_edit),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondaryWarm,
              )
            }
          }
        },
      )
    },
    bottomBar = {
      EditorBottomActionBar(
        onClose = onClose,
        onSave = {
          val form =
            CommonEditFormState(
              appName = appName,
              packageName = packageName,
              installLocation = installLocation,
              versionCode = versionCode,
              versionName = versionName,
              minSdkVersion = minSdk,
              targetSdkVersion = targetSdk,
              customIconBitmap = replacementIcon,
            )
          viewModel.saveEditedApk(
            manifestPatch = form.toManifestPatch(),
            newLauncherIcon = replacementIcon,
            includeZipFileEdits = true,
            outputBaseName = appName.ifBlank { active.displayName },
          )
        },
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      val fileChangesCount = replacements.size + deleted.size
      val filesTabLabel = if (fileChangesCount > 0) "Files ($fileChangesCount)" else "Files"

      SecondaryTabRow(
        selectedTabIndex = selectedTab,
        containerColor = PureWhite,
        contentColor = TerracottaPrimary,
        indicator = {
          TabRowDefaults.SecondaryIndicator(
            modifier = Modifier.tabIndicatorOffset(selectedTab),
            color = TerracottaPrimary,
          )
        },
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = {
            Text(
              text = "Common Info",
              color = if (selectedTab == 0) TerracottaPrimary else TextSecondaryWarm,
              fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
          modifier = Modifier.testTag("full_edit_tab_common"),
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = {
            Text(
              text = filesTabLabel,
              color = if (selectedTab == 1) TerracottaPrimary else TextSecondaryWarm,
              fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
          modifier = Modifier.testTag("full_edit_tab_files"),
        )
      }

      if (selectedTab == 0) {
        CommonEditFormContent(
          displayIcon = replacementIcon ?: active.icon,
          isIconModified = replacementIcon != null,
          onPickIcon = {
            photoPickerLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
          },
          appName = appName,
          onAppNameChange = { appName = it },
          packageName = packageName,
          onPackageNameChange = { packageName = it },
          installLocation = installLocation,
          onInstallLocationChange = { installLocation = it },
          versionCode = versionCode,
          onVersionCodeChange = { versionCode = it.filter { ch -> ch.isDigit() } },
          versionName = versionName,
          onVersionNameChange = { versionName = it },
          minSdk = minSdk,
          onMinSdkChange = { minSdk = it.filter { ch -> ch.isDigit() } },
          targetSdk = targetSdk,
          onTargetSdkChange = { targetSdk = it.filter { ch -> ch.isDigit() } },
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        ApkZipExplorerContent(
          viewModel = viewModel,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

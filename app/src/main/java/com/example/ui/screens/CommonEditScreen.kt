package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.ManifestPatchSpec
import com.example.ui.components.AppIconView
import com.example.ui.components.EditorBottomActionBar
import com.example.ui.components.PeachEditorCard
import com.example.ui.components.UnderlinedEditorField
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

data class CommonEditFormState(
  val appName: String,
  val packageName: String,
  val installLocation: Int,
  val versionCode: String,
  val versionName: String,
  val minSdkVersion: String,
  val targetSdkVersion: String,
  val customIconBitmap: Bitmap?,
) {
  fun toManifestPatch(): ManifestPatchSpec =
    ManifestPatchSpec(
      packageName = packageName.trim().ifBlank { null },
      appLabel = appName.trim().ifBlank { null },
      versionCode = versionCode.trim().toIntOrNull(),
      versionName = versionName.trim().ifBlank { null },
      installLocation = installLocation,
      minSdkVersion = minSdkVersion.trim().toIntOrNull(),
      targetSdkVersion = targetSdkVersion.trim().toIntOrNull(),
    )
}

/**
 * Screen 4: Common Edit Screen (Matches Screenshot 1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonEditScreen(
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
          IconButton(
            onClick = onClose,
            modifier = Modifier.testTag("common_edit_back_button"),
          ) {
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
            Text(
              text = appName.ifBlank { active.displayName },
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
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
            includeZipFileEdits = false,
            outputBaseName = appName.ifBlank { active.displayName },
          )
        },
      )
    },
  ) { innerPadding ->
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
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    )
  }
}

/**
 * Reusable Common Edit form body shared between CommonEditScreen and FullEditScreen Tab 1.
 */
@Composable
fun CommonEditFormContent(
  displayIcon: Bitmap?,
  isIconModified: Boolean,
  onPickIcon: () -> Unit,
  appName: String,
  onAppNameChange: (String) -> Unit,
  packageName: String,
  onPackageNameChange: (String) -> Unit,
  installLocation: Int,
  onInstallLocationChange: (Int) -> Unit,
  versionCode: String,
  onVersionCodeChange: (String) -> Unit,
  versionName: String,
  onVersionNameChange: (String) -> Unit,
  minSdk: String,
  onMinSdkChange: (String) -> Unit,
  targetSdk: String,
  onTargetSdkChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var installDropdownExpanded by remember { mutableStateOf(false) }

  val installLocationOptions =
    listOf(
      -1 to stringResource(R.string.install_location_default),
      0 to stringResource(R.string.install_location_auto),
      1 to stringResource(R.string.install_location_internal),
      2 to stringResource(R.string.install_location_external),
    )
  val selectedLocationLabel =
    installLocationOptions.firstOrNull { it.first == installLocation }?.second
      ?: stringResource(R.string.install_location_default)

  Box(
    modifier = modifier.imePadding(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier =
        Modifier
          .widthIn(max = 600.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Centered 64dp Launcher Icon preview with "Launcher icon" label underneath
      Column(
        modifier =
          Modifier
            .clip(RoundedCornerShape(16.dp))
            .minimumInteractiveComponentSize()
            .clickable(onClick = onPickIcon)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("launcher_icon_picker"),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        AppIconView(
          bitmap = displayIcon,
          size = 64.dp,
          contentDescription = stringResource(R.string.cd_change_launcher_icon),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text =
            if (isIconModified) "${stringResource(R.string.label_launcher_icon)} (Changed)"
            else stringResource(R.string.label_launcher_icon),
          style = MaterialTheme.typography.bodySmall,
          color = if (isIconModified) TerracottaPrimary else TextSecondaryGray,
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Card 1: App name, Package name, Install location
      PeachEditorCard {
        UnderlinedEditorField(
          label = stringResource(R.string.label_app_name),
          value = appName,
          onValueChange = onAppNameChange,
          testTag = "field_app_name",
        )
        Spacer(modifier = Modifier.height(16.dp))
        UnderlinedEditorField(
          label = stringResource(R.string.label_package_name),
          value = packageName,
          onValueChange = onPackageNameChange,
          testTag = "field_package_name",
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.label_install_location),
          style = MaterialTheme.typography.bodySmall,
          color = TextSecondaryWarm,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .minimumInteractiveComponentSize()
                .clickable { installDropdownExpanded = true }
                .padding(vertical = 8.dp)
                .testTag("dropdown_install_location"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = selectedLocationLabel,
              style = MaterialTheme.typography.bodyLarge,
              color = TextPrimaryDark,
            )
            Icon(
              imageVector = Icons.Default.ArrowDropDown,
              contentDescription = stringResource(R.string.label_install_location),
              tint = TextPrimaryDark,
            )
          }
          DropdownMenu(
            expanded = installDropdownExpanded,
            onDismissRequest = { installDropdownExpanded = false },
            containerColor = SoftPeachSurface,
          ) {
            installLocationOptions.forEach { (code, label) ->
              DropdownMenuItem(
                text = {
                  Text(
                    text = label,
                    color = if (code == installLocation) TerracottaPrimary else TextPrimaryDark,
                    fontWeight = if (code == installLocation) FontWeight.SemiBold else FontWeight.Normal,
                  )
                },
                onClick = {
                  onInstallLocationChange(code)
                  installDropdownExpanded = false
                },
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Card 2: Version code, Version name
      PeachEditorCard {
        UnderlinedEditorField(
          label = stringResource(R.string.label_version_code),
          value = versionCode,
          onValueChange = onVersionCodeChange,
          keyboardType = KeyboardType.Number,
          testTag = "field_version_code",
        )
        Spacer(modifier = Modifier.height(16.dp))
        UnderlinedEditorField(
          label = stringResource(R.string.label_version_name),
          value = versionName,
          onValueChange = onVersionNameChange,
          testTag = "field_version_name",
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Card 3: Minimum SDK Version, Target SDK version
      PeachEditorCard {
        UnderlinedEditorField(
          label = stringResource(R.string.label_min_sdk_version),
          value = minSdk,
          onValueChange = onMinSdkChange,
          keyboardType = KeyboardType.Number,
          testTag = "field_min_sdk",
        )
        Spacer(modifier = Modifier.height(16.dp))
        UnderlinedEditorField(
          label = stringResource(R.string.label_target_sdk_version),
          value = targetSdk,
          onValueChange = onTargetSdkChange,
          keyboardType = KeyboardType.Number,
          testTag = "field_target_sdk",
        )
      }

      Spacer(modifier = Modifier.height(18.dp))
    }
  }
}

package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.MainViewModel

/**
 * XML File Edit Screen: Inspects parsed AndroidManifest.xml attributes, permissions,
 * activities, services, receivers, and raw decoded XML preview, with live patching & saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XmlEditScreen(
  viewModel: MainViewModel,
  onClose: () -> Unit,
) {
  val target by viewModel.activeTarget.collectAsStateWithLifecycle()
  val active = target
  if (active == null) {
    onClose()
    return
  }

  val manifest = active.manifestInfo
  var selectedTab by remember { mutableIntStateOf(0) }

  var packageName by remember(active.sourceApkFile.absolutePath) { mutableStateOf(manifest.packageName) }
  var appLabel by remember(active.sourceApkFile.absolutePath) { mutableStateOf(manifest.appLabel) }
  var versionCode by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.versionCode.toString())
  }
  var versionName by remember(active.sourceApkFile.absolutePath) { mutableStateOf(manifest.versionName) }
  var minSdk by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.minSdkVersion.toString())
  }
  var targetSdk by remember(active.sourceApkFile.absolutePath) {
    mutableStateOf(manifest.targetSdkVersion.toString())
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
          IconButton(onClick = onClose, modifier = Modifier.testTag("xml_edit_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconView(bitmap = active.icon, size = 28.dp, contentDescription = active.displayName)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text(
                text = "AndroidManifest.xml",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = active.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondaryWarm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
          val patch =
            ManifestPatchSpec(
              packageName = packageName.trim().ifBlank { null },
              appLabel = appLabel.trim().ifBlank { null },
              versionCode = versionCode.trim().toIntOrNull(),
              versionName = versionName.trim().ifBlank { null },
              minSdkVersion = minSdk.trim().toIntOrNull(),
              targetSdkVersion = targetSdk.trim().toIntOrNull(),
            )
          viewModel.saveEditedApk(
            manifestPatch = patch,
            newLauncherIcon = null,
            includeZipFileEdits = false,
            outputBaseName = appLabel.ifBlank { active.displayName },
          )
        },
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
              "Attributes",
              color = if (selectedTab == 0) TerracottaPrimary else TextSecondaryWarm,
              fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = {
            Text(
              "Components",
              color = if (selectedTab == 1) TerracottaPrimary else TextSecondaryWarm,
              fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = {
            Text(
              "XML Source",
              color = if (selectedTab == 2) TerracottaPrimary else TextSecondaryWarm,
              fontWeight = if (selectedTab == 2) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
        )
      }

      when (selectedTab) {
        0 -> {
          Column(
            modifier =
              Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 600.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            PeachEditorCard {
              Text(
                text = "<manifest> & <application>",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TerracottaPrimary,
              )
              Spacer(modifier = Modifier.height(12.dp))
              UnderlinedEditorField(
                label = "package",
                value = packageName,
                onValueChange = { packageName = it },
                testTag = "xml_field_package",
              )
              Spacer(modifier = Modifier.height(14.dp))
              UnderlinedEditorField(
                label = "android:label",
                value = appLabel,
                onValueChange = { appLabel = it },
                testTag = "xml_field_label",
              )
              Spacer(modifier = Modifier.height(14.dp))
              UnderlinedEditorField(
                label = "android:versionCode",
                value = versionCode,
                onValueChange = { versionCode = it.filter { ch -> ch.isDigit() } },
                keyboardType = KeyboardType.Number,
              )
              Spacer(modifier = Modifier.height(14.dp))
              UnderlinedEditorField(
                label = "android:versionName",
                value = versionName,
                onValueChange = { versionName = it },
              )
            }
            Spacer(modifier = Modifier.height(14.dp))
            PeachEditorCard {
              Text(
                text = "<uses-sdk>",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TerracottaPrimary,
              )
              Spacer(modifier = Modifier.height(12.dp))
              UnderlinedEditorField(
                label = "android:minSdkVersion",
                value = minSdk,
                onValueChange = { minSdk = it.filter { ch -> ch.isDigit() } },
                keyboardType = KeyboardType.Number,
              )
              Spacer(modifier = Modifier.height(14.dp))
              UnderlinedEditorField(
                label = "android:targetSdkVersion",
                value = targetSdk,
                onValueChange = { targetSdk = it.filter { ch -> ch.isDigit() } },
                keyboardType = KeyboardType.Number,
              )
            }
          }
        }
        1 -> {
          Column(
            modifier =
              Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
          ) {
            ManifestComponentSection(
              title = "Permissions (${manifest.permissions.size})",
              items = manifest.permissions,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManifestComponentSection(
              title = "Activities (${manifest.activities.size})",
              items = manifest.activities,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManifestComponentSection(
              title = "Services (${manifest.services.size})",
              items = manifest.services,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ManifestComponentSection(
              title = "Broadcast Receivers (${manifest.receivers.size})",
              items = manifest.receivers,
            )
          }
        }
        2 -> {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = SoftPeachSurface,
            modifier = Modifier.fillMaxSize().padding(14.dp),
          ) {
            Text(
              text = manifest.xmlPreview.ifBlank { "No binary XML content decoded." },
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              color = TextPrimaryDark,
              modifier =
                Modifier
                  .fillMaxSize()
                  .padding(14.dp)
                  .verticalScroll(rememberScrollState())
                  .horizontalScroll(rememberScrollState()),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ManifestComponentSection(
  title: String,
  items: List<String>,
) {
  PeachEditorCard {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
      color = TerracottaPrimary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (items.isEmpty()) {
      Text(
        text = "None declared",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondaryGray,
      )
    } else {
      items.forEachIndexed { idx, entry ->
        Text(
          text = entry,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = TextPrimaryDark,
          modifier = Modifier.padding(vertical = 4.dp),
        )
        if (idx < items.lastIndex) {
          HorizontalDivider(color = DividerWarm.copy(alpha = 0.6f), thickness = 0.5.dp)
        }
      }
    }
  }
}

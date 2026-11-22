package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.ApkEditorEngine
import com.example.ui.components.PeachEditorCard
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurfacePressed
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val certInfo by viewModel.certInfo.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.refreshCertificateInfo()
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
          IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.title_settings),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
          )
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Output folder card
        PeachEditorCard {
          Text(
            text = "Output Directory",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "${ApkEditorEngine.PUBLIC_DISPLAY_DIR}/",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = TextPrimaryDark,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text =
              "Signed APKs are saved directly to public Downloads using MediaStore so ZArchiver and system file managers can open or install them immediately.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondaryGray,
          )
        }

        // APK Signing Options card
        PeachEditorCard {
          Text(
            text = "APK Signing Schemes (apksig)",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))

          SigningToggleRow(
            title = "V1 Signature (JAR Signing)",
            subtitle = "Legacy compatibility for Android 7 and older parsers",
            checked = settings.v1SigningEnabled,
            onCheckedChange = { viewModel.updateV1Signing(it) },
            testTag = "switch_v1_signing",
          )
          HorizontalDivider(color = DividerWarm)
          SigningToggleRow(
            title = "V2 Signature (APK Signature Scheme v2)",
            subtitle = "Required for Android 11+ fast whole-file verification",
            checked = settings.v2SigningEnabled,
            onCheckedChange = { viewModel.updateV2Signing(it) },
            testTag = "switch_v2_signing",
          )
          HorizontalDivider(color = DividerWarm)
          SigningToggleRow(
            title = "V3 Signature (Key Rotation Support)",
            subtitle = "Modern Android 9–15+ signature block verification",
            checked = settings.v3SigningEnabled,
            onCheckedChange = { viewModel.updateV3Signing(it) },
            testTag = "switch_v3_signing",
          )
        }

        // Auto-generated Keystore card
        PeachEditorCard {
          Text(
            text = "Signing Keystore (2048-bit RSA PKCS12)",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          val info = certInfo
          if (info != null) {
            InfoRow("Alias", info.alias)
            InfoRow("Subject", info.subject)
            InfoRow("Valid Until", info.validUntil)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "SHA-256 Fingerprint",
              style = MaterialTheme.typography.labelSmall,
              color = TextSecondaryWarm,
            )
            Text(
              text = info.sha256Fingerprint,
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
              color = TextPrimaryDark,
            )
          } else {
            Text(
              text = "Initializing local PKCS12 signing key...",
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondaryGray,
            )
          }
          Spacer(modifier = Modifier.height(14.dp))
          Button(
            onClick = { viewModel.refreshCertificateInfo(forceRegenerate = true) },
            shape = RoundedCornerShape(10.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = TerracottaPrimary,
                contentColor = PureWhite,
              ),
            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("regenerate_keystore_button"),
          ) {
            Text("Regenerate Self-Signed Keystore")
          }
        }
      }
    }
  }
}

@Composable
private fun SigningToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = TextPrimaryDark,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondaryGray,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = PureWhite,
          checkedTrackColor = TerracottaPrimary,
          uncheckedThumbColor = TextSecondaryWarm,
          uncheckedTrackColor = SoftPeachSurfacePressed,
        ),
      modifier = Modifier.testTag(testTag),
    )
  }
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondaryWarm)
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
      color = TextPrimaryDark,
    )
  }
}

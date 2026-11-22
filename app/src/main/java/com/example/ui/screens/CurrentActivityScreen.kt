package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.engine.ApkEditorEngine
import com.example.ui.components.PeachEditorCard
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrentActivityScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val activeTarget by viewModel.activeTarget.collectAsStateWithLifecycle()
  val canInstallPackages =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
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
          IconButton(onClick = onBack, modifier = Modifier.testTag("current_activity_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.title_current_activity),
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
        PeachEditorCard {
          Text(
            text = "Current Application & Activity",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(10.dp))
          ActivityMetaRow("App Name", stringResource(R.string.app_name))
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Package Name", context.packageName)
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Activity Class", MainActivity::class.java.name)
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }

        PeachEditorCard {
          Text(
            text = "Device & Environment",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(10.dp))
          ActivityMetaRow("Android Version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Supported ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
          HorizontalDivider(color = DividerWarm)
          ActivityMetaRow("Output Folder", "${ApkEditorEngine.PUBLIC_DISPLAY_DIR}/")
        }

        PeachEditorCard {
          Text(
            text = "Install Unknown Apps Permission",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TerracottaPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Status",
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondaryWarm,
            )
            Text(
              text = if (canInstallPackages) "Granted" else "Not Granted",
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = if (canInstallPackages) SuccessGreen else TerracottaPrimary,
            )
          }
          if (!canInstallPackages && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
              onClick = {
                val intent =
                  Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                  }
                context.startActivity(intent)
              },
              shape = RoundedCornerShape(10.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = TerracottaPrimary,
                  contentColor = PureWhite,
                ),
              modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
              Text("Grant Install Permission")
            }
          }
        }

        val lastTarget = activeTarget
        if (lastTarget != null) {
          PeachEditorCard {
            Text(
              text = "Last Inspected Target APK",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
              color = TerracottaPrimary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            ActivityMetaRow("App Label", lastTarget.displayName)
            HorizontalDivider(color = DividerWarm)
            ActivityMetaRow("Package", lastTarget.packageName)
            HorizontalDivider(color = DividerWarm)
            ActivityMetaRow(
              "SDK Range",
              "minSdk ${lastTarget.manifestInfo.minSdkVersion} / targetSdk ${lastTarget.manifestInfo.targetSdkVersion}",
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ActivityMetaRow(label: String, value: String) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondaryWarm)
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
      color = TextPrimaryDark,
    )
  }
}

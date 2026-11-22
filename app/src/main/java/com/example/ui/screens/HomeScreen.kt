package com.example.ui.screens

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray

/**
 * Screen 1: Home Screen (Matches Screenshot 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  onPickApkFile: () -> Unit,
  onSelectInstalledApp: () -> Unit,
  onOpenProjects: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenWebView: () -> Unit,
  onOpenCurrentActivity: () -> Unit,
  onExitApp: () -> Unit,
) {
  var showOverflowMenu by remember { mutableStateOf(false) }
  var showAboutDialog by remember { mutableStateOf(false) }

  Scaffold(
    containerColor = PureWhite,
    topBar = {
      CenterAlignedTopAppBar(
        colors =
          TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = PureWhite,
            titleContentColor = TextPrimaryDark,
            navigationIconContentColor = TextPrimaryDark,
            actionIconContentColor = TextPrimaryDark,
          ),
        navigationIcon = {
          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("home_settings_icon"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Settings,
              contentDescription = stringResource(R.string.cd_settings),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
          )
        },
        actions = {
          Box {
            IconButton(
              onClick = { showOverflowMenu = true },
              modifier = Modifier.testTag("home_overflow_menu"),
            ) {
              Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
              )
            }
            DropdownMenu(
              expanded = showOverflowMenu,
              onDismissRequest = { showOverflowMenu = false },
              containerColor = SoftPeachSurface,
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_projects), color = TextPrimaryDark) },
                onClick = {
                  showOverflowMenu = false
                  onOpenProjects()
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_settings), color = TextPrimaryDark) },
                onClick = {
                  showOverflowMenu = false
                  onOpenSettings()
                },
              )
              DropdownMenuItem(
                text = {
                  Text(stringResource(R.string.menu_current_activity), color = TextPrimaryDark)
                },
                onClick = {
                  showOverflowMenu = false
                  onOpenCurrentActivity()
                },
              )
              DropdownMenuItem(
                text = { Text("About", color = TextPrimaryDark) },
                onClick = {
                  showOverflowMenu = false
                  showAboutDialog = true
                },
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier =
          Modifier
            .weight(1f)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        HomeMenuPillButton(
          label = stringResource(R.string.menu_apk_file),
          testTag = "home_apk_file_button",
          onClick = onPickApkFile,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_installed_app),
          testTag = "home_installed_app_button",
          onClick = onSelectInstalledApp,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_projects),
          testTag = "home_projects_button",
          onClick = onOpenProjects,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_settings),
          testTag = "home_settings_button",
          onClick = onOpenSettings,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_webview),
          testTag = "home_webview_button",
          onClick = onOpenWebView,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_current_activity),
          testTag = "home_current_activity_button",
          onClick = onOpenCurrentActivity,
        )
        Spacer(modifier = Modifier.height(14.dp))
        HomeMenuPillButton(
          label = stringResource(R.string.menu_exit),
          testTag = "home_exit_button",
          onClick = onExitApp,
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
          text = stringResource(R.string.home_news_marquee),
          style = MaterialTheme.typography.bodySmall,
          color = TextSecondaryGray,
          maxLines = 1,
          modifier =
            Modifier
              .fillMaxWidth()
              .basicMarquee(iterations = Int.MAX_VALUE)
              .padding(vertical = 6.dp)
              .testTag("home_news_marquee"),
        )
      }
    }
  }

  if (showAboutDialog) {
    AlertDialog(
      onDismissRequest = { showAboutDialog = false },
      containerColor = SoftPeachSurface,
      title = {
        Text(
          text = stringResource(R.string.app_name),
          color = TextPrimaryDark,
          fontWeight = FontWeight.SemiBold,
        )
      },
      text = {
        Text(
          text =
            "Native Android APK editor with binary AXML manifest patching, launcher icon & ZIP resource replacement, 4-byte/4096-byte alignment, and real V1/V2/V3 signing.\n\nEdited APKs are exported directly to Download/ApkEditor/ for ZArchiver.",
          color = TextPrimaryDark,
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        TextButton(onClick = { showAboutDialog = false }) {
          Text(stringResource(R.string.action_close), color = TerracottaPrimary)
        }
      },
    )
  }
}

@Composable
private fun HomeMenuPillButton(
  label: String,
  testTag: String,
  onClick: () -> Unit,
) {
  val pillShape = RoundedCornerShape(26.dp)
  Surface(
    shape = pillShape,
    color = SoftPeachSurface,
    shadowElevation = 2.dp,
    modifier =
      Modifier
        .fillMaxWidth()
        .height(48.dp)
        .minimumInteractiveComponentSize()
        .clip(pillShape)
        .clickable(onClick = onClick)
        .testTag(testTag),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = TextPrimaryDark,
        textAlign = TextAlign.Center,
      )
    }
  }
}

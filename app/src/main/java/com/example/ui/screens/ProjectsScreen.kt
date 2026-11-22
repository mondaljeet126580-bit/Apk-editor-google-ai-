package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.ApkEditorEngine
import com.example.engine.SavedProjectItem
import com.example.ui.components.AppIconView
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.util.ApkInstallHelper
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Projects Screen: Lists all edited & signed APKs saved in Download/ApkEditor/
 * with Install, Share, Edit Again, and Delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val projects by viewModel.savedProjects.collectAsStateWithLifecycle()
  val isLoading by viewModel.isLoadingProjects.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.loadSavedProjects()
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
            actionIconContentColor = TextPrimaryDark,
          ),
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("projects_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Column {
            Text(
              text = stringResource(R.string.title_projects),
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            )
            Text(
              text = "${ApkEditorEngine.PUBLIC_DISPLAY_DIR}/",
              style = MaterialTheme.typography.labelSmall,
              color = TextSecondaryWarm,
            )
          }
        },
        actions = {
          IconButton(
            onClick = { viewModel.loadSavedProjects() },
            modifier = Modifier.testTag("projects_refresh_button"),
          ) {
            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh projects")
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding(),
    ) {
      when {
        isLoading && projects.isEmpty() -> {
          CircularProgressIndicator(
            color = TerracottaPrimary,
            modifier = Modifier.align(Alignment.Center),
          )
        }
        projects.isEmpty() -> {
          Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              imageVector = Icons.Outlined.FolderOpen,
              contentDescription = null,
              tint = TextSecondaryWarm,
              modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = stringResource(R.string.empty_projects_message),
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondaryGray,
              textAlign = TextAlign.Center,
            )
          }
        }
        else -> {
          LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("projects_list"),
          ) {
            items(items = projects, key = { it.fileName }) { item ->
              ProjectItemCard(
                item = item,
                onInstall = {
                  ApkInstallHelper.installApk(context, item.localFile, item.contentUri)
                },
                onShare = {
                  ApkInstallHelper.shareApk(context, item.localFile, item.contentUri, item.fileName)
                },
                onEditAgain = { viewModel.selectSavedProjectForEdit(item) },
                onDelete = { viewModel.deleteProject(item) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProjectItemCard(
  item: SavedProjectItem,
  onInstall: () -> Unit,
  onShare: () -> Unit,
  onEditAgain: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateStr =
    SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
      .format(Date(item.lastModifiedMillis))

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = SoftPeachSurface,
    shadowElevation = 1.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconView(bitmap = item.icon, size = 44.dp, contentDescription = item.appName)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.appName,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimaryDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = item.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondaryWarm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${ApkInstallHelper.formatFileSize(item.fileSizeBytes)} • $dateStr",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryGray,
          )
        }
        IconButton(onClick = onEditAgain) {
          Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = "Edit APK",
            tint = TerracottaPrimary,
          )
        }
        IconButton(onClick = onDelete) {
          Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.action_delete),
            tint = TextSecondaryWarm,
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = onInstall,
          shape = RoundedCornerShape(10.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = TerracottaPrimary,
              contentColor = PureWhite,
            ),
          modifier = Modifier.weight(1f).height(42.dp),
        ) {
          Text(stringResource(R.string.action_install_apk), style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
          onClick = onShare,
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, DividerWarm),
          modifier = Modifier.weight(1f).height(42.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = null,
            tint = TextPrimaryDark,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.action_share), color = TextPrimaryDark)
        }
      }
    }
  }
}

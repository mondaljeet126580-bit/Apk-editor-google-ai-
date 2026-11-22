package com.example.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.InstalledAppItem
import com.example.ui.components.AppIconView
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.viewmodel.AppFilterMode
import com.example.viewmodel.AppSortOrder
import com.example.viewmodel.MainViewModel

/**
 * Screen 2: Select an Application Screen (Matches Screenshot 3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectAppScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
  val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()
  val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
  val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
  val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

  var showMenu by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.loadInstalledAppsIfNeeded()
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
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("select_app_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        title = {
          Text(
            text = stringResource(R.string.title_select_application),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
          )
        },
        actions = {
          Box {
            IconButton(
              onClick = { showMenu = true },
              modifier = Modifier.testTag("select_app_overflow_menu"),
            ) {
              Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
              )
            }
            DropdownMenu(
              expanded = showMenu,
              onDismissRequest = { showMenu = false },
              containerColor = SoftPeachSurface,
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_user_apps), color = TextPrimaryDark) },
                trailingIcon = {
                  if (filterMode == AppFilterMode.USER_APPS) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = TerracottaPrimary)
                  }
                },
                onClick = {
                  viewModel.setFilterMode(AppFilterMode.USER_APPS)
                  showMenu = false
                },
              )
              DropdownMenuItem(
                text = {
                  Text(stringResource(R.string.filter_system_apps), color = TextPrimaryDark)
                },
                trailingIcon = {
                  if (filterMode == AppFilterMode.SYSTEM_APPS) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = TerracottaPrimary)
                  }
                },
                onClick = {
                  viewModel.setFilterMode(AppFilterMode.SYSTEM_APPS)
                  showMenu = false
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.filter_all_apps), color = TextPrimaryDark) },
                trailingIcon = {
                  if (filterMode == AppFilterMode.ALL_APPS) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = TerracottaPrimary)
                  }
                },
                onClick = {
                  viewModel.setFilterMode(AppFilterMode.ALL_APPS)
                  showMenu = false
                },
              )
              HorizontalDivider(color = DividerWarm)
              DropdownMenuItem(
                text = {
                  val suffix = if (sortOrder == AppSortOrder.NAME_ASC) " (A–Z)" else " (Z–A)"
                  Text(
                    stringResource(R.string.sort_by_name) + suffix,
                    color = TextPrimaryDark,
                  )
                },
                onClick = {
                  viewModel.toggleSortOrder()
                  showMenu = false
                },
              )
            }
          }
        },
      )
    },
    bottomBar = {
      Surface(
        color = PureWhite,
        modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
      ) {
        Surface(
          shape = RoundedCornerShape(26.dp),
          color = SoftPeachSurface,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 8.dp)
              .height(48.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
              if (searchQuery.isEmpty()) {
                Text(
                  text = stringResource(R.string.search_hint),
                  style = MaterialTheme.typography.bodyMedium,
                  color = TextSecondaryWarm,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              BasicTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
                cursorBrush = SolidColor(TerracottaPrimary),
                modifier = Modifier.fillMaxWidth().testTag("select_app_search_input"),
              )
            }
            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = { viewModel.setSearchQuery("") },
                modifier = Modifier.size(36.dp).testTag("select_app_clear_search"),
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = stringResource(R.string.cd_clear_search),
                  tint = TextSecondaryWarm,
                  modifier = Modifier.size(20.dp),
                )
              }
            } else {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.cd_search),
                tint = TextPrimaryDark,
                modifier = Modifier.size(22.dp),
              )
            }
          }
        }
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when {
        isLoading && apps.isEmpty() -> {
          Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            CircularProgressIndicator(color = TerracottaPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Loading installed applications...",
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondaryGray,
            )
          }
        }
        apps.isEmpty() -> {
          Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = stringResource(R.string.empty_apps_message),
              style = MaterialTheme.typography.bodyLarge,
              color = TextSecondaryGray,
            )
          }
        }
        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("installed_apps_list"),
          ) {
            items(items = apps, key = { it.packageName }) { app ->
              InstalledAppRow(
                item = app,
                loadIcon = { viewModel.getAppIcon(app.packageName) },
                onClick = { viewModel.selectInstalledApp(app) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun InstalledAppRow(
  item: InstalledAppItem,
  loadIcon: suspend () -> Bitmap?,
  onClick: () -> Unit,
) {
  var iconBitmap by remember(item.packageName) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(item.packageName) {
    iconBitmap = loadIcon()
  }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .minimumInteractiveComponentSize()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("app_item_${item.packageName}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
  ) {
    AppIconView(
      bitmap = iconBitmap,
      size = 44.dp,
      contentDescription = item.appName,
    )
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.appName,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = TextPrimaryDark,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = item.packageName,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondaryGray,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.ApkEntryItem
import com.example.ui.components.AppIconView
import com.example.ui.components.EditorBottomActionBar
import com.example.ui.theme.DividerWarm
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftPeachSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TerracottaPrimary
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryGray
import com.example.ui.theme.TextSecondaryWarm
import com.example.util.ApkInstallHelper
import com.example.viewmodel.MainViewModel
import java.util.Locale
import kotlinx.coroutines.launch

private enum class ZipCategory(val label: String) {
  ALL("All"),
  RES("res/"),
  ASSETS("assets/"),
  LIB("lib/"),
  DEX("dex"),
  OTHER("Other"),
}

/**
 * Simple Edit Screen: APK ZIP File Explorer with real file/image replacement & signing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleEditScreen(
  viewModel: MainViewModel,
  onClose: () -> Unit,
) {
  val target by viewModel.activeTarget.collectAsStateWithLifecycle()
  val active = target
  if (active == null) {
    onClose()
    return
  }

  val replacements by viewModel.fileReplacements.collectAsStateWithLifecycle()
  val deleted by viewModel.deletedEntries.collectAsStateWithLifecycle()

  LaunchedEffect(active.sourceApkFile.absolutePath) {
    viewModel.loadZipEntriesForActiveTarget()
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
          IconButton(onClick = onClose, modifier = Modifier.testTag("simple_edit_back_button")) {
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
                text = active.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              val changeCount = replacements.size + deleted.size
              Text(
                text =
                  if (changeCount > 0) "Simple edit • $changeCount modified"
                  else stringResource(R.string.title_simple_edit),
                style = MaterialTheme.typography.labelSmall,
                color = if (changeCount > 0) TerracottaPrimary else TextSecondaryWarm,
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
          viewModel.saveEditedApk(
            manifestPatch = null,
            newLauncherIcon = null,
            includeZipFileEdits = true,
            outputBaseName = active.displayName,
          )
        },
      )
    },
  ) { innerPadding ->
    ApkZipExplorerContent(
      viewModel = viewModel,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    )
  }
}

/**
 * Reusable APK ZIP File Explorer used by both Simple Edit and Full Edit screens.
 */
@Composable
fun ApkZipExplorerContent(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  val entries by viewModel.zipEntries.collectAsStateWithLifecycle()
  val isLoading by viewModel.isLoadingEntries.collectAsStateWithLifecycle()
  val replacements by viewModel.fileReplacements.collectAsStateWithLifecycle()
  val deleted by viewModel.deletedEntries.collectAsStateWithLifecycle()

  var selectedCategory by remember { mutableStateOf(ZipCategory.ALL) }
  var query by remember { mutableStateOf("") }
  var pendingReplacePath by remember { mutableStateOf<String?>(null) }

  var previewPath by remember { mutableStateOf<String?>(null) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var previewText by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  val replaceFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val path = pendingReplacePath
      pendingReplacePath = null
      if (uri != null && path != null) {
        viewModel.replaceZipEntryFromUri(path, uri)
      }
    }

  val filteredEntries =
    remember(entries, selectedCategory, query) {
      val q = query.trim().lowercase(Locale.ROOT)
      entries.filter { entry ->
        if (entry.isDirectory) return@filter false
        val matchesCategory =
          when (selectedCategory) {
            ZipCategory.ALL -> true
            ZipCategory.RES -> entry.path.startsWith("res/")
            ZipCategory.ASSETS -> entry.path.startsWith("assets/")
            ZipCategory.LIB -> entry.path.startsWith("lib/")
            ZipCategory.DEX -> entry.path.endsWith(".dex", ignoreCase = true)
            ZipCategory.OTHER ->
              !entry.path.startsWith("res/") &&
                !entry.path.startsWith("assets/") &&
                !entry.path.startsWith("lib/") &&
                !entry.path.endsWith(".dex", ignoreCase = true)
          }
        matchesCategory && (q.isEmpty() || entry.path.lowercase(Locale.ROOT).contains(q))
      }
    }

  Column(modifier = modifier) {
    // Search input
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = SoftPeachSurface,
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(44.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = null,
          tint = TextSecondaryWarm,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty()) {
            Text(
              text = "Filter files in APK (e.g. ic_launcher, assets/)",
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondaryWarm,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimaryDark),
            cursorBrush = SolidColor(TerracottaPrimary),
            modifier = Modifier.fillMaxWidth().testTag("zip_explorer_search"),
          )
        }
        if (query.isNotEmpty()) {
          IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.cd_clear_search),
              tint = TextSecondaryWarm,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }

    // Category filter chips
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ZipCategory.entries.forEach { cat ->
        FilterChip(
          selected = selectedCategory == cat,
          onClick = { selectedCategory = cat },
          label = { Text(cat.label, style = MaterialTheme.typography.labelMedium) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = TerracottaPrimary,
              selectedLabelColor = PureWhite,
              containerColor = SoftPeachSurface,
              labelColor = TextPrimaryDark,
            ),
          border = null,
        )
      }
    }

    if (isLoading) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = TerracottaPrimary)
      }
    } else if (filteredEntries.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = "No matching entries in archive",
          style = MaterialTheme.typography.bodyMedium,
          color = TextSecondaryGray,
        )
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().testTag("zip_entries_list")) {
        items(items = filteredEntries, key = { it.path }) { entry ->
          val isReplaced = entry.path in replacements
          val isDeleted = entry.path in deleted
          ZipEntryRow(
            entry = entry,
            isReplaced = isReplaced,
            isDeleted = isDeleted,
            replacedSize = replacements[entry.path]?.size?.toLong(),
            onPreview = {
              scope.launch {
                val bytes = viewModel.readEntryPreviewBytes(entry.path)
                val lower = entry.path.lowercase(Locale.ROOT)
                if (bytes != null && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"))) {
                  previewBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                  previewText = null
                  previewPath = entry.path
                } else if (bytes != null && (lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".properties") || lower.endsWith(".mf") || lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css"))) {
                  previewBitmap = null
                  previewText = bytes.decodeToString().take(8000)
                  previewPath = entry.path
                }
              }
            },
            onReplace = {
              pendingReplacePath = entry.path
              replaceFileLauncher.launch(arrayOf("*/*"))
            },
            onToggleDelete = { viewModel.toggleDeleteZipEntry(entry.path) },
          )
          HorizontalDivider(color = DividerWarm.copy(alpha = 0.45f), thickness = 0.5.dp)
        }
      }
    }
  }

  if (previewPath != null) {
    AlertDialog(
      onDismissRequest = {
        previewPath = null
        previewBitmap = null
        previewText = null
      },
      containerColor = SoftPeachSurface,
      title = {
        Text(
          text = previewPath!!.substringAfterLast('/'),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          color = TextPrimaryDark,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      },
      text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = previewPath!!,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryWarm,
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(12.dp))
          val bmp = previewBitmap
          if (bmp != null) {
            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(180.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(PureWhite),
              contentAlignment = Alignment.Center,
            ) {
              Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = previewPath,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(12.dp).fillMaxSize(),
              )
            }
          } else if (previewText != null) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = PureWhite,
              modifier = Modifier.fillMaxWidth().height(220.dp),
            ) {
              Text(
                text = previewText!!,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimaryDark,
                modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val pathToReplace = previewPath
            previewPath = null
            previewBitmap = null
            previewText = null
            if (pathToReplace != null) {
              pendingReplacePath = pathToReplace
              replaceFileLauncher.launch(arrayOf("*/*"))
            }
          }
        ) {
          Text(stringResource(R.string.action_replace), color = TerracottaPrimary)
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            previewPath = null
            previewBitmap = null
            previewText = null
          }
        ) {
          Text(stringResource(R.string.action_close), color = TextPrimaryDark)
        }
      },
    )
  }
}

@Composable
private fun ZipEntryRow(
  entry: ApkEntryItem,
  isReplaced: Boolean,
  isDeleted: Boolean,
  replacedSize: Long?,
  onPreview: () -> Unit,
  onReplace: () -> Unit,
  onToggleDelete: () -> Unit,
) {
  val lower = entry.path.lowercase(Locale.ROOT)
  val icon =
    when {
      lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ->
        Icons.Outlined.Image
      lower.endsWith(".xml") || lower.endsWith(".json") -> Icons.Outlined.Description
      lower.endsWith(".dex") || lower.endsWith(".so") -> Icons.Outlined.Code
      else -> Icons.Outlined.InsertDriveFile
    }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onPreview)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint =
        when {
          isDeleted -> MaterialTheme.colorScheme.error
          isReplaced -> SuccessGreen
          else -> TerracottaPrimary
        },
      modifier = Modifier.size(22.dp),
    )
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.path,
        style =
          MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            textDecoration = if (isDeleted) TextDecoration.LineThrough else TextDecoration.None,
          ),
        color = if (isDeleted) TextSecondaryGray else TextPrimaryDark,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      val sizeStr = ApkInstallHelper.formatFileSize(replacedSize ?: entry.size)
      val statusTag =
        when {
          isDeleted -> " • Marked for removal"
          isReplaced -> " • Replaced"
          else -> ""
        }
      Text(
        text = "$sizeStr$statusTag",
        style = MaterialTheme.typography.labelSmall,
        color =
          when {
            isDeleted -> MaterialTheme.colorScheme.error
            isReplaced -> SuccessGreen
            else -> TextSecondaryGray
          },
      )
    }
    IconButton(onClick = onReplace) {
      Icon(
        imageVector = Icons.Outlined.SwapHoriz,
        contentDescription = stringResource(R.string.action_replace),
        tint = TerracottaPrimary,
      )
    }
    IconButton(onClick = onToggleDelete) {
      Icon(
        imageVector = if (isDeleted) Icons.Outlined.RestoreFromTrash else Icons.Outlined.DeleteOutline,
        contentDescription = stringResource(R.string.action_delete),
        tint = if (isDeleted) TerracottaPrimary else TextSecondaryWarm,
      )
    }
  }
}

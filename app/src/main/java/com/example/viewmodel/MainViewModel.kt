package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppSettings
import com.example.data.InstalledAppItem
import com.example.data.InstalledAppsRepository
import com.example.data.SettingsRepository
import com.example.engine.ApkEditRequest
import com.example.engine.ApkEditorEngine
import com.example.engine.ApkEntryItem
import com.example.engine.ApkSignerHelper
import com.example.engine.KeystoreCertificateInfo
import com.example.engine.ManifestPatchSpec
import com.example.engine.ParsedManifestInfo
import com.example.engine.SavedApkResult
import com.example.engine.SavedProjectItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppFilterMode {
  USER_APPS,
  SYSTEM_APPS,
  ALL_APPS,
}

enum class AppSortOrder {
  NAME_ASC,
  NAME_DESC,
}

data class ActiveApkTarget(
  val displayName: String,
  val packageName: String,
  val sourceApkFile: File,
  val icon: Bitmap?,
  val manifestInfo: ParsedManifestInfo,
  val isExternalFile: Boolean,
)

sealed interface BuildOperationState {
  data object Idle : BuildOperationState
  data class InProgress(val statusMessage: String) : BuildOperationState
  data class Success(val result: SavedApkResult) : BuildOperationState
  data class Error(val message: String) : BuildOperationState
}

sealed interface TargetLoadState {
  data object Idle : TargetLoadState
  data object Loading : TargetLoadState
  data class Error(val message: String) : TargetLoadState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val appsRepository = InstalledAppsRepository(appContext)
  private val settingsRepository = SettingsRepository(appContext)

  val settings: StateFlow<AppSettings> =
    settingsRepository.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = AppSettings(),
    )

  // Installed apps list state
  private val _allInstalledApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
  private val _isLoadingApps = MutableStateFlow(false)
  val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

  private val _filterMode = MutableStateFlow(AppFilterMode.ALL_APPS)
  val filterMode: StateFlow<AppFilterMode> = _filterMode.asStateFlow()

  private val _sortOrder = MutableStateFlow(AppSortOrder.NAME_ASC)
  val sortOrder: StateFlow<AppSortOrder> = _sortOrder.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  val filteredApps: StateFlow<List<InstalledAppItem>> =
    combine(_allInstalledApps, _filterMode, _sortOrder, _searchQuery) { apps, filter, sort, query ->
      val byFilter =
        when (filter) {
          AppFilterMode.USER_APPS -> apps.filter { !it.isSystemApp }
          AppFilterMode.SYSTEM_APPS -> apps.filter { it.isSystemApp }
          AppFilterMode.ALL_APPS -> apps
        }
      val trimmed = query.trim().lowercase(Locale.ROOT)
      val byQuery =
        if (trimmed.isEmpty()) {
          byFilter
        } else {
          byFilter.filter {
            it.appName.lowercase(Locale.ROOT).contains(trimmed) ||
              it.packageName.lowercase(Locale.ROOT).contains(trimmed)
          }
        }
      when (sort) {
        AppSortOrder.NAME_ASC -> byQuery.sortedBy { it.appName.lowercase(Locale.ROOT) }
        AppSortOrder.NAME_DESC -> byQuery.sortedByDescending { it.appName.lowercase(Locale.ROOT) }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // Icon LRU cache for smooth scrolling in Select Application list
  private val iconCache = LruCache<String, Bitmap>(160)

  // Active selected APK target & Edit Mode dialog state
  private val _activeTarget = MutableStateFlow<ActiveApkTarget?>(null)
  val activeTarget: StateFlow<ActiveApkTarget?> = _activeTarget.asStateFlow()

  private val _showEditModeDialog = MutableStateFlow(false)
  val showEditModeDialog: StateFlow<Boolean> = _showEditModeDialog.asStateFlow()

  private val _targetLoadState = MutableStateFlow<TargetLoadState>(TargetLoadState.Idle)
  val targetLoadState: StateFlow<TargetLoadState> = _targetLoadState.asStateFlow()

  // ZIP explorer entries & pending replacements for Simple/Full edit
  private val _zipEntries = MutableStateFlow<List<ApkEntryItem>>(emptyList())
  val zipEntries: StateFlow<List<ApkEntryItem>> = _zipEntries.asStateFlow()

  private val _isLoadingEntries = MutableStateFlow(false)
  val isLoadingEntries: StateFlow<Boolean> = _isLoadingEntries.asStateFlow()

  private val _fileReplacements = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
  val fileReplacements: StateFlow<Map<String, ByteArray>> = _fileReplacements.asStateFlow()

  private val _deletedEntries = MutableStateFlow<Set<String>>(emptySet())
  val deletedEntries: StateFlow<Set<String>> = _deletedEntries.asStateFlow()

  // Build / Sign / Save operation state
  private val _buildState = MutableStateFlow<BuildOperationState>(BuildOperationState.Idle)
  val buildState: StateFlow<BuildOperationState> = _buildState.asStateFlow()

  // Saved Projects screen state
  private val _savedProjects = MutableStateFlow<List<SavedProjectItem>>(emptyList())
  val savedProjects: StateFlow<List<SavedProjectItem>> = _savedProjects.asStateFlow()

  private val _isLoadingProjects = MutableStateFlow(false)
  val isLoadingProjects: StateFlow<Boolean> = _isLoadingProjects.asStateFlow()

  // Keystore certificate info for Settings / Current Activity
  private val _certInfo = MutableStateFlow<KeystoreCertificateInfo?>(null)
  val certInfo: StateFlow<KeystoreCertificateInfo?> = _certInfo.asStateFlow()

  init {
    refreshCertificateInfo()
  }

  fun loadInstalledAppsIfNeeded(forceReload: Boolean = false) {
    if (!forceReload && _allInstalledApps.value.isNotEmpty()) return
    viewModelScope.launch {
      _isLoadingApps.value = true
      try {
        _allInstalledApps.value = appsRepository.loadInstalledApps()
      } finally {
        _isLoadingApps.value = false
      }
    }
  }

  suspend fun getAppIcon(packageName: String): Bitmap? {
    iconCache.get(packageName)?.let { return it }
    val loaded = appsRepository.loadAppIcon(packageName)
    if (loaded != null) {
      iconCache.put(packageName, loaded)
    }
    return loaded
  }

  fun setFilterMode(mode: AppFilterMode) {
    _filterMode.value = mode
  }

  fun toggleSortOrder() {
    _sortOrder.update {
      if (it == AppSortOrder.NAME_ASC) AppSortOrder.NAME_DESC else AppSortOrder.NAME_ASC
    }
  }

  fun setSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun dismissEditModeDialog() {
    _showEditModeDialog.value = false
  }

  fun clearTargetLoadError() {
    _targetLoadState.value = TargetLoadState.Idle
  }

  fun selectInstalledApp(item: InstalledAppItem) {
    viewModelScope.launch {
      _targetLoadState.value = TargetLoadState.Loading
      try {
        val apkFile = File(item.sourceApkPath)
        if (!apkFile.exists() || !apkFile.canRead()) {
          throw IllegalStateException("Cannot read APK file at ${item.sourceApkPath}")
        }
        val (parsed, icon) = ApkEditorEngine.inspectApk(appContext, apkFile)
        val mergedManifest =
          parsed.copy(
            appLabel =
              if (parsed.appLabel.isBlank() || parsed.isLabelResourceRef) item.appName
              else parsed.appLabel,
            packageName = parsed.packageName.ifBlank { item.packageName },
            versionName = parsed.versionName.ifBlank { item.versionName },
            versionCode = if (parsed.versionCode > 0) parsed.versionCode else item.versionCode.toInt(),
            minSdkVersion = if (parsed.minSdkVersion > 0) parsed.minSdkVersion else item.minSdk,
            targetSdkVersion =
              if (parsed.targetSdkVersion > 0) parsed.targetSdkVersion else item.targetSdk,
          )
        val finalIcon = icon ?: getAppIcon(item.packageName)
        resetPendingFileEdits()
        _activeTarget.value =
          ActiveApkTarget(
            displayName = item.appName,
            packageName = item.packageName,
            sourceApkFile = apkFile,
            icon = finalIcon,
            manifestInfo = mergedManifest,
            isExternalFile = false,
          )
        _targetLoadState.value = TargetLoadState.Idle
        _showEditModeDialog.value = true
      } catch (e: Exception) {
        _targetLoadState.value =
          TargetLoadState.Error(e.message ?: "Failed to inspect selected application APK.")
      }
    }
  }

  fun selectExternalApkUri(uri: Uri) {
    viewModelScope.launch {
      _targetLoadState.value = TargetLoadState.Loading
      try {
        val copiedFile = appsRepository.copyExternalApkToCache(uri)
        val (parsed, icon) = ApkEditorEngine.inspectApk(appContext, copiedFile)
        resetPendingFileEdits()
        _activeTarget.value =
          ActiveApkTarget(
            displayName = parsed.appLabel.ifBlank { copiedFile.nameWithoutExtension },
            packageName = parsed.packageName.ifBlank { "unknown.package" },
            sourceApkFile = copiedFile,
            icon = icon,
            manifestInfo = parsed,
            isExternalFile = true,
          )
        _targetLoadState.value = TargetLoadState.Idle
        _showEditModeDialog.value = true
      } catch (e: Exception) {
        _targetLoadState.value =
          TargetLoadState.Error(
            "Unable to open APK file. Make sure you selected a valid .apk archive.\n(${e.message})"
          )
      }
    }
  }

  fun selectSavedProjectForEdit(project: SavedProjectItem) {
    viewModelScope.launch {
      _targetLoadState.value = TargetLoadState.Loading
      try {
        val file = project.localFile ?: appsRepository.copyExternalApkToCache(project.contentUri)
        val (parsed, icon) = ApkEditorEngine.inspectApk(appContext, file)
        resetPendingFileEdits()
        _activeTarget.value =
          ActiveApkTarget(
            displayName = parsed.appLabel.ifBlank { project.appName },
            packageName = parsed.packageName.ifBlank { project.packageName },
            sourceApkFile = file,
            icon = icon ?: project.icon,
            manifestInfo = parsed,
            isExternalFile = true,
          )
        _targetLoadState.value = TargetLoadState.Idle
        _showEditModeDialog.value = true
      } catch (e: Exception) {
        _targetLoadState.value =
          TargetLoadState.Error("Failed to open project APK: ${e.message}")
      }
    }
  }

  fun loadZipEntriesForActiveTarget() {
    val target = _activeTarget.value ?: return
    viewModelScope.launch {
      _isLoadingEntries.value = true
      try {
        _zipEntries.value = ApkEditorEngine.listApkEntries(target.sourceApkFile)
      } catch (_: Exception) {
        _zipEntries.value = emptyList()
      } finally {
        _isLoadingEntries.value = false
      }
    }
  }

  suspend fun readEntryPreviewBytes(path: String): ByteArray? {
    _fileReplacements.value[path]?.let { return it }
    val target = _activeTarget.value ?: return null
    return ApkEditorEngine.extractEntryBytes(target.sourceApkFile, path)
  }

  fun replaceZipEntryFromUri(entryPath: String, uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val bytes =
          appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
        _fileReplacements.update { it + (entryPath to bytes) }
        _deletedEntries.update { it - entryPath }
      } catch (_: Exception) {
      }
    }
  }

  fun toggleDeleteZipEntry(entryPath: String) {
    _deletedEntries.update { set ->
      if (entryPath in set) set - entryPath else set + entryPath
    }
  }

  fun resetPendingFileEdits() {
    _fileReplacements.value = emptyMap()
    _deletedEntries.value = emptySet()
  }

  suspend fun decodeImageUriToBitmap(uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
      try {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
          BitmapFactory.decodeStream(input)
        }
      } catch (_: Exception) {
        null
      }
    }

  fun saveEditedApk(
    manifestPatch: ManifestPatchSpec?,
    newLauncherIcon: Bitmap?,
    includeZipFileEdits: Boolean,
    outputBaseName: String,
  ) {
    val target = _activeTarget.value ?: return
    viewModelScope.launch {
      _buildState.value = BuildOperationState.InProgress("Preparing APK editor engine...")
      try {
        val request =
          ApkEditRequest(
            manifestPatch = manifestPatch,
            newLauncherIcon = newLauncherIcon,
            fileReplacements = if (includeZipFileEdits) _fileReplacements.value else emptyMap(),
            deletedEntries = if (includeZipFileEdits) _deletedEntries.value else emptySet(),
            outputBaseName = outputBaseName.ifBlank { target.displayName },
          )
        val result =
          ApkEditorEngine.buildSignAndExportApk(
            context = appContext,
            sourceApkFile = target.sourceApkFile,
            request = request,
            signingOptions = settings.value.toSigningOptions(),
            onProgress = { msg -> _buildState.value = BuildOperationState.InProgress(msg) },
          )
        _buildState.value = BuildOperationState.Success(result)
        loadSavedProjects()
      } catch (e: Exception) {
        _buildState.value =
          BuildOperationState.Error(
            e.message ?: "An unexpected error occurred while building and signing the APK."
          )
      }
    }
  }

  fun dismissBuildState() {
    _buildState.value = BuildOperationState.Idle
  }

  fun loadSavedProjects() {
    viewModelScope.launch {
      _isLoadingProjects.value = true
      try {
        _savedProjects.value = ApkEditorEngine.listSavedProjects(appContext)
      } finally {
        _isLoadingProjects.value = false
      }
    }
  }

  fun deleteProject(item: SavedProjectItem) {
    viewModelScope.launch {
      ApkEditorEngine.deleteSavedProject(appContext, item)
      loadSavedProjects()
    }
  }

  fun updateV1Signing(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setV1Signing(enabled) }
  }

  fun updateV2Signing(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setV2Signing(enabled) }
  }

  fun updateV3Signing(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setV3Signing(enabled) }
  }

  fun refreshCertificateInfo(forceRegenerate: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (forceRegenerate) {
          ApkSignerHelper.ensureKeystore(appContext, forceRegenerate = true)
        }
        _certInfo.value = ApkSignerHelper.getCertificateInfo(appContext)
      } catch (_: Exception) {
      }
    }
  }
}

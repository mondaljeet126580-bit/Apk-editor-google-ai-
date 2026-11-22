package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.ApkEditorEngine
import com.example.engine.SigningOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "apk_editor_gm_settings"
)

data class AppSettings(
  val outputDirectoryDisplay: String = ApkEditorEngine.PUBLIC_DISPLAY_DIR,
  val v1SigningEnabled: Boolean = true,
  val v2SigningEnabled: Boolean = true,
  val v3SigningEnabled: Boolean = true,
) {
  fun toSigningOptions(): SigningOptions =
    SigningOptions(
      v1Enabled = v1SigningEnabled,
      v2Enabled = v2SigningEnabled,
      v3Enabled = v3SigningEnabled,
    )
}

class SettingsRepository(private val context: Context) {
  private object Keys {
    val OUTPUT_DIR = stringPreferencesKey("output_directory_display")
    val V1_SIGNING = booleanPreferencesKey("v1_signing_enabled")
    val V2_SIGNING = booleanPreferencesKey("v2_signing_enabled")
    val V3_SIGNING = booleanPreferencesKey("v3_signing_enabled")
  }

  val settingsFlow: Flow<AppSettings> =
    context.settingsDataStore.data.map { prefs ->
      AppSettings(
        outputDirectoryDisplay = prefs[Keys.OUTPUT_DIR] ?: ApkEditorEngine.PUBLIC_DISPLAY_DIR,
        v1SigningEnabled = prefs[Keys.V1_SIGNING] ?: true,
        v2SigningEnabled = prefs[Keys.V2_SIGNING] ?: true,
        v3SigningEnabled = prefs[Keys.V3_SIGNING] ?: true,
      )
    }

  suspend fun setV1Signing(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.V1_SIGNING] = enabled }
  }

  suspend fun setV2Signing(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.V2_SIGNING] = enabled }
  }

  suspend fun setV3Signing(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.V3_SIGNING] = enabled }
  }
}

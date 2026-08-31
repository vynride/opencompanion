// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.vynride.opencompanion.core.config.CompanionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private object Keys {
    val companionName = stringPreferencesKey("companion_name")
    val locationName = stringPreferencesKey("location_name")
    val lat = doublePreferencesKey("lat")
    val lon = doublePreferencesKey("lon")
    val timezone = stringPreferencesKey("timezone")
    val baseUrl = stringPreferencesKey("base_url")
    val apiKey = stringPreferencesKey("api_key")
    val authHeader = stringPreferencesKey("auth_header")
    val chatModel = stringPreferencesKey("chat_model")
    val transcribeModel = stringPreferencesKey("transcribe_model")
    val ttsModel = stringPreferencesKey("tts_model")
    val chatApi = stringPreferencesKey("chat_api")
    val voice = stringPreferencesKey("voice")
    val speed = floatPreferencesKey("speed")
    val wakeThreshold = floatPreferencesKey("wake_threshold")
    val wakeModelFile = stringPreferencesKey("wake_model_file")
    val exaKey = stringPreferencesKey("exa_key")
    val laptopHost = stringPreferencesKey("laptop_host")
    val cameraEnabled = booleanPreferencesKey("camera_enabled")
    val kioskPinned = booleanPreferencesKey("kiosk_pinned")
    val cameraStepDone = booleanPreferencesKey("camera_step_done")
}

/** Preferences-DataStore-backed store for every user-editable setting. */
class SettingsRepository(
    private val context: Context,
) {
    val flow: Flow<Settings> =
        context.settingsDataStore.data.map { prefs ->
            Settings(
                companionName = prefs[Keys.companionName] ?: "",
                locationName = prefs[Keys.locationName] ?: "",
                lat = prefs[Keys.lat] ?: 0.0,
                lon = prefs[Keys.lon] ?: 0.0,
                timezone = prefs[Keys.timezone] ?: "",
                baseUrl = prefs[Keys.baseUrl] ?: "",
                apiKey = prefs[Keys.apiKey] ?: "",
                authHeader = prefs[Keys.authHeader] ?: "",
                chatModel = prefs[Keys.chatModel] ?: "",
                transcribeModel = prefs[Keys.transcribeModel] ?: "",
                ttsModel = prefs[Keys.ttsModel] ?: "",
                chatApi = prefs[Keys.chatApi] ?: "",
                voice = prefs[Keys.voice] ?: "",
                speed = prefs[Keys.speed] ?: 1.0f,
                wakeThreshold = prefs[Keys.wakeThreshold] ?: 0.4f,
                wakeModelFile = prefs[Keys.wakeModelFile] ?: "",
                exaKey = prefs[Keys.exaKey] ?: "",
                laptopHost = prefs[Keys.laptopHost] ?: "",
                cameraEnabled = prefs[Keys.cameraEnabled] ?: true,
                kioskPinned = prefs[Keys.kioskPinned] ?: false,
                cameraStepDone = prefs[Keys.cameraStepDone] ?: false,
            )
        }

    suspend fun current(): Settings = flow.first()

    suspend fun config(): CompanionConfig = ConfigMapper.toConfig(current())

    suspend fun update(transform: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(transform)
    }

    suspend fun setCompanionName(value: String) = update { it[Keys.companionName] = value }

    suspend fun setLocationName(value: String) = update { it[Keys.locationName] = value }

    suspend fun setLat(value: Double) = update { it[Keys.lat] = value }

    suspend fun setLon(value: Double) = update { it[Keys.lon] = value }

    suspend fun setTimezone(value: String) = update { it[Keys.timezone] = value }

    suspend fun setBaseUrl(value: String) = update { it[Keys.baseUrl] = value }

    suspend fun setApiKey(value: String) = update { it[Keys.apiKey] = value }

    suspend fun setAuthHeader(value: String) = update { it[Keys.authHeader] = value }

    suspend fun setChatModel(value: String) = update { it[Keys.chatModel] = value }

    suspend fun setTranscribeModel(value: String) = update { it[Keys.transcribeModel] = value }

    suspend fun setTtsModel(value: String) = update { it[Keys.ttsModel] = value }

    suspend fun setChatApi(value: String) = update { it[Keys.chatApi] = value }

    suspend fun setVoice(value: String) = update { it[Keys.voice] = value }

    suspend fun setSpeed(value: Float) = update { it[Keys.speed] = value }

    suspend fun setWakeThreshold(value: Float) = update { it[Keys.wakeThreshold] = value }

    suspend fun setExaKey(value: String) = update { it[Keys.exaKey] = value }

    suspend fun setLaptopHost(value: String) = update { it[Keys.laptopHost] = value }

    suspend fun setCameraEnabled(value: Boolean) = update { it[Keys.cameraEnabled] = value }

    suspend fun setKioskPinned(value: Boolean) = update { it[Keys.kioskPinned] = value }

    suspend fun setCameraStepDone(value: Boolean) = update { it[Keys.cameraStepDone] = value }

    /** Copies the picked document into `filesDir/models/wakeword/<name>` and remembers it. */
    suspend fun importWakeModel(
        uri: Uri,
        resolver: ContentResolver,
    ): String {
        val displayName = queryDisplayName(uri, resolver) ?: "wakeword.onnx"
        val dir = File(context.filesDir, "models/wakeword").apply { mkdirs() }
        val dest = File(dir, displayName)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val stored = "wakeword/$displayName"
        update { it[Keys.wakeModelFile] = stored }
        return stored
    }

    private fun queryDisplayName(
        uri: Uri,
        resolver: ContentResolver,
    ): String? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0 || !cursor.moveToFirst()) null else cursor.getString(column)
    }
}

// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.vynride.opencompanion.R
import kotlinx.coroutines.launch

@Composable
internal fun CompanionPage(
    repo: SettingsRepository,
    onOpenPersonality: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    SettingsPage(stringResource(R.string.settings_page_companion), onBack) {
        LabeledField(
            label = stringResource(R.string.settings_companion_name),
            value = settings.companionName,
            onChange = { scope.launch { repo.setCompanionName(it) } },
        )
        ListItem(
            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.settings_edit_personality)) },
            supportingContent = { Text(stringResource(R.string.settings_personality_summary)) },
            modifier = Modifier.clickable(onClick = onOpenPersonality),
        )
    }
}

@Composable
internal fun AiServicesPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    SettingsPage(stringResource(R.string.settings_page_services), onBack) {
        Text(
            stringResource(R.string.settings_shared_endpoint),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        LabeledField(
            label = stringResource(R.string.settings_base_url),
            value = settings.baseUrl,
            onChange = { scope.launch { repo.setBaseUrl(it) } },
        )
        LabeledField(
            label = stringResource(R.string.settings_api_key),
            value = settings.apiKey,
            onChange = { scope.launch { repo.setApiKey(it) } },
            visualTransformation = PasswordVisualTransformation(),
        )
        Dropdown(
            label = stringResource(R.string.settings_auth_header),
            options = listOf("authorization", "api-key"),
            selected = settings.authHeader.ifBlank { "authorization" },
            onSelect = { scope.launch { repo.setAuthHeader(it) } },
        )
        LabeledField(
            label = stringResource(R.string.settings_chat_model),
            value = settings.chatModel,
            onChange = { scope.launch { repo.setChatModel(it) } },
        )
        LabeledField(
            label = stringResource(R.string.settings_transcribe_model),
            value = settings.transcribeModel,
            onChange = { scope.launch { repo.setTranscribeModel(it) } },
        )
        LabeledField(
            label = stringResource(R.string.settings_tts_model),
            value = settings.ttsModel,
            onChange = { scope.launch { repo.setTtsModel(it) } },
        )
        Dropdown(
            label = stringResource(R.string.settings_chat_api),
            options = listOf("chat", "responses"),
            selected = settings.chatApi.ifBlank { "chat" },
            onSelect = { scope.launch { repo.setChatApi(it) } },
        )

        Text(
            stringResource(R.string.settings_group_api_overrides),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        OverrideCard(
            service = stringResource(R.string.settings_override_chat),
            baseUrl = settings.chatBaseUrl,
            apiKey = settings.chatApiKey,
            authHeader = settings.chatAuthHeader,
            onBaseUrl = { scope.launch { repo.setChatBaseUrl(it) } },
            onApiKey = { scope.launch { repo.setChatApiKey(it) } },
            onAuthHeader = { scope.launch { repo.setChatAuthHeader(it) } },
        )
        OverrideCard(
            service = stringResource(R.string.settings_override_transcribe),
            baseUrl = settings.transcribeBaseUrl,
            apiKey = settings.transcribeApiKey,
            authHeader = settings.transcribeAuthHeader,
            onBaseUrl = { scope.launch { repo.setTranscribeBaseUrl(it) } },
            onApiKey = { scope.launch { repo.setTranscribeApiKey(it) } },
            onAuthHeader = { scope.launch { repo.setTranscribeAuthHeader(it) } },
        )
        OverrideCard(
            service = stringResource(R.string.settings_override_tts),
            baseUrl = settings.ttsBaseUrl,
            apiKey = settings.ttsApiKey,
            authHeader = settings.ttsAuthHeader,
            onBaseUrl = { scope.launch { repo.setTtsBaseUrl(it) } },
            onApiKey = { scope.launch { repo.setTtsApiKey(it) } },
            onAuthHeader = { scope.launch { repo.setTtsAuthHeader(it) } },
        )
    }
}

/** One service's endpoint overrides; blank fields and the inherit choice fall back to the shared API settings. */
@Composable
private fun OverrideCard(
    service: String,
    baseUrl: String,
    apiKey: String,
    authHeader: String,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onAuthHeader: (String) -> Unit,
) {
    // Open when any override is set, so active overrides are visible at a glance.
    var expanded by remember { mutableStateOf(baseUrl.isNotBlank() || apiKey.isNotBlank() || authHeader.isNotBlank()) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(service) },
            trailingContent = {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                LabeledField(
                    label = stringResource(R.string.settings_base_url),
                    value = baseUrl,
                    onChange = onBaseUrl,
                )
                LabeledField(
                    label = stringResource(R.string.settings_api_key),
                    value = apiKey,
                    onChange = onApiKey,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Dropdown(
                    label = stringResource(R.string.settings_auth_header),
                    options = listOf("inherit", "authorization", "api-key"),
                    selected = authHeader.ifBlank { "inherit" },
                    onSelect = { onAuthHeader(if (it == "inherit") "" else it) },
                )
            }
        }
    }
}

@Composable
internal fun VoicePage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    SettingsPage(stringResource(R.string.settings_page_voice), onBack) {
        LabeledField(
            label = stringResource(R.string.settings_voice),
            value = settings.voice,
            onChange = { scope.launch { repo.setVoice(it) } },
        )
        LabeledSlider(
            label = stringResource(R.string.settings_speed),
            value = settings.speed,
            valueRange = 0.5f..2.0f,
            onCommit = { scope.launch { repo.setSpeed(it) } },
        )
    }
}

@Composable
internal fun WakeWordPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    val context = LocalContext.current
    SettingsPage(stringResource(R.string.settings_page_wake_word), onBack) {
        LabeledSlider(
            label = stringResource(R.string.settings_wake_threshold),
            value = settings.wakeThreshold,
            valueRange = 0.05f..0.95f,
            onCommit = { scope.launch { repo.setWakeThreshold(it) } },
        )
        Text(
            settings.wakeModelFile.ifBlank { stringResource(R.string.settings_wake_model_none) },
            style = MaterialTheme.typography.bodySmall,
        )
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch { repo.importWakeModel(uri, context.contentResolver) }
                }
            }
        Button(onClick = { launcher.launch(arrayOf("application/octet-stream", "*/*")) }) {
            Text(stringResource(R.string.settings_import_model))
        }
    }
}

@Composable
internal fun IntegrationsPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    SettingsPage(stringResource(R.string.settings_page_integrations), onBack) {
        LabeledField(
            label = stringResource(R.string.settings_exa_key),
            value = settings.exaKey,
            onChange = { scope.launch { repo.setExaKey(it) } },
            visualTransformation = PasswordVisualTransformation(),
        )
        LabeledField(
            label = stringResource(R.string.settings_laptop_host),
            value = settings.laptopHost,
            onChange = { scope.launch { repo.setLaptopHost(it) } },
        )
        SwitchRow(
            label = stringResource(R.string.settings_camera_enabled),
            checked = settings.cameraEnabled,
            onCheckedChange = { scope.launch { repo.setCameraEnabled(it) } },
        )
    }
}

@Composable
internal fun DevicePage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    val context = LocalContext.current
    SettingsPage(stringResource(R.string.settings_page_device), onBack) {
        var homeEnabled by remember { mutableStateOf(HomeAlias.isEnabled(context)) }
        SwitchRow(
            label = stringResource(R.string.settings_use_as_home),
            checked = homeEnabled,
            onCheckedChange = { enabled ->
                HomeAlias.setEnabled(context, enabled)
                homeEnabled = enabled
            },
        )
        SwitchRow(
            label = stringResource(R.string.settings_pin_screen),
            checked = settings.kioskPinned,
            onCheckedChange = { scope.launch { repo.setKioskPinned(it) } },
        )
    }
}

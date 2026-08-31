// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.vynride.opencompanion.R
import io.github.vynride.opencompanion.service.CompanionService
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(repo: SettingsRepository) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        Group(stringResource(R.string.settings_group_companion)) {
            LabeledField(
                label = stringResource(R.string.settings_companion_name),
                value = settings.companionName,
                onChange = { scope.launch { repo.setCompanionName(it) } },
            )
            PersonalityEditor(context)
        }

        Group(stringResource(R.string.settings_group_location)) {
            LabeledField(
                label = stringResource(R.string.settings_location_name),
                value = settings.locationName,
                onChange = { scope.launch { repo.setLocationName(it) } },
            )
            LabeledField(
                label = stringResource(R.string.settings_lat),
                value = settings.lat.toString(),
                onChange = { it.toDoubleOrNull()?.let { v -> scope.launch { repo.setLat(v) } } },
                keyboardType = KeyboardType.Decimal,
            )
            LabeledField(
                label = stringResource(R.string.settings_lon),
                value = settings.lon.toString(),
                onChange = { it.toDoubleOrNull()?.let { v -> scope.launch { repo.setLon(v) } } },
                keyboardType = KeyboardType.Decimal,
            )
            LabeledField(
                label = stringResource(R.string.settings_timezone),
                value = settings.timezone,
                onChange = { scope.launch { repo.setTimezone(it) } },
            )
        }

        Group(stringResource(R.string.settings_group_api)) {
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
        }

        Group(stringResource(R.string.settings_group_voice)) {
            LabeledField(
                label = stringResource(R.string.settings_voice),
                value = settings.voice,
                onChange = { scope.launch { repo.setVoice(it) } },
            )
            Text(stringResource(R.string.settings_speed) + ": ${"%.2f".format(settings.speed)}")
            Slider(
                value = settings.speed,
                onValueChange = { scope.launch { repo.setSpeed(it) } },
                valueRange = 0.5f..2.0f,
            )
        }

        Group(stringResource(R.string.settings_group_wake_word)) {
            Text(stringResource(R.string.settings_wake_threshold) + ": ${"%.2f".format(settings.wakeThreshold)}")
            Slider(
                value = settings.wakeThreshold,
                onValueChange = { scope.launch { repo.setWakeThreshold(it) } },
                valueRange = 0.05f..0.95f,
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

        Group(stringResource(R.string.settings_group_extras)) {
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

        Group(stringResource(R.string.settings_group_device)) {
            SwitchRow(
                label = stringResource(R.string.settings_use_as_home),
                checked = settings.kioskPinned,
                onCheckedChange = { enabled -> HomeAlias.setEnabled(context, enabled) },
            )
            SwitchRow(
                label = stringResource(R.string.settings_pin_screen),
                checked = settings.kioskPinned,
                onCheckedChange = { scope.launch { repo.setKioskPinned(it) } },
            )
            Button(onClick = { restartCompanion(context) }) {
                Text(stringResource(R.string.settings_restart_companion))
            }
        }

        Group(stringResource(R.string.settings_group_about)) {
            val versionName =
                remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                        .getOrNull() ?: ""
                }
            Text(stringResource(R.string.settings_version, versionName))
            Text(stringResource(R.string.settings_licence), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Settings changes take effect the next time the service starts; restarting it here
// is the only way to apply them immediately, there is no live-reload path.
private fun restartCompanion(context: Context) {
    val hasMic =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    val intent = Intent(context, CompanionService::class.java)
    context.stopService(intent)
    if (hasMic) {
        ContextCompat.startForegroundService(context, intent)
    }
}

@Composable
private fun PersonalityEditor(context: Context) {
    var open by remember { mutableStateOf(false) }
    val file = remember { File(context.filesDir, "memory/personality.md") }
    var text by remember { mutableStateOf("") }

    TextButton(onClick = {
        text = runCatching { file.readText() }.getOrDefault("")
        open = true
    }) {
        Text(stringResource(R.string.settings_edit_personality))
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_personality_title)) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                    open = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}

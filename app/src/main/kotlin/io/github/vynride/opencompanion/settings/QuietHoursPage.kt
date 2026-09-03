// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.vynride.opencompanion.R
import io.github.vynride.opencompanion.service.QuietHoursScheduler
import kotlinx.coroutines.launch

@Composable
internal fun QuietHoursPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    val context = LocalContext.current

    // Every change re-arms the alarm so the next boundary reflects the new window.
    fun apply(write: suspend () -> Unit) = scope.launch {
        write()
        QuietHoursScheduler.reschedule(context)
    }
    SettingsPage(stringResource(R.string.settings_page_quiet_hours), onBack) {
        SwitchRow(
            label = stringResource(R.string.settings_quiet_hours_enabled),
            checked = settings.quietHoursEnabled,
            onCheckedChange = { enabled -> apply { repo.setQuietHoursEnabled(enabled) } },
        )
        TimeRow(
            label = stringResource(R.string.settings_quiet_start),
            minutes = settings.quietStartMin,
            onPick = { minutes -> apply { repo.setQuietStartMin(minutes) } },
        )
        TimeRow(
            label = stringResource(R.string.settings_quiet_end),
            minutes = settings.quietEndMin,
            onPick = { minutes -> apply { repo.setQuietEndMin(minutes) } },
        )
        Text(stringResource(R.string.settings_quiet_hours_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** A row showing a time of day; tapping opens a picker and commits on OK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(
    label: String,
    minutes: Int,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(clockLabel(minutes)) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(state.hour * 60 + state.minute)
                    open = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }
}

internal fun clockLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.vynride.opencompanion.R
import kotlinx.coroutines.launch

@Composable
internal fun LocationPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    SettingsPage(stringResource(R.string.settings_page_location), onBack) {
        Text(
            stringResource(R.string.settings_manual_header),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        LabeledField(
            label = stringResource(R.string.settings_location_name),
            value = settings.locationName,
            onChange = { scope.launch { repo.setLocationName(it) } },
        )
        LabeledField(
            label = stringResource(R.string.settings_lat),
            value = settings.lat?.toString() ?: "",
            onChange = { it.toDoubleOrNull()?.let { v -> scope.launch { repo.setLat(v) } } },
            keyboardType = KeyboardType.Decimal,
        )
        LabeledField(
            label = stringResource(R.string.settings_lon),
            value = settings.lon?.toString() ?: "",
            onChange = { it.toDoubleOrNull()?.let { v -> scope.launch { repo.setLon(v) } } },
            keyboardType = KeyboardType.Decimal,
        )
        LabeledField(
            label = stringResource(R.string.settings_timezone),
            value = settings.timezone,
            onChange = { scope.launch { repo.setTimezone(it) } },
        )
    }
}

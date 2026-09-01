// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.vynride.opencompanion.R
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.TimeZone

@Composable
internal fun LocationPage(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val scope = rememberSettingsScope()
    // Lookups are page-scoped on purpose: leaving the page abandons an in-flight search.
    val lookupScope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<GeoPlace>()) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val failedToast = stringResource(R.string.settings_location_failed)

    fun fail() {
        Toast.makeText(context, failedToast, Toast.LENGTH_SHORT).show()
    }

    fun apply(place: GeoPlace) {
        scope.launch {
            if (place.name.isNotBlank()) repo.setLocationName(place.name)
            repo.setLat(place.latitude)
            repo.setLon(place.longitude)
            if (place.timezone.isNotBlank()) repo.setTimezone(place.timezone)
        }
        results = emptyList()
        searched = false
    }

    SettingsPage(stringResource(R.string.settings_page_location), onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_city_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !busy && query.isNotBlank(),
                onClick = {
                    lookupScope.launch {
                        busy = true
                        runCatching { searchCities(http, query.trim()) }
                            .onSuccess {
                                results = it
                                searched = true
                            }.onFailure { fail() }
                        busy = false
                    }
                },
            ) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.settings_search))
            }
        }
        if (searched && results.isEmpty()) {
            Text(stringResource(R.string.settings_no_matches), style = MaterialTheme.typography.bodySmall)
        }
        results.forEach { place ->
            ListItem(
                leadingContent = { Icon(Icons.Default.Place, contentDescription = null) },
                headlineContent = { Text(place.name) },
                supportingContent = { Text(place.detail) },
                modifier = Modifier.clickable { apply(place) },
            )
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                lookupScope.launch {
                    busy = true
                    runCatching { detectFromNetwork(http) }
                        .onSuccess { place -> if (place == null) fail() else apply(place) }
                        .onFailure { fail() }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Text(stringResource(R.string.settings_detect_network), modifier = Modifier.padding(start = 8.dp))
        }

        if (settings.timezone.isBlank()) {
            val deviceZone = remember { TimeZone.getDefault().id }
            TextButton(onClick = { scope.launch { repo.setTimezone(deviceZone) } }) {
                Text(stringResource(R.string.settings_use_device_timezone, deviceZone))
            }
        }

        Text(
            stringResource(R.string.settings_manual_header),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
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

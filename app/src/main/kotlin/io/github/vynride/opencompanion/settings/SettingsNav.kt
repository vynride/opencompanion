// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.vynride.opencompanion.R
import java.net.URI

internal object SettingsRoutes {
    const val HOME = "home"
    const val COMPANION = "companion"
    const val LOCATION = "location"
    const val SERVICES = "services"
    const val VOICE = "voice"
    const val WAKE_WORD = "wake_word"
    const val INTEGRATIONS = "integrations"
    const val DEVICE = "device"
    const val PERSONALITY = "personality"
}

@Composable
fun SettingsNav(repo: SettingsRepository) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = SettingsRoutes.HOME) {
        composable(SettingsRoutes.HOME) { SettingsHomePage(repo) { route -> nav.navigate(route) } }
        composable(SettingsRoutes.COMPANION) {
            CompanionPage(repo, onOpenPersonality = { nav.navigate(SettingsRoutes.PERSONALITY) }, onBack = back)
        }
        composable(SettingsRoutes.LOCATION) { LocationPage(repo, onBack = back) }
        composable(SettingsRoutes.SERVICES) { AiServicesPage(repo, onBack = back) }
        composable(SettingsRoutes.VOICE) { VoicePage(repo, onBack = back) }
        composable(SettingsRoutes.WAKE_WORD) { WakeWordPage(repo, onBack = back) }
        composable(SettingsRoutes.INTEGRATIONS) { IntegrationsPage(repo, onBack = back) }
        composable(SettingsRoutes.DEVICE) { DevicePage(repo, onBack = back) }
        composable(SettingsRoutes.PERSONALITY) { PersonalityPage(onBack = back) }
    }
}

/** The section list; each row shows the section and a one-line summary of its current values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomePage(
    repo: SettingsRepository,
    onOpen: (String) -> Unit,
) {
    val settings by repo.flow.collectAsState(initial = Settings())
    val context = LocalContext.current
    val notSet = stringResource(R.string.settings_not_set)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionRow(
                icon = Icons.Default.Face,
                title = stringResource(R.string.settings_page_companion),
                summary = settings.companionName.ifBlank { notSet },
            ) { onOpen(SettingsRoutes.COMPANION) }
            SectionRow(
                icon = Icons.Default.Place,
                title = stringResource(R.string.settings_page_location),
                summary = settings.locationName.ifBlank { notSet },
            ) { onOpen(SettingsRoutes.LOCATION) }
            SectionRow(
                icon = Icons.Default.Build,
                title = stringResource(R.string.settings_page_services),
                summary = baseHost(settings.baseUrl) ?: notSet,
            ) { onOpen(SettingsRoutes.SERVICES) }
            SectionRow(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.settings_page_voice),
                summary = settings.voice.ifBlank { notSet },
            ) { onOpen(SettingsRoutes.VOICE) }
            SectionRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.settings_page_wake_word),
                summary = settings.wakeModelFile.substringAfterLast('/').ifBlank { stringResource(R.string.settings_wake_model_none) },
            ) { onOpen(SettingsRoutes.WAKE_WORD) }
            SectionRow(
                icon = Icons.Default.Share,
                title = stringResource(R.string.settings_page_integrations),
                summary = stringResource(R.string.settings_summary_integrations),
            ) { onOpen(SettingsRoutes.INTEGRATIONS) }
            SectionRow(
                icon = Icons.Default.Home,
                title = stringResource(R.string.settings_page_device),
                summary = stringResource(R.string.settings_summary_device),
            ) { onOpen(SettingsRoutes.DEVICE) }

            Button(
                onClick = { restartCompanion(context) },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.settings_restart_companion))
            }

            val versionName =
                remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                        .getOrNull() ?: ""
                }
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.settings_licence),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, maxLines = 1) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun baseHost(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

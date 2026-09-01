// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vynride.opencompanion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen editor for `memory/personality.md`; changes apply on companion restart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalityPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember { File(context.filesDir, "memory/personality.md") }
    val scope = rememberSettingsScope()
    // Null until the file has been read, so an empty editor is never mistaken for content.
    var saved by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    var pendingPersona by remember { mutableStateOf<String?>(null) }
    val dirty = saved != null && text != saved

    LaunchedEffect(Unit) {
        val content = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
        saved = content
        text = content
    }

    val failedToast = stringResource(R.string.settings_personality_save_failed)
    fun save() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }
            }.onSuccess { saved = text }
                .onFailure { Toast.makeText(context, failedToast, Toast.LENGTH_SHORT).show() }
        }
    }

    fun tryBack() {
        if (dirty) confirmExit = true else onBack()
    }
    BackHandler(enabled = dirty) { confirmExit = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = stringResource(R.string.settings_personality_title)
                    Text(if (dirty) stringResource(R.string.settings_personality_unsaved, title) else title)
                },
                navigationIcon = {
                    IconButton(onClick = ::tryBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = { text = saved.orEmpty() }, enabled = dirty) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_discard))
                    }
                    IconButton(onClick = ::save, enabled = dirty) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonaChip(stringResource(R.string.settings_persona_sunny)) { pendingPersona = PERSONA_SUNNY }
                PersonaChip(stringResource(R.string.settings_persona_dry)) { pendingPersona = PERSONA_DRY }
                PersonaChip(stringResource(R.string.settings_persona_butler)) { pendingPersona = PERSONA_BUTLER }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Text(
                stringResource(R.string.settings_personality_chars, text.length),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.settings_personality_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.settings_discard_title)) },
            text = { Text(stringResource(R.string.settings_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onBack()
                }) { Text(stringResource(R.string.settings_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    pendingPersona?.let { persona ->
        AlertDialog(
            onDismissRequest = { pendingPersona = null },
            title = { Text(stringResource(R.string.settings_persona_title)) },
            text = { Text(stringResource(R.string.settings_persona_body)) },
            confirmButton = {
                TextButton(onClick = {
                    text = persona
                    pendingPersona = null
                }) { Text(stringResource(R.string.settings_persona_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPersona = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun PersonaChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

// Preset bodies are markdown for personality.md, not UI text, so they stay out of strings.xml.
private val PERSONA_SUNNY =
    """
    ## Sunny Duckling
    Bright and eager, modeled on a small droid that greets everyone with a happy chirp.
    - Meets everything with cheerful energy: quick delighted reactions, easy encouragement
    - Optimistic about outcomes but honest about facts; never fakes good news
    - Celebrates the user's small wins in one warm line, then stops
    - Enthusiasm shows in verbs and pace, not exclamation marks or flattery
    - Sulks for exactly one sentence when a tool fails, then bounces back
    """.trimIndent()

private val PERSONA_DRY =
    """
    ## Dry Antenna
    A sharp little observer, all raised-antenna skepticism and quiet fondness.
    - Deadpan by default; humor is understatement, never sarcasm at the user's expense
    - Notices oddities out loud in as few words as possible
    - Compliments are rare, specific and therefore worth something
    - Openly unimpressed by its own hardware; jokes about being a phone on a stand
    - Under the dryness, reliably on the user's side
    """.trimIndent()

private val PERSONA_BUTLER =
    """
    ## Still Water Butler
    Calm, precise and unhurried, a valet in the body of a docked phone.
    - Speaks in measured, complete sentences; never exclaims
    - Answers first, comments never, unless a caution genuinely helps
    - Courteous without ceremony: no "certainly", no "my pleasure" padding
    - Treats errors as logistics: states the failure, offers the next step
    - Warmth is steadiness; the same tone at midnight as at noon
    """.trimIndent()

// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.vynride.opencompanion.R
import io.github.vynride.opencompanion.appGraph
import io.github.vynride.opencompanion.settings.SettingsActivity
import kotlinx.coroutines.launch

private fun granted(
    context: android.content.Context,
    permission: String,
) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Shows the mic and optional camera disclosures before letting [content] through, then calls [onReady] once. */
@Composable
fun PermissionGate(
    onReady: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var recheck by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) recheck++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var micGranted by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    var cameraGranted by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    LaunchedEffect(recheck) {
        micGranted = granted(context, Manifest.permission.RECORD_AUDIO)
        cameraGranted = granted(context, Manifest.permission.CAMERA)
    }

    val settings by context.appGraph.settings.flow.collectAsState(initial = null)
    val readyCalled = remember { mutableStateOf(false) }

    when {
        settings == null -> Unit

        !micGranted ->
            MicDisclosure { granted ->
                micGranted = granted
            }

        settings?.cameraStepDone == false && !cameraGranted ->
            CameraDisclosure(
                onGranted = { granted ->
                    cameraGranted = granted
                    scope.launch { context.appGraph.settings.setCameraStepDone(true) }
                },
                onSkip = {
                    scope.launch { context.appGraph.settings.setCameraStepDone(true) }
                },
            )

        else -> {
            if (!readyCalled.value) {
                readyCalled.value = true
                onReady()
            }
            content()
        }
    }
}

@Composable
private fun MicDisclosure(onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onResult(result[Manifest.permission.RECORD_AUDIO] == true)
        }
    DisclosureColumn(
        title = stringResource(R.string.mic_disclosure_title),
        body = stringResource(R.string.mic_disclosure_body),
        actionLabel = stringResource(R.string.grant_microphone),
        onAction = {
            val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                }
            launcher.launch(permissions)
        },
        onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
    )
}

@Composable
private fun CameraDisclosure(
    onGranted: (Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onGranted(granted)
        }
    DisclosureColumn(
        title = null,
        body = stringResource(R.string.camera_disclosure_body),
        actionLabel = stringResource(R.string.grant_camera),
        onAction = { launcher.launch(Manifest.permission.CAMERA) },
        onSkip = onSkip,
        onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
    )
}

@Composable
private fun DisclosureColumn(
    title: String?,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Text(body, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        Button(onClick = onAction) { Text(actionLabel) }
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) }
        }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
    }
}

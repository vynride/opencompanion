// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.face

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.vynride.opencompanion.appGraph
import io.github.vynride.opencompanion.core.bus.StateChanged
import io.github.vynride.opencompanion.permissions.PermissionGate
import io.github.vynride.opencompanion.service.CompanionService
import io.github.vynride.opencompanion.settings.ScreenRelay
import io.github.vynride.opencompanion.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.vynride.opencompanion.core.Companion as CoreCompanion

class FaceActivity : ComponentActivity() {
    private val faceWebView: WebView by lazy { buildFaceWebView(this) }
    private var bound = false
    private var eventsJob: Job? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val binder = service as? CompanionService.LocalBinder ?: return
                // A rebuild swaps the companion instance; collectLatest drops the dead
                // instance's bus collection and rewires onto the new one.
                eventsJob =
                    lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            binder.companion.collectLatest { companion ->
                                if (companion != null) wireCompanion(companion)
                            }
                        }
                    }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

    private suspend fun wireCompanion(companion: CoreCompanion) {
        FaceMessages.forEvent(StateChanged(companion.state.value))?.let(faceWebView::push)
        companion.bus.events.collect { event ->
            FaceMessages.forEvent(event)?.let(faceWebView::push)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOverLockScreen()
        hideSystemBars()
        setContent {
            // The app lives on black screens; the default scheme is light and
            // renders its text near-black on them.
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Pure black matches nothing in the scheme, so the content color must be
                // explicit or Surface falls back to the default black-on-black.
                Surface(color = Color.Black, contentColor = Color.White) {
                    PermissionGate(onReady = { startAndBindCompanion() }) {
                        FaceContent(faceWebView, onLongPress = { openSettings() })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound && micGranted()) {
            startAndBindCompanion()
        }
    }

    private fun startAndBindCompanion() {
        if (bound) return
        CompanionService.start(this)
        CompanionService.bind(this, connection)
        bound = true
    }

    override fun onStop() {
        super.onStop()
        eventsJob?.cancel()
        eventsJob = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ScreenRelay.attach(window)
        if (runBlocking { appGraph.settings.current().kioskPinned }) {
            runCatching { startLockTask() }
        }
    }

    override fun onPause() {
        super.onPause()
        ScreenRelay.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        faceWebView.destroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun micGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** Full-size face WebView with a transparent long-press overlay on top that opens settings. */
@Composable
fun FaceContent(
    webView: WebView,
    onLongPress: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
        )
    }
}

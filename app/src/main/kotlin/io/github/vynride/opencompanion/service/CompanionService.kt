// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.vynride.opencompanion.appGraph
import io.github.vynride.opencompanion.core.Companion
import io.github.vynride.opencompanion.core.Ports
import io.github.vynride.opencompanion.core.ports.SystemClock
import io.github.vynride.opencompanion.platform.AndroidAudioInput
import io.github.vynride.opencompanion.platform.AndroidAudioOutput
import io.github.vynride.opencompanion.platform.AndroidClipboard
import io.github.vynride.opencompanion.platform.AndroidHaptics
import io.github.vynride.opencompanion.platform.AndroidNotifications
import io.github.vynride.opencompanion.platform.AndroidSensors
import io.github.vynride.opencompanion.platform.AssetModelStore
import io.github.vynride.opencompanion.platform.CameraXCamera
import io.github.vynride.opencompanion.settings.ScreenRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId

class CompanionService : Service() {
    inner class LocalBinder : Binder() {
        // A flow, not a snapshot: a rebuild swaps the instance in place and bound
        // clients must rewire to the new one instead of collecting a dead bus.
        val companion: StateFlow<Companion?> get() = this@CompanionService.companionFlow
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var buildJob: Job? = null
    private val companionFlow = MutableStateFlow<Companion?>(null)
    private var companion: Companion?
        get() = companionFlow.value
        set(value) {
            companionFlow.value = value
        }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            startForegroundWithTypes()
            rebuild()
            return START_STICKY
        }
        if (companion == null && buildJob?.isActive != true) {
            startForegroundWithTypes()
            rebuild()
        }
        return START_STICKY
    }

    // Building reads settings and loads models, so it runs off the main thread; the face
    // activity observes the binder's flow and wires up when the companion appears.
    private fun rebuild() {
        val previous = buildJob
        buildJob =
            scope.launch {
                previous?.cancelAndJoin()
                companion?.stop()
                companion = null
                val built = build()
                // Bail before starting anything so a destroy-time join returns promptly;
                // nothing to release here, resources are only acquired in start().
                ensureActive()
                companion = built
                built.start()
            }
    }

    // stopSelf() alone leaves a bound service running, and the face activity keeps the
    // service bound whenever it is open; tear the companion down explicitly so the stop
    // action works either way, and drop the notification with it. onDestroy still covers
    // a teardown cancelled mid-flight: the companion is nulled only after it stopped.
    private fun shutdown() {
        val previous = buildJob
        buildJob =
            scope.launch {
                previous?.cancelAndJoin()
                companion?.stop()
                companion = null
            }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithTypes() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ServiceCompat.startForeground(this, ServiceNotification.ID, ServiceNotification.build(this), types)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun build(): Companion {
        val graph = appGraph
        val config = graph.settings.config()
        val camera =
            if (granted(Manifest.permission.CAMERA) && config.cameraEnabled) {
                CameraXCamera(this, config.look.maxPx, graph.log)
            } else {
                null
            }
        val ports =
            Ports(
                audioInput = AndroidAudioInput(graph.log),
                audioOutput = AndroidAudioOutput(this, graph.log),
                camera = camera,
                sensors = AndroidSensors(this),
                screen = ScreenRelay,
                haptics = AndroidHaptics(this),
                notifications = AndroidNotifications(this),
                clipboard = AndroidClipboard(this),
                models = AssetModelStore(this),
                clock = SystemClock(ZoneId.of(config.location.timezone)),
            )
        val memoryDir = File(filesDir, "memory").apply { mkdirs() }
        seedPersonality(memoryDir)
        return Companion(config, ports, OkHttpClient(), memoryDir.toPath(), graph.log, extraRuntimeInfo = { batteryInfo() })
    }

    private fun seedPersonality(memoryDir: File) {
        val target = File(memoryDir, "personality.md")
        if (!target.exists()) assets.open("personality.md").use { target.outputStream().use(it::copyTo) }
    }

    private fun batteryInfo(): Map<String, String> {
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (pct in 0..100) mapOf("battery" to "$pct%") else emptyMap()
    }

    override fun onDestroy() {
        // Cancellation only lands at a suspension point, so an in-flight build can still
        // assign and start a companion after cancel() returns; wait for every job in the
        // scope (the current build and any superseded one) before stopping what survived.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        companion?.stop()
        companion = null
        super.onDestroy()
    }

    companion object Statics {
        private const val ACTION_STOP = "io.github.vynride.opencompanion.STOP"
        private const val ACTION_RESTART = "io.github.vynride.opencompanion.RESTART"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompanionService::class.java))
        }

        fun stopIntent(context: Context): Intent = Intent(context, CompanionService::class.java).setAction(ACTION_STOP)

        fun restartIntent(context: Context): Intent = Intent(context, CompanionService::class.java).setAction(ACTION_RESTART)

        fun bind(
            context: Context,
            connection: ServiceConnection,
        ) {
            context.bindService(Intent(context, CompanionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }
}

// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.platform

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.vynride.opencompanion.core.Log
import io.github.vynride.opencompanion.core.ports.Camera
import io.github.vynride.opencompanion.core.ports.Lens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Binds only ImageCapture for the duration of one capture; never holds the camera open otherwise. */
class CameraXCamera(
    private val context: Context,
    private val maxPx: Int,
    private val log: Log,
) : Camera {
    private val lifecycleOwner = CaptureLifecycleOwner()

    override suspend fun captureJpeg(lens: Lens): ByteArray =
        withContext(Dispatchers.Main) {
            val provider = awaitCameraProvider()
            val imageCapture =
                ImageCapture
                    .Builder()
                    .setResolutionSelector(
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(maxPx, maxPx), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            ).build(),
                    ).build()
            lifecycleOwner.start()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, lens.toCameraSelector(), imageCapture)
                takePicture(imageCapture)
            } catch (e: ImageCaptureException) {
                log.error("camera", "capture failed", e)
                throw e
            } finally {
                provider.unbindAll()
                lifecycleOwner.stop()
            }
        }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider =
                    try {
                        future.get()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return@addListener
                    }
                if (cont.isActive) cont.resume(provider)
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(false) }
        }

    private suspend fun takePicture(imageCapture: ImageCapture): ByteArray =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = image.toJpegBytes()
                        image.close()
                        cont.resume(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    private fun Lens.toCameraSelector(): CameraSelector =
        when (this) {
            Lens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            Lens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }

    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}

private class CaptureLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.CREATED
    }
}

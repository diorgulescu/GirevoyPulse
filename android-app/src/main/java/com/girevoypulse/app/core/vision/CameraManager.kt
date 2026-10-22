// Copyright (C) 2026 Dragos Iorgulescu & GirevoyPulse Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.girevoypulse.app.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Android CameraX adapter replacing iOS `AVCaptureSession`.
 * Configures the front selfie camera at 1080p and throttles frames (`frameStride = 2`, ~20–30 Hz)
 * on a dedicated background executor (`com.girevoypulse.visionqueue`).
 */
class CameraManager {

    enum class CameraStatus(val label: String) {
        UNCONFIGURED("Unconfigured"),
        UNAUTHORIZED("Camera Permission Denied"),
        READY("Camera Ready"),
        RUNNING("Live 20-30 Hz MediaPipe"),
        UNAVAILABLE("Simulated Camera")
    }

    private val _status = MutableStateFlow(CameraStatus.UNCONFIGURED)
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val _effectiveFrameRateHz = MutableStateFlow(0.0)
    val effectiveFrameRateHz: StateFlow<Double> = _effectiveFrameRateHz.asStateFlow()

    /** Frame stride for thermal throttling: processes 1 frame every `frameStride` incoming frames (default 2 -> ~20–30 Hz). */
    var frameStride: Int = 2

    /** Callback receiving throttled `Bitmap` frames on `com.girevoypulse.visionqueue`. */
    var onThrottledFrame: ((Bitmap, Double) -> Unit)? = null

    private val visionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "com.girevoypulse.visionqueue").apply { isDaemon = true }
    }

    private var incomingFrameCounter: Long = 0L
    private var processedFrameCounter: Long = 0L
    private var lastFpsWindowTimestamp: Double = 0.0

    fun shouldProcessIncomingFrame(index: Long): Boolean {
        val stride = max(1, frameStride).toLong()
        return index % stride == 0L
    }

    fun markPermissionDenied() {
        _status.value = CameraStatus.UNAUTHORIZED
    }

    fun bindToLifecycle(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (_: Throwable) {
                _status.value = CameraStatus.UNAVAILABLE
                return@addListener
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(visionExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                _status.value = CameraStatus.RUNNING
            } catch (_: Throwable) {
                _status.value = CameraStatus.UNAVAILABLE
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            incomingFrameCounter += 1L
            if (!shouldProcessIncomingFrame(incomingFrameCounter)) {
                return
            }

            val nowSeconds = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
            processedFrameCounter += 1L
            if (lastFpsWindowTimestamp == 0.0) {
                lastFpsWindowTimestamp = nowSeconds
            } else {
                val windowElapsed = nowSeconds - lastFpsWindowTimestamp
                if (windowElapsed >= 1.0) {
                    _effectiveFrameRateHz.value = processedFrameCounter.toDouble() / windowElapsed
                    processedFrameCounter = 0L
                    lastFpsWindowTimestamp = nowSeconds
                }
            }

            val callback = onThrottledFrame ?: return
            val bitmap = imageProxy.toBitmap()
            callback(bitmap, nowSeconds)
        } catch (_: Throwable) {
            // Ignore transient frame conversion issues
        } finally {
            imageProxy.close()
        }
    }
}

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

package com.girevoypulse.app.core.ble

import android.content.Context
import com.girevoypulse.app.domain.models.SternumAccSample
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Connection lifecycle state for the Polar H10 chest strap on Android. */
sealed interface PolarConnectionState {
    data object Disconnected : PolarConnectionState
    data object Searching : PolarConnectionState
    data class Connecting(val deviceId: String) : PolarConnectionState
    data class Connected(val deviceId: String, val batteryLevel: Int?) : PolarConnectionState
    data class Reconnecting(val deviceId: String) : PolarConnectionState
    data object SimulatedH10 : PolarConnectionState

    val isConnectedOrSimulated: Boolean
        get() = this is Connected || this is SimulatedH10

    val badgeText: String
        get() = when (this) {
            is Disconnected -> "H10 DISCONNECTED"
            is Searching -> "SCANNING FOR H10..."
            is Connecting -> "CONNECTING $deviceId..."
            is Connected -> if (batteryLevel != null) {
                "POLAR H10 ($deviceId) • $batteryLevel%"
            } else {
                "POLAR H10 ($deviceId)"
            }
            is Reconnecting -> "RECONNECTING $deviceId..."
            is SimulatedH10 -> "POLAR H10 (SIM)"
        }
}

/** Immutable UI snapshot of Polar H10 telemetry for Jetpack Compose. */
data class PolarTelemetryUiState(
    val connectionState: PolarConnectionState = PolarConnectionState.Disconnected,
    val bpm: Int = 0,
    val rrIntervals: List<Double> = emptyList(),
    val rmssdHRVMilliseconds: Double = 0.0,
    val latestAccSample: SternumAccSample? = null,
    val thoracicDipAngleDegrees: Double = 0.0,
    val batteryLevel: Int? = null
)

/**
 * Android Polar H10 BLE Manager wrapping `PolarBleApiDefaultImpl` (`com.github.polarofficial:polar-ble-sdk`).
 * Supports Heart Rate + RR interval streaming, 25 Hz / 50 Hz sternum accelerometer streaming,
 * and automatic reconnect without interrupting active workouts.
 */
class PolarManager(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(PolarTelemetryUiState())
    val uiState: StateFlow<PolarTelemetryUiState> = _uiState.asStateFlow()

    val connectionState: PolarConnectionState get() = _uiState.value.connectionState
    val bpm: Int get() = _uiState.value.bpm
    val rrIntervals: List<Double> get() = _uiState.value.rrIntervals
    val rmssdHRVMilliseconds: Double get() = _uiState.value.rmssdHRVMilliseconds
    val latestAccSample: SternumAccSample? get() = _uiState.value.latestAccSample
    val thoracicDipAngleDegrees: Double get() = _uiState.value.thoracicDipAngleDegrees

    private val rrHistory = mutableListOf<Double>()
    private var targetDeviceId: String? = null
    private var shouldMaintainStreamsOnReconnect: Boolean = false
    private var isAccStreamRequested: Boolean = false
    private var preferredAccSampleRateHz: Int = 50
    private var simulationJob: Job? = null

    private var api: PolarBleApi? = null
    private var searchDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var accDisposable: Disposable? = null

    init {
        if (context != null) {
            initializeSdk(context.applicationContext)
        }
    }

    fun initializeSdk(appContext: Context) {
        if (api != null) return
        val instance = PolarBleApiDefaultImpl.defaultImplementation(
            appContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO
            )
        )
        instance.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                updateState { copy(connectionState = PolarConnectionState.Connecting(polarDeviceInfo.deviceId)) }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                targetDeviceId = polarDeviceInfo.deviceId
                updateState {
                    copy(
                        connectionState = PolarConnectionState.Connected(
                            deviceId = polarDeviceInfo.deviceId,
                            batteryLevel = batteryLevel
                        )
                    )
                }
                if (shouldMaintainStreamsOnReconnect) {
                    startHeartRateStream()
                }
                if (isAccStreamRequested) {
                    startAccStream(preferredAccSampleRateHz)
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                handleUnexpectedDisconnect(polarDeviceInfo.deviceId)
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                updateState {
                    val updatedConn = if (connectionState is PolarConnectionState.Connected) {
                        PolarConnectionState.Connected(identifier, level)
                    } else connectionState
                    copy(batteryLevel = level, connectionState = updatedConn)
                }
            }

            override fun blePowerStateChanged(powered: Boolean) {
                if (!powered) {
                    updateState { copy(connectionState = PolarConnectionState.Disconnected) }
                }
            }
        })
        api = instance
    }

    // MARK: - Discovery & Connection

    fun searchAndConnect() {
        stopSimulation()
        shouldMaintainStreamsOnReconnect = true
        updateState { copy(connectionState = PolarConnectionState.Searching) }

        val polarApi = api ?: return
        searchDisposable?.dispose()
        searchDisposable = polarApi.searchForDevice()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { deviceInfo ->
                    val upper = deviceInfo.name.uppercase()
                    if (upper.contains("H10") || upper.contains("POLAR")) {
                        searchDisposable?.dispose()
                        autoConnect(deviceInfo.deviceId)
                    }
                },
                {
                    updateState { copy(connectionState = PolarConnectionState.Disconnected) }
                }
            )
    }

    fun autoConnect(deviceId: String) {
        stopSimulation()
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty()) {
            searchAndConnect()
            return
        }
        targetDeviceId = trimmed
        shouldMaintainStreamsOnReconnect = true
        updateState { copy(connectionState = PolarConnectionState.Connecting(trimmed)) }

        try {
            api?.connectToDevice(trimmed)
        } catch (_: Throwable) {
            // Ignored in JVM unit tests
        }
    }

    fun disconnect() {
        shouldMaintainStreamsOnReconnect = false
        stopSimulation()
        searchDisposable?.dispose()
        hrDisposable?.dispose()
        accDisposable?.dispose()
        targetDeviceId?.let { id ->
            try {
                api?.disconnectFromDevice(id)
            } catch (_: Throwable) {
            }
        }
        updateState { copy(connectionState = PolarConnectionState.Disconnected, bpm = 0) }
    }

    // MARK: - Online Streaming (HR + RR & Sternum Accelerometer)

    fun startHeartRateStream() {
        shouldMaintainStreamsOnReconnect = true
        val polarApi = api ?: return
        val deviceId = targetDeviceId ?: return

        hrDisposable?.dispose()
        hrDisposable = polarApi.startHrStreaming(deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { hrData: PolarHrData ->
                    for (sample in hrData.samples) {
                        val rrsMs = sample.rrsMs.map { it.toDouble() }
                        ingestHeartRateSample(bpm = sample.hr, rrIntervalsMs = rrsMs)
                    }
                },
                {
                    if (shouldMaintainStreamsOnReconnect) {
                        updateState { copy(connectionState = PolarConnectionState.Reconnecting(deviceId)) }
                    }
                }
            )
    }

    fun startAccStream(sampleRateHz: Int = 50) {
        isAccStreamRequested = true
        preferredAccSampleRateHz = if (sampleRateHz == 25 || sampleRateHz == 50) sampleRateHz else 50

        val polarApi = api ?: return
        val deviceId = targetDeviceId ?: return

        val settingsMap = mapOf(
            PolarSensorSetting.SettingType.SAMPLE_RATE to preferredAccSampleRateHz,
            PolarSensorSetting.SettingType.RESOLUTION to 16,
            PolarSensorSetting.SettingType.RANGE to 8,
            PolarSensorSetting.SettingType.CHANNELS to 3
        )
        val sensorSettings = PolarSensorSetting(settingsMap)

        accDisposable?.dispose()
        accDisposable = polarApi.startAccStreaming(deviceId, sensorSettings)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { accData: PolarAccelerometerData ->
                    val last = accData.samples.lastOrNull()
                    if (last != null) {
                        ingestAccelerometerSample(
                            SternumAccSample(xMillig = last.x, yMillig = last.y, zMillig = last.z)
                        )
                    }
                },
                {
                    // Keep workout active if optional ACC stream drops
                }
            )
    }

    // MARK: - Pure Telemetry Ingestion & HRV Calculation

    fun ingestHeartRateSample(bpm: Int, rrIntervalsMs: List<Double>) {
        val clampedBpm = max(0, bpm)
        if (rrIntervalsMs.isNotEmpty()) {
            rrHistory.addAll(rrIntervalsMs)
            while (rrHistory.size > 60) {
                rrHistory.removeAt(0)
            }
        }
        val rmssd = computeRMSSD(rrHistory)
        val snapshotRRs = rrHistory.toList()
        updateState {
            copy(
                bpm = clampedBpm,
                rrIntervals = snapshotRRs,
                rmssdHRVMilliseconds = rmssd
            )
        }
    }

    fun ingestAccelerometerSample(sample: SternumAccSample) {
        updateState {
            copy(
                latestAccSample = sample,
                thoracicDipAngleDegrees = sample.thoracicDipAngleDegrees
            )
        }
    }

    fun handleUnexpectedDisconnect(deviceId: String) {
        if (shouldMaintainStreamsOnReconnect) {
            updateState { copy(connectionState = PolarConnectionState.Reconnecting(deviceId)) }
            try {
                api?.connectToDevice(deviceId)
            } catch (_: Throwable) {
            }
        } else {
            updateState { copy(connectionState = PolarConnectionState.Disconnected) }
        }
    }

    fun startSimulatedH10Stream() {
        stopSimulation()
        updateState { copy(connectionState = PolarConnectionState.SimulatedH10) }
        ingestHeartRateSample(bpm = 142, rrIntervalsMs = listOf(422.0, 418.0, 425.0))
        ingestAccelerometerSample(SternumAccSample(xMillig = 15, yMillig = -980, zMillig = 140))

        simulationJob = scope.launch {
            while (isActive && connectionState == PolarConnectionState.SimulatedH10) {
                delay(1000L)
                val phase = System.currentTimeMillis() / 1000.0
                val simulatedBpm = 148 + (sin(phase * 0.25) * 14.0).toInt()
                val baseRR = 60_000.0 / max(60, simulatedBpm).toDouble()
                val rr1 = baseRR + sin(phase * 1.3) * 11.0
                val rr2 = baseRR - cos(phase * 1.7) * 9.0
                ingestHeartRateSample(bpm = simulatedBpm, rrIntervalsMs = listOf(rr1, rr2))

                val dipZ = (120.0 + abs(sin(phase * 1.5)) * 180.0).toInt()
                ingestAccelerometerSample(SternumAccSample(xMillig = 12, yMillig = -970, zMillig = dipZ))
            }
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    private inline fun updateState(transform: PolarTelemetryUiState.() -> PolarTelemetryUiState) {
        _uiState.value = _uiState.value.transform()
    }

    companion object {
        /** Computes Root Mean Square of Successive Differences (RMSSD) in ms from RR intervals. */
        fun computeRMSSD(rrIntervalsMs: List<Double>): Double {
            if (rrIntervalsMs.size < 2) return 0.0
            var sumSquaredDiffs = 0.0
            var count = 0
            for (i in 1 until rrIntervalsMs.size) {
                val diff = rrIntervalsMs[i] - rrIntervalsMs[i - 1]
                sumSquaredDiffs += diff * diff
                count += 1
            }
            return if (count > 0) sqrt(sumSquaredDiffs / count.toDouble()) else 0.0
        }
    }
}

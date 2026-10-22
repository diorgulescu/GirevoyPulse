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

package com.girevoypulse.app.core.timing

import android.os.SystemClock
import com.girevoypulse.app.core.audio.GSAudioCuePlaying
import com.girevoypulse.app.core.audio.GSAudioFeedbackEngine
import com.girevoypulse.app.domain.models.GSTimerMode
import com.girevoypulse.app.domain.models.MinuteSplit
import com.girevoypulse.app.domain.models.TimerIntervalPhase
import com.girevoypulse.app.domain.timing.GSTimerCoreEngine
import com.girevoypulse.app.domain.timing.MonotonicClock
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
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Android hardware monotonic clock powered by `SystemClock.elapsedRealtimeNanos()`. */
class SystemMonotonicClock : MonotonicClock {
    override fun now(): Double {
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        return if (elapsedNanos > 0L) {
            elapsedNanos / 1_000_000_000.0
        } else {
            System.nanoTime() / 1_000_000_000.0
        }
    }
}

/** Immutable UI snapshot of `GSTimerCoreEngine` for Jetpack Compose. */
data class GSTimerUiState(
    val mode: GSTimerMode = GSTimerMode.Traditional10Min(targetRPM = 16.0),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val elapsedTime: Double = 0.0,
    val remainingTime: Double = 600.0,
    val intervalPhase: TimerIntervalPhase = TimerIntervalPhase.Work(round = 1, totalRounds = 1),
    val phaseRemainingTime: Double = 600.0,
    val splits: List<MinuteSplit> = emptyList(),
    val handSwitchAlertsTriggered: Int = 0,
    val secondsUntilNextSwitch: Double? = null,
    val metronomeTicksFired: Int = 0,
    val currentMinuteReps: Int = 0,
    val currentRPM: Double = 0.0
)

/**
 * Android reactive timer adapter driving `GSTimerCoreEngine` at 100 Hz (10ms period)
 * against a monotonic hardware clock (`SystemClock.elapsedRealtimeNanos()`).
 */
class GSTimerEngine(
    initialMode: GSTimerMode = GSTimerMode.Traditional10Min(targetRPM = 16.0),
    private val clock: MonotonicClock = SystemMonotonicClock(),
    private val audioEngine: GSAudioCuePlaying = GSAudioFeedbackEngine.shared,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    val core = GSTimerCoreEngine(mode = initialMode)

    private val _uiState = MutableStateFlow(snapshotFromCore())
    val uiState: StateFlow<GSTimerUiState> = _uiState.asStateFlow()

    var onHandSwitchAlert: (() -> Unit)? = null

    private var tickerJob: Job? = null

    // Convenience accessors matching Swift `GSTimerEngine`
    val mode: GSTimerMode get() = core.mode
    val isRunning: Boolean get() = core.isRunning
    val isPaused: Boolean get() = core.isPaused
    val isCompleted: Boolean get() = core.isCompleted
    val elapsedTime: Double get() = core.elapsedTime
    val remainingTime: Double get() = core.remainingTime
    val intervalPhase: TimerIntervalPhase get() = core.intervalPhase
    val phaseRemainingTime: Double get() = core.phaseRemainingTime
    val splits: List<MinuteSplit> get() = core.splits
    val handSwitchAlertsTriggered: Int get() = core.handSwitchAlertsTriggered
    val secondsUntilNextSwitch: Double? get() = core.secondsUntilNextSwitch
    val metronomeTicksFired: Int get() = core.metronomeTicksFired
    val currentMinuteReps: Int get() = core.currentMinuteReps
    val currentRPM: Double get() = core.currentRPM

    fun configure(mode: GSTimerMode) {
        stopTicker()
        core.configure(mode)
        publishState()
    }

    fun start() {
        if (core.isRunning) return
        val cues = core.start(clock.now())
        publishState()
        cues.forEach { audioEngine.play(it) }
        startTicker()
    }

    fun pause() {
        if (!core.isRunning) return
        core.pause(clock.now())
        stopTicker()
        publishState()
    }

    fun stop() {
        stopTicker()
        core.stop()
        publishState()
    }

    fun recordRep(currentHeartRate: Int? = null) {
        core.recordRep(monotonicNow = clock.now(), currentHeartRate = currentHeartRate)
        publishState()
    }

    fun recordHeartRateSample(bpm: Int) {
        core.recordHeartRateSample(bpm)
    }

    fun stepToMonotonicTime(monotonicNow: Double) {
        if (!core.isRunning) return
        val result = core.step(monotonicNow)
        publishState()
        result.cues.forEach { audioEngine.play(it) }
        if (result.didTriggerHandSwitch) {
            onHandSwitchAlert?.invoke()
        }
        if (core.isCompleted) {
            stopTicker()
        }
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (isActive && core.isRunning) {
                delay(10L) // 100 Hz monotonic polling
                stepToMonotonicTime(clock.now())
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun publishState() {
        _uiState.value = snapshotFromCore()
    }

    private fun snapshotFromCore(): GSTimerUiState = GSTimerUiState(
        mode = core.mode,
        isRunning = core.isRunning,
        isPaused = core.isPaused,
        isCompleted = core.isCompleted,
        elapsedTime = core.elapsedTime,
        remainingTime = core.remainingTime,
        intervalPhase = core.intervalPhase,
        phaseRemainingTime = core.phaseRemainingTime,
        splits = core.splits,
        handSwitchAlertsTriggered = core.handSwitchAlertsTriggered,
        secondsUntilNextSwitch = core.secondsUntilNextSwitch,
        metronomeTicksFired = core.metronomeTicksFired,
        currentMinuteReps = core.currentMinuteReps,
        currentRPM = core.currentRPM
    )

    companion object {
        fun formatTime(seconds: Double, includeTenths: Boolean = false): String {
            val clamped = max(0.0, seconds)
            val totalWholeSeconds = floor(clamped).toInt()
            val minutes = totalWholeSeconds / 60
            val secs = totalWholeSeconds % 60
            return if (includeTenths) {
                val tenths = min(9, max(0, floor((clamped - totalWholeSeconds) * 10.0).toInt()))
                String.format(Locale.US, "%02d:%02d.%d", minutes, secs, tenths)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, secs)
            }
        }
    }
}

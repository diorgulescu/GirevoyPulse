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

package com.girevoypulse.app.domain.timing

import com.girevoypulse.app.domain.models.GSAudioCue
import com.girevoypulse.app.domain.models.GSTimerMode
import com.girevoypulse.app.domain.models.MinuteSplit
import com.girevoypulse.app.domain.models.TimerIntervalPhase
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Platform-agnostic monotonic clock interface.
 * - Android implementation: `SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0`
 * - iOS implementation: `CACurrentMediaTime()`
 */
fun interface MonotonicClock {
    fun now(): Double
}

/** Step output from `GSTimerCoreEngine.step(at:)`. */
data class GSTimerStepResult(
    val cues: List<GSAudioCue>,
    val didTriggerHandSwitch: Boolean
)

/**
 * Pure, platform-agnostic Girevoy Sport Timing Engine state machine.
 * Handles monotonic elapsed/remaining calculations, 1-minute splits, RPM cadence metronome ticks,
 * IKMF marathon hand-switch alerts, and Work/Rest interval cycles with zero platform framework dependencies.
 */
class GSTimerCoreEngine(
    mode: GSTimerMode = GSTimerMode.Traditional10Min(targetRPM = 16.0)
) {
    var mode: GSTimerMode = mode
        private set
    var isRunning: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var isCompleted: Boolean = false
        private set

    var elapsedTime: Double = 0.0
        private set
    var remainingTime: Double = 600.0
        private set
    var intervalPhase: TimerIntervalPhase = TimerIntervalPhase.Work(round = 1, totalRounds = 1)
        private set
    var phaseRemainingTime: Double = 600.0
        private set

    var splits: List<MinuteSplit> = emptyList()
        private set
    var handSwitchAlertsTriggered: Int = 0
        private set
    var secondsUntilNextSwitch: Double? = null
        private set
    var metronomeTicksFired: Int = 0
        private set
    var currentMinuteReps: Int = 0
        private set
    var currentRPM: Double = 0.0
        private set

    private var startMonotonicTimestamp: Double? = null
    private var pauseStartMonotonicTimestamp: Double? = null
    private var accumulatedPausedDuration: Double = 0.0

    private var totalRepsRecorded: Int = 0
    private var repsAtLastSplitBoundary: Int = 0
    private var lastSplitMinuteLogged: Int = 0
    private var lastMetronomeIndexFired: Int = -1
    private var lastSwitchIndexFired: Int = 0
    private var lastCountdownSecondFired: Int? = null
    private val heartRateSamplesInCurrentMinute = mutableListOf<Int>()
    private val recentRepMonotonicTimestamps = mutableListOf<Double>()

    init {
        resetStateForCurrentMode()
    }

    fun configure(newMode: GSTimerMode) {
        mode = newMode
        isRunning = false
        isPaused = false
        resetStateForCurrentMode()
    }

    fun start(monotonicNow: Double): List<GSAudioCue> {
        if (isRunning) return emptyList()
        if (isCompleted) {
            resetStateForCurrentMode()
        }

        val emittedCues = mutableListOf<GSAudioCue>()
        val pauseStart = pauseStartMonotonicTimestamp
        if (isPaused && pauseStart != null) {
            accumulatedPausedDuration += (monotonicNow - pauseStart)
            pauseStartMonotonicTimestamp = null
        } else if (startMonotonicTimestamp == null) {
            startMonotonicTimestamp = monotonicNow
            accumulatedPausedDuration = 0.0
            if (mode.targetRPM > 0.0) {
                lastMetronomeIndexFired = 0
                metronomeTicksFired = 1
                emittedCues.add(GSAudioCue.MetronomeTick)
            }
        }

        isRunning = true
        isPaused = false
        val tickResult = step(monotonicNow)
        emittedCues.addAll(tickResult.cues)
        return emittedCues
    }

    fun pause(monotonicNow: Double) {
        if (!isRunning || isPaused) return
        pauseStartMonotonicTimestamp = monotonicNow
        isRunning = false
        isPaused = true
    }

    fun stop() {
        isRunning = false
        isPaused = false
        resetStateForCurrentMode()
    }

    fun recordRep(monotonicNow: Double, currentHeartRate: Int? = null) {
        totalRepsRecorded += 1
        currentMinuteReps = totalRepsRecorded - repsAtLastSplitBoundary
        if (currentHeartRate != null && currentHeartRate > 0) {
            heartRateSamplesInCurrentMinute.add(currentHeartRate)
        }
        recentRepMonotonicTimestamps.add(monotonicNow)
        updateLiveRPM(monotonicNow)
    }

    fun recordHeartRateSample(bpm: Int) {
        if (!isRunning || bpm <= 0) return
        heartRateSamplesInCurrentMinute.add(bpm)
    }

    fun step(monotonicNow: Double): GSTimerStepResult {
        val start = startMonotonicTimestamp
        if (!isRunning || start == null) {
            return GSTimerStepResult(emptyList(), false)
        }

        val cues = mutableListOf<GSAudioCue>()
        var didTriggerHandSwitch = false

        val rawElapsed = max(0.0, monotonicNow - start - accumulatedPausedDuration)
        val totalDuration = mode.totalPlannedDuration
        val clampedElapsed = min(rawElapsed, totalDuration)

        elapsedTime = clampedElapsed
        remainingTime = max(0.0, totalDuration - clampedElapsed)

        evaluateMinuteSplits(clampedElapsed)

        when (val currentMode = mode) {
            is GSTimerMode.Traditional10Min -> {
                phaseRemainingTime = remainingTime
                evaluateMetronome(phaseElapsed = clampedElapsed, isWorkPhase = true, cues = cues)
                evaluateFinalTenSecondCountdown(phaseRemaining = remainingTime, cues = cues)
            }

            is GSTimerMode.Marathon -> {
                phaseRemainingTime = remainingTime
                evaluateMetronome(phaseElapsed = clampedElapsed, isWorkPhase = true, cues = cues)
                didTriggerHandSwitch = evaluateMarathonSwitchAlerts(
                    elapsed = clampedElapsed,
                    totalDuration = totalDuration,
                    switchIntervalMinutes = currentMode.switchIntervalMinutes,
                    cues = cues
                )
                evaluateFinalTenSecondCountdown(phaseRemaining = remainingTime, cues = cues)
            }

            is GSTimerMode.Intervals -> {
                evaluateIntervalCycles(
                    elapsed = clampedElapsed,
                    workSeconds = currentMode.workSeconds,
                    restSeconds = currentMode.restSeconds,
                    rounds = max(1, currentMode.rounds),
                    cues = cues
                )
            }
        }

        updateLiveRPM(monotonicNow)

        if (rawElapsed + 1e-9 >= totalDuration && !isCompleted) {
            elapsedTime = totalDuration
            remainingTime = 0.0
            evaluateMinuteSplits(totalDuration)
            isCompleted = true
            isRunning = false
            isPaused = false
            intervalPhase = TimerIntervalPhase.Completed
            phaseRemainingTime = 0.0
            cues.add(GSAudioCue.SetCompleted)
        }

        return GSTimerStepResult(cues, didTriggerHandSwitch)
    }

    // MARK: - Internal Pure Calculations

    private fun resetStateForCurrentMode() {
        startMonotonicTimestamp = null
        pauseStartMonotonicTimestamp = null
        accumulatedPausedDuration = 0.0
        elapsedTime = 0.0
        isCompleted = false
        splits = emptyList()
        handSwitchAlertsTriggered = 0
        metronomeTicksFired = 0
        totalRepsRecorded = 0
        repsAtLastSplitBoundary = 0
        lastSplitMinuteLogged = 0
        lastMetronomeIndexFired = -1
        lastSwitchIndexFired = 0
        lastCountdownSecondFired = null
        currentMinuteReps = 0
        currentRPM = 0.0
        heartRateSamplesInCurrentMinute.clear()
        recentRepMonotonicTimestamps.clear()

        val totalDuration = mode.totalPlannedDuration
        remainingTime = totalDuration

        when (val currentMode = mode) {
            is GSTimerMode.Traditional10Min -> {
                intervalPhase = TimerIntervalPhase.Work(round = 1, totalRounds = 1)
                phaseRemainingTime = 600.0
                secondsUntilNextSwitch = null
            }
            is GSTimerMode.Marathon -> {
                intervalPhase = TimerIntervalPhase.Work(round = 1, totalRounds = 1)
                phaseRemainingTime = currentMode.duration.totalSeconds
                secondsUntilNextSwitch = if (currentMode.switchIntervalMinutes > 0) {
                    (currentMode.switchIntervalMinutes * 60).toDouble()
                } else null
            }
            is GSTimerMode.Intervals -> {
                intervalPhase = TimerIntervalPhase.Work(round = 1, totalRounds = max(1, currentMode.rounds))
                phaseRemainingTime = currentMode.workSeconds
                secondsUntilNextSwitch = null
            }
        }
    }

    private fun evaluateMinuteSplits(elapsed: Double) {
        val completedMinutes = floor((elapsed + 1e-9) / 60.0).toInt()
        if (completedMinutes <= lastSplitMinuteLogged) return

        val updatedSplits = splits.toMutableList()
        for (minute in (lastSplitMinuteLogged + 1)..completedMinutes) {
            val repsInThisMinute: Int
            if (minute == completedMinutes) {
                repsInThisMinute = totalRepsRecorded - repsAtLastSplitBoundary
                repsAtLastSplitBoundary = totalRepsRecorded
            } else {
                repsInThisMinute = 0
            }
            val avgHR = if (heartRateSamplesInCurrentMinute.isEmpty()) {
                null
            } else {
                heartRateSamplesInCurrentMinute.sum() / heartRateSamplesInCurrentMinute.size
            }
            heartRateSamplesInCurrentMinute.clear()

            updatedSplits.add(
                MinuteSplit(
                    minuteIndex = minute,
                    repsInMinute = repsInThisMinute,
                    cumulativeReps = totalRepsRecorded,
                    averageHeartRate = avgHR
                )
            )
            currentMinuteReps = 0
        }
        splits = updatedSplits
        lastSplitMinuteLogged = completedMinutes
    }

    private fun evaluateMetronome(
        phaseElapsed: Double,
        isWorkPhase: Boolean,
        cues: MutableList<GSAudioCue>
    ) {
        val period = mode.metronomePeriodSeconds ?: return
        if (!isWorkPhase || period <= 0.0) return

        val currentTickIndex = floor(phaseElapsed / period).toInt()
        if (currentTickIndex > lastMetronomeIndexFired) {
            val missedOrCurrentTicks = currentTickIndex - lastMetronomeIndexFired
            lastMetronomeIndexFired = currentTickIndex
            metronomeTicksFired += missedOrCurrentTicks
            cues.add(GSAudioCue.MetronomeTick)
        }
    }

    private fun evaluateMarathonSwitchAlerts(
        elapsed: Double,
        totalDuration: Double,
        switchIntervalMinutes: Int,
        cues: MutableList<GSAudioCue>
    ): Boolean {
        if (switchIntervalMinutes <= 0) {
            secondsUntilNextSwitch = null
            return false
        }
        val switchIntervalSeconds = (switchIntervalMinutes * 60).toDouble()
        if (switchIntervalSeconds <= 0.0) return false

        var didTrigger = false
        val switchIndex = floor(elapsed / switchIntervalSeconds).toInt()
        if (switchIndex > lastSwitchIndexFired && elapsed < (totalDuration - 0.5)) {
            lastSwitchIndexFired = switchIndex
            handSwitchAlertsTriggered += 1
            cues.add(GSAudioCue.HandSwitchAlert)
            didTrigger = true
        }

        val nextSwitchTime = (switchIndex + 1) * switchIntervalSeconds
        secondsUntilNextSwitch = if (nextSwitchTime < totalDuration) {
            max(0.0, nextSwitchTime - elapsed)
        } else {
            null
        }
        return didTrigger
    }

    private fun evaluateIntervalCycles(
        elapsed: Double,
        workSeconds: Double,
        restSeconds: Double,
        rounds: Int,
        cues: MutableList<GSAudioCue>
    ) {
        if (workSeconds <= 0.0) return
        val cycleLength = workSeconds + max(0.0, restSeconds)
        val totalDuration = mode.totalPlannedDuration

        if (elapsed >= totalDuration) {
            intervalPhase = TimerIntervalPhase.Completed
            phaseRemainingTime = 0.0
            return
        }

        val currentRoundZeroBased: Int
        val offsetInCycle: Double
        if (restSeconds > 0.0 && cycleLength > 0.0) {
            val rawCycleIndex = floor(elapsed / cycleLength).toInt()
            currentRoundZeroBased = min(rounds - 1, rawCycleIndex)
            offsetInCycle = elapsed - (currentRoundZeroBased * cycleLength)
        } else {
            val rawRoundIndex = floor(elapsed / workSeconds).toInt()
            currentRoundZeroBased = min(rounds - 1, rawRoundIndex)
            offsetInCycle = elapsed - (currentRoundZeroBased * workSeconds)
        }

        val roundNumber = currentRoundZeroBased + 1
        val newPhase: TimerIntervalPhase
        val phaseElapsed: Double
        val phaseRemaining: Double

        if (offsetInCycle < workSeconds || roundNumber == rounds) {
            newPhase = TimerIntervalPhase.Work(round = roundNumber, totalRounds = rounds)
            phaseElapsed = min(workSeconds, offsetInCycle)
            phaseRemaining = max(0.0, workSeconds - phaseElapsed)
        } else {
            newPhase = TimerIntervalPhase.Rest(round = roundNumber, totalRounds = rounds)
            phaseElapsed = offsetInCycle - workSeconds
            phaseRemaining = max(0.0, restSeconds - phaseElapsed)
        }

        if (newPhase != intervalPhase) {
            val previousPhase = intervalPhase
            intervalPhase = newPhase
            lastMetronomeIndexFired = -1
            lastCountdownSecondFired = null
            if (newPhase is TimerIntervalPhase.Work && previousPhase != newPhase) {
                cues.add(GSAudioCue.IntervalPhaseTransition(isWork = true))
                if (mode.targetRPM > 0.0) {
                    lastMetronomeIndexFired = 0
                    metronomeTicksFired += 1
                    cues.add(GSAudioCue.MetronomeTick)
                }
            } else if (newPhase is TimerIntervalPhase.Rest) {
                cues.add(GSAudioCue.IntervalPhaseTransition(isWork = false))
            }
        }

        phaseRemainingTime = phaseRemaining
        evaluateMetronome(phaseElapsed = phaseElapsed, isWorkPhase = newPhase.isWorkPhase, cues = cues)
        evaluateFinalTenSecondCountdown(phaseRemaining = phaseRemaining, cues = cues)
    }

    private fun evaluateFinalTenSecondCountdown(
        phaseRemaining: Double,
        cues: MutableList<GSAudioCue>
    ) {
        if (phaseRemaining <= 0.0 || phaseRemaining > 10.0) return
        val wholeSecondsRemaining = ceil(phaseRemaining).toInt()
        if (wholeSecondsRemaining !in 1..10) return
        if (lastCountdownSecondFired != wholeSecondsRemaining) {
            lastCountdownSecondFired = wholeSecondsRemaining
            cues.add(GSAudioCue.FinalTenSecondTick(secondsRemaining = wholeSecondsRemaining))
        }
    }

    private fun updateLiveRPM(monotonicNow: Double) {
        recentRepMonotonicTimestamps.removeAll { monotonicNow - it > 60.0 }
        if (elapsedTime <= 1.0 || totalRepsRecorded <= 0) {
            currentRPM = 0.0
            return
        }
        currentRPM = if (elapsedTime < 60.0) {
            totalRepsRecorded.toDouble() / (elapsedTime / 60.0)
        } else {
            recentRepMonotonicTimestamps.size.toDouble()
        }
    }
}

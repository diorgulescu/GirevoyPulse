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

import Foundation

/// Platform-agnostic monotonic clock interface.
/// - iOS implementation: `CACurrentMediaTime()`
/// - Android implementation: `SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0`
public protocol MonotonicClock: Sendable {
    func now() -> TimeInterval
}

/// Pure, platform-agnostic Girevoy Sport Timing Engine state machine (`Foundation`-only).
/// Handles monotonic elapsed/remaining calculations, 1-minute splits, RPM cadence metronome ticks,
/// IKMF marathon hand-switch alerts, and Work/Rest interval cycles with zero Apple framework dependencies.
public struct GSTimerCoreEngine: Sendable {
    public private(set) var mode: GSTimerMode
    public private(set) var isRunning: Bool = false
    public private(set) var isPaused: Bool = false
    public private(set) var isCompleted: Bool = false

    public private(set) var elapsedTime: TimeInterval = 0.0
    public private(set) var remainingTime: TimeInterval = 600.0
    public private(set) var intervalPhase: TimerIntervalPhase = .work(round: 1, totalRounds: 1)
    public private(set) var phaseRemainingTime: TimeInterval = 600.0

    public private(set) var splits: [MinuteSplit] = []
    public private(set) var handSwitchAlertsTriggered: Int = 0
    public private(set) var secondsUntilNextSwitch: TimeInterval?
    public private(set) var metronomeTicksFired: Int = 0
    public private(set) var currentMinuteReps: Int = 0
    public private(set) var currentRPM: Double = 0.0

    private var startMonotonicTimestamp: TimeInterval?
    private var pauseStartMonotonicTimestamp: TimeInterval?
    private var accumulatedPausedDuration: TimeInterval = 0.0

    private var totalRepsRecorded: Int = 0
    private var repsAtLastSplitBoundary: Int = 0
    private var lastSplitMinuteLogged: Int = 0
    private var lastMetronomeIndexFired: Int = -1
    private var lastSwitchIndexFired: Int = 0
    private var lastCountdownSecondFired: Int?
    private var heartRateSamplesInCurrentMinute: [Int] = []
    private var recentRepMonotonicTimestamps: [TimeInterval] = []

    public init(mode: GSTimerMode = .traditional10Min(targetRPM: 16.0)) {
        self.mode = mode
        resetStateForCurrentMode()
    }

    public mutating func configure(mode: GSTimerMode) {
        self.mode = mode
        isRunning = false
        isPaused = false
        resetStateForCurrentMode()
    }

    /// Starts or resumes the monotonic timer at `monotonicNow`, returning any immediate `GSAudioCue`s to play.
    @discardableResult
    public mutating func start(at monotonicNow: TimeInterval) -> [GSAudioCue] {
        guard !isRunning else { return [] }
        if isCompleted {
            resetStateForCurrentMode()
        }

        var emittedCues: [GSAudioCue] = []
        if isPaused, let pauseStart = pauseStartMonotonicTimestamp {
            accumulatedPausedDuration += (monotonicNow - pauseStart)
            pauseStartMonotonicTimestamp = nil
        } else if startMonotonicTimestamp == nil {
            startMonotonicTimestamp = monotonicNow
            accumulatedPausedDuration = 0.0
            if mode.targetRPM > 0 {
                lastMetronomeIndexFired = 0
                metronomeTicksFired = 1
                emittedCues.append(.metronomeTick)
            }
        }

        isRunning = true
        isPaused = false
        let tickResult = step(at: monotonicNow)
        emittedCues.append(contentsOf: tickResult.cues)
        return emittedCues
    }

    public mutating func pause(at monotonicNow: TimeInterval) {
        guard isRunning, !isPaused else { return }
        pauseStartMonotonicTimestamp = monotonicNow
        isRunning = false
        isPaused = true
    }

    public mutating func stop() {
        isRunning = false
        isPaused = false
        resetStateForCurrentMode()
    }

    public mutating func recordRep(at monotonicNow: TimeInterval, currentHeartRate: Int? = nil) {
        totalRepsRecorded += 1
        currentMinuteReps = totalRepsRecorded - repsAtLastSplitBoundary
        if let hr = currentHeartRate, hr > 0 {
            heartRateSamplesInCurrentMinute.append(hr)
        }
        recentRepMonotonicTimestamps.append(monotonicNow)
        updateLiveRPM(at: monotonicNow)
    }

    public mutating func recordHeartRateSample(_ bpm: Int) {
        guard isRunning, bpm > 0 else { return }
        heartRateSamplesInCurrentMinute.append(bpm)
    }

    /// Advances the timer state to `monotonicNow` and returns emitted audio cues + whether a hand-switch alert occurred.
    public mutating func step(at monotonicNow: TimeInterval) -> (cues: [GSAudioCue], didTriggerHandSwitch: Bool) {
        guard isRunning, let start = startMonotonicTimestamp else {
            return ([], false)
        }

        var cues: [GSAudioCue] = []
        var didTriggerHandSwitch = false

        let rawElapsed = max(0.0, monotonicNow - start - accumulatedPausedDuration)
        let totalDuration = mode.totalPlannedDuration
        let clampedElapsed = min(rawElapsed, totalDuration)

        elapsedTime = clampedElapsed
        remainingTime = max(0.0, totalDuration - clampedElapsed)

        evaluateMinuteSplits(elapsed: clampedElapsed)

        switch mode {
        case .traditional10Min:
            phaseRemainingTime = remainingTime
            evaluateMetronome(phaseElapsed: clampedElapsed, isWorkPhase: true, cues: &cues)
            evaluateFinalTenSecondCountdown(phaseRemaining: remainingTime, cues: &cues)

        case .marathon(_, let switchIntervalMinutes, _):
            phaseRemainingTime = remainingTime
            evaluateMetronome(phaseElapsed: clampedElapsed, isWorkPhase: true, cues: &cues)
            evaluateMarathonSwitchAlerts(
                elapsed: clampedElapsed,
                totalDuration: totalDuration,
                switchIntervalMinutes: switchIntervalMinutes,
                cues: &cues,
                didTriggerHandSwitch: &didTriggerHandSwitch
            )
            evaluateFinalTenSecondCountdown(phaseRemaining: remainingTime, cues: &cues)

        case .intervals(let workSeconds, let restSeconds, let rounds, _):
            evaluateIntervalCycles(
                elapsed: clampedElapsed,
                workSeconds: workSeconds,
                restSeconds: restSeconds,
                rounds: max(1, rounds),
                cues: &cues
            )
        }

        updateLiveRPM(at: monotonicNow)

        if rawElapsed + 1e-9 >= totalDuration && !isCompleted {
            elapsedTime = totalDuration
            remainingTime = 0.0
            evaluateMinuteSplits(elapsed: totalDuration)
            isCompleted = true
            isRunning = false
            isPaused = false
            intervalPhase = .completed
            phaseRemainingTime = 0.0
            cues.append(.setCompleted)
        }

        return (cues, didTriggerHandSwitch)
    }

    // MARK: - Internal Pure Calculations

    private mutating func resetStateForCurrentMode() {
        startMonotonicTimestamp = nil
        pauseStartMonotonicTimestamp = nil
        accumulatedPausedDuration = 0.0
        elapsedTime = 0.0
        isCompleted = false
        splits = []
        handSwitchAlertsTriggered = 0
        metronomeTicksFired = 0
        totalRepsRecorded = 0
        repsAtLastSplitBoundary = 0
        lastSplitMinuteLogged = 0
        lastMetronomeIndexFired = -1
        lastSwitchIndexFired = 0
        lastCountdownSecondFired = nil
        currentMinuteReps = 0
        currentRPM = 0.0
        heartRateSamplesInCurrentMinute = []
        recentRepMonotonicTimestamps = []

        let totalDuration = mode.totalPlannedDuration
        remainingTime = totalDuration

        switch mode {
        case .traditional10Min:
            intervalPhase = .work(round: 1, totalRounds: 1)
            phaseRemainingTime = 600.0
            secondsUntilNextSwitch = nil
        case .marathon(let duration, let switchMinutes, _):
            intervalPhase = .work(round: 1, totalRounds: 1)
            phaseRemainingTime = duration.totalSeconds
            secondsUntilNextSwitch = switchMinutes > 0 ? TimeInterval(switchMinutes * 60) : nil
        case .intervals(let workSeconds, _, let rounds, _):
            intervalPhase = .work(round: 1, totalRounds: max(1, rounds))
            phaseRemainingTime = workSeconds
            secondsUntilNextSwitch = nil
        }
    }

    private mutating func evaluateMinuteSplits(elapsed: TimeInterval) {
        let completedMinutes = Int(((elapsed + 1e-9) / 60.0).rounded(.down))
        guard completedMinutes > lastSplitMinuteLogged else { return }

        for minute in (lastSplitMinuteLogged + 1)...completedMinutes {
            let repsInThisMinute: Int
            if minute == completedMinutes {
                repsInThisMinute = totalRepsRecorded - repsAtLastSplitBoundary
                repsAtLastSplitBoundary = totalRepsRecorded
            } else {
                repsInThisMinute = 0
            }
            let avgHR: Int? = heartRateSamplesInCurrentMinute.isEmpty
                ? nil
                : (heartRateSamplesInCurrentMinute.reduce(0, +) / heartRateSamplesInCurrentMinute.count)
            heartRateSamplesInCurrentMinute.removeAll(keepingCapacity: true)

            splits.append(
                MinuteSplit(
                    minuteIndex: minute,
                    repsInMinute: repsInThisMinute,
                    cumulativeReps: totalRepsRecorded,
                    averageHeartRate: avgHR
                )
            )
            currentMinuteReps = 0
        }
        lastSplitMinuteLogged = completedMinutes
    }

    private mutating func evaluateMetronome(
        phaseElapsed: TimeInterval,
        isWorkPhase: Bool,
        cues: inout [GSAudioCue]
    ) {
        guard isWorkPhase,
              let period = mode.metronomePeriodSeconds,
              period > 0 else { return }

        let currentTickIndex = Int((phaseElapsed / period).rounded(.down))
        if currentTickIndex > lastMetronomeIndexFired {
            let missedOrCurrentTicks = currentTickIndex - lastMetronomeIndexFired
            lastMetronomeIndexFired = currentTickIndex
            metronomeTicksFired += missedOrCurrentTicks
            cues.append(.metronomeTick)
        }
    }

    private mutating func evaluateMarathonSwitchAlerts(
        elapsed: TimeInterval,
        totalDuration: TimeInterval,
        switchIntervalMinutes: Int,
        cues: inout [GSAudioCue],
        didTriggerHandSwitch: inout Bool
    ) {
        guard switchIntervalMinutes > 0 else {
            secondsUntilNextSwitch = nil
            return
        }
        let switchIntervalSeconds = TimeInterval(switchIntervalMinutes * 60)
        guard switchIntervalSeconds > 0 else { return }

        let switchIndex = Int((elapsed / switchIntervalSeconds).rounded(.down))
        if switchIndex > lastSwitchIndexFired && elapsed < (totalDuration - 0.5) {
            lastSwitchIndexFired = switchIndex
            handSwitchAlertsTriggered += 1
            cues.append(.handSwitchAlert)
            didTriggerHandSwitch = true
        }

        let nextSwitchTime = Double(switchIndex + 1) * switchIntervalSeconds
        if nextSwitchTime < totalDuration {
            secondsUntilNextSwitch = max(0.0, nextSwitchTime - elapsed)
        } else {
            secondsUntilNextSwitch = nil
        }
    }

    private mutating func evaluateIntervalCycles(
        elapsed: TimeInterval,
        workSeconds: TimeInterval,
        restSeconds: TimeInterval,
        rounds: Int,
        cues: inout [GSAudioCue]
    ) {
        guard workSeconds > 0 else { return }
        let cycleLength = workSeconds + max(0.0, restSeconds)
        let totalDuration = mode.totalPlannedDuration

        if elapsed >= totalDuration {
            intervalPhase = .completed
            phaseRemainingTime = 0.0
            return
        }

        let currentRoundZeroBased: Int
        let offsetInCycle: TimeInterval

        if restSeconds > 0 && cycleLength > 0 {
            let rawCycleIndex = Int((elapsed / cycleLength).rounded(.down))
            currentRoundZeroBased = min(rounds - 1, rawCycleIndex)
            offsetInCycle = elapsed - (Double(currentRoundZeroBased) * cycleLength)
        } else {
            let rawRoundIndex = Int((elapsed / workSeconds).rounded(.down))
            currentRoundZeroBased = min(rounds - 1, rawRoundIndex)
            offsetInCycle = elapsed - (Double(currentRoundZeroBased) * workSeconds)
        }

        let roundNumber = currentRoundZeroBased + 1
        let newPhase: TimerIntervalPhase
        let phaseElapsed: TimeInterval
        let phaseRemaining: TimeInterval

        if offsetInCycle < workSeconds || roundNumber == rounds {
            newPhase = .work(round: roundNumber, totalRounds: rounds)
            phaseElapsed = min(workSeconds, offsetInCycle)
            phaseRemaining = max(0.0, workSeconds - phaseElapsed)
        } else {
            newPhase = .rest(round: roundNumber, totalRounds: rounds)
            phaseElapsed = offsetInCycle - workSeconds
            phaseRemaining = max(0.0, restSeconds - phaseElapsed)
        }

        if newPhase != intervalPhase {
            let previousPhase = intervalPhase
            intervalPhase = newPhase
            lastMetronomeIndexFired = -1
            lastCountdownSecondFired = nil
            if case .work = newPhase, previousPhase != newPhase {
                cues.append(.intervalPhaseTransition(isWork: true))
                if mode.targetRPM > 0 {
                    lastMetronomeIndexFired = 0
                    metronomeTicksFired += 1
                    cues.append(.metronomeTick)
                }
            } else if case .rest = newPhase {
                cues.append(.intervalPhaseTransition(isWork: false))
            }
        }

        phaseRemainingTime = phaseRemaining
        evaluateMetronome(phaseElapsed: phaseElapsed, isWorkPhase: newPhase.isWorkPhase, cues: &cues)
        evaluateFinalTenSecondCountdown(phaseRemaining: phaseRemaining, cues: &cues)
    }

    private mutating func evaluateFinalTenSecondCountdown(
        phaseRemaining: TimeInterval,
        cues: inout [GSAudioCue]
    ) {
        guard phaseRemaining > 0.0, phaseRemaining <= 10.0 else { return }
        let wholeSecondsRemaining = Int(ceil(phaseRemaining))
        guard (1...10).contains(wholeSecondsRemaining) else { return }
        if lastCountdownSecondFired != wholeSecondsRemaining {
            lastCountdownSecondFired = wholeSecondsRemaining
            cues.append(.finalTenSecondTick(secondsRemaining: wholeSecondsRemaining))
        }
    }

    private mutating func updateLiveRPM(at monotonicNow: TimeInterval) {
        recentRepMonotonicTimestamps.removeAll { monotonicNow - $0 > 60.0 }
        guard elapsedTime > 1.0, totalRepsRecorded > 0 else {
            currentRPM = 0.0
            return
        }
        if elapsedTime < 60.0 {
            currentRPM = Double(totalRepsRecorded) / (elapsedTime / 60.0)
        } else {
            currentRPM = Double(recentRepMonotonicTimestamps.count)
        }
    }
}

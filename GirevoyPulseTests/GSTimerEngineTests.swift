import Foundation
import QuartzCore
import XCTest
@testable import GirevoyPulse

/// Mock monotonic clock for deterministic, zero-flake testing of `GSTimerEngine` and `GSFixationDetector`.
final class MockMonotonicClock: MonotonicClock, @unchecked Sendable {
    var currentTime: TimeInterval

    init(initialTime: TimeInterval = 1_000.0) {
        self.currentTime = initialTime
    }

    func now() -> TimeInterval {
        currentTime
    }

    func advance(by delta: TimeInterval) {
        currentTime += delta
    }
}

/// Spy audio engine that records all emitted `GSAudioCue` events in order.
final class SpyAudioCuePlayer: GSAudioCuePlaying, @unchecked Sendable {
    private let lock = NSLock()
    private var _cues: [GSAudioCue] = []

    var cues: [GSAudioCue] {
        lock.lock()
        defer { lock.unlock() }
        return _cues
    }

    func play(_ cue: GSAudioCue) {
        lock.lock()
        _cues.append(cue)
        lock.unlock()
    }
}

@MainActor
final class GSTimerEngineTests: XCTestCase {

    func testMonotonicClockDriftImmunityUnderVariableFrameJitter() {
        let clock = MockMonotonicClock(initialTime: 5_000.0)
        let spyAudio = SpyAudioCuePlayer()
        let engine = GSTimerEngine(
            mode: .traditional10Min(targetRPM: 16.0),
            clock: clock,
            audioEngine: spyAudio
        )

        engine.start()
        XCTAssertEqual(engine.elapsedTime, 0.0, accuracy: 1e-6)
        XCTAssertEqual(engine.remainingTime, 600.0, accuracy: 1e-6)

        // Simulate irregular, jittery CPU/GPU callback intervals (e.g., 14ms, 47ms, 112ms, 3ms)
        let jitterIntervals: [TimeInterval] = [
            0.014, 0.047, 0.112, 0.003, 0.089, 0.235, 0.500,
            1.750, 1.000, 2.250, 54.000, 120.000, 420.000
        ]
        var expectedElapsed: TimeInterval = 0.0

        for delta in jitterIntervals {
            clock.advance(by: delta)
            expectedElapsed += delta
            engine.stepToMonotonicTime(clock.now())
            XCTAssertEqual(
                engine.elapsedTime,
                min(600.0, expectedElapsed),
                accuracy: 1e-9,
                "Elapsed time must have zero cumulative drift against monotonic clock"
            )
        }

        XCTAssertTrue(engine.isCompleted)
        XCTAssertEqual(engine.remainingTime, 0.0, accuracy: 1e-9)
        XCTAssertEqual(engine.splits.count, 10)
    }

    func testRealTimeDispatchSourceTimerDriftWithinTolerance() async throws {
        let spyAudio = SpyAudioCuePlayer()
        let engine = GSTimerEngine(
            mode: .traditional10Min(targetRPM: 16.0),
            clock: SystemMonotonicClock(),
            audioEngine: spyAudio
        )

        let wallStart = CACurrentMediaTime()
        engine.start()
        try await Task.sleep(nanoseconds: 350_000_000) // 350ms
        let wallElapsed = CACurrentMediaTime() - wallStart
        let engineElapsed = engine.elapsedTime
        engine.stop()

        // DispatchSourceTimer polls every 10ms, so engineElapsed should match wallElapsed within 25ms
        XCTAssertEqual(engineElapsed, wallElapsed, accuracy: 0.025)
    }

    func testTraditional10MinCadenceMetronomeAndMinuteSplits() {
        let clock = MockMonotonicClock(initialTime: 100.0)
        let spyAudio = SpyAudioCuePlayer()
        // 16 RPM => 1 rep every 3.75 seconds
        let engine = GSTimerEngine(
            mode: .traditional10Min(targetRPM: 16.0),
            clock: clock,
            audioEngine: spyAudio
        )

        engine.start()
        // Tick 0 fires at start (t = 0.0)
        XCTAssertEqual(engine.metronomeTicksFired, 1)

        // Advance through Minute 1 (60s) in 0.25s increments, logging 16 reps
        for step in 1...240 {
            clock.advance(by: 0.25)
            // Log a rep every 3.75s (every 15 steps of 0.25s)
            if step % 15 == 0 {
                engine.recordRep(currentHeartRate: 155)
            }
            engine.stepToMonotonicTime(clock.now())
        }

        // At 60.0s, 16 intervals of 3.75s have elapsed + initial tick at 0.0s = 17 ticks
        XCTAssertEqual(engine.metronomeTicksFired, 17)
        XCTAssertEqual(engine.splits.count, 1)
        XCTAssertEqual(engine.splits.first?.minuteIndex, 1)
        XCTAssertEqual(engine.splits.first?.repsInMinute, 16)
        XCTAssertEqual(engine.splits.first?.cumulativeReps, 16)
        XCTAssertEqual(engine.splits.first?.averageHeartRate, 155)
    }

    func testPauseAndResumeExcludesPausedDurationFromMonotonicElapsed() {
        let clock = MockMonotonicClock(initialTime: 2_000.0)
        let spyAudio = SpyAudioCuePlayer()
        let engine = GSTimerEngine(
            mode: .traditional10Min(targetRPM: 12.0),
            clock: clock,
            audioEngine: spyAudio
        )

        engine.start()
        clock.advance(by: 45.0)
        engine.stepToMonotonicTime(clock.now())
        XCTAssertEqual(engine.elapsedTime, 45.0, accuracy: 1e-6)

        engine.pause()
        XCTAssertTrue(engine.isPaused)

        // Advance clock by 120 seconds while paused
        clock.advance(by: 120.0)
        engine.start() // Resume

        clock.advance(by: 15.0)
        engine.stepToMonotonicTime(clock.now())
        XCTAssertEqual(engine.elapsedTime, 60.0, accuracy: 1e-6)
        XCTAssertEqual(engine.splits.count, 1)
    }

    func testMarathonSwitchAlertsAndFinalCountdown() {
        let clock = MockMonotonicClock(initialTime: 0.0)
        let spyAudio = SpyAudioCuePlayer()
        let engine = GSTimerEngine(
            mode: .marathon(duration: .halfMarathon30Min, switchIntervalMinutes: 5, targetRPM: 14.0),
            clock: clock,
            audioEngine: spyAudio
        )

        var switchCallbacks = 0
        engine.onHandSwitchAlert = {
            switchCallbacks += 1
        }

        engine.start()
        // Advance through 30 minutes (1800s) in 1-second steps
        for _ in 1...1800 {
            clock.advance(by: 1.0)
            engine.stepToMonotonicTime(clock.now())
        }

        // In a 30-minute half-marathon with 5-minute switches, switches happen at 5m, 10m, 15m, 20m, 25m (5 switches; 30m is completion)
        XCTAssertEqual(engine.handSwitchAlertsTriggered, 5)
        XCTAssertEqual(switchCallbacks, 5)
        XCTAssertTrue(engine.isCompleted)

        // Verify all 10 final-second countdown cues fired (10 down to 1)
        let countdownCues = spyAudio.cues.compactMap { cue -> Int? in
            if case .finalTenSecondTick(let sec) = cue { return sec }
            return nil
        }
        XCTAssertEqual(countdownCues, [10, 9, 8, 7, 6, 5, 4, 3, 2, 1])
    }

    func testIntervalModeWorkRestCycles() {
        let clock = MockMonotonicClock(initialTime: 500.0)
        let spyAudio = SpyAudioCuePlayer()
        // 60s work / 30s rest x 3 rounds => total planned duration = 60*3 + 30*2 = 240s
        let engine = GSTimerEngine(
            mode: .intervals(workSeconds: 60.0, restSeconds: 30.0, rounds: 3, targetRPM: 15.0),
            clock: clock,
            audioEngine: spyAudio
        )

        XCTAssertEqual(engine.mode.totalPlannedDuration, 240.0, accuracy: 1e-6)
        engine.start()
        XCTAssertEqual(engine.intervalPhase, .work(round: 1, totalRounds: 3))

        // Advance to 65s -> should be in Rest Round 1 with 25s remaining in rest
        clock.advance(by: 65.0)
        engine.stepToMonotonicTime(clock.now())
        XCTAssertEqual(engine.intervalPhase, .rest(round: 1, totalRounds: 3))
        XCTAssertEqual(engine.phaseRemainingTime, 25.0, accuracy: 1e-6)

        // Advance to 95s -> should be in Work Round 2 with 55s remaining in work
        clock.advance(by: 30.0)
        engine.stepToMonotonicTime(clock.now())
        XCTAssertEqual(engine.intervalPhase, .work(round: 2, totalRounds: 3))
        XCTAssertEqual(engine.phaseRemainingTime, 55.0, accuracy: 1e-6)

        // Advance to 240s -> completed
        clock.advance(by: 145.0)
        engine.stepToMonotonicTime(clock.now())
        XCTAssertEqual(engine.intervalPhase, .completed)
        XCTAssertTrue(engine.isCompleted)
    }
}

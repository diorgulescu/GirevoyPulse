import Foundation
import XCTest
@testable import GirevoyPulse

@MainActor
final class PolarManagerTests: XCTestCase {

    func testHeartRateAndRRIntervalStreamingComputesRMSSD() {
        let manager = PolarManager()
        manager.ingestHeartRateSample(bpm: 162, rrIntervalsMs: [370.0, 380.0, 368.0, 376.0])

        XCTAssertEqual(manager.bpm, 162)
        XCTAssertEqual(manager.rrIntervals, [370.0, 380.0, 368.0, 376.0])

        // Differences: +10, -12, +8 -> squared: 100, 144, 64 -> mean = 308/3 = 102.6667 -> sqrt = 10.132456
        XCTAssertEqual(manager.rmssdHRVMilliseconds, sqrt(308.0 / 3.0), accuracy: 1e-4)
    }

    func testSternumAccelerometerComputesThoracicDipAngle() {
        let manager = PolarManager()
        // Upright sternum (z = 0, y = -1000 mG) -> 0 degree dip
        manager.ingestAccelerometerSample(SternumAccSample(xMillig: 0, yMillig: -1000, zMillig: 0))
        XCTAssertEqual(manager.thoracicDipAngleDegrees, 0.0, accuracy: 1e-3)

        // 45-degree torso lean during dip/drive (y = -707 mG, z = 707 mG) -> 45 degrees
        manager.ingestAccelerometerSample(SternumAccSample(xMillig: 0, yMillig: -707, zMillig: 707))
        XCTAssertEqual(manager.thoracicDipAngleDegrees, 45.0, accuracy: 0.1)
    }

    func testUnexpectedBLEDisconnectReconnectsGracefullyWithoutStoppingTimer() {
        let clock = MockMonotonicClock(initialTime: 100.0)
        let spyAudio = SpyAudioCuePlayer()
        let timer = GSTimerEngine(
            mode: .traditional10Min(targetRPM: 16.0),
            clock: clock,
            audioEngine: spyAudio
        )
        let polar = PolarManager()

        timer.start()
        polar.autoConnect(deviceId: "A1B2C3D4")
        XCTAssertEqual(polar.connectionState, .connecting(deviceId: "A1B2C3D4"))

        // Advance timer by 30s while Polar disconnects unexpectedly
        clock.advance(by: 30.0)
        timer.stepToMonotonicTime(clock.now())
        polar.handleUnexpectedDisconnect(deviceId: "A1B2C3D4")

        // Timer remains running and accurate; Polar enters .reconnecting state
        XCTAssertTrue(timer.isRunning)
        XCTAssertEqual(timer.elapsedTime, 30.0, accuracy: 1e-6)
        XCTAssertEqual(polar.connectionState, .reconnecting(deviceId: "A1B2C3D4"))
    }
}

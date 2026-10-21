import Foundation
import XCTest
@testable import GirevoyPulse

final class GSFixationDetectorTests: XCTestCase {

    // MARK: - Helper Pose Builders

    /// Constructs a synthetic `BodyPoseFrame` with configurable wrist height and joint lockout angles.
    private func makePoseFrame(
        timestamp: TimeInterval,
        wristY: Double,
        elbowBendOffsetX: Double = 0.0,
        kneeBendOffsetX: Double = 0.0,
        hipBendOffsetX: Double = 0.0
    ) -> BodyPoseFrame {
        let shoulderY = 0.65
        let elbowY = (shoulderY + wristY) * 0.5

        let joints: [GSJointName: JointPoint] = [
            .neck: JointPoint(x: 0.50, y: 0.70),
            .rightShoulder: JointPoint(x: 0.60, y: shoulderY),
            .rightElbow: JointPoint(x: 0.60 + elbowBendOffsetX, y: elbowY),
            .rightWrist: JointPoint(x: 0.60, y: wristY),
            .leftShoulder: JointPoint(x: 0.40, y: shoulderY),
            .leftElbow: JointPoint(x: 0.40, y: 0.50),
            .leftWrist: JointPoint(x: 0.40, y: 0.45),
            .rightHip: JointPoint(x: 0.60 + hipBendOffsetX, y: 0.45),
            .leftHip: JointPoint(x: 0.40 + hipBendOffsetX, y: 0.45),
            .rightKnee: JointPoint(x: 0.60 + kneeBendOffsetX, y: 0.25),
            .leftKnee: JointPoint(x: 0.40 + kneeBendOffsetX, y: 0.25),
            .rightAnkle: JointPoint(x: 0.60, y: 0.05),
            .leftAnkle: JointPoint(x: 0.40, y: 0.05)
        ]
        return BodyPoseFrame(timestamp: timestamp, joints: joints)
    }

    // MARK: - Geometry & Cross-Platform Coordinate Normalization Tests

    func testVectorAngleCalculations() {
        let straight = PoseGeometry.angleDegrees(
            a: Point2D(x: 0.5, y: 0.8),
            vertex: Point2D(x: 0.5, y: 0.5),
            c: Point2D(x: 0.5, y: 0.2)
        )
        XCTAssertNotNil(straight)
        XCTAssertEqual(straight!, 180.0, accuracy: 1e-5)

        let rightAngle = PoseGeometry.angleDegrees(
            a: Point2D(x: 0.0, y: 1.0),
            vertex: Point2D(x: 0.0, y: 0.0),
            c: Point2D(x: 1.0, y: 0.0)
        )
        XCTAssertNotNil(rightAngle)
        XCTAssertEqual(rightAngle!, 90.0, accuracy: 1e-5)
    }

    func testAndroidMediaPipeTopLeftOriginNormalizesToUpwardY() {
        // In Android MediaPipe Pose, y = 0.08 is near the top of the frame (overhead)
        let mediaPipePoint = JointPoint(x: 0.6, y: 0.08, confidence: 0.95, origin: .topLeft)
        XCTAssertEqual(mediaPipePoint.location.y, 0.92, accuracy: 1e-9)
    }

    func testCameraFrameThrottlingStride() {
        let camera = CameraManager()
        camera.frameStride = 3
        let processedIndices = (1...9).filter { camera.shouldProcessIncomingFrame(index: UInt64($0)) }
        XCTAssertEqual(processedIndices, [3, 6, 9])
    }

    // MARK: - Fixation State Machine Tests

    func testValidFixationCycleCountsRepAndFiresAudioCue() {
        let spyAudio = SpyAudioCuePlayer()
        let detector = GSFixationDetector(audioEngine: spyAudio)
        detector.preferredSide = .right

        // 1. Rack position (wrist below shoulder: wristY = 0.55 < shoulderY = 0.65)
        let f0 = makePoseFrame(timestamp: 1.00, wristY: 0.55)
        XCTAssertEqual(detector.process(frame: f0), .rackOrSwing)

        // 2. Ascent (fast upward movement: dy/dt = (0.85 - 0.55) / 0.10 = 3.0 > epsilon)
        let f1 = makePoseFrame(timestamp: 1.10, wristY: 0.85)
        XCTAssertEqual(detector.process(frame: f1), .ascent)

        // 3. Stabilize overhead at t = 1.20 (wristY = 0.92, velocity slows down at t = 1.25)
        _ = detector.process(frame: makePoseFrame(timestamp: 1.20, wristY: 0.92))
        let fHoldStart = makePoseFrame(timestamp: 1.25, wristY: 0.92)
        XCTAssertEqual(detector.process(frame: fHoldStart), .overheadHolding(startTime: 1.25))
        XCTAssertEqual(detector.repCount, 0)

        // 4. Held for 200ms (t = 1.45) -> still holding (< 300ms)
        let fHoldMid = makePoseFrame(timestamp: 1.45, wristY: 0.921)
        XCTAssertEqual(detector.process(frame: fHoldMid), .overheadHolding(startTime: 1.25))
        XCTAssertEqual(detector.repCount, 0)

        // 5. Held for 320ms (t = 1.57 >= 1.25 + 0.30) -> Fixation Confirmed!
        let fConfirmed = makePoseFrame(timestamp: 1.57, wristY: 0.921)
        XCTAssertEqual(detector.process(frame: fConfirmed), .fixationConfirmed)
        XCTAssertEqual(detector.repCount, 1)
        XCTAssertEqual(spyAudio.cues, [.fixationValidated])

        // 6. Drop wrist below shoulder line (wristY = 0.52 < 0.65) -> transitions to .drop
        let fDrop = makePoseFrame(timestamp: 1.80, wristY: 0.52)
        XCTAssertEqual(detector.process(frame: fDrop), .drop)
    }

    func testPrematureDropBefore300msDoesNotCountRep() {
        let spyAudio = SpyAudioCuePlayer()
        let detector = GSFixationDetector(audioEngine: spyAudio)
        detector.preferredSide = .right

        _ = detector.process(frame: makePoseFrame(timestamp: 0.00, wristY: 0.92))
        XCTAssertEqual(
            detector.process(frame: makePoseFrame(timestamp: 0.05, wristY: 0.92)),
            .overheadHolding(startTime: 0.05)
        )
        _ = detector.process(frame: makePoseFrame(timestamp: 0.25, wristY: 0.92))
        XCTAssertEqual(
            detector.process(frame: makePoseFrame(timestamp: 0.30, wristY: 0.50)),
            .drop
        )
        XCTAssertEqual(detector.repCount, 0)
        XCTAssertTrue(spyAudio.cues.isEmpty)
    }

    func testSoftElbowOrKneePreventsFixationConfirmation() {
        let spyAudio = SpyAudioCuePlayer()
        let detector = GSFixationDetector(audioEngine: spyAudio)
        detector.preferredSide = .right

        for step in 0...10 {
            let t = Double(step) * 0.05
            _ = detector.process(
                frame: makePoseFrame(timestamp: t, wristY: 0.92, elbowBendOffsetX: 0.08)
            )
        }

        XCTAssertEqual(detector.repCount, 0)
        XCTAssertFalse(detector.biomechanics.isElbowLocked)
    }
}

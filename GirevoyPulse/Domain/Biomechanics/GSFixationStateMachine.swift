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

/// Lifecycle phases of a Girevoy Sport repetition (Snatch, Jerk, or Long Cycle).
/// Platform-agnostic enum portable 1:1 to Kotlin (`sealed interface LiftPhase`).
public enum LiftPhase: Equatable, Sendable {
    case rackOrSwing
    case ascent
    case overheadHolding(startTime: TimeInterval)
    case fixationConfirmed
    case drop

    public var displayTitle: String {
        switch self {
        case .rackOrSwing: return "RACK / SWING"
        case .ascent: return "ASCENT"
        case .overheadHolding: return "HOLDING..."
        case .fixationConfirmed: return "FIXATION"
        case .drop: return "DROP"
        }
    }

    public var isFixationConfirmed: Bool {
        if case .fixationConfirmed = self { return true }
        return false
    }
}

/// Configurable biomechanical thresholds for Girevoy Sport top fixation validation.
public struct FixationThresholds: Equatable, Codable, Sendable {
    /// Active arm elbow angle $\ge 168^\circ$ (accommodates hyper/hypo-mobile lifters).
    public var minimumElbowAngleDegrees: Double

    /// Both knees angle $\ge 172^\circ$ (knees locked).
    public var minimumKneeAngleDegrees: Double

    /// Hip extension angle $\ge 170^\circ$ (hips through).
    public var minimumHipAngleDegrees: Double

    /// Trajectory stability threshold $\left|\frac{\Delta y}{\Delta t}\right| < \epsilon$ (normalized height/sec).
    public var velocityEpsilon: Double

    /// Minimum overhead pause duration ($\ge 300\text{ ms}$) required for valid fixation.
    public var minimumFixationHoldDuration: TimeInterval

    public init(
        minimumElbowAngleDegrees: Double = 168.0,
        minimumKneeAngleDegrees: Double = 172.0,
        minimumHipAngleDegrees: Double = 170.0,
        velocityEpsilon: Double = 0.20,
        minimumFixationHoldDuration: TimeInterval = 0.300
    ) {
        self.minimumElbowAngleDegrees = minimumElbowAngleDegrees
        self.minimumKneeAngleDegrees = minimumKneeAngleDegrees
        self.minimumHipAngleDegrees = minimumHipAngleDegrees
        self.velocityEpsilon = velocityEpsilon
        self.minimumFixationHoldDuration = minimumFixationHoldDuration
    }
}

/// Real-time biomechanical telemetry for the current video frame, used to color the HUD skeletal overlay.
public struct FixationBiomechanicsSnapshot: Equatable, Sendable {
    public let activeSide: ActiveSide
    public let elbowAngleDegrees: Double
    public let leftKneeAngleDegrees: Double
    public let rightKneeAngleDegrees: Double
    public let hipAngleDegrees: Double
    public let wristVerticalVelocity: Double
    public let isElbowLocked: Bool
    public let areKneesLocked: Bool
    public let isHipExtended: Bool
    public let isTrajectoryStable: Bool
    public let isOverhead: Bool
    public let currentHoldDurationMs: Int

    public var isAllJointsLockedAndStable: Bool {
        isOverhead && isElbowLocked && areKneesLocked && isHipExtended && isTrajectoryStable
    }

    public static let zero = FixationBiomechanicsSnapshot(
        activeSide: .right,
        elbowAngleDegrees: 0,
        leftKneeAngleDegrees: 0,
        rightKneeAngleDegrees: 0,
        hipAngleDegrees: 0,
        wristVerticalVelocity: 0,
        isElbowLocked: false,
        areKneesLocked: false,
        isHipExtended: false,
        isTrajectoryStable: false,
        isOverhead: false,
        currentHoldDurationMs: 0
    )
}

/// Output emitted by `GSFixationStateMachine.step(frame:)` for a single pose frame.
public struct FixationStepResult: Equatable, Sendable {
    public let phase: LiftPhase
    public let repCount: Int
    public let didConfirmNewRep: Bool
    public let biomechanics: FixationBiomechanicsSnapshot
}

/// Pure, platform-agnostic Girevoy Sport fixation & rep-counting state machine.
/// Contains zero iOS/Apple framework dependencies (`Foundation` only) so it can be ported 1:1
/// to Android Kotlin and fed landmarks from Google MediaPipe Pose / CameraX.
public struct GSFixationStateMachine: Sendable {
    public var thresholds: FixationThresholds
    public var preferredSide: ActiveSide?

    public private(set) var phase: LiftPhase = .rackOrSwing
    public private(set) var repCount: Int = 0
    public private(set) var biomechanics: FixationBiomechanicsSnapshot = .zero

    private var previousWristY: Double?
    private var previousTimestamp: TimeInterval?

    public init(
        thresholds: FixationThresholds = FixationThresholds(),
        preferredSide: ActiveSide? = nil
    ) {
        self.thresholds = thresholds
        self.preferredSide = preferredSide
    }

    public mutating func reset() {
        phase = .rackOrSwing
        repCount = 0
        biomechanics = .zero
        previousWristY = nil
        previousTimestamp = nil
    }

    /// Evaluates a single `BodyPoseFrame` and returns the updated state machine snapshot.
    @discardableResult
    public mutating func step(frame: BodyPoseFrame) -> FixationStepResult {
        let side = preferredSide ?? frame.dominantLiftingSide()

        guard let wrist = activeWristPoint(in: frame, side: side),
              let shoulder = activeShoulderPoint(in: frame, side: side),
              let elbowAngle = frame.elbowAngle(side: side),
              let leftKneeAngle = frame.kneeAngle(side: .left),
              let rightKneeAngle = frame.kneeAngle(side: .right),
              let hipAngle = frame.hipExtensionAngle(side: side) else {
            return FixationStepResult(
                phase: phase,
                repCount: repCount,
                didConfirmNewRep: false,
                biomechanics: biomechanics
            )
        }

        let dyDt: Double
        let hasVelocitySample: Bool
        if let prevY = previousWristY,
           let prevT = previousTimestamp,
           frame.timestamp > prevT {
            dyDt = PoseGeometry.verticalVelocity(
                previousY: prevY,
                currentY: wrist.y,
                deltaTime: frame.timestamp - prevT
            )
            hasVelocitySample = true
        } else {
            dyDt = 0.0
            hasVelocitySample = false
        }
        previousWristY = wrist.y
        previousTimestamp = frame.timestamp

        let isOverhead = wrist.y > shoulder.y
        let isElbowLocked = elbowAngle >= thresholds.minimumElbowAngleDegrees
        let areKneesLocked = leftKneeAngle >= thresholds.minimumKneeAngleDegrees
            && rightKneeAngle >= thresholds.minimumKneeAngleDegrees
        let isHipExtended = hipAngle >= thresholds.minimumHipAngleDegrees
        let isTrajectoryStable = hasVelocitySample && abs(dyDt) < thresholds.velocityEpsilon
        let allCriteriaMet = isOverhead && isElbowLocked && areKneesLocked && isHipExtended && isTrajectoryStable

        var holdMs = 0
        var didConfirmNewRep = false

        switch phase {
        case .rackOrSwing:
            if isOverhead {
                if allCriteriaMet {
                    phase = .overheadHolding(startTime: frame.timestamp)
                } else {
                    phase = .ascent
                }
            } else if dyDt > thresholds.velocityEpsilon {
                phase = .ascent
            }

        case .ascent:
            if !isOverhead && dyDt < -thresholds.velocityEpsilon {
                phase = .rackOrSwing
            } else if allCriteriaMet {
                phase = .overheadHolding(startTime: frame.timestamp)
            }

        case .overheadHolding(let startTime):
            if !isOverhead {
                phase = .drop
            } else if allCriteriaMet {
                let heldDuration = max(0.0, frame.timestamp - startTime)
                holdMs = Int((heldDuration * 1000.0).rounded())
                if heldDuration >= thresholds.minimumFixationHoldDuration {
                    phase = .fixationConfirmed
                    repCount += 1
                    didConfirmNewRep = true
                }
            } else {
                phase = .ascent
            }

        case .fixationConfirmed:
            holdMs = Int((thresholds.minimumFixationHoldDuration * 1000.0).rounded())
            if wrist.y < shoulder.y {
                phase = .drop
            }

        case .drop:
            if wrist.y < shoulder.y && abs(dyDt) < thresholds.velocityEpsilon {
                phase = .rackOrSwing
            } else if isOverhead {
                phase = allCriteriaMet ? .overheadHolding(startTime: frame.timestamp) : .ascent
            }
        }

        biomechanics = FixationBiomechanicsSnapshot(
            activeSide: side,
            elbowAngleDegrees: elbowAngle,
            leftKneeAngleDegrees: leftKneeAngle,
            rightKneeAngleDegrees: rightKneeAngle,
            hipAngleDegrees: hipAngle,
            wristVerticalVelocity: dyDt,
            isElbowLocked: isElbowLocked,
            areKneesLocked: areKneesLocked,
            isHipExtended: isHipExtended,
            isTrajectoryStable: isTrajectoryStable,
            isOverhead: isOverhead,
            currentHoldDurationMs: holdMs
        )

        return FixationStepResult(
            phase: phase,
            repCount: repCount,
            didConfirmNewRep: didConfirmNewRep,
            biomechanics: biomechanics
        )
    }

    private func activeWristPoint(in frame: BodyPoseFrame, side: ActiveSide) -> Point2D? {
        switch side {
        case .left: return frame[.leftWrist]
        case .right: return frame[.rightWrist]
        case .both:
            guard let l = frame[.leftWrist], let r = frame[.rightWrist] else { return nil }
            return Point2D(x: (l.x + r.x) * 0.5, y: min(l.y, r.y))
        }
    }

    private func activeShoulderPoint(in frame: BodyPoseFrame, side: ActiveSide) -> Point2D? {
        switch side {
        case .left: return frame[.leftShoulder]
        case .right: return frame[.rightShoulder]
        case .both:
            guard let l = frame[.leftShoulder], let r = frame[.rightShoulder] else { return nil }
            return Point2D(x: (l.x + r.x) * 0.5, y: max(l.y, r.y))
        }
    }
}

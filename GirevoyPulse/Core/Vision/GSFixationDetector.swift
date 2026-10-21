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

import Combine
import Foundation

/// Thin iOS `ObservableObject` adapter wrapping the platform-agnostic `GSFixationStateMachine`
/// and dispatching audio/haptic cues when valid repetitions are confirmed.
public final class GSFixationDetector: ObservableObject, @unchecked Sendable {
    public private(set) var stateMachine: GSFixationStateMachine

    public var minimumElbowAngleDegrees: Double {
        get { stateMachine.thresholds.minimumElbowAngleDegrees }
        set { stateMachine.thresholds.minimumElbowAngleDegrees = newValue }
    }

    public var minimumKneeAngleDegrees: Double {
        get { stateMachine.thresholds.minimumKneeAngleDegrees }
        set { stateMachine.thresholds.minimumKneeAngleDegrees = newValue }
    }

    public var minimumHipAngleDegrees: Double {
        get { stateMachine.thresholds.minimumHipAngleDegrees }
        set { stateMachine.thresholds.minimumHipAngleDegrees = newValue }
    }

    public var velocityEpsilon: Double {
        get { stateMachine.thresholds.velocityEpsilon }
        set { stateMachine.thresholds.velocityEpsilon = newValue }
    }

    public var minimumFixationHoldDuration: TimeInterval {
        get { stateMachine.thresholds.minimumFixationHoldDuration }
        set { stateMachine.thresholds.minimumFixationHoldDuration = newValue }
    }

    @Published public private(set) var phase: LiftPhase = .rackOrSwing
    @Published public private(set) var repCount: Int = 0
    @Published public private(set) var biomechanics: FixationBiomechanicsSnapshot = .zero
    @Published public var preferredSide: ActiveSide? = nil {
        didSet { stateMachine.preferredSide = preferredSide }
    }

    public var onRepValidated: (@Sendable (Int) -> Void)?

    private let audioEngine: GSAudioCuePlaying

    public init(
        thresholds: FixationThresholds = FixationThresholds(),
        audioEngine: GSAudioCuePlaying = GSAudioFeedbackEngine.shared
    ) {
        self.stateMachine = GSFixationStateMachine(thresholds: thresholds)
        self.audioEngine = audioEngine
    }

    public func reset() {
        stateMachine.reset()
        phase = stateMachine.phase
        repCount = stateMachine.repCount
        biomechanics = stateMachine.biomechanics
    }

    @discardableResult
    public func process(frame: BodyPoseFrame) -> LiftPhase {
        let result = stateMachine.step(frame: frame)
        phase = result.phase
        repCount = result.repCount
        biomechanics = result.biomechanics

        if result.didConfirmNewRep {
            audioEngine.play(.fixationValidated)
            onRepValidated?(result.repCount)
        }
        return result.phase
    }
}

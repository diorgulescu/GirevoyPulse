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

import Combine
import Foundation
import QuartzCore

/// iOS monotonic clock powered by Mach absolute time via `CACurrentMediaTime()`.
/// (On Android, replace with `SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0`).
public struct SystemMonotonicClock: MonotonicClock {
    public init() {}
    public func now() -> TimeInterval {
        CACurrentMediaTime()
    }
}

/// iOS `ObservableObject` adapter driving the platform-agnostic `GSTimerCoreEngine`
/// with a high-precision `DispatchSourceTimer` and `GSAudioCuePlaying`.
@MainActor
public final class GSTimerEngine: ObservableObject {
    // MARK: - Published State (Mirrored from `GSTimerCoreEngine`)

    @Published public private(set) var mode: GSTimerMode
    @Published public private(set) var isRunning: Bool = false
    @Published public private(set) var isPaused: Bool = false
    @Published public private(set) var isCompleted: Bool = false
    @Published public private(set) var elapsedTime: TimeInterval = 0.0
    @Published public private(set) var remainingTime: TimeInterval = 600.0
    @Published public private(set) var intervalPhase: TimerIntervalPhase = .work(round: 1, totalRounds: 1)
    @Published public private(set) var phaseRemainingTime: TimeInterval = 600.0
    @Published public private(set) var splits: [MinuteSplit] = []
    @Published public private(set) var handSwitchAlertsTriggered: Int = 0
    @Published public private(set) var secondsUntilNextSwitch: TimeInterval?
    @Published public private(set) var metronomeTicksFired: Int = 0
    @Published public private(set) var currentMinuteReps: Int = 0
    @Published public private(set) var currentRPM: Double = 0.0

    // MARK: - Platform-Agnostic Core Engine & iOS Dependencies

    public private(set) var core: GSTimerCoreEngine
    private let clock: MonotonicClock
    private let audioEngine: GSAudioCuePlaying
    private var dispatchTimer: DispatchSourceTimer?

    public var onHandSwitchAlert: (@MainActor () -> Void)?

    public init(
        mode: GSTimerMode = .traditional10Min(targetRPM: 16.0),
        clock: MonotonicClock = SystemMonotonicClock(),
        audioEngine: GSAudioCuePlaying = GSAudioFeedbackEngine.shared
    ) {
        self.mode = mode
        self.core = GSTimerCoreEngine(mode: mode)
        self.clock = clock
        self.audioEngine = audioEngine
        syncPublishedStateFromCore()
    }

    deinit {
        dispatchTimer?.setEventHandler {}
        dispatchTimer?.cancel()
    }

    // MARK: - Public Controls

    public func configure(mode: GSTimerMode) {
        cancelDispatchSourceTimer()
        core.configure(mode: mode)
        syncPublishedStateFromCore()
    }

    public func start() {
        guard !core.isRunning else { return }
        let cues = core.start(at: clock.now())
        syncPublishedStateFromCore()
        for cue in cues {
            audioEngine.play(cue)
        }
        startDispatchSourceTimer()
    }

    public func pause() {
        guard core.isRunning else { return }
        core.pause(at: clock.now())
        cancelDispatchSourceTimer()
        syncPublishedStateFromCore()
    }

    public func stop() {
        cancelDispatchSourceTimer()
        core.stop()
        syncPublishedStateFromCore()
    }

    public func recordRep(currentHeartRate: Int? = nil) {
        core.recordRep(at: clock.now(), currentHeartRate: currentHeartRate)
        syncPublishedStateFromCore()
    }

    public func recordHeartRateSample(_ bpm: Int) {
        core.recordHeartRateSample(bpm)
    }

    public func stepToMonotonicTime(_ monotonicNow: TimeInterval) {
        guard core.isRunning else { return }
        let result = core.step(at: monotonicNow)
        syncPublishedStateFromCore()
        for cue in result.cues {
            audioEngine.play(cue)
        }
        if result.didTriggerHandSwitch {
            onHandSwitchAlert?()
        }
        if core.isCompleted {
            cancelDispatchSourceTimer()
        }
    }

    public static func formatTime(_ seconds: TimeInterval, includeTenths: Bool = false) -> String {
        let clamped = max(0.0, seconds)
        let totalWholeSeconds = Int(clamped.rounded(.down))
        let minutes = totalWholeSeconds / 60
        let secs = totalWholeSeconds % 60
        if includeTenths {
            let tenths = Int(((clamped - Double(totalWholeSeconds)) * 10.0).rounded(.down))
            return String(format: "%02d:%02d.%d", minutes, secs, max(0, min(9, tenths)))
        } else {
            return String(format: "%02d:%02d", minutes, secs)
        }
    }

    // MARK: - Internal DispatchSourceTimer & Sync

    private func startDispatchSourceTimer() {
        cancelDispatchSourceTimer()
        let timer = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(10), leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.core.isRunning else { return }
                self.stepToMonotonicTime(self.clock.now())
            }
        }
        dispatchTimer = timer
        timer.resume()
    }

    private func cancelDispatchSourceTimer() {
        dispatchTimer?.setEventHandler {}
        dispatchTimer?.cancel()
        dispatchTimer = nil
    }

    private func syncPublishedStateFromCore() {
        mode = core.mode
        isRunning = core.isRunning
        isPaused = core.isPaused
        isCompleted = core.isCompleted
        elapsedTime = core.elapsedTime
        remainingTime = core.remainingTime
        intervalPhase = core.intervalPhase
        phaseRemainingTime = core.phaseRemainingTime
        splits = core.splits
        handSwitchAlertsTriggered = core.handSwitchAlertsTriggered
        secondsUntilNextSwitch = core.secondsUntilNextSwitch
        metronomeTicksFired = core.metronomeTicksFired
        currentMinuteReps = core.currentMinuteReps
        currentRPM = core.currentRPM
    }
}

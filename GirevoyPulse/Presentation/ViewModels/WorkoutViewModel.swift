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

import AVFoundation
import Combine
import CoreGraphics
import Foundation
import QuartzCore

/// Primary @MainActor ViewModel orchestrating the GS Timing Engine, Vision Pose Pipeline,
/// Fixation State Machine, and Polar H10 BLE Telemetry using Unidirectional Data Flow.
@MainActor
public final class WorkoutViewModel: ObservableObject {
    // MARK: - Sub-Engines

    public let timerEngine: GSTimerEngine
    public let cameraManager: CameraManager
    public let fixationDetector: GSFixationDetector
    public let polarManager: PolarManager
    private let poseEstimator = PoseEstimator()

    // MARK: - Workout Setup Configuration

    @Published public var selectedDiscipline: GSDiscipline = .snatch {
        didSet {
            targetRPM = selectedDiscipline.defaultTargetRPM
            activeSide = (selectedDiscipline == .snatch) ? .right : .both
            fixationDetector.preferredSide = activeSide
            applyTimerConfiguration()
        }
    }

    @Published public var selectedTimerCategory: GSTimerCategory = .traditional10Min {
        didSet { applyTimerConfiguration() }
    }

    @Published public var targetRPM: Double = 16.0 {
        didSet { applyTimerConfiguration() }
    }

    @Published public var marathonDuration: MarathonDuration = .halfMarathon30Min {
        didSet { applyTimerConfiguration() }
    }

    @Published public var marathonSwitchMinutes: Int = 5 {
        didSet { applyTimerConfiguration() }
    }

    @Published public var intervalWorkSeconds: TimeInterval = 60.0 {
        didSet { applyTimerConfiguration() }
    }

    @Published public var intervalRestSeconds: TimeInterval = 60.0 {
        didSet { applyTimerConfiguration() }
    }

    @Published public var intervalRounds: Int = 5 {
        didSet { applyTimerConfiguration() }
    }

    @Published public var activeSide: ActiveSide = .right {
        didSet { fixationDetector.preferredSide = activeSide }
    }

    @Published public var isSetupSheetPresented: Bool = false
    @Published public var isDemoPoseFeedEnabled: Bool = false {
        didSet { updateDemoPoseTimer() }
    }

    // MARK: - Live HUD State Mirrored from Sub-Engines

    @Published public private(set) var latestPoseFrame: BodyPoseFrame?
    @Published public private(set) var repCount: Int = 0
    @Published public private(set) var liftPhase: LiftPhase = .rackOrSwing
    @Published public private(set) var biomechanics: FixationBiomechanicsSnapshot = .zero

    private var cancellables = Set<AnyCancellable>()
    private var demoPoseTimer: Timer?
    private let frameBridge = CameraFrameBridge()

    public init(
        timerEngine: GSTimerEngine? = nil,
        cameraManager: CameraManager = CameraManager(),
        fixationDetector: GSFixationDetector = GSFixationDetector(),
        polarManager: PolarManager = PolarManager()
    ) {
        self.timerEngine = timerEngine ?? GSTimerEngine(mode: .traditional10Min(targetRPM: 16.0))
        self.cameraManager = cameraManager
        self.fixationDetector = fixationDetector
        self.polarManager = polarManager

        self.fixationDetector.preferredSide = activeSide
        bindSubEngines()
        configureVisionPipeline()
    }

    // MARK: - Bindings & Vision Pipeline

    private func bindSubEngines() {
        // Forward changes from nested ObservableObjects so SwiftUI HUD views refresh immediately
        timerEngine.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)

        polarManager.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                if self.polarManager.bpm > 0 {
                    self.timerEngine.recordHeartRateSample(self.polarManager.bpm)
                }
                self.objectWillChange.send()
            }
            .store(in: &cancellables)

        cameraManager.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &cancellables)

        // Automatically switch active hand when a marathon or snatch hand-switch alert triggers
        timerEngine.onHandSwitchAlert = { [weak self] in
            guard let self else { return }
            if self.activeSide != .both {
                self.activeSide.toggleHand()
            }
        }

        // Synchronize validated fixation reps with the timing engine split tracker
        fixationDetector.onRepValidated = { [weak self] newTotalReps in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.repCount = newTotalReps
                let currentHR = self.polarManager.bpm > 0 ? self.polarManager.bpm : nil
                self.timerEngine.recordRep(currentHeartRate: currentHR)
            }
        }
    }

    private func configureVisionPipeline() {
        let estimator = poseEstimator
        frameBridge.onSampleBuffer = { [weak self] sampleBuffer, orientation, timestamp in
            guard let frame = estimator.estimatePose(
                from: sampleBuffer,
                orientation: orientation,
                timestamp: timestamp
            ) else {
                return
            }
            Task { @MainActor [weak self] in
                self?.ingestPoseFrame(frame)
            }
        }
        cameraManager.frameDelegate = frameBridge
    }

    public func ingestPoseFrame(_ frame: BodyPoseFrame) {
        latestPoseFrame = frame
        let newPhase = fixationDetector.process(frame: frame)
        liftPhase = newPhase
        biomechanics = fixationDetector.biomechanics
        repCount = fixationDetector.repCount
    }

    // MARK: - User Actions

    public func onAppear() {
        cameraManager.requestPermissionAndStart()
    }

    public func toggleWorkoutStartPause() {
        if timerEngine.isRunning {
            timerEngine.pause()
        } else {
            if timerEngine.isCompleted {
                resetWorkout()
            }
            timerEngine.start()
        }
    }

    public func resetWorkout() {
        timerEngine.stop()
        fixationDetector.reset()
        repCount = 0
        liftPhase = .rackOrSwing
        biomechanics = .zero
    }

    public func manualIncrementRep() {
        let hr = polarManager.bpm > 0 ? polarManager.bpm : nil
        repCount += 1
        timerEngine.recordRep(currentHeartRate: hr)
        GSAudioFeedbackEngine.shared.play(.fixationValidated)
    }

    public func applyTimerConfiguration() {
        guard !timerEngine.isRunning else { return }
        let newMode: GSTimerMode
        switch selectedTimerCategory {
        case .traditional10Min:
            newMode = .traditional10Min(targetRPM: targetRPM)
        case .marathon:
            newMode = .marathon(
                duration: marathonDuration,
                switchIntervalMinutes: marathonSwitchMinutes,
                targetRPM: targetRPM
            )
        case .intervals:
            newMode = .intervals(
                workSeconds: intervalWorkSeconds,
                restSeconds: intervalRestSeconds,
                rounds: intervalRounds,
                targetRPM: targetRPM
            )
        }
        timerEngine.configure(mode: newMode)
    }

    // MARK: - Demo Pose Feed for Simulator / Distance UI Preview

    private func updateDemoPoseTimer() {
        demoPoseTimer?.invalidate()
        demoPoseTimer = nil
        guard isDemoPoseFeedEnabled else { return }

        // Simulate 25 Hz (40ms) kettlebell lifting cycle synchronized to targetRPM
        demoPoseTimer = Timer.scheduledTimer(withTimeInterval: 0.04, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isDemoPoseFeedEnabled else { return }
                let now = CACurrentMediaTime()
                let cyclePeriod = max(2.5, 60.0 / max(6.0, self.targetRPM))
                let phaseProgress = (now.truncatingRemainder(dividingBy: cyclePeriod)) / cyclePeriod

                // Construct realistic biomechanical trajectory:
                // 0.00..<0.25: rack/swing (wristY = 0.52)
                // 0.25..<0.42: ascent (wristY ramps 0.52 -> 0.92)
                // 0.42..<0.78: locked overhead hold (wristY = 0.92, > 300ms stable)
                // 0.78..<1.00: drop to rack/swing
                let wristY: Double
                let elbowBend: Double
                if phaseProgress < 0.25 {
                    wristY = 0.52
                    elbowBend = 0.06
                } else if phaseProgress < 0.42 {
                    let t = (phaseProgress - 0.25) / 0.17
                    wristY = 0.52 + (0.40 * t)
                    elbowBend = 0.06 * (1.0 - t)
                } else if phaseProgress < 0.78 {
                    wristY = 0.92
                    elbowBend = 0.0 // 180 deg full lockout
                } else {
                    let t = (phaseProgress - 0.78) / 0.22
                    wristY = 0.92 - (0.40 * t)
                    elbowBend = 0.05 * t
                }

                let frame = Self.makeDemoPoseFrame(
                    timestamp: now,
                    wristY: wristY,
                    elbowBend: elbowBend,
                    activeSide: self.activeSide
                )
                self.ingestPoseFrame(frame)
            }
        }
    }

    private static func makeDemoPoseFrame(
        timestamp: TimeInterval,
        wristY: Double,
        elbowBend: Double,
        activeSide: ActiveSide
    ) -> BodyPoseFrame {
        let shoulderY: Double = 0.66
        let rightWristY: Double = (activeSide == .right || activeSide == .both) ? wristY : 0.46
        let leftWristY: Double = (activeSide == .left || activeSide == .both) ? wristY : 0.46

        let joints: [GSJointName: JointPoint] = [
            .neck: JointPoint(x: 0.50, y: 0.72),
            .rightShoulder: JointPoint(x: 0.60, y: shoulderY),
            .rightElbow: JointPoint(x: 0.60 + elbowBend, y: (shoulderY + rightWristY) * 0.5),
            .rightWrist: JointPoint(x: 0.60, y: rightWristY),
            .leftShoulder: JointPoint(x: 0.40, y: shoulderY),
            .leftElbow: JointPoint(x: 0.40 - elbowBend, y: (shoulderY + leftWristY) * 0.5),
            .leftWrist: JointPoint(x: 0.40, y: leftWristY),
            .rightHip: JointPoint(x: 0.58, y: 0.46),
            .leftHip: JointPoint(x: 0.42, y: 0.46),
            .rightKnee: JointPoint(x: 0.58, y: 0.26),
            .leftKnee: JointPoint(x: 0.42, y: 0.26),
            .rightAnkle: JointPoint(x: 0.58, y: 0.08),
            .leftAnkle: JointPoint(x: 0.42, y: 0.08)
        ]
        return BodyPoseFrame(timestamp: timestamp, joints: joints)
    }
}

/// Thread-safe bridge forwarding `CameraManager` vision-queue callbacks to `PoseEstimator`.
private final class CameraFrameBridge: CameraFrameProcessingDelegate, @unchecked Sendable {
    var onSampleBuffer: ((CMSampleBuffer, CGImagePropertyOrientation, TimeInterval) -> Void)?

    func cameraManager(
        _ manager: CameraManager,
        didOutput sampleBuffer: CMSampleBuffer,
        orientation: CGImagePropertyOrientation,
        monotonicTimestamp: TimeInterval
    ) {
        onSampleBuffer?(sampleBuffer, orientation, monotonicTimestamp)
    }
}

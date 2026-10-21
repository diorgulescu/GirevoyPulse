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
import Foundation
import QuartzCore

/// Delegate protocol receiving throttled (20–30 Hz) video sample buffers on `com.girevoypulse.visionqueue`.
public protocol CameraFrameProcessingDelegate: AnyObject, Sendable {
    func cameraManager(
        _ manager: CameraManager,
        didOutput sampleBuffer: CMSampleBuffer,
        orientation: CGImagePropertyOrientation,
        monotonicTimestamp: TimeInterval
    )
}

/// Manages the 1080p front-facing `AVCaptureSession` and throttles frame delivery (1 in every 2–3 frames)
/// on a dedicated background queue (`com.girevoypulse.visionqueue`) to prevent thermal throttling during 10–60m sets.
public final class CameraManager: NSObject, ObservableObject, @unchecked Sendable {
    public enum CameraStatus: String, Sendable {
        case unconfigured = "Unconfigured"
        case unauthorized = "Camera Permission Denied"
        case ready = "Camera Ready"
        case running = "Live 20-30 Hz ANE"
        case unavailable = "Simulated Camera"
    }

    @MainActor @Published public private(set) var status: CameraStatus = .unconfigured
    @MainActor @Published public private(set) var effectiveFrameRateHz: Double = 0.0

    public let captureSession = AVCaptureSession()

    /// Dedicated serial dispatch queue for Vision pose estimation (`com.girevoypulse.visionqueue`).
    public let visionQueue = DispatchQueue(label: "com.girevoypulse.visionqueue", qos: .userInitiated)
    private let sessionQueue = DispatchQueue(label: "com.girevoypulse.capturesession", qos: .userInitiated)

    /// Frame stride for thermal throttling: processes 1 frame every `frameStride` incoming frames (default 2 -> ~20–30 Hz).
    public var frameStride: Int = 2

    public weak var frameDelegate: CameraFrameProcessingDelegate?

    private var incomingFrameCounter: UInt64 = 0
    private var processedFrameCounter: UInt64 = 0
    private var lastFPSWindowTimestamp: TimeInterval = 0.0
    private var isConfigured = false

    public override init() {
        super.init()
    }

    // MARK: - Lifecycle & Permissions

    public func requestPermissionAndStart() {
        let authStatus = AVCaptureDevice.authorizationStatus(for: .video)
        switch authStatus {
        case .authorized:
            configureAndStartSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.configureAndStartSession()
                } else {
                    Task { @MainActor in
                        self.status = .unauthorized
                    }
                }
            }
        case .denied, .restricted:
            Task { @MainActor in
                self.status = .unauthorized
            }
        @unknown default:
            Task { @MainActor in
                self.status = .unavailable
            }
        }
    }

    public func stopSession() {
        sessionQueue.async { [weak self] in
            guard let self, self.captureSession.isRunning else { return }
            self.captureSession.stopRunning()
            Task { @MainActor in
                self.status = .ready
                self.effectiveFrameRateHz = 0.0
            }
        }
    }

    private func configureAndStartSession() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if !self.isConfigured {
                self.configureCaptureSessionLocked()
            }
            guard self.isConfigured else { return }
            if !self.captureSession.isRunning {
                self.captureSession.startRunning()
                Task { @MainActor in
                    self.status = .running
                }
            }
        }
    }

    private func configureCaptureSessionLocked() {
        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        if captureSession.canSetSessionPreset(.hd1920x1080) {
            captureSession.sessionPreset = .hd1920x1080
        } else if captureSession.canSetSessionPreset(.hd1280x720) {
            captureSession.sessionPreset = .hd1280x720
        }

        guard let frontCamera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .front
        ) else {
            Task { @MainActor in
                self.status = .unavailable
            }
            return
        }

        do {
            let videoInput = try AVCaptureDeviceInput(device: frontCamera)
            if captureSession.canAddInput(videoInput) {
                captureSession.addInput(videoInput)
            }
        } catch {
            Task { @MainActor in
                self.status = .unavailable
            }
            return
        }

        let videoOutput = AVCaptureVideoDataOutput()
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)
        ]
        videoOutput.setSampleBufferDelegate(self, queue: visionQueue)

        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        if let connection = videoOutput.connection(with: .video),
           connection.isVideoMirroringSupported {
            connection.isVideoMirrored = true
        }

        isConfigured = true
        Task { @MainActor in
            self.status = .ready
        }
    }

    /// Helper used by unit tests to verify frame-throttling logic without hardware camera buffers.
    public func shouldProcessIncomingFrame(index: UInt64) -> Bool {
        let stride = UInt64(max(1, frameStride))
        return index % stride == 0
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        incomingFrameCounter &+= 1
        guard shouldProcessIncomingFrame(index: incomingFrameCounter) else {
            return
        }

        let now = CACurrentMediaTime()
        processedFrameCounter &+= 1
        if lastFPSWindowTimestamp == 0.0 {
            lastFPSWindowTimestamp = now
        } else {
            let windowElapsed = now - lastFPSWindowTimestamp
            if windowElapsed >= 1.0 {
                let fps = Double(processedFrameCounter) / windowElapsed
                processedFrameCounter = 0
                lastFPSWindowTimestamp = now
                Task { @MainActor [weak self] in
                    self?.effectiveFrameRateHz = fps
                }
            }
        }

        frameDelegate?.cameraManager(
            self,
            didOutput: sampleBuffer,
            orientation: .upMirrored,
            monotonicTimestamp: now
        )
    }
}

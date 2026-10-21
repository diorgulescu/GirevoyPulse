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

import CoreMedia
import Foundation
import Vision

/// iOS Apple Vision adapter executing `VNDetectHumanBodyPoseRequest` on the Apple Neural Engine (ANE)
/// and mapping landmarks into the platform-agnostic `BodyPoseFrame`.
/// (On Android, replace this adapter with Google `MediaPipe Pose Landmarker` outputting `BodyPoseFrame` with `origin: .topLeft`).
public final class PoseEstimator: @unchecked Sendable {
    public static let minimumJointConfidence: Float = BodyPoseFrame.minimumJointConfidence

    private let humanPoseRequest = VNDetectHumanBodyPoseRequest()

    public init() {}

    /// Processes a live camera `CMSampleBuffer` and returns a platform-agnostic `BodyPoseFrame` if a lifter is detected.
    public func estimatePose(
        from sampleBuffer: CMSampleBuffer,
        orientation: CGImagePropertyOrientation = .upMirrored,
        timestamp: TimeInterval
    ) -> BodyPoseFrame? {
        let handler = VNImageRequestHandler(
            cmSampleBuffer: sampleBuffer,
            orientation: orientation,
            options: [:]
        )

        do {
            try handler.perform([humanPoseRequest])
            guard let observation = humanPoseRequest.results?.first else {
                return nil
            }
            return Self.extractPoseFrame(from: observation, timestamp: timestamp)
        } catch {
            return nil
        }
    }

    /// Maps `VNHumanBodyPoseObservation` landmarks into a platform-agnostic `BodyPoseFrame`.
    public static func extractPoseFrame(
        from observation: VNHumanBodyPoseObservation,
        timestamp: TimeInterval
    ) -> BodyPoseFrame? {
        guard let recognizedPoints = try? observation.recognizedPoints(.all) else {
            return nil
        }

        let mapping: [(GSJointName, VNHumanBodyPoseObservation.JointName)] = [
            (.neck, .neck),
            (.leftShoulder, .leftShoulder),
            (.rightShoulder, .rightShoulder),
            (.leftElbow, .leftElbow),
            (.rightElbow, .rightElbow),
            (.leftWrist, .leftWrist),
            (.rightWrist, .rightWrist),
            (.leftHip, .leftHip),
            (.rightHip, .rightHip),
            (.leftKnee, .leftKnee),
            (.rightKnee, .rightKnee),
            (.leftAnkle, .leftAnkle),
            (.rightAnkle, .rightAnkle)
        ]

        var extracted: [GSJointName: JointPoint] = [:]
        extracted.reserveCapacity(mapping.count)

        for (gsJoint, vnJoint) in mapping {
            if let point = recognizedPoints[vnJoint], point.confidence >= minimumJointConfidence {
                extracted[gsJoint] = JointPoint(
                    x: Double(point.location.x),
                    y: Double(point.location.y),
                    confidence: point.confidence,
                    origin: .bottomLeft
                )
            }
        }

        guard !extracted.isEmpty else { return nil }
        return BodyPoseFrame(timestamp: timestamp, joints: extracted)
    }
}

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

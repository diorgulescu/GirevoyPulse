import Foundation

/// Platform-agnostic 2D point in normalized coordinate space (replaces `CGPoint` / `CGFloat` in core domain logic).
/// Portable 1:1 to Kotlin (`data class Point2D(val x: Double, val y: Double)`).
public struct Point2D: Equatable, Codable, Sendable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

/// Specifies the vertical origin of incoming pose coordinates so both Apple `Vision` (bottom-left origin)
/// and Android Google `MediaPipe Pose` (top-left origin) normalize to the same upward `+y` physical axis.
public enum CoordinateOrigin: Sendable {
    /// Origin `(0, 0)` is bottom-left; `y` increases upward (Apple Vision default).
    case bottomLeft
    /// Origin `(0, 0)` is top-left; `y` increases downward (Google MediaPipe / CameraX default).
    case topLeft
}

/// Tracked anatomical landmarks shared across Apple Vision (`VNHumanBodyPoseObservation`)
/// and Google MediaPipe Pose (`PoseLandmark`).
public enum GSJointName: String, CaseIterable, Codable, Sendable {
    // Upper Body
    case neck
    case leftShoulder
    case rightShoulder
    case leftElbow
    case rightElbow
    case leftWrist
    case rightWrist

    // Lower Body
    case leftHip
    case rightHip
    case leftKnee
    case rightKnee
    case leftAnkle
    case rightAnkle
}

/// Normalized 2D joint landmark where `x` is in `[0, 1]` (left-to-right) and `y` is in `[0, 1]` (bottom-to-top).
public struct JointPoint: Equatable, Codable, Sendable {
    public let location: Point2D
    public let confidence: Float

    public init(
        x: Double,
        y: Double,
        confidence: Float = 1.0,
        origin: CoordinateOrigin = .bottomLeft
    ) {
        let normalizedY = (origin == .bottomLeft) ? y : (1.0 - y)
        self.location = Point2D(x: x, y: normalizedY)
        self.confidence = confidence
    }

    public init(location: Point2D, confidence: Float = 1.0) {
        self.location = location
        self.confidence = confidence
    }
}

/// Platform-agnostic biomechanical snapshot for a single frame.
/// Contains zero references to `Vision`, `AVFoundation`, or `CoreGraphics`.
public struct BodyPoseFrame: Equatable, Sendable {
    public static let minimumJointConfidence: Float = 0.25

    public let timestamp: TimeInterval
    public let joints: [GSJointName: JointPoint]

    public init(timestamp: TimeInterval, joints: [GSJointName: JointPoint]) {
        self.timestamp = timestamp
        self.joints = joints
    }

    public subscript(_ joint: GSJointName) -> Point2D? {
        guard let point = joints[joint], point.confidence >= Self.minimumJointConfidence else {
            return nil
        }
        return point.location
    }

    // MARK: - Biomechanical Joint Angle Calculations

    /// $\text{Elbow Angle} = \angle(\text{Shoulder}, \text{Elbow}, \text{Wrist})$ in degrees $[0, 180]$.
    public func elbowAngle(side: ActiveSide) -> Double? {
        switch side {
        case .left:
            guard let s = self[.leftShoulder], let e = self[.leftElbow], let w = self[.leftWrist] else { return nil }
            return PoseGeometry.angleDegrees(a: s, vertex: e, c: w)
        case .right:
            guard let s = self[.rightShoulder], let e = self[.rightElbow], let w = self[.rightWrist] else { return nil }
            return PoseGeometry.angleDegrees(a: s, vertex: e, c: w)
        case .both:
            guard let left = elbowAngle(side: .left), let right = elbowAngle(side: .right) else { return nil }
            return min(left, right)
        }
    }

    /// $\text{Knee Angle} = \angle(\text{Hip}, \text{Knee}, \text{Ankle})$ in degrees $[0, 180]$.
    public func kneeAngle(side: ActiveSide) -> Double? {
        switch side {
        case .left:
            guard let h = self[.leftHip], let k = self[.leftKnee], let a = self[.leftAnkle] else { return nil }
            return PoseGeometry.angleDegrees(a: h, vertex: k, c: a)
        case .right:
            guard let h = self[.rightHip], let k = self[.rightKnee], let a = self[.rightAnkle] else { return nil }
            return PoseGeometry.angleDegrees(a: h, vertex: k, c: a)
        case .both:
            guard let left = kneeAngle(side: .left), let right = kneeAngle(side: .right) else { return nil }
            return min(left, right)
        }
    }

    /// $\text{Hip Extension} = \angle(\text{Shoulder}, \text{Hip}, \text{Knee})$ in degrees $[0, 180]$.
    public func hipExtensionAngle(side: ActiveSide) -> Double? {
        switch side {
        case .left:
            guard let s = self[.leftShoulder], let h = self[.leftHip], let k = self[.leftKnee] else { return nil }
            return PoseGeometry.angleDegrees(a: s, vertex: h, c: k)
        case .right:
            guard let s = self[.rightShoulder], let h = self[.rightHip], let k = self[.rightKnee] else { return nil }
            return PoseGeometry.angleDegrees(a: s, vertex: h, c: k)
        case .both:
            guard let left = hipExtensionAngle(side: .left), let right = hipExtensionAngle(side: .right) else { return nil }
            return min(left, right)
        }
    }

    /// Infers which arm is actively lifting based on wrist vertical height (`y` in bottom-to-top space).
    public func dominantLiftingSide() -> ActiveSide {
        let leftY = self[.leftWrist]?.y ?? -1.0
        let rightY = self[.rightWrist]?.y ?? -1.0
        return leftY >= rightY ? .left : .right
    }
}

/// Pure mathematical vector geometry utilities for joint angle and velocity computation.
/// Zero platform framework dependencies; directly portable to Kotlin (`object PoseGeometry`).
public enum PoseGeometry {
    /// Computes the interior angle $\angle(A, B, C)$ at vertex $B$ in degrees $\in [0^\circ, 180^\circ]$:
    /// $\theta = \arccos\left(\frac{\vec{BA} \cdot \vec{BC}}{\|\vec{BA}\| \|\vec{BC}\|}\right) \cdot \frac{180}{\pi}$
    public static func angleDegrees(a: Point2D, vertex b: Point2D, c: Point2D) -> Double? {
        let baX = a.x - b.x
        let baY = a.y - b.y
        let bcX = c.x - b.x
        let bcY = c.y - b.y

        let magBA = hypot(baX, baY)
        let magBC = hypot(bcX, bcY)
        guard magBA > 1e-6, magBC > 1e-6 else { return nil }

        let dotProduct = (baX * bcX) + (baY * bcY)
        let cosTheta = max(-1.0, min(1.0, dotProduct / (magBA * magBC)))
        return acos(cosTheta) * (180.0 / .pi)
    }

    /// Computes vertical velocity $\frac{\Delta y}{\Delta t}$ in normalized screen heights per second.
    public static func verticalVelocity(previousY: Double, currentY: Double, deltaTime: TimeInterval) -> Double {
        guard deltaTime > 1e-5 else { return 0.0 }
        return (currentY - previousY) / deltaTime
    }
}

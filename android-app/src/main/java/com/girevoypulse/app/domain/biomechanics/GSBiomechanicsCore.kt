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

package com.girevoypulse.app.domain.biomechanics

import com.girevoypulse.app.domain.models.ActiveSide
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Platform-agnostic 2D point in normalized coordinate space. */
data class Point2D(
    val x: Double,
    val y: Double
)

/**
 * Specifies the vertical origin of incoming pose coordinates so both Apple `Vision` (`BOTTOM_LEFT`)
 * and Android Google `MediaPipe Pose` (`TOP_LEFT`) normalize to the same upward `+y` physical axis.
 */
enum class CoordinateOrigin {
    BOTTOM_LEFT,
    TOP_LEFT
}

/** Tracked anatomical landmarks shared across Apple Vision and Google MediaPipe Pose. */
enum class GSJointName {
    NECK,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE
}

/** Normalized 2D joint landmark where `x` is in `[0, 1]` and `y` is in `[0, 1]` (bottom-to-top). */
data class JointPoint(
    val location: Point2D,
    val confidence: Float = 1.0f
) {
    constructor(
        x: Double,
        y: Double,
        confidence: Float = 1.0f,
        origin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
    ) : this(
        location = Point2D(
            x = x,
            y = if (origin == CoordinateOrigin.BOTTOM_LEFT) y else (1.0 - y)
        ),
        confidence = confidence
    )
}

/** Platform-agnostic biomechanical snapshot for a single video frame. */
data class BodyPoseFrame(
    val timestamp: Double,
    val joints: Map<GSJointName, JointPoint>
) {
    companion object {
        const val MINIMUM_JOINT_CONFIDENCE: Float = 0.25f
    }

    operator fun get(joint: GSJointName): Point2D? {
        val point = joints[joint] ?: return null
        return if (point.confidence >= MINIMUM_JOINT_CONFIDENCE) point.location else null
    }

    /** Elbow Angle = angle(Shoulder, Elbow, Wrist) in degrees [0, 180]. */
    fun elbowAngle(side: ActiveSide): Double? = when (side) {
        ActiveSide.LEFT -> {
            val s = this[GSJointName.LEFT_SHOULDER]
            val e = this[GSJointName.LEFT_ELBOW]
            val w = this[GSJointName.LEFT_WRIST]
            if (s != null && e != null && w != null) PoseGeometry.angleDegrees(s, e, w) else null
        }
        ActiveSide.RIGHT -> {
            val s = this[GSJointName.RIGHT_SHOULDER]
            val e = this[GSJointName.RIGHT_ELBOW]
            val w = this[GSJointName.RIGHT_WRIST]
            if (s != null && e != null && w != null) PoseGeometry.angleDegrees(s, e, w) else null
        }
        ActiveSide.BOTH -> {
            val left = elbowAngle(ActiveSide.LEFT)
            val right = elbowAngle(ActiveSide.RIGHT)
            if (left != null && right != null) min(left, right) else null
        }
    }

    /** Knee Angle = angle(Hip, Knee, Ankle) in degrees [0, 180]. */
    fun kneeAngle(side: ActiveSide): Double? = when (side) {
        ActiveSide.LEFT -> {
            val h = this[GSJointName.LEFT_HIP]
            val k = this[GSJointName.LEFT_KNEE]
            val a = this[GSJointName.LEFT_ANKLE]
            if (h != null && k != null && a != null) PoseGeometry.angleDegrees(h, k, a) else null
        }
        ActiveSide.RIGHT -> {
            val h = this[GSJointName.RIGHT_HIP]
            val k = this[GSJointName.RIGHT_KNEE]
            val a = this[GSJointName.RIGHT_ANKLE]
            if (h != null && k != null && a != null) PoseGeometry.angleDegrees(h, k, a) else null
        }
        ActiveSide.BOTH -> {
            val left = kneeAngle(ActiveSide.LEFT)
            val right = kneeAngle(ActiveSide.RIGHT)
            if (left != null && right != null) min(left, right) else null
        }
    }

    /** Hip Extension = angle(Shoulder, Hip, Knee) in degrees [0, 180]. */
    fun hipExtensionAngle(side: ActiveSide): Double? = when (side) {
        ActiveSide.LEFT -> {
            val s = this[GSJointName.LEFT_SHOULDER]
            val h = this[GSJointName.LEFT_HIP]
            val k = this[GSJointName.LEFT_KNEE]
            if (s != null && h != null && k != null) PoseGeometry.angleDegrees(s, h, k) else null
        }
        ActiveSide.RIGHT -> {
            val s = this[GSJointName.RIGHT_SHOULDER]
            val h = this[GSJointName.RIGHT_HIP]
            val k = this[GSJointName.RIGHT_KNEE]
            if (s != null && h != null && k != null) PoseGeometry.angleDegrees(s, h, k) else null
        }
        ActiveSide.BOTH -> {
            val left = hipExtensionAngle(ActiveSide.LEFT)
            val right = hipExtensionAngle(ActiveSide.RIGHT)
            if (left != null && right != null) min(left, right) else null
        }
    }

    /** Infers which arm is actively lifting based on wrist vertical height (`y` in bottom-to-top space). */
    fun dominantLiftingSide(): ActiveSide {
        val leftY = this[GSJointName.LEFT_WRIST]?.y ?: -1.0
        val rightY = this[GSJointName.RIGHT_WRIST]?.y ?: -1.0
        return if (leftY >= rightY) ActiveSide.LEFT else ActiveSide.RIGHT
    }
}

/** Pure mathematical vector geometry utilities for joint angle and velocity computation. */
object PoseGeometry {
    /** Computes the interior angle angle(A, B, C) at vertex B in degrees in [0, 180]. */
    fun angleDegrees(a: Point2D, vertex: Point2D, c: Point2D): Double? {
        val baX = a.x - vertex.x
        val baY = a.y - vertex.y
        val bcX = c.x - vertex.x
        val bcY = c.y - vertex.y

        val magBA = hypot(baX, baY)
        val magBC = hypot(bcX, bcY)
        if (magBA <= 1e-6 || magBC <= 1e-6) return null

        val dotProduct = (baX * bcX) + (baY * bcY)
        val cosTheta = max(-1.0, min(1.0, dotProduct / (magBA * magBC)))
        return acos(cosTheta) * (180.0 / Math.PI)
    }

    /** Computes vertical velocity dy/dt in normalized screen heights per second. */
    fun verticalVelocity(previousY: Double, currentY: Double, deltaTime: Double): Double {
        if (deltaTime <= 1e-5) return 0.0
        return (currentY - previousY) / deltaTime
    }
}

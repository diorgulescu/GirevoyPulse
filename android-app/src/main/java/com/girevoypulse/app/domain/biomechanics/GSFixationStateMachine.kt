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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lifecycle phases of a Girevoy Sport repetition (Snatch, Jerk, or Long Cycle). */
sealed interface LiftPhase {
    data object RackOrSwing : LiftPhase
    data object Ascent : LiftPhase
    data class OverheadHolding(val startTime: Double) : LiftPhase
    data object FixationConfirmed : LiftPhase
    data object Drop : LiftPhase

    val displayTitle: String
        get() = when (this) {
            is RackOrSwing -> "RACK / SWING"
            is Ascent -> "ASCENT"
            is OverheadHolding -> "HOLDING..."
            is FixationConfirmed -> "FIXATION"
            is Drop -> "DROP"
        }

    val isFixationConfirmed: Boolean
        get() = this is FixationConfirmed
}

/** Configurable biomechanical thresholds for Girevoy Sport top fixation validation. */
data class FixationThresholds(
    var minimumElbowAngleDegrees: Double = 168.0,
    var minimumKneeAngleDegrees: Double = 172.0,
    var minimumHipAngleDegrees: Double = 170.0,
    var velocityEpsilon: Double = 0.20,
    var minimumFixationHoldDuration: Double = 0.300
)

/** Real-time biomechanical telemetry for the current video frame, used to color the HUD skeletal overlay. */
data class FixationBiomechanicsSnapshot(
    val activeSide: ActiveSide,
    val elbowAngleDegrees: Double,
    val leftKneeAngleDegrees: Double,
    val rightKneeAngleDegrees: Double,
    val hipAngleDegrees: Double,
    val wristVerticalVelocity: Double,
    val isElbowLocked: Boolean,
    val areKneesLocked: Boolean,
    val isHipExtended: Boolean,
    val isTrajectoryStable: Boolean,
    val isOverhead: Boolean,
    val currentHoldDurationMs: Int
) {
    val isAllJointsLockedAndStable: Boolean
        get() = isOverhead && isElbowLocked && areKneesLocked && isHipExtended && isTrajectoryStable

    companion object {
        val ZERO = FixationBiomechanicsSnapshot(
            activeSide = ActiveSide.RIGHT,
            elbowAngleDegrees = 0.0,
            leftKneeAngleDegrees = 0.0,
            rightKneeAngleDegrees = 0.0,
            hipAngleDegrees = 0.0,
            wristVerticalVelocity = 0.0,
            isElbowLocked = false,
            areKneesLocked = false,
            isHipExtended = false,
            isTrajectoryStable = false,
            isOverhead = false,
            currentHoldDurationMs = 0
        )
    }
}

/** Output emitted by `GSFixationStateMachine.step(frame:)` for a single pose frame. */
data class FixationStepResult(
    val phase: LiftPhase,
    val repCount: Int,
    val didConfirmNewRep: Boolean,
    val biomechanics: FixationBiomechanicsSnapshot
)

/**
 * Pure, platform-agnostic Girevoy Sport fixation & rep-counting state machine.
 * Fed normalized `BodyPoseFrame` landmarks from Google MediaPipe Pose / CameraX.
 */
class GSFixationStateMachine(
    var thresholds: FixationThresholds = FixationThresholds(),
    var preferredSide: ActiveSide? = null
) {
    var phase: LiftPhase = LiftPhase.RackOrSwing
        private set
    var repCount: Int = 0
        private set
    var biomechanics: FixationBiomechanicsSnapshot = FixationBiomechanicsSnapshot.ZERO
        private set

    private var previousWristY: Double? = null
    private var previousTimestamp: Double? = null

    fun reset() {
        phase = LiftPhase.RackOrSwing
        repCount = 0
        biomechanics = FixationBiomechanicsSnapshot.ZERO
        previousWristY = null
        previousTimestamp = null
    }

    fun step(frame: BodyPoseFrame): FixationStepResult {
        val side = preferredSide ?: frame.dominantLiftingSide()

        val wrist = activeWristPoint(frame, side)
        val shoulder = activeShoulderPoint(frame, side)
        val elbowAngle = frame.elbowAngle(side)
        val leftKneeAngle = frame.kneeAngle(ActiveSide.LEFT)
        val rightKneeAngle = frame.kneeAngle(ActiveSide.RIGHT)
        val hipAngle = frame.hipExtensionAngle(side)

        if (wrist == null || shoulder == null || elbowAngle == null ||
            leftKneeAngle == null || rightKneeAngle == null || hipAngle == null
        ) {
            return FixationStepResult(
                phase = phase,
                repCount = repCount,
                didConfirmNewRep = false,
                biomechanics = biomechanics
            )
        }

        val prevY = previousWristY
        val prevT = previousTimestamp
        val dyDt: Double
        val hasVelocitySample: Boolean
        if (prevY != null && prevT != null && frame.timestamp > prevT) {
            dyDt = PoseGeometry.verticalVelocity(
                previousY = prevY,
                currentY = wrist.y,
                deltaTime = frame.timestamp - prevT
            )
            hasVelocitySample = true
        } else {
            dyDt = 0.0
            hasVelocitySample = false
        }
        previousWristY = wrist.y
        previousTimestamp = frame.timestamp

        val isOverhead = wrist.y > shoulder.y
        val isElbowLocked = elbowAngle >= thresholds.minimumElbowAngleDegrees
        val areKneesLocked = leftKneeAngle >= thresholds.minimumKneeAngleDegrees &&
            rightKneeAngle >= thresholds.minimumKneeAngleDegrees
        val isHipExtended = hipAngle >= thresholds.minimumHipAngleDegrees
        val isTrajectoryStable = hasVelocitySample && abs(dyDt) < thresholds.velocityEpsilon
        val allCriteriaMet = isOverhead && isElbowLocked && areKneesLocked && isHipExtended && isTrajectoryStable

        var holdMs = 0
        var didConfirmNewRep = false

        when (val currentPhase = phase) {
            is LiftPhase.RackOrSwing -> {
                if (isOverhead) {
                    phase = if (allCriteriaMet) {
                        LiftPhase.OverheadHolding(startTime = frame.timestamp)
                    } else {
                        LiftPhase.Ascent
                    }
                } else if (dyDt > thresholds.velocityEpsilon) {
                    phase = LiftPhase.Ascent
                }
            }

            is LiftPhase.Ascent -> {
                if (!isOverhead && dyDt < -thresholds.velocityEpsilon) {
                    phase = LiftPhase.RackOrSwing
                } else if (allCriteriaMet) {
                    phase = LiftPhase.OverheadHolding(startTime = frame.timestamp)
                }
            }

            is LiftPhase.OverheadHolding -> {
                if (!isOverhead) {
                    phase = LiftPhase.Drop
                } else if (allCriteriaMet) {
                    val heldDuration = max(0.0, frame.timestamp - currentPhase.startTime)
                    holdMs = (heldDuration * 1000.0).roundToInt()
                    if (heldDuration >= thresholds.minimumFixationHoldDuration) {
                        phase = LiftPhase.FixationConfirmed
                        repCount += 1
                        didConfirmNewRep = true
                    }
                } else {
                    phase = LiftPhase.Ascent
                }
            }

            is LiftPhase.FixationConfirmed -> {
                holdMs = (thresholds.minimumFixationHoldDuration * 1000.0).roundToInt()
                if (wrist.y < shoulder.y) {
                    phase = LiftPhase.Drop
                }
            }

            is LiftPhase.Drop -> {
                if (wrist.y < shoulder.y && abs(dyDt) < thresholds.velocityEpsilon) {
                    phase = LiftPhase.RackOrSwing
                } else if (isOverhead) {
                    phase = if (allCriteriaMet) {
                        LiftPhase.OverheadHolding(startTime = frame.timestamp)
                    } else {
                        LiftPhase.Ascent
                    }
                }
            }
        }

        biomechanics = FixationBiomechanicsSnapshot(
            activeSide = side,
            elbowAngleDegrees = elbowAngle,
            leftKneeAngleDegrees = leftKneeAngle,
            rightKneeAngleDegrees = rightKneeAngle,
            hipAngleDegrees = hipAngle,
            wristVerticalVelocity = dyDt,
            isElbowLocked = isElbowLocked,
            areKneesLocked = areKneesLocked,
            isHipExtended = isHipExtended,
            isTrajectoryStable = isTrajectoryStable,
            isOverhead = isOverhead,
            currentHoldDurationMs = holdMs
        )

        return FixationStepResult(
            phase = phase,
            repCount = repCount,
            didConfirmNewRep = didConfirmNewRep,
            biomechanics = biomechanics
        )
    }

    private fun activeWristPoint(frame: BodyPoseFrame, side: ActiveSide): Point2D? = when (side) {
        ActiveSide.LEFT -> frame[GSJointName.LEFT_WRIST]
        ActiveSide.RIGHT -> frame[GSJointName.RIGHT_WRIST]
        ActiveSide.BOTH -> {
            val l = frame[GSJointName.LEFT_WRIST]
            val r = frame[GSJointName.RIGHT_WRIST]
            if (l != null && r != null) Point2D((l.x + r.x) * 0.5, min(l.y, r.y)) else null
        }
    }

    private fun activeShoulderPoint(frame: BodyPoseFrame, side: ActiveSide): Point2D? = when (side) {
        ActiveSide.LEFT -> frame[GSJointName.LEFT_SHOULDER]
        ActiveSide.RIGHT -> frame[GSJointName.RIGHT_SHOULDER]
        ActiveSide.BOTH -> {
            val l = frame[GSJointName.LEFT_SHOULDER]
            val r = frame[GSJointName.RIGHT_SHOULDER]
            if (l != null && r != null) Point2D((l.x + r.x) * 0.5, max(l.y, r.y)) else null
        }
    }
}

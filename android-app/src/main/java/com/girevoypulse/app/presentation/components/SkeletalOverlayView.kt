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

package com.girevoypulse.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.girevoypulse.app.domain.biomechanics.BodyPoseFrame
import com.girevoypulse.app.domain.biomechanics.FixationBiomechanicsSnapshot
import com.girevoypulse.app.domain.biomechanics.GSJointName
import com.girevoypulse.app.domain.biomechanics.LiftPhase
import com.girevoypulse.app.domain.biomechanics.Point2D
import com.girevoypulse.app.domain.models.ActiveSide
import kotlin.math.max

private val TORSO_BONES = listOf(
    GSJointName.NECK to GSJointName.LEFT_SHOULDER,
    GSJointName.NECK to GSJointName.RIGHT_SHOULDER,
    GSJointName.LEFT_SHOULDER to GSJointName.RIGHT_SHOULDER,
    GSJointName.LEFT_SHOULDER to GSJointName.LEFT_HIP,
    GSJointName.RIGHT_SHOULDER to GSJointName.RIGHT_HIP,
    GSJointName.LEFT_HIP to GSJointName.RIGHT_HIP
)

private val ARM_BONES = listOf(
    Triple(GSJointName.LEFT_SHOULDER, GSJointName.LEFT_ELBOW, ActiveSide.LEFT),
    Triple(GSJointName.LEFT_ELBOW, GSJointName.LEFT_WRIST, ActiveSide.LEFT),
    Triple(GSJointName.RIGHT_SHOULDER, GSJointName.RIGHT_ELBOW, ActiveSide.RIGHT),
    Triple(GSJointName.RIGHT_ELBOW, GSJointName.RIGHT_WRIST, ActiveSide.RIGHT)
)

private val LEG_BONES = listOf(
    GSJointName.LEFT_HIP to GSJointName.LEFT_KNEE,
    GSJointName.LEFT_KNEE to GSJointName.LEFT_ANKLE,
    GSJointName.RIGHT_HIP to GSJointName.RIGHT_KNEE,
    GSJointName.RIGHT_KNEE to GSJointName.RIGHT_ANKLE
)

/**
 * Renders the lifter's normalized 2D skeleton over the live CameraX preview.
 * Bones and joints light up vibrant green when biomechanical lockout thresholds are met,
 * yellow during overhead holding/ascent, and coral/red when unlocked.
 */
@Composable
fun SkeletalOverlayView(
    poseFrame: BodyPoseFrame?,
    biomechanics: FixationBiomechanicsSnapshot,
    liftPhase: LiftPhase,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val frame = poseFrame ?: return@Canvas

        // Shoulder reference threshold line
        val leftS = frame[GSJointName.LEFT_SHOULDER]
        val rightS = frame[GSJointName.RIGHT_SHOULDER]
        if (leftS != null && rightS != null) {
            val yScreen = ((1.0 - max(leftS.y, rightS.y)) * size.height).toFloat()
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(0f, yScreen),
                end = Offset(size.width, yScreen),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
            )
        }

        // 1. Torso / Hip extension bones
        val torsoColor = boneColor(biomechanics.isHipExtended, liftPhase)
        for ((a, b) in TORSO_BONES) {
            drawBone(a, b, frame, torsoColor, 10f)
        }

        // 2. Upper limb bones
        for ((a, b, side) in ARM_BONES) {
            val isActiveArm = (biomechanics.activeSide == side || biomechanics.activeSide == ActiveSide.BOTH)
            val color = if (isActiveArm) {
                boneColor(biomechanics.isElbowLocked && biomechanics.isOverhead, liftPhase)
            } else {
                Color.Cyan.copy(alpha = 0.45f)
            }
            drawBone(a, b, frame, color, if (isActiveArm) 13f else 7f)
        }

        // 3. Lower limb bones
        val legColor = boneColor(biomechanics.areKneesLocked, liftPhase)
        for ((a, b) in LEG_BONES) {
            drawBone(a, b, frame, legColor, 10f)
        }

        // 4. Joint nodes
        for (joint in GSJointName.entries) {
            val normalized = frame[joint] ?: continue
            val pt = screenOffset(normalized, size)
            val nodeColor = jointColor(joint, biomechanics, liftPhase)
            val radius = if (joint == GSJointName.LEFT_WRIST || joint == GSJointName.RIGHT_WRIST) 16f else 11f
            drawCircle(color = nodeColor, radius = radius, center = pt)
            drawCircle(
                color = Color.Black.copy(alpha = 0.75f),
                radius = radius,
                center = pt,
                style = Stroke(width = 4f)
            )
        }
    }
}

private fun DrawScope.drawBone(
    a: GSJointName,
    b: GSJointName,
    frame: BodyPoseFrame,
    color: Color,
    strokeWidth: Float
) {
    val p1 = frame[a] ?: return
    val p2 = frame[b] ?: return
    drawLine(
        color = color,
        start = screenOffset(p1, size),
        end = screenOffset(p2, size),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun screenOffset(normalized: Point2D, canvasSize: Size): Offset = Offset(
    x = (normalized.x * canvasSize.width).toFloat(),
    y = ((1.0 - normalized.y) * canvasSize.height).toFloat()
)

private fun boneColor(isLocked: Boolean, liftPhase: LiftPhase): Color {
    if (liftPhase.isFixationConfirmed) {
        return Color(0xFF1FF56B)
    }
    if (isLocked) {
        return Color(0xFF38E67A).copy(alpha = 0.90f)
    }
    if (liftPhase is LiftPhase.OverheadHolding) {
        return Color(0xFFFFD60A).copy(alpha = 0.90f)
    }
    return Color(0xFFFF5C47).copy(alpha = 0.85f)
}

private fun jointColor(
    joint: GSJointName,
    biomechanics: FixationBiomechanicsSnapshot,
    liftPhase: LiftPhase
): Color = when (joint) {
    GSJointName.LEFT_ELBOW, GSJointName.RIGHT_ELBOW, GSJointName.LEFT_WRIST, GSJointName.RIGHT_WRIST ->
        boneColor(biomechanics.isElbowLocked && biomechanics.isOverhead, liftPhase)
    GSJointName.LEFT_KNEE, GSJointName.RIGHT_KNEE, GSJointName.LEFT_ANKLE, GSJointName.RIGHT_ANKLE ->
        boneColor(biomechanics.areKneesLocked, liftPhase)
    GSJointName.LEFT_HIP, GSJointName.RIGHT_HIP, GSJointName.LEFT_SHOULDER, GSJointName.RIGHT_SHOULDER, GSJointName.NECK ->
        boneColor(biomechanics.isHipExtended, liftPhase)
}

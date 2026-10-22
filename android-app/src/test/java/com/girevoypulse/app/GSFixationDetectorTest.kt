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

package com.girevoypulse.app

import com.girevoypulse.app.core.vision.CameraManager
import com.girevoypulse.app.core.vision.GSFixationDetector
import com.girevoypulse.app.domain.biomechanics.BodyPoseFrame
import com.girevoypulse.app.domain.biomechanics.CoordinateOrigin
import com.girevoypulse.app.domain.biomechanics.GSJointName
import com.girevoypulse.app.domain.biomechanics.JointPoint
import com.girevoypulse.app.domain.biomechanics.LiftPhase
import com.girevoypulse.app.domain.biomechanics.Point2D
import com.girevoypulse.app.domain.biomechanics.PoseGeometry
import com.girevoypulse.app.domain.models.ActiveSide
import com.girevoypulse.app.domain.models.GSAudioCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GSFixationDetectorTest {

    private fun makePoseFrame(
        timestamp: Double,
        wristY: Double,
        elbowBendOffsetX: Double = 0.0,
        kneeBendOffsetX: Double = 0.0,
        hipBendOffsetX: Double = 0.0
    ): BodyPoseFrame {
        val shoulderY = 0.65
        val elbowY = (shoulderY + wristY) * 0.5

        val joints = mapOf(
            GSJointName.NECK to JointPoint(x = 0.50, y = 0.70),
            GSJointName.RIGHT_SHOULDER to JointPoint(x = 0.60, y = shoulderY),
            GSJointName.RIGHT_ELBOW to JointPoint(x = 0.60 + elbowBendOffsetX, y = elbowY),
            GSJointName.RIGHT_WRIST to JointPoint(x = 0.60, y = wristY),
            GSJointName.LEFT_SHOULDER to JointPoint(x = 0.40, y = shoulderY),
            GSJointName.LEFT_ELBOW to JointPoint(x = 0.40, y = 0.50),
            GSJointName.LEFT_WRIST to JointPoint(x = 0.40, y = 0.45),
            GSJointName.RIGHT_HIP to JointPoint(x = 0.60 + hipBendOffsetX, y = 0.45),
            GSJointName.LEFT_HIP to JointPoint(x = 0.40 + hipBendOffsetX, y = 0.45),
            GSJointName.RIGHT_KNEE to JointPoint(x = 0.60 + kneeBendOffsetX, y = 0.25),
            GSJointName.LEFT_KNEE to JointPoint(x = 0.40 + kneeBendOffsetX, y = 0.25),
            GSJointName.RIGHT_ANKLE to JointPoint(x = 0.60, y = 0.05),
            GSJointName.LEFT_ANKLE to JointPoint(x = 0.40, y = 0.05)
        )
        return BodyPoseFrame(timestamp = timestamp, joints = joints)
    }

    @Test
    fun vectorAngleCalculations() {
        val straight = PoseGeometry.angleDegrees(
            a = Point2D(0.5, 0.8),
            vertex = Point2D(0.5, 0.5),
            c = Point2D(0.5, 0.2)
        )
        assertNotNull(straight)
        assertEquals(180.0, straight!!, 1e-5)

        val rightAngle = PoseGeometry.angleDegrees(
            a = Point2D(0.0, 1.0),
            vertex = Point2D(0.0, 0.0),
            c = Point2D(1.0, 0.0)
        )
        assertNotNull(rightAngle)
        assertEquals(90.0, rightAngle!!, 1e-5)
    }

    @Test
    fun androidMediaPipeTopLeftOriginNormalizesToUpwardY() {
        val mediaPipePoint = JointPoint(x = 0.6, y = 0.08, confidence = 0.95f, origin = CoordinateOrigin.TOP_LEFT)
        assertEquals(0.92, mediaPipePoint.location.y, 1e-9)
    }

    @Test
    fun cameraFrameThrottlingStride() {
        val camera = CameraManager()
        camera.frameStride = 3
        val processedIndices = (1L..9L).filter { camera.shouldProcessIncomingFrame(it) }
        assertEquals(listOf(3L, 6L, 9L), processedIndices)
    }

    @Test
    fun validFixationCycleCountsRepAndFiresAudioCue() {
        val spyAudio = SpyAudioCuePlayer()
        val detector = GSFixationDetector(audioEngine = spyAudio)
        detector.preferredSide = ActiveSide.RIGHT

        // 1. Rack position
        val f0 = makePoseFrame(timestamp = 1.00, wristY = 0.55)
        assertEquals(LiftPhase.RackOrSwing, detector.process(f0))

        // 2. Ascent
        val f1 = makePoseFrame(timestamp = 1.10, wristY = 0.85)
        assertEquals(LiftPhase.Ascent, detector.process(f1))

        // 3. Stabilize overhead at t = 1.20 -> hold starts at t = 1.25
        detector.process(makePoseFrame(timestamp = 1.20, wristY = 0.92))
        val fHoldStart = makePoseFrame(timestamp = 1.25, wristY = 0.92)
        assertEquals(LiftPhase.OverheadHolding(startTime = 1.25), detector.process(fHoldStart))
        assertEquals(0, detector.repCount)

        // 4. Held for 200ms (< 300ms)
        val fHoldMid = makePoseFrame(timestamp = 1.45, wristY = 0.921)
        assertEquals(LiftPhase.OverheadHolding(startTime = 1.25), detector.process(fHoldMid))
        assertEquals(0, detector.repCount)

        // 5. Held for 320ms (>= 300ms) -> FixationConfirmed!
        val fConfirmed = makePoseFrame(timestamp = 1.57, wristY = 0.921)
        assertEquals(LiftPhase.FixationConfirmed, detector.process(fConfirmed))
        assertEquals(1, detector.repCount)
        assertEquals(listOf(GSAudioCue.FixationValidated), spyAudio.cues)

        // 6. Drop wrist below shoulder line -> Drop
        val fDrop = makePoseFrame(timestamp = 1.80, wristY = 0.52)
        assertEquals(LiftPhase.Drop, detector.process(fDrop))
    }

    @Test
    fun prematureDropBefore300msDoesNotCountRep() {
        val spyAudio = SpyAudioCuePlayer()
        val detector = GSFixationDetector(audioEngine = spyAudio)
        detector.preferredSide = ActiveSide.RIGHT

        detector.process(makePoseFrame(timestamp = 0.00, wristY = 0.92))
        assertEquals(
            LiftPhase.OverheadHolding(startTime = 0.05),
            detector.process(makePoseFrame(timestamp = 0.05, wristY = 0.92))
        )
        detector.process(makePoseFrame(timestamp = 0.25, wristY = 0.92))
        assertEquals(
            LiftPhase.Drop,
            detector.process(makePoseFrame(timestamp = 0.30, wristY = 0.50))
        )
        assertEquals(0, detector.repCount)
        assertTrue(spyAudio.cues.isEmpty())
    }

    @Test
    fun softElbowOrKneePreventsFixationConfirmation() {
        val spyAudio = SpyAudioCuePlayer()
        val detector = GSFixationDetector(audioEngine = spyAudio)
        detector.preferredSide = ActiveSide.RIGHT

        for (step in 0..10) {
            val t = step * 0.05
            detector.process(makePoseFrame(timestamp = t, wristY = 0.92, elbowBendOffsetX = 0.08))
        }

        assertEquals(0, detector.repCount)
        assertFalse(detector.biomechanics.isElbowLocked)
    }
}

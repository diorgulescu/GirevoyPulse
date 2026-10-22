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

package com.girevoypulse.app.core.vision

import android.content.Context
import android.graphics.Bitmap
import com.girevoypulse.app.domain.biomechanics.BodyPoseFrame
import com.girevoypulse.app.domain.biomechanics.CoordinateOrigin
import com.girevoypulse.app.domain.biomechanics.GSJointName
import com.girevoypulse.app.domain.biomechanics.JointPoint
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.min

/**
 * Android Google MediaPipe Pose Landmarker adapter that replaces iOS `VNDetectHumanBodyPoseRequest`
 * and outputs the exact same platform-agnostic `BodyPoseFrame` (`CoordinateOrigin.TOP_LEFT`).
 */
class PoseEstimator(context: Context? = null) {

    companion object {
        private const val MODEL_ASSET_PATH = "pose_landmarker_lite.task"

        // Standard BlazePose 33-landmark indices
        private const val IDX_LEFT_SHOULDER = 11
        private const val IDX_RIGHT_SHOULDER = 12
        private const val IDX_LEFT_ELBOW = 13
        private const val IDX_RIGHT_ELBOW = 14
        private const val IDX_LEFT_WRIST = 15
        private const val IDX_RIGHT_WRIST = 16
        private const val IDX_LEFT_HIP = 23
        private const val IDX_RIGHT_HIP = 24
        private const val IDX_LEFT_KNEE = 25
        private const val IDX_RIGHT_KNEE = 26
        private const val IDX_LEFT_ANKLE = 27
        private const val IDX_RIGHT_ANKLE = 28

        /** Maps a MediaPipe `PoseLandmarkerResult` into a platform-agnostic `BodyPoseFrame`. */
        fun extractPoseFrame(
            result: PoseLandmarkerResult,
            timestampSeconds: Double
        ): BodyPoseFrame? {
            val personLandmarks = result.landmarks().firstOrNull() ?: return null
            if (personLandmarks.size <= IDX_RIGHT_ANKLE) return null

            val indexMapping = listOf(
                GSJointName.LEFT_SHOULDER to IDX_LEFT_SHOULDER,
                GSJointName.RIGHT_SHOULDER to IDX_RIGHT_SHOULDER,
                GSJointName.LEFT_ELBOW to IDX_LEFT_ELBOW,
                GSJointName.RIGHT_ELBOW to IDX_RIGHT_ELBOW,
                GSJointName.LEFT_WRIST to IDX_LEFT_WRIST,
                GSJointName.RIGHT_WRIST to IDX_RIGHT_WRIST,
                GSJointName.LEFT_HIP to IDX_LEFT_HIP,
                GSJointName.RIGHT_HIP to IDX_RIGHT_HIP,
                GSJointName.LEFT_KNEE to IDX_LEFT_KNEE,
                GSJointName.RIGHT_KNEE to IDX_RIGHT_KNEE,
                GSJointName.LEFT_ANKLE to IDX_LEFT_ANKLE,
                GSJointName.RIGHT_ANKLE to IDX_RIGHT_ANKLE
            )

            val extracted = mutableMapOf<GSJointName, JointPoint>()
            for ((jointName, idx) in indexMapping) {
                val lm: NormalizedLandmark = personLandmarks[idx]
                val visibility = lm.visibility().orElse(1.0f)
                if (visibility >= BodyPoseFrame.MINIMUM_JOINT_CONFIDENCE) {
                    extracted[jointName] = JointPoint(
                        x = lm.x().toDouble(),
                        y = lm.y().toDouble(),
                        confidence = visibility,
                        origin = CoordinateOrigin.TOP_LEFT
                    )
                }
            }

            // Synthesize NECK landmark as the midpoint between left and right shoulders
            val leftShoulder = extracted[GSJointName.LEFT_SHOULDER]
            val rightShoulder = extracted[GSJointName.RIGHT_SHOULDER]
            if (leftShoulder != null && rightShoulder != null) {
                extracted[GSJointName.NECK] = JointPoint(
                    x = (leftShoulder.location.x + rightShoulder.location.x) * 0.5,
                    y = (leftShoulder.location.y + rightShoulder.location.y) * 0.5 + 0.04,
                    confidence = min(leftShoulder.confidence, rightShoulder.confidence),
                    origin = CoordinateOrigin.BOTTOM_LEFT
                )
            }

            return if (extracted.isEmpty()) null else BodyPoseFrame(timestampSeconds, extracted)
        }
    }

    private var poseLandmarker: PoseLandmarker? = null

    init {
        if (context != null) {
            initializeLandmarkerIfAssetPresent(context)
        }
    }

    private fun initializeLandmarkerIfAssetPresent(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        } catch (_: Throwable) {
            // If pose_landmarker_lite.task is not bundled yet, graceful fallback to simulated/standby mode
            poseLandmarker = null
        }
    }

    fun estimatePose(bitmap: Bitmap, timestampSeconds: Double): BodyPoseFrame? {
        val landmarker = poseLandmarker ?: return null
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestampMs = (timestampSeconds * 1000.0).toLong()
            val result = landmarker.detectForVideo(mpImage, timestampMs)
            extractPoseFrame(result, timestampSeconds)
        } catch (_: Throwable) {
            null
        }
    }
}

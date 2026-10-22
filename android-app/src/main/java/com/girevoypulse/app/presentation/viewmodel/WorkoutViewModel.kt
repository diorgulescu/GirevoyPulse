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

package com.girevoypulse.app.presentation.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.girevoypulse.app.core.audio.GSAudioFeedbackEngine
import com.girevoypulse.app.core.ble.PolarManager
import com.girevoypulse.app.core.timing.GSTimerEngine
import com.girevoypulse.app.core.vision.CameraManager
import com.girevoypulse.app.core.vision.GSFixationDetector
import com.girevoypulse.app.core.vision.PoseEstimator
import com.girevoypulse.app.domain.biomechanics.BodyPoseFrame
import com.girevoypulse.app.domain.biomechanics.FixationBiomechanicsSnapshot
import com.girevoypulse.app.domain.biomechanics.GSJointName
import com.girevoypulse.app.domain.biomechanics.JointPoint
import com.girevoypulse.app.domain.biomechanics.LiftPhase
import com.girevoypulse.app.domain.models.ActiveSide
import com.girevoypulse.app.domain.models.GSAudioCue
import com.girevoypulse.app.domain.models.GSDiscipline
import com.girevoypulse.app.domain.models.GSTimerCategory
import com.girevoypulse.app.domain.models.GSTimerMode
import com.girevoypulse.app.domain.models.MarathonDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Immutable UI state for `LiveWorkoutScreen` and `WorkoutSetupSheet`. */
data class WorkoutConfigUiState(
    val selectedDiscipline: GSDiscipline = GSDiscipline.SNATCH,
    val selectedTimerCategory: GSTimerCategory = GSTimerCategory.TRADITIONAL_10_MIN,
    val targetRPM: Double = 16.0,
    val marathonDuration: MarathonDuration = MarathonDuration.HALF_MARATHON_30_MIN,
    val marathonSwitchMinutes: Int = 5,
    val intervalWorkSeconds: Double = 60.0,
    val intervalRestSeconds: Double = 60.0,
    val intervalRounds: Int = 5,
    val activeSide: ActiveSide = ActiveSide.RIGHT,
    val isSetupSheetPresented: Boolean = false,
    val isDemoPoseFeedEnabled: Boolean = false,
    val latestPoseFrame: BodyPoseFrame? = null,
    val repCount: Int = 0,
    val liftPhase: LiftPhase = LiftPhase.RackOrSwing,
    val biomechanics: FixationBiomechanicsSnapshot = FixationBiomechanicsSnapshot.ZERO
)

/**
 * Primary Android ViewModel orchestrating the GS Timing Engine, MediaPipe Pose Pipeline,
 * Fixation State Machine, and Polar H10 BLE Telemetry using Unidirectional Data Flow.
 */
class WorkoutViewModel : ViewModel() {

    val timerEngine = GSTimerEngine(scope = viewModelScope)
    val cameraManager = CameraManager()
    val fixationDetector = GSFixationDetector()
    val polarManager = PolarManager(scope = viewModelScope)
    private var poseEstimator: PoseEstimator? = null

    private val _configState = MutableStateFlow(WorkoutConfigUiState())
    val configState: StateFlow<WorkoutConfigUiState> = _configState.asStateFlow()

    private var demoPoseJob: Job? = null

    init {
        fixationDetector.preferredSide = _configState.value.activeSide

        timerEngine.onHandSwitchAlert = {
            val currentSide = _configState.value.activeSide
            if (currentSide != ActiveSide.BOTH) {
                selectActiveSide(currentSide.toggledHand())
            }
        }

        fixationDetector.onRepValidated = { newTotalReps ->
            val currentHR = polarManager.bpm.takeIf { it > 0 }
            timerEngine.recordRep(currentHeartRate = currentHR)
            _configState.value = _configState.value.copy(repCount = newTotalReps)
        }

        cameraManager.onThrottledFrame = { bitmap, timestampSeconds ->
            val frame = poseEstimator?.estimatePose(bitmap, timestampSeconds)
            if (frame != null) {
                viewModelScope.launch {
                    ingestPoseFrame(frame)
                }
            }
        }

        viewModelScope.launch {
            polarManager.uiState.collect { polarState ->
                if (polarState.bpm > 0) {
                    timerEngine.recordHeartRateSample(polarState.bpm)
                }
            }
        }
    }

    fun initializePlatformServices(appContext: Context) {
        if (poseEstimator == null) {
            poseEstimator = PoseEstimator(appContext)
        }
        polarManager.initializeSdk(appContext)
    }

    fun ingestPoseFrame(frame: BodyPoseFrame) {
        val newPhase = fixationDetector.process(frame)
        _configState.value = _configState.value.copy(
            latestPoseFrame = frame,
            liftPhase = newPhase,
            biomechanics = fixationDetector.biomechanics,
            repCount = fixationDetector.repCount
        )
    }

    // MARK: - User Actions & Setup Mutations

    fun selectDiscipline(discipline: GSDiscipline) {
        val defaultSide = if (discipline == GSDiscipline.SNATCH) ActiveSide.RIGHT else ActiveSide.BOTH
        fixationDetector.preferredSide = defaultSide
        _configState.value = _configState.value.copy(
            selectedDiscipline = discipline,
            targetRPM = discipline.defaultTargetRPM,
            activeSide = defaultSide
        )
        applyTimerConfiguration()
    }

    fun selectActiveSide(side: ActiveSide) {
        fixationDetector.preferredSide = side
        _configState.value = _configState.value.copy(activeSide = side)
    }

    fun toggleActiveHand() {
        selectActiveSide(_configState.value.activeSide.toggledHand())
    }

    fun selectTimerCategory(category: GSTimerCategory) {
        _configState.value = _configState.value.copy(selectedTimerCategory = category)
        applyTimerConfiguration()
    }

    fun updateTargetRPM(rpm: Double) {
        _configState.value = _configState.value.copy(targetRPM = rpm)
        applyTimerConfiguration()
    }

    fun selectMarathonDuration(duration: MarathonDuration) {
        _configState.value = _configState.value.copy(marathonDuration = duration)
        applyTimerConfiguration()
    }

    fun updateMarathonSwitchMinutes(minutes: Int) {
        _configState.value = _configState.value.copy(marathonSwitchMinutes = minutes.coerceIn(1, 15))
        applyTimerConfiguration()
    }

    fun updateIntervalWorkSeconds(seconds: Double) {
        _configState.value = _configState.value.copy(intervalWorkSeconds = seconds.coerceIn(15.0, 600.0))
        applyTimerConfiguration()
    }

    fun updateIntervalRestSeconds(seconds: Double) {
        _configState.value = _configState.value.copy(intervalRestSeconds = seconds.coerceIn(15.0, 300.0))
        applyTimerConfiguration()
    }

    fun updateIntervalRounds(rounds: Int) {
        _configState.value = _configState.value.copy(intervalRounds = rounds.coerceIn(1, 30))
        applyTimerConfiguration()
    }

    fun setSetupSheetPresented(presented: Boolean) {
        _configState.value = _configState.value.copy(isSetupSheetPresented = presented)
    }

    fun setDemoPoseFeedEnabled(enabled: Boolean) {
        _configState.value = _configState.value.copy(isDemoPoseFeedEnabled = enabled)
        updateDemoPoseLoop(enabled)
    }

    fun toggleWorkoutStartPause() {
        if (timerEngine.isRunning) {
            timerEngine.pause()
        } else {
            if (timerEngine.isCompleted) {
                resetWorkout()
            }
            timerEngine.start()
        }
    }

    fun resetWorkout() {
        timerEngine.stop()
        fixationDetector.reset()
        _configState.value = _configState.value.copy(
            repCount = 0,
            liftPhase = LiftPhase.RackOrSwing,
            biomechanics = FixationBiomechanicsSnapshot.ZERO
        )
    }

    fun manualIncrementRep() {
        val currentHR = polarManager.bpm.takeIf { it > 0 }
        val updatedReps = _configState.value.repCount + 1
        _configState.value = _configState.value.copy(repCount = updatedReps)
        timerEngine.recordRep(currentHeartRate = currentHR)
        GSAudioFeedbackEngine.shared.play(GSAudioCue.FixationValidated)
    }

    fun applyTimerConfiguration() {
        if (timerEngine.isRunning) return
        val s = _configState.value
        val newMode = when (s.selectedTimerCategory) {
            GSTimerCategory.TRADITIONAL_10_MIN -> GSTimerMode.Traditional10Min(targetRPM = s.targetRPM)
            GSTimerCategory.MARATHON -> GSTimerMode.Marathon(
                duration = s.marathonDuration,
                switchIntervalMinutes = s.marathonSwitchMinutes,
                targetRPM = s.targetRPM
            )
            GSTimerCategory.INTERVALS -> GSTimerMode.Intervals(
                workSeconds = s.intervalWorkSeconds,
                restSeconds = s.intervalRestSeconds,
                rounds = s.intervalRounds,
                targetRPM = s.targetRPM
            )
        }
        timerEngine.configure(newMode)
    }

    // MARK: - 25 Hz Simulated Lifter Pose Feed

    private fun updateDemoPoseLoop(enabled: Boolean) {
        demoPoseJob?.cancel()
        demoPoseJob = null
        if (!enabled) return

        demoPoseJob = viewModelScope.launch {
            while (isActive && _configState.value.isDemoPoseFeedEnabled) {
                delay(40L) // 25 Hz
                val s = _configState.value
                val now = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
                val cyclePeriod = max(2.5, 60.0 / max(6.0, s.targetRPM))
                val phaseProgress = (now % cyclePeriod) / cyclePeriod

                val wristY: Double
                val elbowBend: Double
                when {
                    phaseProgress < 0.25 -> {
                        wristY = 0.52
                        elbowBend = 0.06
                    }
                    phaseProgress < 0.42 -> {
                        val t = (phaseProgress - 0.25) / 0.17
                        wristY = 0.52 + (0.40 * t)
                        elbowBend = 0.06 * (1.0 - t)
                    }
                    phaseProgress < 0.78 -> {
                        wristY = 0.92
                        elbowBend = 0.0
                    }
                    else -> {
                        val t = (phaseProgress - 0.78) / 0.22
                        wristY = 0.92 - (0.40 * t)
                        elbowBend = 0.05 * t
                    }
                }

                val frame = makeDemoPoseFrame(
                    timestamp = now,
                    wristY = wristY,
                    elbowBend = elbowBend,
                    activeSide = s.activeSide
                )
                ingestPoseFrame(frame)
            }
        }
    }

    private fun makeDemoPoseFrame(
        timestamp: Double,
        wristY: Double,
        elbowBend: Double,
        activeSide: ActiveSide
    ): BodyPoseFrame {
        val shoulderY = 0.66
        val rightWristY = if (activeSide == ActiveSide.RIGHT || activeSide == ActiveSide.BOTH) wristY else 0.46
        val leftWristY = if (activeSide == ActiveSide.LEFT || activeSide == ActiveSide.BOTH) wristY else 0.46

        val joints = mapOf(
            GSJointName.NECK to JointPoint(x = 0.50, y = 0.72),
            GSJointName.RIGHT_SHOULDER to JointPoint(x = 0.60, y = shoulderY),
            GSJointName.RIGHT_ELBOW to JointPoint(x = 0.60 + elbowBend, y = (shoulderY + rightWristY) * 0.5),
            GSJointName.RIGHT_WRIST to JointPoint(x = 0.60, y = rightWristY),
            GSJointName.LEFT_SHOULDER to JointPoint(x = 0.40, y = shoulderY),
            GSJointName.LEFT_ELBOW to JointPoint(x = 0.40 - elbowBend, y = (shoulderY + leftWristY) * 0.5),
            GSJointName.LEFT_WRIST to JointPoint(x = 0.40, y = leftWristY),
            GSJointName.RIGHT_HIP to JointPoint(x = 0.58, y = 0.46),
            GSJointName.LEFT_HIP to JointPoint(x = 0.42, y = 0.46),
            GSJointName.RIGHT_KNEE to JointPoint(x = 0.58, y = 0.26),
            GSJointName.LEFT_KNEE to JointPoint(x = 0.42, y = 0.26),
            GSJointName.RIGHT_ANKLE to JointPoint(x = 0.58, y = 0.08),
            GSJointName.LEFT_ANKLE to JointPoint(x = 0.42, y = 0.08)
        )
        return BodyPoseFrame(timestamp = timestamp, joints = joints)
    }
}

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

package com.girevoypulse.app.domain.models

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** Girevoy Sport competitive disciplines. */
enum class GSDiscipline(
    val displayName: String,
    val shortName: String,
    val description: String,
    val defaultTargetRPM: Double
) {
    SNATCH(
        displayName = "Snatch",
        shortName = "SNATCH",
        description = "Single-bell continuous swing overhead (1 hand switch in 10m rank set)",
        defaultTargetRPM = 16.0
    ),
    JERK(
        displayName = "Jerk",
        shortName = "JERK",
        description = "Short Cycle — rack launch to overhead lockout",
        defaultTargetRPM = 12.0
    ),
    LONG_CYCLE(
        displayName = "Long Cycle",
        shortName = "TALC",
        description = "Clean & Jerk — swing, rack, and overhead fixation every rep",
        defaultTargetRPM = 8.0
    )
}

/** Active lifting side for single-bell or double-bell analysis. */
enum class ActiveSide(val label: String) {
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    BOTH("BOTH");

    fun toggledHand(): ActiveSide = when (this) {
        LEFT -> RIGHT
        RIGHT -> LEFT
        BOTH -> BOTH
    }
}

/** IKMF Marathon duration presets. */
enum class MarathonDuration(val totalSeconds: Double, val title: String) {
    HALF_MARATHON_30_MIN(1800.0, "Half-Marathon (30:00)"),
    FULL_MARATHON_60_MIN(3600.0, "Full Marathon (60:00)")
}

/** High-level timer mode category for the Setup Sheet. */
enum class GSTimerCategory(val title: String) {
    TRADITIONAL_10_MIN("10-Min Traditional"),
    MARATHON("IKMF Marathon"),
    INTERVALS("Intervals / Sprint")
}

/** Detailed configuration for `GSTimerCoreEngine`. */
sealed interface GSTimerMode {
    val targetRPM: Double

    data class Traditional10Min(
        override val targetRPM: Double
    ) : GSTimerMode

    data class Marathon(
        val duration: MarathonDuration,
        val switchIntervalMinutes: Int,
        override val targetRPM: Double
    ) : GSTimerMode

    data class Intervals(
        val workSeconds: Double,
        val restSeconds: Double,
        val rounds: Int,
        override val targetRPM: Double
    ) : GSTimerMode

    val totalPlannedDuration: Double
        get() = when (this) {
            is Traditional10Min -> 600.0
            is Marathon -> duration.totalSeconds
            is Intervals -> {
                if (rounds <= 0) 0.0
                else (workSeconds * rounds) + (restSeconds * max(0, rounds - 1))
            }
        }

    val metronomePeriodSeconds: Double?
        get() = if (targetRPM > 0.0) (60.0 / targetRPM) else null
}

/** Represents a 1-minute split record logged during a set. */
data class MinuteSplit(
    val minuteIndex: Int,
    val repsInMinute: Int,
    val cumulativeReps: Int,
    val averageHeartRate: Int? = null
)

/** Current phase of the timer (Work vs Rest in interval mode). */
sealed interface TimerIntervalPhase {
    data class Work(val round: Int, val totalRounds: Int) : TimerIntervalPhase
    data class Rest(val round: Int, val totalRounds: Int) : TimerIntervalPhase
    data object Completed : TimerIntervalPhase

    val isWorkPhase: Boolean
        get() = this is Work

    val label: String
        get() = when (this) {
            is Work -> if (totalRounds > 1) "WORK R$round/$totalRounds" else "WORK SET"
            is Rest -> "REST R$round/$totalRounds"
            is Completed -> "COMPLETE"
        }
}

/** Distinct audio/haptic cues emitted by `GSTimerCoreEngine` and `GSFixationStateMachine`. */
sealed interface GSAudioCue {
    data object MetronomeTick : GSAudioCue
    data object FixationValidated : GSAudioCue
    data object HandSwitchAlert : GSAudioCue
    data class FinalTenSecondTick(val secondsRemaining: Int) : GSAudioCue
    data class IntervalPhaseTransition(val isWork: Boolean) : GSAudioCue
    data object SetCompleted : GSAudioCue
}

/** Heart Rate Zone classification for Girevoy Sport endurance pacing. */
enum class HeartRateZone(val zoneNumber: Int, val title: String) {
    ZONE_1_RECOVERY(1, "Z1 RECOVERY"),
    ZONE_2_AEROBIC(2, "Z2 AEROBIC"),
    ZONE_3_TEMPO(3, "Z3 TEMPO"),
    ZONE_4_THRESHOLD(4, "Z4 THRESHOLD"),
    ZONE_5_REDLINE(5, "Z5 REDLINE");

    companion object {
        fun zoneFor(bpm: Int, maxHR: Int = 190): HeartRateZone {
            if (maxHR <= 0 || bpm <= 0) return ZONE_1_RECOVERY
            val ratio = bpm.toDouble() / maxHR.toDouble()
            return when {
                ratio < 0.60 -> ZONE_1_RECOVERY
                ratio < 0.70 -> ZONE_2_AEROBIC
                ratio < 0.80 -> ZONE_3_TEMPO
                ratio < 0.90 -> ZONE_4_THRESHOLD
                else -> ZONE_5_REDLINE
            }
        }
    }
}

/** 3-axis sternum accelerometer sample from the Polar H10 (in millig-force mG). */
data class SternumAccSample(
    val xMillig: Int,
    val yMillig: Int,
    val zMillig: Int
) {
    /** Approximate sagittal thoracic inclination / torso dip angle in degrees. */
    val thoracicDipAngleDegrees: Double
        get() {
            val yG = yMillig / 1000.0
            val zG = zMillig / 1000.0
            if (abs(yG) <= 1e-4 && abs(zG) <= 1e-4) return 0.0
            return abs(atan2(zG, max(0.001, abs(yG))) * (180.0 / Math.PI))
        }
}

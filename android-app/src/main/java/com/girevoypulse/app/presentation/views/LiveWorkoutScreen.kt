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

package com.girevoypulse.app.presentation.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.girevoypulse.app.core.timing.GSTimerEngine
import com.girevoypulse.app.domain.biomechanics.LiftPhase
import com.girevoypulse.app.domain.models.HeartRateZone
import com.girevoypulse.app.presentation.components.CameraPreviewView
import com.girevoypulse.app.presentation.components.SkeletalOverlayView
import com.girevoypulse.app.presentation.viewmodel.WorkoutViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-screen Android Girevoy Sport Training HUD engineered for high-contrast legibility
 * at a 3-meter platform distance.
 */
@Composable
fun LiveWorkoutScreen(
    viewModel: WorkoutViewModel
) {
    val config by viewModel.configState.collectAsState()
    val timerState by viewModel.timerEngine.uiState.collectAsState()
    val polarState by viewModel.polarManager.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Live Selfie CameraX Preview Layer
        CameraPreviewView(
            cameraManager = viewModel.cameraManager,
            modifier = Modifier.fillMaxSize()
        )

        // High-contrast dark vignette for 3-meter typography legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.74f),
                            Color.Black.copy(alpha = 0.28f),
                            Color.Black.copy(alpha = 0.80f)
                        )
                    )
                )
        )

        // 2. Low-Opacity Biomechanical Skeletal Overlay
        SkeletalOverlayView(
            poseFrame = config.latestPoseFrame,
            biomechanics = config.biomechanics,
            liftPhase = config.liftPhase,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Distance-Optimized HUD Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .clickable { viewModel.toggleActiveHand() }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = config.selectedDiscipline.shortName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.Black,
                        modifier = Modifier
                            .background(Color(0xFFFFD60A), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "HAND: ${config.activeSide.label}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .clickable { viewModel.setSetupSheetPresented(true) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                if (polarState.connectionState.isConnectedOrSimulated) Color(0xFF2EE66B)
                                else Color(0xFFFF9F0A),
                                CircleShape
                            )
                    )
                    Text(
                        text = polarState.connectionState.badgeText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // "FIXATION" Lockout Indicator Banner
            val isConfirmed = config.liftPhase.isFixationConfirmed
            val isHolding = config.liftPhase is LiftPhase.OverheadHolding
            val bannerBg = when {
                isConfirmed -> Color(0xFF19FA6B)
                isHolding -> Color(0xFFFF9F0A).copy(alpha = 0.88f)
                else -> Color.White.copy(alpha = 0.14f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bannerBg, RoundedCornerShape(16.dp))
                    .border(
                        width = if (isConfirmed) 3.dp else 1.dp,
                        color = if (isConfirmed) Color.White else Color.White.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isConfirmed) "FIXATION" else config.liftPhase.displayTitle,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isConfirmed) Color.Black else Color.White
                )
                if (isHolding || isConfirmed) {
                    Text(
                        text = "  ${config.biomechanics.currentHoldDurationMs} ms",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isConfirmed) Color.Black.copy(alpha = 0.85f) else Color.Yellow
                    )
                }
            }

            // Huge 3-Meter Glanceable Rep Counter
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.manualIncrementRep() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VALID REPS",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White.copy(alpha = 0.75f)
                )
                Text(
                    text = "${config.repCount}",
                    fontSize = 116.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isConfirmed) Color(0xFF26FF73) else Color.White,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Biomechanical Lockout Badges
            val bio = config.biomechanics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AngleBadge(
                    label = "ELBOW",
                    value = String.format(Locale.US, "%.0f°", bio.elbowAngleDegrees),
                    target = "≥168°",
                    locked = bio.isElbowLocked,
                    modifier = Modifier.weight(1f)
                )
                AngleBadge(
                    label = "KNEES",
                    value = String.format(Locale.US, "%.0f°", min(bio.leftKneeAngleDegrees, bio.rightKneeAngleDegrees)),
                    target = "≥172°",
                    locked = bio.areKneesLocked,
                    modifier = Modifier.weight(1f)
                )
                AngleBadge(
                    label = "HIP EXT",
                    value = String.format(Locale.US, "%.0f°", bio.hipAngleDegrees),
                    target = "≥170°",
                    locked = bio.isHipExtended,
                    modifier = Modifier.weight(1f)
                )
                AngleBadge(
                    label = "STABLE",
                    value = String.format(Locale.US, "%.2f", abs(bio.wristVerticalVelocity)),
                    target = "≥300ms",
                    locked = bio.isTrajectoryStable && bio.isOverhead,
                    modifier = Modifier.weight(1f)
                )
            }

            // Monotonic Elapsed Time & Split Pace (Current RPM vs Target RPM)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = timerState.intervalPhase.label,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF64D2FF)
                        )
                        timerState.secondsUntilNextSwitch?.let { switchSec ->
                            Text(
                                text = "• SWITCH IN ${GSTimerEngine.formatTime(switchSec)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD60A)
                            )
                        }
                    }
                    Text(
                        text = GSTimerEngine.formatTime(timerState.elapsedTime, includeTenths = true),
                        fontSize = 40.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = "REMAINING ${GSTimerEngine.formatTime(timerState.phaseRemainingTime)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "PACE (RPM)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        val delta = timerState.currentRPM - config.targetRPM
                        val paceColor = when {
                            timerState.currentRPM <= 0.0 -> Color.White
                            abs(delta) <= 1.5 -> Color(0xFF2EE66B)
                            delta > 1.5 -> Color(0xFFFFD60A)
                            else -> Color(0xFFFF9F0A)
                        }
                        Text(
                            text = String.format(Locale.US, "%.1f", timerState.currentRPM),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = paceColor
                        )
                        Text(
                            text = " / ${config.targetRPM.toInt()}",
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = "THIS MIN: ${timerState.currentMinuteReps} REPS",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2EE66B)
                    )
                }
            }

            // Live Heart Rate (BPM) + HR Zone Color Bar
            val currentZone = HeartRateZone.zoneFor(polarState.bpm)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Heart Rate",
                            tint = Color.Red,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = if (polarState.bpm > 0) "${polarState.bpm}" else "--",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Column {
                            Text(
                                text = "BPM",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = Color.Red
                            )
                            Text(
                                text = currentZone.title,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = zoneColor(currentZone)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        val lastRR = polarState.rrIntervals.lastOrNull()
                        Text(
                            text = if (lastRR != null) {
                                String.format(
                                    Locale.US,
                                    "RR %.0f ms • HRV %.1f ms",
                                    lastRR,
                                    polarState.rmssdHRVMilliseconds
                                )
                            } else {
                                "RR / HRV STANDBY"
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (lastRR != null) Color(0xFF64D2FF) else Color.White.copy(alpha = 0.5f)
                        )
                        if (polarState.latestAccSample != null) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "TORSO DIP %.1f°",
                                    polarState.thoracicDipAngleDegrees
                                ),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD60A)
                            )
                        }
                    }
                }

                // 5-Segment HR Zone Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HeartRateZone.entries.forEach { zone ->
                        val isActive = polarState.bpm > 0 && zone == currentZone
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(if (isActive) 10.dp else 6.dp)
                                .background(
                                    zoneColor(zone).copy(alpha = if (isActive) 1f else 0.28f),
                                    RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
            }

            // 1-Minute Split Log Strip
            if (timerState.splits.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timerState.splits.forEach { split ->
                        Row(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "M${split.minuteIndex}:",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${split.repsInMinute}",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF2EE66B)
                            )
                        }
                    }
                }
            }

            // Bottom Controls Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.toggleWorkoutStartPause() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timerState.isRunning) Color(0xFFFFD60A) else Color(0xFF2EE66B),
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = if (timerState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Text(
                        text = if (timerState.isRunning) "  PAUSE SET" else "  START SET",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                IconButton(
                    onClick = { viewModel.resetWorkout() },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.White)
                }

                IconButton(
                    onClick = { viewModel.setSetupSheetPresented(true) },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Setup", tint = Color.White)
                }
            }
        }

        if (config.isSetupSheetPresented) {
            WorkoutSetupSheet(
                viewModel = viewModel,
                onDismiss = { viewModel.setSetupSheetPresented(false) }
            )
        }
    }
}

@Composable
private fun AngleBadge(
    label: String,
    value: String,
    target: String,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .border(
                width = 1.5.dp,
                color = if (locked) Color(0xFF2EE66B).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            color = if (locked) Color(0xFF2EE66B) else Color(0xFFFFD60A)
        )
        Text(
            text = target,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

private fun zoneColor(zone: HeartRateZone): Color = when (zone) {
    HeartRateZone.ZONE_1_RECOVERY -> Color(0xFF0A84FF)
    HeartRateZone.ZONE_2_AEROBIC -> Color(0xFF2EE66B)
    HeartRateZone.ZONE_3_TEMPO -> Color(0xFFFFD60A)
    HeartRateZone.ZONE_4_THRESHOLD -> Color(0xFFFF9F0A)
    HeartRateZone.ZONE_5_REDLINE -> Color(0xFFFF3B30)
}

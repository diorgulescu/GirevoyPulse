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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.girevoypulse.app.core.ble.PolarConnectionState
import com.girevoypulse.app.domain.models.ActiveSide
import com.girevoypulse.app.domain.models.GSDiscipline
import com.girevoypulse.app.domain.models.GSTimerCategory
import com.girevoypulse.app.domain.models.MarathonDuration
import com.girevoypulse.app.presentation.viewmodel.WorkoutViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Jetpack Compose Configuration Bottom Sheet for selecting GS discipline, timer mode,
 * target cadence (RPM), Polar H10 BLE chest strap controls, and GNU GPLv3 legal notices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSetupSheet(
    viewModel: WorkoutViewModel,
    onDismiss: () -> Unit
) {
    val config by viewModel.configState.collectAsState()
    val polarState by viewModel.polarManager.uiState.collectAsState()
    val context = LocalContext.current
    var customPolarDeviceId by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141418),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GirevoyPulse Setup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Button(onClick = {
                    viewModel.applyTimerConfiguration()
                    onDismiss()
                }) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }

            // 1. Discipline & Bell Side
            SectionTitle("1. Discipline & Bell Side")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GSDiscipline.entries.forEach { discipline ->
                    FilterChip(
                        selected = config.selectedDiscipline == discipline,
                        onClick = { viewModel.selectDiscipline(discipline) },
                        label = { Text(discipline.displayName) }
                    )
                }
            }
            Text(
                text = config.selectedDiscipline.description,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActiveSide.entries.forEach { side ->
                    FilterChip(
                        selected = config.activeSide == side,
                        onClick = { viewModel.selectActiveSide(side) },
                        label = { Text(side.label) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // 2. Timing Mode
            SectionTitle("2. Timing Mode (Monotonic Clock)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GSTimerCategory.entries.forEach { category ->
                    FilterChip(
                        selected = config.selectedTimerCategory == category,
                        onClick = { viewModel.selectTimerCategory(category) },
                        label = { Text(category.title, fontSize = 11.sp) }
                    )
                }
            }

            when (config.selectedTimerCategory) {
                GSTimerCategory.TRADITIONAL_10_MIN -> {
                    Text(
                        text = "Set Duration: 10:00 (Rank Set) • 1:00 Split Logging",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                GSTimerCategory.MARATHON -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarathonDuration.entries.forEach { duration ->
                            FilterChip(
                                selected = config.marathonDuration == duration,
                                onClick = { viewModel.selectMarathonDuration(duration) },
                                label = { Text(duration.title) }
                            )
                        }
                    }
                    StepperRow(
                        label = "Hand Switch Alert: Every ${config.marathonSwitchMinutes} min",
                        onDecrement = { viewModel.updateMarathonSwitchMinutes(config.marathonSwitchMinutes - 1) },
                        onIncrement = { viewModel.updateMarathonSwitchMinutes(config.marathonSwitchMinutes + 1) }
                    )
                }
                GSTimerCategory.INTERVALS -> {
                    StepperRow(
                        label = "Work Phase: ${config.intervalWorkSeconds.toInt()}s",
                        onDecrement = { viewModel.updateIntervalWorkSeconds(config.intervalWorkSeconds - 15.0) },
                        onIncrement = { viewModel.updateIntervalWorkSeconds(config.intervalWorkSeconds + 15.0) }
                    )
                    StepperRow(
                        label = "Rest Phase: ${config.intervalRestSeconds.toInt()}s",
                        onDecrement = { viewModel.updateIntervalRestSeconds(config.intervalRestSeconds - 15.0) },
                        onIncrement = { viewModel.updateIntervalRestSeconds(config.intervalRestSeconds + 15.0) }
                    )
                    StepperRow(
                        label = "Rounds: ${config.intervalRounds}",
                        onDecrement = { viewModel.updateIntervalRounds(config.intervalRounds - 1) },
                        onIncrement = { viewModel.updateIntervalRounds(config.intervalRounds + 1) }
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // 3. Target Cadence (RPM Metronome)
            SectionTitle("3. Target Cadence (RPM Metronome)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Target Cadence", color = Color.White)
                Text(
                    text = "${config.targetRPM.roundToInt()} RPM",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2EE66B)
                )
            }
            Slider(
                value = config.targetRPM.toFloat(),
                onValueChange = { viewModel.updateTargetRPM(it.roundToInt().toDouble()) },
                valueRange = 4f..30f
            )
            if (config.targetRPM > 0.0) {
                val period = 60.0 / config.targetRPM
                Text(
                    text = String.format(Locale.US, "Metronome Woodblock Beep: Every %.2f sec", period),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // 4. Polar H10 BLE Telemetry
            SectionTitle("4. Polar H10 BLE Telemetry")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (polarState.connectionState.isConnectedOrSimulated) Color(0xFF2EE66B) else Color(0xFFFF9F0A),
                            CircleShape
                        )
                )
                Text(
                    text = polarState.connectionState.badgeText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.polarManager.searchAndConnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Scan Nearest H10")
                }
                if (polarState.connectionState != PolarConnectionState.Disconnected) {
                    OutlinedButton(onClick = { viewModel.polarManager.disconnect() }) {
                        Text("Disconnect")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customPolarDeviceId,
                    onValueChange = { customPolarDeviceId = it.uppercase() },
                    label = { Text("Polar Device ID (e.g. A1B2C3D4)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = { viewModel.polarManager.autoConnect(customPolarDeviceId) },
                    enabled = customPolarDeviceId.isNotBlank()
                ) {
                    Text("Connect")
                }
            }
            OutlinedButton(
                onClick = { viewModel.polarManager.startAccStream(50) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable 50 Hz Sternum Accelerometer (Dip/Drive)")
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // 5. Bench / Simulator Diagnostics
            SectionTitle("5. Bench / Simulator Diagnostics")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Simulated 25 Hz Lifter Pose Feed", color = Color.White)
                Switch(
                    checked = config.isDemoPoseFeedEnabled,
                    onCheckedChange = { viewModel.setDemoPoseFeedEnabled(it) }
                )
            }
            OutlinedButton(
                onClick = {
                    if (polarState.connectionState == PolarConnectionState.SimulatedH10) {
                        viewModel.polarManager.disconnect()
                    } else {
                        viewModel.polarManager.startSimulatedH10Stream()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (polarState.connectionState == PolarConnectionState.SimulatedH10) {
                        "Stop Simulated Polar H10 Stream"
                    } else {
                        "Start Simulated Polar H10 (HR + RR + Acc)"
                    }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // 6. Legal & License (GNU GPLv3 Section 0 & 5d)
            SectionTitle("6. Legal & License (GNU GPLv3)")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "GirevoyPulse — Copyright (C) 2026 Dragos Iorgulescu & Contributors",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "This program comes with ABSOLUTELY NO WARRANTY. This is free software, and you are welcome to redistribute it under the terms of the GNU General Public License v3.0 (or later).",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.72f)
                )
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"))
                        context.startActivity(intent)
                    }
                ) {
                    Text("View GNU General Public License v3.0")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFF64D2FF)
    )
}

@Composable
private fun StepperRow(
    label: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDecrement) { Text("-") }
            OutlinedButton(onClick = onIncrement) { Text("+") }
        }
    }
}

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

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.girevoypulse.app.presentation.viewmodel.WorkoutViewModel
import com.girevoypulse.app.presentation.views.LiveWorkoutScreen

/**
 * Primary Android entry point for GirevoyPulse.
 * Keeps the screen awake during 10-to-60 minute sets, requests Camera & Bluetooth BLE permissions,
 * and hosts the distance-optimized Jetpack Compose HUD.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WorkoutViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            viewModel.cameraManager.markPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        viewModel.initializePlatformServices(applicationContext)
        requestRequiredHardwarePermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LiveWorkoutScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestRequiredHardwarePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}

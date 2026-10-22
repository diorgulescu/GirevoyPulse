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

import com.girevoypulse.app.core.ble.PolarConnectionState
import com.girevoypulse.app.core.ble.PolarManager
import com.girevoypulse.app.core.timing.GSTimerEngine
import com.girevoypulse.app.domain.models.GSTimerMode
import com.girevoypulse.app.domain.models.SternumAccSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PolarManagerTest {

    @Test
    fun heartRateAndRRIntervalStreamingComputesRMSSD() {
        val manager = PolarManager()
        manager.ingestHeartRateSample(bpm = 162, rrIntervalsMs = listOf(370.0, 380.0, 368.0, 376.0))

        assertEquals(162, manager.bpm)
        assertEquals(listOf(370.0, 380.0, 368.0, 376.0), manager.rrIntervals)
        assertEquals(sqrt(308.0 / 3.0), manager.rmssdHRVMilliseconds, 1e-4)
    }

    @Test
    fun sternumAccelerometerComputesThoracicDipAngle() {
        val manager = PolarManager()
        manager.ingestAccelerometerSample(SternumAccSample(xMillig = 0, yMillig = -1000, zMillig = 0))
        assertEquals(0.0, manager.thoracicDipAngleDegrees, 1e-3)

        manager.ingestAccelerometerSample(SternumAccSample(xMillig = 0, yMillig = -707, zMillig = 707))
        assertEquals(45.0, manager.thoracicDipAngleDegrees, 0.1)
    }

    @Test
    fun unexpectedBLEDisconnectReconnectsGracefullyWithoutStoppingTimer() {
        val clock = MockMonotonicClock(100.0)
        val spyAudio = SpyAudioCuePlayer()
        val timer = GSTimerEngine(
            initialMode = GSTimerMode.Traditional10Min(targetRPM = 16.0),
            clock = clock,
            audioEngine = spyAudio
        )
        val polar = PolarManager()

        timer.start()
        polar.autoConnect("A1B2C3D4")
        assertEquals(PolarConnectionState.Connecting("A1B2C3D4"), polar.connectionState)

        clock.advance(30.0)
        timer.stepToMonotonicTime(clock.now())
        polar.handleUnexpectedDisconnect("A1B2C3D4")

        assertTrue(timer.isRunning)
        assertEquals(30.0, timer.elapsedTime, 1e-6)
        assertEquals(PolarConnectionState.Reconnecting("A1B2C3D4"), polar.connectionState)
        timer.stop()
    }
}

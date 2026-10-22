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

import com.girevoypulse.app.core.audio.GSAudioCuePlaying
import com.girevoypulse.app.core.timing.GSTimerEngine
import com.girevoypulse.app.domain.models.GSAudioCue
import com.girevoypulse.app.domain.models.GSTimerMode
import com.girevoypulse.app.domain.models.MarathonDuration
import com.girevoypulse.app.domain.models.TimerIntervalPhase
import com.girevoypulse.app.domain.timing.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class MockMonotonicClock(var currentTime: Double = 1000.0) : MonotonicClock {
    override fun now(): Double = currentTime
    fun advance(delta: Double) {
        currentTime += delta
    }
}

class SpyAudioCuePlayer : GSAudioCuePlaying {
    private val _cues = mutableListOf<GSAudioCue>()
    val cues: List<GSAudioCue> get() = _cues.toList()

    override fun play(cue: GSAudioCue) {
        _cues.add(cue)
    }
}

class GSTimerEngineTest {

    @Test
    fun monotonicClockDriftImmunityUnderVariableFrameJitter() {
        val clock = MockMonotonicClock(5000.0)
        val spyAudio = SpyAudioCuePlayer()
        val engine = GSTimerEngine(
            initialMode = GSTimerMode.Traditional10Min(targetRPM = 16.0),
            clock = clock,
            audioEngine = spyAudio
        )

        engine.start()
        assertEquals(0.0, engine.elapsedTime, 1e-6)
        assertEquals(600.0, engine.remainingTime, 1e-6)

        val jitterIntervals = listOf(
            0.014, 0.047, 0.112, 0.003, 0.089, 0.235, 0.500,
            1.750, 1.000, 2.250, 54.000, 120.000, 420.000
        )
        var expectedElapsed = 0.0
        for (delta in jitterIntervals) {
            clock.advance(delta)
            expectedElapsed += delta
            engine.stepToMonotonicTime(clock.now())
            assertEquals(min(600.0, expectedElapsed), engine.elapsedTime, 1e-9)
        }

        assertTrue(engine.isCompleted)
        assertEquals(0.0, engine.remainingTime, 1e-9)
        assertEquals(10, engine.splits.size)
        engine.stop()
    }

    @Test
    fun traditional10MinCadenceMetronomeAndMinuteSplits() {
        val clock = MockMonotonicClock(100.0)
        val spyAudio = SpyAudioCuePlayer()
        val engine = GSTimerEngine(
            initialMode = GSTimerMode.Traditional10Min(targetRPM = 16.0),
            clock = clock,
            audioEngine = spyAudio
        )

        engine.start()
        assertEquals(1, engine.metronomeTicksFired)

        for (step in 1..240) {
            clock.advance(0.25)
            if (step % 15 == 0) {
                engine.recordRep(currentHeartRate = 155)
            }
            engine.stepToMonotonicTime(clock.now())
        }

        assertEquals(17, engine.metronomeTicksFired)
        assertEquals(1, engine.splits.size)
        assertEquals(1, engine.splits.first().minuteIndex)
        assertEquals(16, engine.splits.first().repsInMinute)
        assertEquals(16, engine.splits.first().cumulativeReps)
        assertEquals(155, engine.splits.first().averageHeartRate)
        engine.stop()
    }

    @Test
    fun pauseAndResumeExcludesPausedDurationFromMonotonicElapsed() {
        val clock = MockMonotonicClock(2000.0)
        val spyAudio = SpyAudioCuePlayer()
        val engine = GSTimerEngine(
            initialMode = GSTimerMode.Traditional10Min(targetRPM = 12.0),
            clock = clock,
            audioEngine = spyAudio
        )

        engine.start()
        clock.advance(45.0)
        engine.stepToMonotonicTime(clock.now())
        assertEquals(45.0, engine.elapsedTime, 1e-6)

        engine.pause()
        assertTrue(engine.isPaused)

        clock.advance(120.0)
        engine.start() // Resume

        clock.advance(15.0)
        engine.stepToMonotonicTime(clock.now())
        assertEquals(60.0, engine.elapsedTime, 1e-6)
        assertEquals(1, engine.splits.size)
        engine.stop()
    }

    @Test
    fun marathonSwitchAlertsAndFinalCountdown() {
        val clock = MockMonotonicClock(0.0)
        val spyAudio = SpyAudioCuePlayer()
        val engine = GSTimerEngine(
            initialMode = GSTimerMode.Marathon(
                duration = MarathonDuration.HALF_MARATHON_30_MIN,
                switchIntervalMinutes = 5,
                targetRPM = 14.0
            ),
            clock = clock,
            audioEngine = spyAudio
        )

        var switchCallbacks = 0
        engine.onHandSwitchAlert = { switchCallbacks += 1 }

        engine.start()
        for (i in 1..1800) {
            clock.advance(1.0)
            engine.stepToMonotonicTime(clock.now())
        }

        assertEquals(5, engine.handSwitchAlertsTriggered)
        assertEquals(5, switchCallbacks)
        assertTrue(engine.isCompleted)

        val countdownCues = spyAudio.cues.mapNotNull { cue ->
            (cue as? GSAudioCue.FinalTenSecondTick)?.secondsRemaining
        }
        assertEquals(listOf(10, 9, 8, 7, 6, 5, 4, 3, 2, 1), countdownCues)
        engine.stop()
    }

    @Test
    fun intervalModeWorkRestCycles() {
        val clock = MockMonotonicClock(500.0)
        val spyAudio = SpyAudioCuePlayer()
        val engine = GSTimerEngine(
            initialMode = GSTimerMode.Intervals(
                workSeconds = 60.0,
                restSeconds = 30.0,
                rounds = 3,
                targetRPM = 15.0
            ),
            clock = clock,
            audioEngine = spyAudio
        )

        assertEquals(240.0, engine.mode.totalPlannedDuration, 1e-6)
        engine.start()
        assertEquals(TimerIntervalPhase.Work(round = 1, totalRounds = 3), engine.intervalPhase)

        clock.advance(65.0)
        engine.stepToMonotonicTime(clock.now())
        assertEquals(TimerIntervalPhase.Rest(round = 1, totalRounds = 3), engine.intervalPhase)
        assertEquals(25.0, engine.phaseRemainingTime, 1e-6)

        clock.advance(30.0)
        engine.stepToMonotonicTime(clock.now())
        assertEquals(TimerIntervalPhase.Work(round = 2, totalRounds = 3), engine.intervalPhase)
        assertEquals(55.0, engine.phaseRemainingTime, 1e-6)

        clock.advance(145.0)
        engine.stepToMonotonicTime(clock.now())
        assertEquals(TimerIntervalPhase.Completed, engine.intervalPhase)
        assertTrue(engine.isCompleted)
        engine.stop()
    }
}

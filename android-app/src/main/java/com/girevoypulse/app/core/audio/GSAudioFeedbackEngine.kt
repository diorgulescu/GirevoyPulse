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

package com.girevoypulse.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.girevoypulse.app.domain.models.GSAudioCue
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Interface for playing Girevoy Sport audio & haptic cues (enables deterministic unit testing). */
fun interface GSAudioCuePlaying {
    fun play(cue: GSAudioCue)
}

/**
 * Synthesizes low-latency 16-bit 44.1 kHz PCM audio cues via Android `AudioTrack`
 * (woodblock metronome, fixation double-chime, hand-switch horn, and final 10s countdown ticks).
 */
class GSAudioFeedbackEngine : GSAudioCuePlaying {
    companion object {
        val shared = GSAudioFeedbackEngine()
        private const val SAMPLE_RATE = 44_100
    }

    data class ToneSegment(
        val frequency: Double,
        val duration: Double,
        val amplitude: Double,
        val decay: Double
    )

    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "com.girevoypulse.audioqueue").apply { isDaemon = true }
    }

    private val pcmBuffers: Map<String, ShortArray> = mapOf(
        "metronomeTick" to synthesizePCM(
            listOf(ToneSegment(frequency = 820.0, duration = 0.045, amplitude = 0.45, decay = 38.0))
        ),
        "fixationValidated" to synthesizePCM(
            listOf(
                ToneSegment(frequency = 1046.5, duration = 0.065, amplitude = 0.65, decay = 18.0),
                ToneSegment(frequency = 1567.98, duration = 0.11, amplitude = 0.75, decay = 14.0)
            )
        ),
        "handSwitchAlert" to synthesizePCM(
            listOf(
                ToneSegment(frequency = 880.0, duration = 0.09, amplitude = 0.80, decay = 12.0),
                ToneSegment(frequency = 0.0, duration = 0.03, amplitude = 0.0, decay = 1.0),
                ToneSegment(frequency = 880.0, duration = 0.09, amplitude = 0.80, decay = 12.0),
                ToneSegment(frequency = 0.0, duration = 0.03, amplitude = 0.0, decay = 1.0),
                ToneSegment(frequency = 1174.66, duration = 0.16, amplitude = 0.85, decay = 9.0)
            )
        ),
        "finalTenSecondTick" to synthesizePCM(
            listOf(ToneSegment(frequency = 987.77, duration = 0.075, amplitude = 0.65, decay = 20.0))
        ),
        "phaseWork" to synthesizePCM(
            listOf(
                ToneSegment(frequency = 659.25, duration = 0.08, amplitude = 0.70, decay = 12.0),
                ToneSegment(frequency = 1318.51, duration = 0.16, amplitude = 0.80, decay = 8.0)
            )
        ),
        "phaseRest" to synthesizePCM(
            listOf(
                ToneSegment(frequency = 880.0, duration = 0.08, amplitude = 0.60, decay = 12.0),
                ToneSegment(frequency = 440.0, duration = 0.16, amplitude = 0.60, decay = 10.0)
            )
        ),
        "setCompleted" to synthesizePCM(
            listOf(
                ToneSegment(frequency = 523.25, duration = 0.09, amplitude = 0.75, decay = 10.0),
                ToneSegment(frequency = 659.25, duration = 0.09, amplitude = 0.75, decay = 10.0),
                ToneSegment(frequency = 783.99, duration = 0.09, amplitude = 0.75, decay = 10.0),
                ToneSegment(frequency = 1046.50, duration = 0.24, amplitude = 0.85, decay = 6.0)
            )
        )
    )

    @Volatile
    var isMuted: Boolean = false

    override fun play(cue: GSAudioCue) {
        if (isMuted) return
        val key = bufferKey(cue)
        val samples = pcmBuffers[key] ?: return

        audioExecutor.execute {
            try {
                val byteCount = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(byteCount, 2048))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
            } catch (_: Throwable) {
                // Non-fatal in headless JVM unit tests
            }
        }
    }

    private fun bufferKey(cue: GSAudioCue): String = when (cue) {
        is GSAudioCue.MetronomeTick -> "metronomeTick"
        is GSAudioCue.FixationValidated -> "fixationValidated"
        is GSAudioCue.HandSwitchAlert -> "handSwitchAlert"
        is GSAudioCue.FinalTenSecondTick -> "finalTenSecondTick"
        is GSAudioCue.IntervalPhaseTransition -> if (cue.isWork) "phaseWork" else "phaseRest"
        is GSAudioCue.SetCompleted -> "setCompleted"
    }

    private fun synthesizePCM(segments: List<ToneSegment>): ShortArray {
        val totalSamples = segments.sumOf { max(1, (it.duration * SAMPLE_RATE).toInt()) }
        val output = ShortArray(totalSamples)
        var cursor = 0
        for (seg in segments) {
            val count = max(1, (seg.duration * SAMPLE_RATE).toInt())
            for (i in 0 until count) {
                if (seg.frequency <= 0.0 || seg.amplitude <= 0.0) {
                    output[cursor++] = 0
                    continue
                }
                val t = i.toDouble() / SAMPLE_RATE.toDouble()
                val envelope = exp(-seg.decay * t)
                val fundamental = sin(2.0 * Math.PI * seg.frequency * t)
                val overtone = 0.25 * sin(2.0 * Math.PI * (seg.frequency * 1.5) * t)
                val raw = (fundamental + overtone) * 0.8 * seg.amplitude * envelope
                val clamped = max(-1.0, min(1.0, raw))
                output[cursor++] = (clamped * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return output
    }
}

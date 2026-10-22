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

import com.girevoypulse.app.core.audio.GSAudioCuePlaying
import com.girevoypulse.app.core.audio.GSAudioFeedbackEngine
import com.girevoypulse.app.domain.biomechanics.BodyPoseFrame
import com.girevoypulse.app.domain.biomechanics.FixationBiomechanicsSnapshot
import com.girevoypulse.app.domain.biomechanics.FixationThresholds
import com.girevoypulse.app.domain.biomechanics.GSFixationStateMachine
import com.girevoypulse.app.domain.biomechanics.LiftPhase
import com.girevoypulse.app.domain.models.ActiveSide
import com.girevoypulse.app.domain.models.GSAudioCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin Android reactive adapter wrapping the platform-agnostic `GSFixationStateMachine`
 * and dispatching audio cues when valid repetitions are confirmed.
 */
class GSFixationDetector(
    thresholds: FixationThresholds = FixationThresholds(),
    private val audioEngine: GSAudioCuePlaying = GSAudioFeedbackEngine.shared
) {
    val stateMachine = GSFixationStateMachine(thresholds = thresholds)

    private val _phase = MutableStateFlow<LiftPhase>(LiftPhase.RackOrSwing)
    val phaseFlow: StateFlow<LiftPhase> = _phase.asStateFlow()

    private val _repCount = MutableStateFlow(0)
    val repCountFlow: StateFlow<Int> = _repCount.asStateFlow()

    private val _biomechanics = MutableStateFlow(FixationBiomechanicsSnapshot.ZERO)
    val biomechanicsFlow: StateFlow<FixationBiomechanicsSnapshot> = _biomechanics.asStateFlow()

    val phase: LiftPhase get() = _phase.value
    val repCount: Int get() = _repCount.value
    val biomechanics: FixationBiomechanicsSnapshot get() = _biomechanics.value

    var preferredSide: ActiveSide?
        get() = stateMachine.preferredSide
        set(value) {
            stateMachine.preferredSide = value
        }

    var onRepValidated: ((Int) -> Unit)? = null

    fun reset() {
        stateMachine.reset()
        _phase.value = stateMachine.phase
        _repCount.value = stateMachine.repCount
        _biomechanics.value = stateMachine.biomechanics
    }

    fun process(frame: BodyPoseFrame): LiftPhase {
        val result = stateMachine.step(frame)
        _phase.value = result.phase
        _repCount.value = result.repCount
        _biomechanics.value = result.biomechanics

        if (result.didConfirmNewRep) {
            audioEngine.play(GSAudioCue.FixationValidated)
            onRepValidated?.invoke(result.repCount)
        }
        return result.phase
    }
}

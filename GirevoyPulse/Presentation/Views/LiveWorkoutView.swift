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

import SwiftUI

/// Full-screen Girevoy Sport Training HUD engineered for high-contrast legibility at a 3-meter platform distance.
public struct LiveWorkoutView: View {
    @StateObject private var viewModel: WorkoutViewModel

    public init(viewModel: WorkoutViewModel = WorkoutViewModel()) {
        _viewModel = StateObject(wrappedValue: viewModel)
    }

    public var body: some View {
        ZStack {
            // 1. Live Selfie Camera Preview Layer
            CameraPreviewView(session: viewModel.cameraManager.captureSession)
                .ignoresSafeArea()

            // High-contrast dark vignette for 3-meter typography legibility
            LinearGradient(
                colors: [
                    Color.black.opacity(0.72),
                    Color.black.opacity(0.28),
                    Color.black.opacity(0.78)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            // 2. Low-Opacity Biomechanical Skeletal Overlay
            SkeletalOverlayView(
                poseFrame: viewModel.latestPoseFrame,
                biomechanics: viewModel.biomechanics,
                liftPhase: viewModel.liftPhase
            )
            .ignoresSafeArea()

            // 3. Distance-Optimized HUD Overlay
            VStack(spacing: 12) {
                topStatusBar
                fixationBanner
                hugeRepCounterSection
                Spacer(minLength: 4)
                biomechanicalAnglesStrip
                timingAndPaceCard
                heartRateZoneCard
                if !viewModel.timerEngine.splits.isEmpty {
                    splitsStrip
                }
                bottomControlsBar
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .preferredColorScheme(.dark)
        .onAppear {
            viewModel.onAppear()
        }
        .sheet(isPresented: $viewModel.isSetupSheetPresented) {
            WorkoutSetupSheet(viewModel: viewModel)
        }
    }

    // MARK: - 1. Top Status Bar

    private var topStatusBar: some View {
        HStack(spacing: 10) {
            // Discipline & Active Hand Pill
            Button {
                viewModel.activeSide.toggleHand()
            } label: {
                HStack(spacing: 6) {
                    Text(viewModel.selectedDiscipline.shortName)
                        .font(.system(size: 15, weight: .black, design: .rounded))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.yellow, in: Capsule())

                    Text("HAND: \(viewModel.activeSide.rawValue)")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                }
                .padding(.trailing, 10)
                .background(Color.black.opacity(0.65), in: Capsule())
            }
            .buttonStyle(.plain)

            Spacer()

            // Polar H10 Connection Badge
            Button {
                viewModel.isSetupSheetPresented = true
            } label: {
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.polarManager.connectionState.isConnectedOrSimulated ? Color.green : Color.orange)
                        .frame(width: 9, height: 9)
                    Text(viewModel.polarManager.connectionState.badgeText)
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.black.opacity(0.65), in: Capsule())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 2. "FIXATION" Lockout Indicator Banner

    private var fixationBanner: some View {
        let isConfirmed = viewModel.liftPhase.isFixationConfirmed
        let isHolding: Bool = {
            if case .overheadHolding = viewModel.liftPhase { return true }
            return false
        }()

        return HStack(spacing: 12) {
            Image(systemName: isConfirmed ? "checkmark.seal.fill" : (isHolding ? "timer" : "figure.strengthtraining.traditional"))
                .font(.system(size: 26, weight: .black))
                .foregroundStyle(isConfirmed ? .black : .white)

            Text(isConfirmed ? "FIXATION" : viewModel.liftPhase.displayTitle)
                .font(.system(size: 30, weight: .black, design: .rounded))
                .tracking(2)
                .foregroundStyle(isConfirmed ? .black : .white)

            if isHolding || isConfirmed {
                Text("\(viewModel.biomechanics.currentHoldDurationMs) ms")
                    .font(.system(size: 18, weight: .heavy, design: .monospaced))
                    .foregroundStyle(isConfirmed ? .black.opacity(0.85) : .yellow)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(
                    isConfirmed
                        ? Color(red: 0.10, green: 0.98, blue: 0.42)
                        : (isHolding ? Color.orange.opacity(0.85) : Color.white.opacity(0.14))
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(isConfirmed ? Color.white : Color.white.opacity(0.25), lineWidth: isConfirmed ? 3 : 1)
        )
        .shadow(
            color: isConfirmed ? Color.green.opacity(0.75) : .clear,
            radius: 18
        )
        .animation(.spring(response: 0.22, dampingFraction: 0.72), value: viewModel.liftPhase)
    }

    // MARK: - 3. Huge 3-Meter Glanceable Rep Counter

    private var hugeRepCounterSection: some View {
        VStack(spacing: -8) {
            Text("VALID REPS")
                .font(.system(size: 14, weight: .heavy, design: .monospaced))
                .tracking(3)
                .foregroundStyle(.white.opacity(0.75))

            Text("\(viewModel.repCount)")
                .font(.system(size: 132, weight: .black, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(
                    viewModel.liftPhase.isFixationConfirmed
                        ? Color(red: 0.15, green: 1.0, blue: 0.45)
                        : Color.white
                )
                .shadow(color: .black.opacity(0.85), radius: 12, x: 0, y: 4)
                .contentTransition(.numericText())
                .onTapGesture {
                    viewModel.manualIncrementRep()
                }
        }
    }

    // MARK: - 4. Biomechanical Lockout Badges

    private var biomechanicalAnglesStrip: some View {
        let bio = viewModel.biomechanics
        return HStack(spacing: 8) {
            angleBadge(
                label: "ELBOW",
                value: String(format: "%.0f°", bio.elbowAngleDegrees),
                target: "≥168°",
                locked: bio.isElbowLocked
            )
            angleBadge(
                label: "KNEES",
                value: String(format: "%.0f°", min(bio.leftKneeAngleDegrees, bio.rightKneeAngleDegrees)),
                target: "≥172°",
                locked: bio.areKneesLocked
            )
            angleBadge(
                label: "HIP EXT",
                value: String(format: "%.0f°", bio.hipAngleDegrees),
                target: "≥170°",
                locked: bio.isHipExtended
            )
            angleBadge(
                label: "STABLE",
                value: String(format: "%.2f", abs(bio.wristVerticalVelocity)),
                target: "≥300ms",
                locked: bio.isTrajectoryStable && bio.isOverhead
            )
        }
    }

    private func angleBadge(label: String, value: String, target: String, locked: Bool) -> some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .foregroundStyle(.white.opacity(0.7))
            Text(value)
                .font(.system(size: 17, weight: .black, design: .monospaced))
                .foregroundStyle(locked ? Color.green : Color.yellow)
            Text(target)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.55))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.black.opacity(0.65))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(locked ? Color.green.opacity(0.7) : Color.white.opacity(0.15), lineWidth: 1.5)
                )
        )
    }

    // MARK: - 5. Monotonic Elapsed Time & Split Pace (Current RPM vs Target RPM)

    private var timingAndPaceCard: some View {
        HStack(spacing: 12) {
            // Monotonic Clock Column
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(viewModel.timerEngine.intervalPhase.label)
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(.cyan)
                    if let switchSec = viewModel.timerEngine.secondsUntilNextSwitch {
                        Text("• SWITCH IN \(GSTimerEngine.formatTime(switchSec))")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(.yellow)
                    }
                }

                Text(GSTimerEngine.formatTime(viewModel.timerEngine.elapsedTime, includeTenths: true))
                    .font(.system(size: 46, weight: .black, design: .monospaced))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.75)
                    .lineLimit(1)

                Text("REMAINING \(GSTimerEngine.formatTime(viewModel.timerEngine.phaseRemainingTime))")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.75))
            }

            Spacer()

            // Split Pace (Current RPM vs Target RPM)
            VStack(alignment: .trailing, spacing: 4) {
                Text("PACE (RPM)")
                    .font(.system(size: 12, weight: .heavy, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.75))

                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(String(format: "%.1f", viewModel.timerEngine.currentRPM))
                        .font(.system(size: 42, weight: .black, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(paceComparisonColor)

                    Text("/ \(Int(viewModel.targetRPM))")
                        .font(.system(size: 22, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.7))
                }

                Text("THIS MIN: \(viewModel.timerEngine.currentMinuteReps) REPS")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(.green)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.black.opacity(0.74))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color.white.opacity(0.20), lineWidth: 1)
                )
        )
    }

    private var paceComparisonColor: Color {
        let current = viewModel.timerEngine.currentRPM
        guard current > 0 else { return .white }
        let delta = current - viewModel.targetRPM
        if abs(delta) <= 1.5 { return .green }
        return delta > 1.5 ? .yellow : .orange
    }

    // MARK: - 6. Live Heart Rate (BPM) + HR Zone Color Bar

    private var heartRateZoneCard: some View {
        let bpm = viewModel.polarManager.bpm
        let currentZone = HeartRateZone.zone(for: bpm)

        return VStack(spacing: 8) {
            HStack(alignment: .center) {
                HStack(spacing: 8) {
                    Image(systemName: "heart.fill")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(.red)

                    Text(bpm > 0 ? "\(bpm)" : "--")
                        .font(.system(size: 44, weight: .black, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(.white)

                    VStack(alignment: .leading, spacing: 1) {
                        Text("BPM")
                            .font(.system(size: 12, weight: .black, design: .monospaced))
                            .foregroundStyle(.red)
                        Text(currentZone.title)
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(color(for: currentZone))
                    }
                }

                Spacer()

                // HRV Strain (RMSSD) & Sternum Thoracic Dip
                VStack(alignment: .trailing, spacing: 3) {
                    if let lastRR = viewModel.polarManager.rrIntervals.last {
                        Text(String(format: "RR %.0f ms • HRV %.1f ms", lastRR, viewModel.polarManager.rmssdHRVMilliseconds))
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(.cyan)
                    } else {
                        Text("RR / HRV STANDBY")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.5))
                    }

                    if viewModel.polarManager.latestAccSample != nil {
                        Text(String(format: "TORSO DIP %.1f°", viewModel.polarManager.thoracicDipAngleDegrees))
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(.yellow)
                    }
                }
            }

            // 5-Segment HR Zone Color Bar
            GeometryReader { geo in
                HStack(spacing: 4) {
                    ForEach(HeartRateZone.allCases, id: \.rawValue) { zone in
                        let isActive = (bpm > 0 && zone == currentZone)
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(color(for: zone).opacity(isActive ? 1.0 : 0.28))
                            .frame(height: isActive ? 10 : 6)
                            .overlay(
                                RoundedRectangle(cornerRadius: 4, style: .continuous)
                                    .stroke(isActive ? Color.white : .clear, lineWidth: 1.5)
                            )
                    }
                }
                .frame(width: geo.size.width, height: 10)
            }
            .frame(height: 10)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.black.opacity(0.74))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color.white.opacity(0.20), lineWidth: 1)
                )
        )
    }

    private func color(for zone: HeartRateZone) -> Color {
        switch zone {
        case .zone1Recovery: return .blue
        case .zone2Aerobic: return .green
        case .zone3Tempo: return .yellow
        case .zone4Threshold: return .orange
        case .zone5Redline: return .red
        }
    }

    // MARK: - 7. 1-Minute Split Log Strip

    private var splitsStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.timerEngine.splits) { split in
                    HStack(spacing: 4) {
                        Text("M\(split.minuteIndex):")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.7))
                        Text("\(split.repsInMinute)")
                            .font(.system(size: 14, weight: .black, design: .monospaced))
                            .foregroundStyle(.green)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.7), in: Capsule())
                }
            }
        }
    }

    // MARK: - 8. Bottom Controls

    private var bottomControlsBar: some View {
        HStack(spacing: 14) {
            // Start / Pause Set
            Button {
                viewModel.toggleWorkoutStartPause()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: viewModel.timerEngine.isRunning ? "pause.fill" : "play.fill")
                    Text(viewModel.timerEngine.isRunning ? "PAUSE SET" : "START SET")
                }
                .font(.system(size: 18, weight: .black, design: .rounded))
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    viewModel.timerEngine.isRunning ? Color.yellow : Color.green,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
            }

            // Reset Set
            Button {
                viewModel.resetWorkout()
            } label: {
                Image(systemName: "arrow.counterclockwise")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 50)
                    .background(Color.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }

            // Open Setup Sheet
            Button {
                viewModel.isSetupSheetPresented = true
            } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 50)
                    .background(Color.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
    }
}

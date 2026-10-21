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

/// Configuration sheet for selecting GS discipline, timer mode, target cadence (RPM),
/// and managing the Polar H10 BLE chest strap connection.
public struct WorkoutSetupSheet: View {
    @ObservedObject public var viewModel: WorkoutViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var customPolarDeviceId: String = ""

    public init(viewModel: WorkoutViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            Form {
                disciplineSection
                setTypeSection
                cadenceSection
                polarBleSection
                simulationSection
                licenseSection
            }
            .navigationTitle("GirevoyPulse Setup")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        viewModel.applyTimerConfiguration()
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Sections

    private var disciplineSection: some View {
        Section {
            Picker("Discipline", selection: $viewModel.selectedDiscipline) {
                ForEach(GSDiscipline.allCases) { discipline in
                    Text(discipline.rawValue).tag(discipline)
                }
            }
            .pickerStyle(.segmented)

            Text(viewModel.selectedDiscipline.description)
                .font(.footnote)
                .foregroundStyle(.secondary)

            Picker("Active Bell Side", selection: $viewModel.activeSide) {
                ForEach(ActiveSide.allCases) { side in
                    Text(side.rawValue).tag(side)
                }
            }
            .pickerStyle(.segmented)
        } header: {
            Text("1. Discipline & Bell Side")
        }
    }

    private var setTypeSection: some View {
        Section {
            Picker("Set Type", selection: $viewModel.selectedTimerCategory) {
                ForEach(GSTimerCategory.allCases) { category in
                    Text(category.rawValue).tag(category)
                }
            }
            .pickerStyle(.segmented)

            switch viewModel.selectedTimerCategory {
            case .traditional10Min:
                LabeledContent("Set Duration", value: "10:00 (Rank Set)")
                LabeledContent("Split Logging", value: "Every 1:00")

            case .marathon:
                Picker("Marathon Distance", selection: $viewModel.marathonDuration) {
                    ForEach(MarathonDuration.allCases) { duration in
                        Text(duration.title).tag(duration)
                    }
                }
                Stepper(
                    "Hand Switch Alert: Every \(viewModel.marathonSwitchMinutes) min",
                    value: $viewModel.marathonSwitchMinutes,
                    in: 1...15
                )

            case .intervals:
                Stepper(
                    "Work Phase: \(Int(viewModel.intervalWorkSeconds))s",
                    value: $viewModel.intervalWorkSeconds,
                    in: 15...600,
                    step: 15
                )
                Stepper(
                    "Rest Phase: \(Int(viewModel.intervalRestSeconds))s",
                    value: $viewModel.intervalRestSeconds,
                    in: 15...300,
                    step: 15
                )
                Stepper(
                    "Rounds: \(viewModel.intervalRounds)",
                    value: $viewModel.intervalRounds,
                    in: 1...30
                )
            }
        } header: {
            Text("2. Timing Mode (Monotonic Clock)")
        }
    }

    private var cadenceSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("Target Cadence")
                    Spacer()
                    Text("\(Int(viewModel.targetRPM)) RPM")
                        .font(.system(.headline, design: .monospaced))
                        .foregroundStyle(.green)
                }
                Slider(value: $viewModel.targetRPM, in: 4...30, step: 1)
                    .tint(.green)

                if viewModel.targetRPM > 0 {
                    let secondsPerRep = 60.0 / viewModel.targetRPM
                    Text(String(format: "Metronome Woodblock Beep: Every %.2f sec", secondsPerRep))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text("3. Target Cadence (RPM Metronome)")
        }
    }

    private var polarBleSection: some View {
        Section {
            HStack {
                Circle()
                    .fill(viewModel.polarManager.connectionState.isConnectedOrSimulated ? Color.green : Color.orange)
                    .frame(width: 12, height: 12)
                Text(viewModel.polarManager.connectionState.badgeText)
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                Spacer()
                if viewModel.polarManager.bpm > 0 {
                    Text("\(viewModel.polarManager.bpm) BPM")
                        .font(.system(.subheadline, design: .monospaced).weight(.bold))
                        .foregroundStyle(.red)
                }
            }

            HStack(spacing: 12) {
                Button {
                    viewModel.polarManager.searchAndConnect()
                } label: {
                    Label("Scan Nearest H10", systemImage: "antenna.radiowaves.left.and.right")
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)

                if viewModel.polarManager.connectionState != .disconnected {
                    Button("Disconnect", role: .destructive) {
                        viewModel.polarManager.disconnect()
                    }
                    .buttonStyle(.bordered)
                }
            }

            HStack {
                TextField("Polar Device ID (e.g. A1B2C3D4)", text: $customPolarDeviceId)
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    #endif
                    .autocorrectionDisabled()
                Button("Auto-Connect") {
                    viewModel.polarManager.autoConnect(deviceId: customPolarDeviceId)
                }
                .disabled(customPolarDeviceId.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            Button {
                viewModel.polarManager.startAccStream(sampleRateHz: 50)
            } label: {
                Label("Enable 50 Hz Sternum Accelerometer (Dip/Drive)", systemImage: "gyroscope")
            }
        } header: {
            Text("4. Polar H10 BLE Telemetry")
        }
    }

    private var simulationSection: some View {
        Section {
            Toggle("Simulated 25 Hz Lifter Pose Feed", isOn: $viewModel.isDemoPoseFeedEnabled)
            Button {
                if viewModel.polarManager.connectionState == .simulatedH10 {
                    viewModel.polarManager.disconnect()
                } else {
                    viewModel.polarManager.startSimulatedH10Stream()
                }
            } label: {
                Label(
                    viewModel.polarManager.connectionState == .simulatedH10
                        ? "Stop Simulated Polar H10 Stream"
                        : "Start Simulated Polar H10 (HR + RR + Acc)",
                    systemImage: "waveform.path.ecg"
                )
            }
        } header: {
            Text("5. Bench / Simulator Diagnostics")
        } footer: {
            Text("Use simulated streams to test 300ms fixation counting and HRV telemetry when away from the gym.")
        }
    }

    private var licenseSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text("GirevoyPulse — Copyright (C) 2026 Dragos Iorgulescu & Contributors")
                    .font(.footnote.weight(.semibold))
                Text("This program comes with ABSOLUTELY NO WARRANTY. This is free software, and you are welcome to redistribute it under the terms of the GNU General Public License v3.0 (or later).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let url = URL(string: "https://www.gnu.org/licenses/gpl-3.0.html") {
                    Link("View GNU General Public License v3.0", destination: url)
                        .font(.caption.weight(.semibold))
                }
            }
            .padding(.vertical, 4)
        } header: {
            Text("6. Legal & License (GNU GPLv3)")
        }
    }
}

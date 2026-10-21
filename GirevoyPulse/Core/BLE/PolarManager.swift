import Combine
import Foundation
#if canImport(PolarBleSdk)
import CoreBluetooth
import PolarBleSdk
#endif

/// Connection lifecycle state for the Polar H10 chest strap.
public enum PolarConnectionState: Equatable, Sendable {
    case disconnected
    case searching
    case connecting(deviceId: String)
    case connected(deviceId: String, batteryLevel: UInt?)
    case reconnecting(deviceId: String)
    case simulatedH10

    public var isConnectedOrSimulated: Bool {
        switch self {
        case .connected, .simulatedH10:
            return true
        default:
            return false
        }
    }

    public var badgeText: String {
        switch self {
        case .disconnected:
            return "H10 DISCONNECTED"
        case .searching:
            return "SCANNING FOR H10..."
        case .connecting(let id):
            return "CONNECTING \(id)..."
        case .connected(let id, let battery):
            if let battery {
                return "POLAR H10 (\(id)) • \(battery)%"
            }
            return "POLAR H10 (\(id))"
        case .reconnecting(let id):
            return "RECONNECTING \(id)..."
        case .simulatedH10:
            return "POLAR H10 (SIM)"
        }
    }
}

/// 3-axis sternum accelerometer sample from the Polar H10 (in millig-force mG and G).
public struct SternumAccSample: Equatable, Sendable {
    public let xMillig: Int32
    public let yMillig: Int32
    public let zMillig: Int32

    public init(xMillig: Int32, yMillig: Int32, zMillig: Int32) {
        self.xMillig = xMillig
        self.yMillig = yMillig
        self.zMillig = zMillig
    }

    /// Approximate sagittal thoracic inclination / torso dip angle in degrees.
    public var thoracicDipAngleDegrees: Double {
        let yG = Double(yMillig) / 1000.0
        let zG = Double(zMillig) / 1000.0
        guard abs(yG) > 1e-4 || abs(zG) > 1e-4 else { return 0.0 }
        return abs(atan2(zG, max(0.001, abs(yG))) * (180.0 / .pi))
    }
}

/// Manages Polar H10 BLE discovery, connection resilience, real-time Heart Rate + RR interval streaming,
/// and 25 Hz / 50 Hz sternum accelerometer streaming via `PolarBleSdk`.
@MainActor
public final class PolarManager: ObservableObject {
    // MARK: - Published Telemetry

    @Published public private(set) var connectionState: PolarConnectionState = .disconnected
    @Published public private(set) var bpm: Int = 0
    @Published public private(set) var rrIntervals: [Double] = []
    @Published public private(set) var rmssdHRVMilliseconds: Double = 0.0
    @Published public private(set) var latestAccSample: SternumAccSample?
    @Published public private(set) var thoracicDipAngleDegrees: Double = 0.0
    @Published public private(set) var batteryLevel: UInt?

    // MARK: - Internal State

    private var targetDeviceId: String?
    private var shouldMaintainStreamsOnReconnect: Bool = false
    private var isAccStreamRequested: Bool = false
    private var preferredAccSampleRateHz: UInt16 = 50
    private var simulationTimer: Timer?

    #if canImport(PolarBleSdk)
    nonisolated(unsafe) private var api: PolarBleApi?
    private var searchTask: Task<Void, Never>?
    private var hrStreamTask: Task<Void, Never>?
    private var accStreamTask: Task<Void, Never>?
    #endif

    public init() {
        setupPolarSdkIfAvailable()
    }

    // MARK: - SDK Initialization

    private func setupPolarSdkIfAvailable() {
        #if canImport(PolarBleSdk)
        var instance = PolarBleApiDefaultImpl.polarImplementation(
            DispatchQueue.main,
            features: [
                PolarBleSdkFeature.feature_hr,
                PolarBleSdkFeature.feature_polar_online_streaming,
                PolarBleSdkFeature.feature_battery_info
            ]
        )
        instance.observer = self
        instance.deviceInfoObserver = self
        instance.powerStateObserver = self
        self.api = instance
        #endif
    }

    // MARK: - Connection & Discovery

    /// Scans for the strongest nearby Polar H10 (RSSI > -75 dBm) and connects automatically.
    public func searchAndConnect() {
        stopSimulation()
        connectionState = .searching
        shouldMaintainStreamsOnReconnect = true

        #if canImport(PolarBleSdk)
        guard let api else { return }
        searchTask?.cancel()
        let stream = api.searchForDevice()
        searchTask = Task { @MainActor [weak self] in
            do {
                for try await deviceInfo in stream {
                    guard let self, !Task.isCancelled else { break }
                    let upperName = deviceInfo.name.uppercased()
                    if upperName.contains("H10") || upperName.contains("POLAR") {
                        self.searchTask?.cancel()
                        self.autoConnect(deviceId: deviceInfo.deviceId)
                        break
                    }
                }
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.connectionState = .disconnected
            }
        }
        #endif
    }

    /// Connects directly to a known Polar H10 device identifier (e.g., `"A1B2C3D4"`).
    public func autoConnect(deviceId: String) {
        stopSimulation()
        let trimmed = deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchAndConnect()
            return
        }
        targetDeviceId = trimmed
        shouldMaintainStreamsOnReconnect = true
        connectionState = .connecting(deviceId: trimmed)

        #if canImport(PolarBleSdk)
        try? api?.connectToDevice(trimmed)
        #endif
    }

    /// Disconnects cleanly from the current Polar H10 without interrupting any running `GSTimerEngine`.
    public func disconnect() {
        shouldMaintainStreamsOnReconnect = false
        stopSimulation()

        #if canImport(PolarBleSdk)
        searchTask?.cancel()
        hrStreamTask?.cancel()
        accStreamTask?.cancel()
        if let deviceId = targetDeviceId {
            try? api?.disconnectFromDevice(deviceId)
        }
        #endif

        connectionState = .disconnected
        bpm = 0
    }

    // MARK: - Online Streaming (HR + RR Intervals & Sternum Accelerometer)

    /// Starts real-time Heart Rate (BPM) and RR interval (ms) streaming from the connected Polar H10.
    public func startHeartRateStream() {
        shouldMaintainStreamsOnReconnect = true

        #if canImport(PolarBleSdk)
        guard let api, let deviceId = targetDeviceId else { return }
        hrStreamTask?.cancel()
        let stream = api.startHrStreaming(deviceId)
        hrStreamTask = Task { @MainActor [weak self] in
            do {
                for try await hrData in stream {
                    guard let self, !Task.isCancelled else { break }
                    for sample in hrData {
                        let rrsInMs = sample.rrsMs.map { Double($0) }
                        self.ingestHeartRateSample(bpm: Int(sample.hr), rrIntervalsMs: rrsInMs)
                    }
                }
            } catch {
                // Stream errors on transient BLE drop are handled gracefully via reconnect
                guard let self, !Task.isCancelled, let id = self.targetDeviceId else { return }
                if self.shouldMaintainStreamsOnReconnect {
                    self.connectionState = .reconnecting(deviceId: id)
                }
            }
        }
        #endif
    }

    /// Starts real-time Sternum Accelerometer streaming (25 Hz or 50 Hz) to track torso dip & thoracic posture.
    public func startAccStream(sampleRateHz: UInt16 = 50) {
        isAccStreamRequested = true
        preferredAccSampleRateHz = (sampleRateHz == 25 || sampleRateHz == 50) ? sampleRateHz : 50

        #if canImport(PolarBleSdk)
        guard let api, let deviceId = targetDeviceId else { return }
        accStreamTask?.cancel()

        let settingsDict: [PolarSensorSetting.SettingType: UInt32] = [
            .sampleRate: UInt32(preferredAccSampleRateHz),
            .resolution: 16,
            .range: 8,
            .channels: 3
        ]
        guard let sensorSettings = try? PolarSensorSetting(settingsDict) else { return }

        let stream = api.startAccStreaming(deviceId, settings: sensorSettings)
        accStreamTask = Task { @MainActor [weak self] in
            do {
                for try await accData in stream {
                    guard let self, !Task.isCancelled else { break }
                    if let last = accData.last {
                        self.ingestAccelerometerSample(
                            SternumAccSample(xMillig: last.x, yMillig: last.y, zMillig: last.z)
                        )
                    }
                }
            } catch {
                // Keep workout active if optional ACC stream drops
            }
        }
        #endif
    }

    // MARK: - Telemetry Ingestion & HRV Calculation (Testable without BLE Hardware)

    /// Ingests a heart rate and RR-interval packet and updates rolling RMSSD HRV strain.
    public func ingestHeartRateSample(bpm: Int, rrIntervalsMs: [Double]) {
        self.bpm = max(0, bpm)
        if !rrIntervalsMs.isEmpty {
            rrIntervals.append(contentsOf: rrIntervalsMs)
            // Retain the most recent 60 RR intervals for sliding-window HRV strain calculation
            if rrIntervals.count > 60 {
                rrIntervals.removeFirst(rrIntervals.count - 60)
            }
            rmssdHRVMilliseconds = Self.computeRMSSD(from: rrIntervals)
        }
    }

    /// Ingests a 3-axis sternum accelerometer sample and updates the thoracic dip angle.
    public func ingestAccelerometerSample(_ sample: SternumAccSample) {
        latestAccSample = sample
        thoracicDipAngleDegrees = sample.thoracicDipAngleDegrees
    }

    /// Simulates a unexpected BLE link loss while keeping the workout active and transitioning to `.reconnecting`.
    public func handleUnexpectedDisconnect(deviceId: String) {
        if shouldMaintainStreamsOnReconnect {
            connectionState = .reconnecting(deviceId: deviceId)
            #if canImport(PolarBleSdk)
            try? api?.connectToDevice(deviceId)
            #endif
        } else {
            connectionState = .disconnected
        }
    }

    /// Starts a simulated Polar H10 telemetry stream for indoor/simulator testing without a chest strap.
    public func startSimulatedH10Stream() {
        stopSimulation()
        connectionState = .simulatedH10
        ingestHeartRateSample(bpm: 142, rrIntervalsMs: [422.0, 418.0, 425.0])
        ingestAccelerometerSample(SternumAccSample(xMillig: 15, yMillig: -980, zMillig: 140))

        simulationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.connectionState == .simulatedH10 else { return }
                let phase = Date().timeIntervalSinceReferenceDate
                let simulatedBPM = 148 + Int(sin(phase * 0.25) * 14.0)
                let baseRR = 60_000.0 / Double(max(60, simulatedBPM))
                let rr1 = baseRR + sin(phase * 1.3) * 11.0
                let rr2 = baseRR - cos(phase * 1.7) * 9.0
                self.ingestHeartRateSample(bpm: simulatedBPM, rrIntervalsMs: [rr1, rr2])

                let dipZ = Int32(120.0 + abs(sin(phase * 1.5)) * 180.0)
                self.ingestAccelerometerSample(SternumAccSample(xMillig: 12, yMillig: -970, zMillig: dipZ))
            }
        }
    }

    public func stopSimulation() {
        simulationTimer?.invalidate()
        simulationTimer = nil
    }

    /// Computes Root Mean Square of Successive Differences (RMSSD) in ms from RR intervals.
    public static func computeRMSSD(from rrIntervalsMs: [Double]) -> Double {
        guard rrIntervalsMs.count >= 2 else { return 0.0 }
        var sumSquaredDiffs = 0.0
        var count = 0
        for i in 1..<rrIntervalsMs.count {
            let diff = rrIntervalsMs[i] - rrIntervalsMs[i - 1]
            sumSquaredDiffs += diff * diff
            count += 1
        }
        guard count > 0 else { return 0.0 }
        return sqrt(sumSquaredDiffs / Double(count))
    }
}

// MARK: - PolarBleSdk Observer Conformances

#if canImport(PolarBleSdk)
extension PolarManager: PolarBleApiObserver, PolarBleApiDeviceInfoObserver, PolarBleApiPowerStateObserver {
    nonisolated public func deviceConnecting(_ identifier: PolarDeviceInfo) {
        let id = identifier.deviceId
        Task { @MainActor [weak self] in
            self?.connectionState = .connecting(deviceId: id)
        }
    }

    nonisolated public func deviceConnected(_ identifier: PolarDeviceInfo) {
        let id = identifier.deviceId
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.targetDeviceId = id
            self.connectionState = .connected(deviceId: id, batteryLevel: self.batteryLevel)
            if self.shouldMaintainStreamsOnReconnect {
                self.startHeartRateStream()
            }
            if self.isAccStreamRequested {
                self.startAccStream(sampleRateHz: self.preferredAccSampleRateHz)
            }
        }
    }

    nonisolated public func deviceDisconnected(_ identifier: PolarDeviceInfo, info: PolarBleDisconnectInfo) {
        let id = identifier.deviceId
        Task { @MainActor [weak self] in
            self?.handleUnexpectedDisconnect(deviceId: id)
        }
    }

    nonisolated public func batteryLevelReceived(_ identifier: String, batteryLevel: UInt) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.batteryLevel = batteryLevel
            if case .connected(let id, _) = self.connectionState {
                self.connectionState = .connected(deviceId: id, batteryLevel: batteryLevel)
            }
        }
    }

    nonisolated public func batteryChargingStatusReceived(
        _ identifier: String,
        chargingStatus: BleBasClient.ChargeState
    ) {}

    nonisolated public func disInformationReceivedWithKeysAsStrings(
        _ identifier: String,
        key: String,
        value: String
    ) {}

    nonisolated public func disInformationReceived(_ identifier: String, uuid: CBUUID, value: String) {}

    nonisolated public func blePowerOn() {}

    nonisolated public func blePowerOff() {
        Task { @MainActor [weak self] in
            self?.connectionState = .disconnected
        }
    }
}
#endif

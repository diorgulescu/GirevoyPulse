import Foundation

/// Girevoy Sport competitive disciplines.
public enum GSDiscipline: String, CaseIterable, Identifiable, Codable, Sendable {
    case snatch = "Snatch"
    case jerk = "Jerk"
    case longCycle = "Long Cycle"

    public var id: String { rawValue }

    public var shortName: String {
        switch self {
        case .snatch: return "SNATCH"
        case .jerk: return "JERK"
        case .longCycle: return "TALC"
        }
    }

    public var description: String {
        switch self {
        case .snatch:
            return "Single-bell continuous swing overhead (1 hand switch in 10m rank set)"
        case .jerk:
            return "Short Cycle — rack launch to overhead lockout"
        case .longCycle:
            return "Clean & Jerk — swing, rack, and overhead fixation every rep"
        }
    }

    public var defaultTargetRPM: Double {
        switch self {
        case .snatch: return 16.0
        case .jerk: return 12.0
        case .longCycle: return 8.0
        }
    }
}

/// Active lifting side for single-bell or double-bell analysis.
public enum ActiveSide: String, CaseIterable, Identifiable, Codable, Sendable {
    case left = "LEFT"
    case right = "RIGHT"
    case both = "BOTH"

    public var id: String { rawValue }

    public mutating func toggleHand() {
        switch self {
        case .left: self = .right
        case .right: self = .left
        case .both: self = .both
        }
    }
}

/// IKMF Marathon duration presets.
public enum MarathonDuration: Int, CaseIterable, Identifiable, Codable, Sendable {
    case halfMarathon30Min = 1800 // 30:00
    case fullMarathon60Min = 3600 // 60:00

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .halfMarathon30Min: return "Half-Marathon (30:00)"
        case .fullMarathon60Min: return "Full Marathon (60:00)"
        }
    }

    public var totalSeconds: TimeInterval {
        TimeInterval(rawValue)
    }
}

/// High-level timer mode category for the Setup Sheet.
public enum GSTimerCategory: String, CaseIterable, Identifiable, Codable, Sendable {
    case traditional10Min = "10-Min Traditional"
    case marathon = "IKMF Marathon"
    case intervals = "Intervals / Sprint"

    public var id: String { rawValue }
}

/// Detailed configuration for `GSTimerEngine`.
public enum GSTimerMode: Equatable, Codable, Sendable {
    /// Traditional 10:00 Girevoy Sport rank set (600 seconds) with RPM metronome and 1-minute split tracking.
    case traditional10Min(targetRPM: Double)

    /// IKMF Half-Marathon (30:00) or Full Marathon (60:00) with configurable hand switch interval in minutes.
    case marathon(duration: MarathonDuration, switchIntervalMinutes: Int, targetRPM: Double)

    /// Customizable Work/Rest intervals (e.g., 60s work / 60s rest x 5 rounds).
    case intervals(workSeconds: TimeInterval, restSeconds: TimeInterval, rounds: Int, targetRPM: Double)

    public var targetRPM: Double {
        switch self {
        case .traditional10Min(let rpm),
             .marathon(_, _, let rpm),
             .intervals(_, _, _, let rpm):
            return rpm
        }
    }

    public var totalPlannedDuration: TimeInterval {
        switch self {
        case .traditional10Min:
            return 600.0
        case .marathon(let duration, _, _):
            return duration.totalSeconds
        case .intervals(let work, let rest, let rounds, _):
            guard rounds > 0 else { return 0 }
            // Final round does not require trailing rest
            return (work * Double(rounds)) + (rest * Double(max(0, rounds - 1)))
        }
    }

    /// Seconds per rep for the configured target RPM, or nil if RPM <= 0.
    public var metronomePeriodSeconds: TimeInterval? {
        let rpm = targetRPM
        guard rpm > 0 else { return nil }
        return 60.0 / rpm
    }
}

/// Represents a 1-minute split record logged during a set.
public struct MinuteSplit: Identifiable, Equatable, Codable, Sendable {
    public let minuteIndex: Int // 1-based minute index (1...60)
    public let repsInMinute: Int
    public let cumulativeReps: Int
    public let averageHeartRate: Int?

    public var id: Int { minuteIndex }

    public init(minuteIndex: Int, repsInMinute: Int, cumulativeReps: Int, averageHeartRate: Int? = nil) {
        self.minuteIndex = minuteIndex
        self.repsInMinute = repsInMinute
        self.cumulativeReps = cumulativeReps
        self.averageHeartRate = averageHeartRate
    }
}

/// Current phase of the timer (Work vs Rest in interval mode).
public enum TimerIntervalPhase: Equatable, Sendable {
    case work(round: Int, totalRounds: Int)
    case rest(round: Int, totalRounds: Int)
    case completed

    public var isWorkPhase: Bool {
        if case .work = self { return true }
        return false
    }

    public var label: String {
        switch self {
        case .work(let round, let total):
            return total > 1 ? "WORK R\(round)/\(total)" : "WORK SET"
        case .rest(let round, let total):
            return "REST R\(round)/\(total)"
        case .completed:
            return "COMPLETE"
        }
    }
}

/// Distinct audio/haptic cues emitted by `GSTimerEngine` and `GSFixationDetector`.
public enum GSAudioCue: Equatable, Sendable {
    case metronomeTick
    case fixationValidated
    case handSwitchAlert
    case finalTenSecondTick(secondsRemaining: Int)
    case intervalPhaseTransition(isWork: Bool)
    case setCompleted
}

/// Heart Rate Zone classification for Girevoy Sport endurance pacing.
public enum HeartRateZone: Int, CaseIterable, Sendable {
    case zone1Recovery = 1
    case zone2Aerobic = 2
    case zone3Tempo = 3
    case zone4Threshold = 4
    case zone5Redline = 5

    public static func zone(for bpm: Int, maxHR: Int = 190) -> HeartRateZone {
        guard maxHR > 0, bpm > 0 else { return .zone1Recovery }
        let ratio = Double(bpm) / Double(maxHR)
        switch ratio {
        case ..<0.60: return .zone1Recovery
        case 0.60..<0.70: return .zone2Aerobic
        case 0.70..<0.80: return .zone3Tempo
        case 0.80..<0.90: return .zone4Threshold
        default: return .zone5Redline
        }
    }

    public var title: String {
        switch self {
        case .zone1Recovery: return "Z1 RECOVERY"
        case .zone2Aerobic: return "Z2 AEROBIC"
        case .zone3Tempo: return "Z3 TEMPO"
        case .zone4Threshold: return "Z4 THRESHOLD"
        case .zone5Redline: return "Z5 REDLINE"
        }
    }
}

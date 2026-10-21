import AVFoundation
import AudioToolbox
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Protocol for playing Girevoy Sport audio & haptic cues (enables deterministic unit testing).
public protocol GSAudioCuePlaying: Sendable {
    func play(_ cue: GSAudioCue)
}

/// Synthesizes low-latency PCM audio cues (woodblock metronome, fixation double-chime,
/// hand-switch horn, and final 10s countdown ticks) and triggers tactile haptics.
public final class GSAudioFeedbackEngine: GSAudioCuePlaying, @unchecked Sendable {
    public static let shared = GSAudioFeedbackEngine()

    private let audioQueue = DispatchQueue(label: "com.girevoypulse.audioqueue", qos: .userInteractive)
    private var players: [String: AVAudioPlayer] = [:]
    public var isMuted: Bool = false

    public init() {
        audioQueue.async { [weak self] in
            self?.configureAudioSession()
            self?.preloadSynthesizedBuffers()
        }
    }

    public func play(_ cue: GSAudioCue) {
        guard !isMuted else { return }

        #if canImport(UIKit)
        Task { @MainActor in
            self.triggerHaptic(for: cue)
        }
        #endif

        audioQueue.async { [weak self] in
            guard let self else { return }
            let key = self.bufferKey(for: cue)
            if let player = self.players[key] {
                if player.isPlaying {
                    player.currentTime = 0
                } else {
                    player.play()
                }
            } else {
                AudioServicesPlaySystemSound(1104)
            }
        }
    }

    // MARK: - Audio Session & Synthesis

    private func configureAudioSession() {
        #if os(iOS)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            // Non-fatal in simulator or restricted audio environments
        }
        #endif
    }

    private func bufferKey(for cue: GSAudioCue) -> String {
        switch cue {
        case .metronomeTick: return "metronomeTick"
        case .fixationValidated: return "fixationValidated"
        case .handSwitchAlert: return "handSwitchAlert"
        case .finalTenSecondTick: return "finalTenSecondTick"
        case .intervalPhaseTransition(let isWork): return isWork ? "phaseWork" : "phaseRest"
        case .setCompleted: return "setCompleted"
        }
    }

    private func preloadSynthesizedBuffers() {
        // 1. Subtle woodblock metronome tick (short resonant percussion at 820 Hz + 1240 Hz overtone)
        registerTone(
            key: "metronomeTick",
            segments: [(frequency: 820.0, duration: 0.045, amplitude: 0.45, decay: 38.0)]
        )

        // 2. Fixation Validated chime (bright ascending dual-tone C6 -> G6)
        registerTone(
            key: "fixationValidated",
            segments: [
                (frequency: 1046.5, duration: 0.065, amplitude: 0.65, decay: 18.0),
                (frequency: 1567.98, duration: 0.11, amplitude: 0.75, decay: 14.0)
            ]
        )

        // 3. Hand Switch Alert (3-pulse urgent attention chime at 880 Hz)
        registerTone(
            key: "handSwitchAlert",
            segments: [
                (frequency: 880.0, duration: 0.09, amplitude: 0.80, decay: 12.0),
                (frequency: 0.0, duration: 0.03, amplitude: 0.0, decay: 1.0),
                (frequency: 880.0, duration: 0.09, amplitude: 0.80, decay: 12.0),
                (frequency: 0.0, duration: 0.03, amplitude: 0.0, decay: 1.0),
                (frequency: 1174.66, duration: 0.16, amplitude: 0.85, decay: 9.0)
            ]
        )

        // 4. Final 10-second countdown tick (crisp 987.77 Hz B5 beep)
        registerTone(
            key: "finalTenSecondTick",
            segments: [(frequency: 987.77, duration: 0.075, amplitude: 0.65, decay: 20.0)]
        )

        // 5. Interval Phase Transitions & Completion
        registerTone(
            key: "phaseWork",
            segments: [
                (frequency: 659.25, duration: 0.08, amplitude: 0.7, decay: 12.0),
                (frequency: 1318.51, duration: 0.16, amplitude: 0.8, decay: 8.0)
            ]
        )
        registerTone(
            key: "phaseRest",
            segments: [
                (frequency: 880.0, duration: 0.08, amplitude: 0.6, decay: 12.0),
                (frequency: 440.0, duration: 0.16, amplitude: 0.6, decay: 10.0)
            ]
        )
        registerTone(
            key: "setCompleted",
            segments: [
                (frequency: 523.25, duration: 0.09, amplitude: 0.75, decay: 10.0),
                (frequency: 659.25, duration: 0.09, amplitude: 0.75, decay: 10.0),
                (frequency: 783.99, duration: 0.09, amplitude: 0.75, decay: 10.0),
                (frequency: 1046.50, duration: 0.24, amplitude: 0.85, decay: 6.0)
            ]
        )
    }

    private func registerTone(
        key: String,
        segments: [(frequency: Double, duration: Double, amplitude: Double, decay: Double)]
    ) {
        let wavData = Self.makeWAVData(segments: segments)
        if let player = try? AVAudioPlayer(data: wavData) {
            player.prepareToPlay()
            players[key] = player
        }
    }

    /// Generates an in-memory 16-bit mono 44.1 kHz WAV file from tone segments.
    internal static func makeWAVData(
        segments: [(frequency: Double, duration: Double, amplitude: Double, decay: Double)],
        sampleRate: Int = 44_100
    ) -> Data {
        var samples: [Int16] = []
        for seg in segments {
            let count = max(1, Int(seg.duration * Double(sampleRate)))
            for i in 0..<count {
                let t = Double(i) / Double(sampleRate)
                guard seg.frequency > 0, seg.amplitude > 0 else {
                    samples.append(0)
                    continue
                }
                let envelope = exp(-seg.decay * t)
                let fundamental = sin(2.0 * .pi * seg.frequency * t)
                let overtone = 0.25 * sin(2.0 * .pi * (seg.frequency * 1.5) * t)
                let raw = (fundamental + overtone) * 0.8 * seg.amplitude * envelope
                let clamped = max(-1.0, min(1.0, raw))
                samples.append(Int16(clamped * Double(Int16.max)))
            }
        }

        let dataSize = UInt32(samples.count * MemoryLayout<Int16>.size)
        let fileSize = 36 + dataSize
        var header = Data()
        header.append(contentsOf: "RIFF".utf8)
        header.append(contentsOf: Self.leBytes(fileSize))
        header.append(contentsOf: "WAVE".utf8)
        header.append(contentsOf: "fmt ".utf8)
        header.append(contentsOf: Self.leBytes(UInt32(16))) // PCM chunk size
        header.append(contentsOf: Self.leBytes(UInt16(1)))  // PCM format
        header.append(contentsOf: Self.leBytes(UInt16(1)))  // Mono
        header.append(contentsOf: Self.leBytes(UInt32(sampleRate)))
        header.append(contentsOf: Self.leBytes(UInt32(sampleRate * 2))) // Byte rate
        header.append(contentsOf: Self.leBytes(UInt16(2)))  // Block align
        header.append(contentsOf: Self.leBytes(UInt16(16))) // Bits per sample
        header.append(contentsOf: "data".utf8)
        header.append(contentsOf: Self.leBytes(dataSize))

        samples.withUnsafeBufferPointer { ptr in
            header.append(UnsafeBufferPointer(
                start: UnsafeRawPointer(ptr.baseAddress)?.assumingMemoryBound(to: UInt8.self),
                count: Int(dataSize)
            ))
        }
        return header
    }

    private static func leBytes<T: FixedWidthInteger>(_ value: T) -> [UInt8] {
        withUnsafeBytes(of: value.littleEndian) { Array($0) }
    }

    #if canImport(UIKit)
    @MainActor
    private func triggerHaptic(for cue: GSAudioCue) {
        switch cue {
        case .fixationValidated:
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        case .handSwitchAlert, .setCompleted:
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.warning)
        case .finalTenSecondTick:
            let generator = UIImpactFeedbackGenerator(style: .rigid)
            generator.impactOccurred()
        case .metronomeTick, .intervalPhaseTransition:
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.impactOccurred(intensity: 0.6)
        }
    }
    #endif
}

# GirevoyPulse — Cross-Platform iOS & Android Girevoy Sport Training Assistant

[![GirevoyPulse CI](https://github.com/diorgulescu/GirevoyPulse/actions/workflows/ci.yml/badge.svg)](https://github.com/diorgulescu/GirevoyPulse/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**GirevoyPulse** is a native **iOS 17+ (Swift 6 / SwiftUI)** and **Android 8.0+ (Kotlin 2.0 / Jetpack Compose)** training and competition assistant built for **Kettlebell Sport (Girevoy Sport / GS)** athletes across **Snatch**, **Jerk (Short Cycle)**, and **Long Cycle (TALC)**.

Both **Xcode** (`GirevoyPulse.xcodeproj` / `Package.swift`) and **Android Studio** (`settings.gradle.kts` / `:android-app`) projects coexist at the root of this repository with a 1:1 mirrored Clean Architecture.

---

## Core Functional Pillars

### 1. High-Precision Monotonic Timing Engine (`GSTimerCoreEngine` / `GSTimerEngine`)
* Anchored to hardware monotonic time (`CACurrentMediaTime()` on iOS; `SystemClock.elapsedRealtimeNanos()` on Android) and polled at 100 Hz so CPU/GPU video processing never introduces timer drift.
* **Traditional 10-Minute Rank Set:** 10:00 countdown & elapsed timer, target RPM woodblock cadence metronome, and per-minute split tracker (`MinuteSplit`).
* **IKMF Marathon / Half-Marathon:** Continuous 30:00 or 60:00 clock with configurable hand-switch alerts (e.g., every 1, 3, or 5 minutes).
* **Interval / Sprint Mode:** Customizable Work/Rest cycles (e.g., 1:00 on / 1:00 off × $N$ rounds).
* **Synthesized Audio & Haptics (`GSAudioFeedbackEngine`):** Zero-latency in-memory 16-bit 44.1 kHz PCM synthesis (`AVAudioPlayer` on iOS; `AudioTrack` `MODE_STATIC` on Android) for woodblock metronome ticks, ascending fixation validation chimes, 3-pulse hand-switch alerts, and final 10-second countdown beeps.

### 2. Real-Time Computer Vision & Fixation Pipeline (`CameraManager` / `PoseEstimator` / `GSFixationStateMachine`)
* **1080p Selfie Pipeline:** Captures video on a dedicated background queue (`com.girevoypulse.visionqueue`) with configurable frame throttling (`frameStride = 2`, ~20–30 Hz) to prevent thermal throttling during 10-to-60 minute sets.
* **Biomechanical Lockout Verification:**
  * Active arm elbow angle $\ge 168^\circ$
  * Both knees angle $\ge 172^\circ$
  * Hip extension angle $\ge 170^\circ$
  * Active wrist vertical velocity $\left|\frac{\Delta y}{\Delta t}\right| < \epsilon$
  * Overhead pause held stably for $\ge 300\text{ ms}$

### 3. Polar H10 BLE Telemetry (`PolarManager`)
* Integrates [`PolarBleSdk`](https://github.com/polarofficial/polar-ble-sdk) on both iOS (SPM `>= 8.3.0`) and Android (`com.github.polarofficial:polar-ble-sdk:5.16.0`) with `feature_hr`, `feature_polar_online_streaming`, and `feature_battery_info`.
* Streams real-time **Heart Rate (BPM)**, **RR intervals (ms)** with rolling **RMSSD HRV strain**, and **25 Hz / 50 Hz 3-axis sternum accelerometer** telemetry to measure sagittal thoracic dip during the jerk dip/drive.
* Handles unexpected BLE disconnects via automatic `.reconnecting` recovery without interrupting the active workout timer.

---

## Unified Repository Structure (Xcode & Android Studio)

| Layer | iOS (Swift 6 / Xcode) | Android (Kotlin 2.0 / Android Studio) |
| :--- | :--- | :--- |
| **IDE Entry Point** | `GirevoyPulse.xcodeproj` & `Package.swift` | `settings.gradle.kts`, `build.gradle.kts`, `android-app/` |
| **Domain Core (Pure Logic)** | `GirevoyPulse/Domain/` (`Foundation`-only) | `android-app/src/main/java/com/girevoypulse/app/domain/` (Pure Kotlin) |
| **Camera Adapter** | `GirevoyPulse/Core/Vision/CameraManager.swift` (`AVCaptureSession`) | `.../core/vision/CameraManager.kt` (`CameraX` `ImageAnalysis`) |
| **Pose Estimation Adapter** | `GirevoyPulse/Core/Vision/PoseEstimator.swift` (`VNDetectHumanBodyPoseRequest`) | `.../core/vision/PoseEstimator.kt` (Google `MediaPipe Pose Landmarker`) |
| **Monotonic Timer Adapter** | `GirevoyPulse/Core/Timing/GSTimerEngine.swift` (`DispatchSourceTimer` + `CACurrentMediaTime()`) | `.../core/timing/GSTimerEngine.kt` (Coroutines + `SystemClock.elapsedRealtimeNanos()`) |
| **Polar BLE Adapter** | `GirevoyPulse/Core/BLE/PolarManager.swift` (`PolarBleSdk`) | `.../core/ble/PolarManager.kt` (`PolarBleSdk`) |
| **Distance HUD & Setup UI** | `GirevoyPulse/Presentation/` (SwiftUI) | `.../presentation/` (Jetpack Compose) |
| **Unit Tests** | `GirevoyPulseTests/` (`XCTest`) | `android-app/src/test/java/com/girevoypulse/app/` (`JUnit 4`) |

---

## Building & Running Tests

### iOS (Xcode / SwiftPM)
```bash
# Run Swift Package unit tests
swift test

# Build iOS application for Simulator
xcodebuild build \
  -project GirevoyPulse.xcodeproj \
  -scheme GirevoyPulse \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO
```

### Android (Android Studio / Gradle)
Open the repository root folder directly in **Android Studio**, or run via Gradle CLI:
```bash
gradle :android-app:testDebugUnitTest :android-app:assembleDebug
```

---

## License

Copyright (C) 2026 Dragos Iorgulescu & GirevoyPulse Contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

See the [LICENSE](LICENSE) file for the full text of the GNU General Public License v3.0.

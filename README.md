# GirevoyPulse — Native iOS Girevoy Sport Training Assistant

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

**GirevoyPulse** is a native iOS 17+ training and competition assistant built for **Kettlebell Sport (Girevoy Sport / GS)** athletes across **Snatch**, **Jerk (Short Cycle)**, and **Long Cycle (TALC)**.

---

## Core Functional Pillars

### 1. High-Precision Monotonic Timing Engine (`GSTimerCoreEngine` / `GSTimerEngine`)
* Anchored to hardware monotonic time (`CACurrentMediaTime()` on iOS; `SystemClock.elapsedRealtimeNanos()` on Android) and driven by a 100 Hz `DispatchSourceTimer` so video processing never introduces timer drift.
* **Traditional 10-Minute Rank Set:** 10:00 countdown & elapsed timer, target RPM woodblock cadence metronome, and per-minute split tracker (`[MinuteSplit]`).
* **IKMF Marathon / Half-Marathon:** Continuous 30:00 or 60:00 clock with configurable hand-switch alerts (e.g., every 1, 3, or 5 minutes).
* **Interval / Sprint Mode:** Customizable Work/Rest cycles (e.g., 1:00 on / 1:00 off × $N$ rounds).
* **Synthesized Audio & Haptics (`GSAudioFeedbackEngine`):** Zero-latency in-memory PCM WAV synthesis for woodblock metronome ticks, ascending fixation validation chimes, 3-pulse hand-switch alerts, and final 10-second countdown beeps.

### 2. Real-Time Computer Vision & Fixation Pipeline (`CameraManager` / `PoseEstimator` / `GSFixationStateMachine`)
* **1080p Selfie Pipeline:** Captures video on a dedicated background queue (`com.girevoypulse.visionqueue`) with configurable frame throttling (`frameStride = 2`, ~20–30 Hz) to prevent thermal throttling during 10-to-60 minute sets.
* **Biomechanical Lockout Verification:**
  * Active arm elbow angle $\ge 168^\circ$
  * Both knees angle $\ge 172^\circ$
  * Hip extension angle $\ge 170^\circ$
  * Active wrist vertical velocity $\left|\frac{\Delta y}{\Delta t}\right| < \epsilon$
  * Overhead pause held stably for $\ge 300\text{ ms}$

### 3. Polar H10 BLE Telemetry (`PolarManager`)
* Integrates [`PolarBleSdk`](https://github.com/polarofficial/polar-ble-sdk) (`>= 8.3.0`) with `feature_hr`, `feature_polar_online_streaming`, and `feature_battery_info`.
* Streams real-time **Heart Rate (BPM)**, **RR intervals (ms)** with rolling **RMSSD HRV strain**, and **25 Hz / 50 Hz 3-axis sternum accelerometer** telemetry to measure sagittal thoracic dip during the jerk dip/drive.
* Handles unexpected BLE disconnects via automatic `.reconnecting` recovery without interrupting the active workout timer.

---

## Cross-Platform Clean Architecture (iOS & Android Ready)

All core Girevoy Sport business logic in `GirevoyPulse/Domain/` imports **only `Foundation`** (zero dependencies on `Vision`, `AVFoundation`, `CoreGraphics`, `QuartzCore`, or `Combine`) for 1:1 portability to Android Kotlin:

| Layer | Directory | Responsibility | Android Equivalent |
| :--- | :--- | :--- | :--- |
| **Domain Core** | `GirevoyPulse/Domain/` | `GSTimerCoreEngine`, `GSFixationStateMachine`, `PoseGeometry`, `BodyPoseFrame` (`CoordinateOrigin.bottomLeft` & `.topLeft`), `WorkoutModels` | 1:1 Kotlin `domain` module |
| **Platform Adapters** | `GirevoyPulse/Core/` | `CameraManager` (`AVCaptureSession`), `PoseEstimator` (`VNDetectHumanBodyPoseRequest`), `GSTimerEngine` (`DispatchSourceTimer`), `PolarManager` (`PolarBleSdk`) | `CameraX`, Google `MediaPipe Pose`, Coroutine Ticker, Android `polar-ble-sdk` |
| **Presentation (HUD)** | `GirevoyPulse/Presentation/` | 3-meter distance-optimized SwiftUI HUD (`LiveWorkoutView`, `SkeletalOverlayView`, `WorkoutSetupSheet`, `WorkoutViewModel`) | Jetpack Compose HUD |

---

## Building & Running Tests

### Run Core Domain & State Machine Unit Tests
```bash
swift test
```

### Build iOS Application for Simulator
```bash
xcodebuild build \
  -project GirevoyPulse.xcodeproj \
  -scheme GirevoyPulse \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO
```

---

## License

Copyright (C) 2026 Dragos Iorgulescu & GirevoyPulse Contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

See the [LICENSE](LICENSE) file for the full text of the GNU General Public License v3.0.

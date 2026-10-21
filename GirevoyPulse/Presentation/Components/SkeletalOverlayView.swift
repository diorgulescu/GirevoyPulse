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

/// Renders the lifter's normalized 2D skeleton over the live camera preview.
/// Bones and joints light up vibrant green when biomechanical lockout thresholds are met,
/// yellow during overhead holding/ascent, and coral/red when unlocked.
public struct SkeletalOverlayView: View {
    public let poseFrame: BodyPoseFrame?
    public let biomechanics: FixationBiomechanicsSnapshot
    public let liftPhase: LiftPhase

    public init(
        poseFrame: BodyPoseFrame?,
        biomechanics: FixationBiomechanicsSnapshot,
        liftPhase: LiftPhase
    ) {
        self.poseFrame = poseFrame
        self.biomechanics = biomechanics
        self.liftPhase = liftPhase
    }

    private static let torsoBones: [(GSJointName, GSJointName)] = [
        (.neck, .leftShoulder),
        (.neck, .rightShoulder),
        (.leftShoulder, .rightShoulder),
        (.leftShoulder, .leftHip),
        (.rightShoulder, .rightHip),
        (.leftHip, .rightHip)
    ]

    private static let armBones: [(GSJointName, GSJointName, ActiveSide)] = [
        (.leftShoulder, .leftElbow, .left),
        (.leftElbow, .leftWrist, .left),
        (.rightShoulder, .rightElbow, .right),
        (.rightElbow, .rightWrist, .right)
    ]

    private static let legBones: [(GSJointName, GSJointName)] = [
        (.leftHip, .leftKnee),
        (.leftKnee, .leftAnkle),
        (.rightHip, .rightKnee),
        (.rightKnee, .rightAnkle)
    ]

    public var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                guard let frame = poseFrame else { return }

                // Draw shoulder reference threshold line
                if let leftS = frame[.leftShoulder], let rightS = frame[.rightShoulder] {
                    let yScreen = (1.0 - CGFloat(max(leftS.y, rightS.y))) * size.height
                    var linePath = Path()
                    linePath.move(to: CGPoint(x: 0, y: yScreen))
                    linePath.addLine(to: CGPoint(x: size.width, y: yScreen))
                    context.stroke(
                        linePath,
                        with: .color(Color.white.opacity(0.22)),
                        style: StrokeStyle(lineWidth: 1.5, dash: [8, 6])
                    )
                }

                // 1. Torso / Hip extension bones
                let torsoColor = boneColor(isLocked: biomechanics.isHipExtended)
                for (a, b) in Self.torsoBones {
                    drawBone(from: a, to: b, in: frame, size: size, color: torsoColor, context: &context)
                }

                // 2. Upper limb bones
                for (a, b, side) in Self.armBones {
                    let isActiveArm = (biomechanics.activeSide == side || biomechanics.activeSide == .both)
                    let color = isActiveArm
                        ? boneColor(isLocked: biomechanics.isElbowLocked && biomechanics.isOverhead)
                        : Color.cyan.opacity(0.45)
                    drawBone(
                        from: a,
                        to: b,
                        in: frame,
                        size: size,
                        color: color,
                        lineWidth: isActiveArm ? 6.0 : 3.5,
                        context: &context
                    )
                }

                // 3. Lower limb bones
                let legColor = boneColor(isLocked: biomechanics.areKneesLocked)
                for (a, b) in Self.legBones {
                    drawBone(from: a, to: b, in: frame, size: size, color: legColor, context: &context)
                }

                // 4. Joint nodes
                for joint in GSJointName.allCases {
                    guard let normalized = frame[joint] else { continue }
                    let pt = screenPoint(from: normalized, in: size)
                    let nodeColor = jointColor(for: joint)
                    let radius: CGFloat = (joint == .leftWrist || joint == .rightWrist) ? 8.5 : 6.0
                    let rect = CGRect(x: pt.x - radius, y: pt.y - radius, width: radius * 2, height: radius * 2)
                    context.fill(Path(ellipseIn: rect), with: .color(nodeColor))
                    context.stroke(Path(ellipseIn: rect), with: .color(.black.opacity(0.75)), lineWidth: 2.0)
                }
            }
            .opacity(0.82)
            .allowsHitTesting(false)
        }
    }

    private func screenPoint(from normalized: Point2D, in size: CGSize) -> CGPoint {
        // Normalized domain coordinates have (0,0) at bottom-left; SwiftUI has (0,0) at top-left
        CGPoint(
            x: CGFloat(normalized.x) * size.width,
            y: (1.0 - CGFloat(normalized.y)) * size.height
        )
    }

    private func drawBone(
        from a: GSJointName,
        to b: GSJointName,
        in frame: BodyPoseFrame,
        size: CGSize,
        color: Color,
        lineWidth: CGFloat = 5.0,
        context: inout GraphicsContext
    ) {
        guard let p1 = frame[a], let p2 = frame[b] else { return }
        var path = Path()
        path.move(to: screenPoint(from: p1, in: size))
        path.addLine(to: screenPoint(from: p2, in: size))
        context.stroke(
            path,
            with: .color(color),
            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
        )
    }

    private func boneColor(isLocked: Bool) -> Color {
        if liftPhase.isFixationConfirmed {
            return Color(red: 0.12, green: 0.96, blue: 0.42) // Vibrant fixation green
        }
        if isLocked {
            return Color(red: 0.22, green: 0.90, blue: 0.48).opacity(0.90)
        }
        if case .overheadHolding = liftPhase {
            return Color.yellow.opacity(0.90)
        }
        return Color(red: 1.0, green: 0.36, blue: 0.28).opacity(0.85)
    }

    private func jointColor(for joint: GSJointName) -> Color {
        switch joint {
        case .leftElbow, .rightElbow, .leftWrist, .rightWrist:
            return boneColor(isLocked: biomechanics.isElbowLocked && biomechanics.isOverhead)
        case .leftKnee, .rightKnee, .leftAnkle, .rightAnkle:
            return boneColor(isLocked: biomechanics.areKneesLocked)
        case .leftHip, .rightHip, .leftShoulder, .rightShoulder, .neck:
            return boneColor(isLocked: biomechanics.isHipExtended)
        }
    }
}

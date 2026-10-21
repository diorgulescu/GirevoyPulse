import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

@main
public struct GirevoyPulseApp: App {
    public init() {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = true
        #endif
    }

    public var body: some Scene {
        WindowGroup {
            LiveWorkoutView()
        }
    }
}

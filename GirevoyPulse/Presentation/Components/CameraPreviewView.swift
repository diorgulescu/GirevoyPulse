import AVFoundation
import SwiftUI

#if canImport(UIKit)
import UIKit

/// UIKit-backed `AVCaptureVideoPreviewLayer` host view that automatically resizes with SwiftUI layout.
public final class PreviewLayerHostView: UIView {
    public override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    public var videoPreviewLayer: AVCaptureVideoPreviewLayer {
        // Guaranteed by `layerClass` override
        layer as! AVCaptureVideoPreviewLayer
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        videoPreviewLayer.frame = bounds
    }
}

/// Full-screen SwiftUI wrapper around `AVCaptureSession` for live selfie camera feedback.
public struct CameraPreviewView: UIViewRepresentable {
    public let session: AVCaptureSession

    public init(session: AVCaptureSession) {
        self.session = session
    }

    public func makeUIView(context: Context) -> PreviewLayerHostView {
        let view = PreviewLayerHostView()
        view.backgroundColor = .black
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    public func updateUIView(_ uiView: PreviewLayerHostView, context: Context) {
        if uiView.videoPreviewLayer.session !== session {
            uiView.videoPreviewLayer.session = session
        }
    }
}
#else
public struct CameraPreviewView: View {
    public let session: AVCaptureSession
    public init(session: AVCaptureSession) { self.session = session }
    public var body: some View { Color.black }
}
#endif

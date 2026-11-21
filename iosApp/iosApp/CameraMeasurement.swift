import AVFoundation
import ComposeApp
import OSLog
import UIKit
import Vision

final class CameraMeasurement: NSObject, IosMeasurementOperation, AVCaptureVideoDataOutputSampleBufferDelegate {
    private static let capture = DispatchQueue(label: "ch.ciuin.measure.camera", qos: .userInitiated)
    private static let detection = DispatchQueue(label: "ch.ciuin.measure.people", qos: .userInitiated)
    private static let detectionSlot = DispatchSemaphore(value: 1)
    private static let log = Logger(subsystem: "ch.ciuin.app", category: "MeasurementCamera")

    private let sink: IosCameraSink
    private let orientation: CGImagePropertyOrientation
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private var camera: AVCaptureDevice?
    private var firstTimestamp: Double?
    private var exposureReady = false
    private var lastDetection = -Double.infinity
    private var runtimeError: NSObjectProtocol?
    private var running = false

    init(sink: IosCameraSink, orientation: UIInterfaceOrientation) {
        self.sink = sink
        switch orientation {
        case .landscapeLeft: self.orientation = .down
        case .landscapeRight: self.orientation = .up
        case .portraitUpsideDown: self.orientation = .left
        default: self.orientation = .right
        }
    }

    static func device() -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
    }

    func start() {
        Self.capture.async { self.configureAndStart() }
    }

    func stop() {
        Self.capture.async { self.stopOnCaptureQueue() }
    }

    private func configureAndStart() {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
              let camera = Self.device() else {
            sink.ended()
            return
        }

        do {
            let input = try AVCaptureDeviceInput(device: camera)
            guard session.canAddInput(input) else {
                sink.ended()
                return
            }
            session.automaticallyConfiguresApplicationAudioSession = false
            session.addInput(input)
            guard session.canAddOutput(output) else {
                sink.ended()
                return
            }
            session.addOutput(output)
            guard let pixelFormat = [
                kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ].first(where: output.availableVideoPixelFormatTypes.contains) else {
                sink.ended()
                return
            }
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: pixelFormat]
            output.alwaysDiscardsLateVideoFrames = true
            Self.configureFormat(camera)
            self.camera = camera

            output.setSampleBufferDelegate(self, queue: Self.capture)
            runtimeError = NotificationCenter.default.addObserver(
                forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil
            ) { [weak self] _ in
                self?.stop()
            }
            session.startRunning()
            running = session.isRunning
            if !running { stopOnCaptureQueue() }
        } catch {
            Self.log.error("Camera input setup failed")
            stopOnCaptureQueue()
        }
    }

    private static func configureFormat(_ camera: AVCaptureDevice) {
        let formats = camera.formats.flatMap { format -> [VideoFormat] in
            let size = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            guard size.width >= 640, size.width <= 1920 else { return [] }
            return format.videoSupportedFrameRateRanges.compactMap { range in
                let rate = min(60, range.maxFrameRate)
                guard rate >= range.minFrameRate else { return nil }
                let duration = rate == 60 ? CMTime(value: 1, timescale: 60) : range.minFrameDuration
                return VideoFormat(format: format, duration: duration, rate: rate, area: size.width * size.height)
            }
        }
        let best = formats.min { a, b in
            a.rate == b.rate ? a.area < b.area : a.rate > b.rate
        }

        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            if let best {
                camera.activeFormat = best.format
                camera.activeVideoMinFrameDuration = best.duration
                camera.activeVideoMaxFrameDuration = best.duration
            }
            if camera.isExposureModeSupported(.continuousAutoExposure) {
                camera.exposureMode = .continuousAutoExposure
            }
        } catch {
            Self.log.error("Camera format could not be configured")
        }
    }

    private func stopOnCaptureQueue() {
        running = false
        output.setSampleBufferDelegate(nil, queue: nil)
        if let runtimeError {
            NotificationCenter.default.removeObserver(runtimeError)
            self.runtimeError = nil
        }
        if session.isRunning { session.stopRunning() }
        camera = nil
        sink.ended()
    }

    func captureOutput(
        _ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection
    ) {
        guard running, let camera, let pixels = CMSampleBufferGetImageBuffer(sample) else { return }
        let seconds = CMSampleBufferGetPresentationTimeStamp(sample).seconds
        guard seconds.isFinite, seconds >= 0, seconds < Double(Int64.max) / 1e9 else { return }
        if firstTimestamp == nil { firstTimestamp = seconds }
        let warmup = seconds - (firstTimestamp ?? seconds)
        if !exposureReady, warmup >= 0.5 && (!camera.isAdjustingExposure || warmup >= 2.5) {
            exposureReady = Self.lockExposure(camera)
        }
        if exposureReady, let value = Self.meanLuminance(pixels) {
            sink.luminance(timestampNanos: Int64((seconds * 1e9).rounded()), value: value)
        }
    }

    private static func lockExposure(_ camera: AVCaptureDevice) -> Bool {
        guard camera.isExposureModeSupported(.locked) else { return true }
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }
            camera.exposureMode = .locked
            return true
        } catch {
            return false
        }
    }

    private static func meanLuminance(_ pixels: CVPixelBuffer) -> Double? {
        guard CVPixelBufferIsPlanar(pixels),
              CVPixelBufferLockBaseAddress(pixels, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let plane = CVPixelBufferGetBaseAddressOfPlane(pixels, 0) else { return nil }
        let width = CVPixelBufferGetWidthOfPlane(pixels, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixels, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
        let bytes = plane.assumingMemoryBound(to: UInt8.self)
        var sum = 0
        for y in 0..<24 {
            let row = (2 * y + 1) * height / 48 * stride
            for x in 0..<24 {
                sum += Int(bytes[row + (2 * x + 1) * width / 48])
            }
        }
        let mean = Double(sum) / 576
        if CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange {
            return min(1, max(0, (mean - 16) / 219))
        }
        return mean / 255
    }

    private func detectPeople(_ pixels: CVPixelBuffer) {
        Self.detection.async { [sink, orientation] in
            defer { Self.detectionSlot.signal() }
            let request = VNDetectHumanRectanglesRequest()
            request.upperBodyOnly = true
            let handler = VNImageRequestHandler(cvPixelBuffer: pixels, orientation: orientation, options: [:])
            let start = DispatchTime.now().uptimeNanoseconds
            do {
                try handler.perform([request])
                let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6
                if let people = request.results {
                    sink.people(count: Int32(people.count), inferenceMs: elapsed)
                }
            } catch {
                Self.log.debug("Vision person detection did not complete")
            }
        }
    }
}

private struct VideoFormat {
    let format: AVCaptureDevice.Format
    let duration: CMTime
    let rate: Double
    let area: Int32
}

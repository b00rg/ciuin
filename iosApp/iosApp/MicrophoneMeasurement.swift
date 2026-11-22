import AVFoundation
import ComposeApp
import OSLog

final class MicrophoneMeasurement: NSObject, IosMeasurementOperation {
    private static let control = DispatchQueue(label: "ch.ciuin.measure.microphone", qos: .userInitiated)
    private static let log = Logger(subsystem: "ch.ciuin.app", category: "MeasurementMicrophone")

    private let engine = AVAudioEngine()
    private let sink: IosMicrophoneSink
    private var hasTap = false
    private var sessionActive = false
    private var interruption: NSObjectProtocol?

    init(sink: IosMicrophoneSink) {
        self.sink = sink
    }

    func start() {
        Self.control.async { self.startOnControlQueue() }
    }

    func stop() {
        Self.control.async { self.stopOnControlQueue() }
    }

    private func startOnControlQueue() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setActive(true)
            sessionActive = true

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.channelCount > 0, format.commonFormat == .pcmFormatFloat32,
                  (8_000...192_000).contains(format.sampleRate) else {
                stopOnControlQueue()
                return
            }
            let windows = AudioWindows(sink: sink, sampleRate: Int(format.sampleRate.rounded()))
            input.installTap(onBus: 0, bufferSize: 1024, format: nil) { buffer, _ in
                windows.accept(buffer)
            }
            hasTap = true
            interruption = NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification, object: session, queue: nil
            ) { [weak self] notification in
                if notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                    self?.stop()
                }
            }
            try engine.start()
        } catch {
            Self.log.error("Microphone input could not start")
            stopOnControlQueue()
        }
    }

    private func stopOnControlQueue() {
        if let interruption {
            NotificationCenter.default.removeObserver(interruption)
            self.interruption = nil
        }
        if hasTap {
            engine.inputNode.removeTap(onBus: 0)
            hasTap = false
        }
        engine.stop()
        if sessionActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            sessionActive = false
        }
        sink.ended()
    }
}

private final class AudioWindows {
    private static let analysis = DispatchQueue(label: "ch.ciuin.measure.sound-level", qos: .userInitiated)
    private static let analysisSlot = DispatchSemaphore(value: 1)

    private let sink: IosMicrophoneSink
    private let sampleRate: Int
    private var window: AudioWindow
    private var offset = 0

    init(sink: IosMicrophoneSink, sampleRate: Int) {
        self.sink = sink
        self.sampleRate = sampleRate
        window = AudioWindow(sampleRate: sampleRate)
    }

    func accept(_ buffer: AVAudioPCMBuffer) {
        guard buffer.format.sampleRate.rounded() == Double(sampleRate),
              let channels = buffer.floatChannelData else { return }
        let count = Int(buffer.format.channelCount)
        let stride = buffer.stride
        for frame in 0..<Int(buffer.frameLength) {
            var sum: Float = 0
            for channel in 0..<count {
                sum += channels[channel][frame * stride]
            }
            let sample = sum / Float(count)
            window.samples[offset] = sample.isFinite ? min(1, max(-1, sample)) : 0
            offset += 1
            if offset == sampleRate {
                emit()
                offset = 0
            }
        }
    }

    private func emit() {
        guard Self.analysisSlot.wait(timeout: .now()) == .success else {
            window.clear()
            return
        }
        let completed = window
        window = AudioWindow(sampleRate: sampleRate)
        Self.analysis.async { [sink, sampleRate] in
            defer { Self.analysisSlot.signal() }
            completed.withBytes { bytes in
                sink.window(samples: bytes, sampleRate: Int32(sampleRate))
            }
        }
    }
}

private final class AudioWindow {
    var samples: [Float]

    init(sampleRate: Int) {
        samples = [Float](repeating: 0, count: sampleRate)
    }

    deinit {
        clear()
    }

    func withBytes(_ analyze: (Data) -> Void) {
        samples.withUnsafeMutableBytes { bytes in
            guard let base = bytes.baseAddress else { return }
            // The Kotlin callback copies synchronously. This view is valid only for that call.
            analyze(Data(bytesNoCopy: base, count: bytes.count, deallocator: .none))
        }
    }

    func clear() {
        samples.withUnsafeMutableBytes { bytes in
            if let base = bytes.baseAddress {
                memset(base, 0, bytes.count)
            }
        }
    }
}

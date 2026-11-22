import AVFoundation
import ComposeApp
import UIKit

final class MeasurementDevices: NSObject, IosMeasurementDevices {
    private weak var camera: CameraMeasurement?
    private weak var microphone: MicrophoneMeasurement?

    func requestPermissions(sink: IosPermissionSink) {
        requestCamera { camera in
            DispatchQueue.main.async {
                guard UIApplication.shared.applicationState != .background else {
                    sink.complete(camera: camera, microphone: false)
                    return
                }
                Self.requestMicrophone { microphone in
                    sink.complete(camera: camera, microphone: microphone)
                }
            }
        }
    }

    func startCamera(sink: IosCameraSink) -> IosMeasurementOperation {
        let orientation = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?.interfaceOrientation ?? .portrait
        let camera = CameraMeasurement(sink: sink, orientation: orientation)
        self.camera = camera
        camera.start()
        return camera
    }

    func startMicrophone(sink: IosMicrophoneSink) -> IosMeasurementOperation {
        let microphone = MicrophoneMeasurement(sink: sink)
        self.microphone = microphone
        microphone.start()
        return microphone
    }

    func stopAll() {
        camera?.stop()
        microphone?.stop()
    }

    private func requestCamera(completion: @escaping (Bool) -> Void) {
        // Without a camera there is no system permission to request. The camera flow ends empty.
        guard CameraMeasurement.device() != nil else {
            completion(true)
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        default:
            completion(false)
        }
    }

    private static func requestMicrophone(completion: @escaping (Bool) -> Void) {
        if #available(iOS 17, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: completion)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(completion)
        }
    }
}

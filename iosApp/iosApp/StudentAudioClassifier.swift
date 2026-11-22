import ComposeApp
import Foundation
import LiteRT
import OSLog

final class StudentAudioClassifier: NSObject, IosAudioClassifier {
    private static let inputSize = 15_600
    private static let classCount = 9
    private static let log = Logger(subsystem: "ch.ciuin.app", category: "StudentAudioClassifier")

    private let model: CompiledModel
    private let inputs: [TensorBuffer]
    private let outputs: [TensorBuffer]

    static func modelPath(variant: String) -> String? {
        guard variant == "S" || variant == "M" else { return nil }
        return Bundle.main.path(forResource: "audio-student-\(variant.lowercased())", ofType: "tflite")
    }

    init(variant: String) throws {
        guard let path = Self.modelPath(variant: variant) else { throw ModelError.missingModel }
        let options = try Options()
        try options.setHardwareAccelerators([.cpu])
        let cpu = try CpuOptions()
        try cpu.setKernelMode(.builtin)
        try cpu.setNumThreads(1)
        try options.addConcreteOptions(cpu)
        let model = try CompiledModel(filePath: path, environment: Environment(), options: options)
        let input = try model.inputTensorType(inputIndex: 0)
        let output = try model.outputTensorType(outputIndex: 0)
        guard try model.inputCount() == 1, try model.outputCount() == 1,
              input.elementType == .float32, input.layout.dimensions == [1, Self.inputSize],
              output.elementType == .float32, output.layout.dimensions == [1, Self.classCount] else {
            throw ModelError.invalidSignature
        }
        self.model = model
        self.inputs = try model.createInputBuffers()
        self.outputs = try model.createOutputBuffers()
    }

    func classify(window: Data) -> Data? {
        guard window.count == Self.inputSize * MemoryLayout<Float>.size else { return nil }
        do {
            try inputs[0].write(window)
            try model.run(inputs: inputs, outputs: outputs)
            return try outputs[0].read().prefix(Self.classCount * MemoryLayout<Float>.size)
        } catch {
            Self.log.error("Student audio inference failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    private enum ModelError: Error {
        case missingModel
        case invalidSignature
    }
}

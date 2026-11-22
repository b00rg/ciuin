import Foundation
import XCTest
@testable import iosApp

final class AudioStudentTests: XCTestCase {
    private let sampleCount = 15_600
    private let outputCount = 9
    private let classes = [
        "speech_crowd", "music", "traffic", "siren", "construction",
        "alarm_beep", "dog", "machinery_hum", "other",
    ]

    func testStudentSMatchesTheModelHandoff() throws {
        let reference = try resource("expected", "json", in: "test_clips")
        try assertModel("S", matches: reference)
    }

    func testStudentMMatchesHostLiteRT() throws {
        let reference = try resource("audio-student-m-expected", "json", in: "Fixtures")
        try assertModel("M", matches: reference)
    }

    private func assertModel(_ variant: String, matches reference: URL) throws {
        let expected = try JSONDecoder().decode(Handoff.self, from: Data(contentsOf: reference))
        XCTAssertEqual(expected.model, "audio-student-\(variant.lowercased()).tflite")
        XCTAssertEqual(expected.classes, classes)
        XCTAssertEqual(expected.clips.count, 10)
        XCTAssertEqual(expected.clips.reduce(0) { $0 + $1.windows.count }, 65)

        let devices = MeasurementDevices()
        XCTAssertTrue(devices.supportsAudioClassifier(modelVariant: variant))
        let classifier = try XCTUnwrap(devices.createAudioClassifier(modelVariant: variant))
        for clip in expected.clips {
            let url = try resource(String(clip.file.dropLast(4)), "wav", in: "test_clips")
            let samples = try waveform(from: url)
            var sums = Array(repeating: 0.0, count: outputCount)
            for window in clip.windows {
                let label = "Student \(variant), \(clip.file) @ \(window.startSample)"
                guard (0..<samples.count).contains(window.startSample) else {
                    XCTFail("Window starts outside clip: \(label)")
                    continue
                }
                var input = Array(samples.dropFirst(window.startSample).prefix(sampleCount))
                input.append(contentsOf: repeatElement(0, count: sampleCount - input.count))
                let output = try XCTUnwrap(classifier.classify(window: input.withUnsafeBytes { Data($0) }), label)
                XCTAssertEqual(output.count, outputCount * MemoryLayout<Float>.size, label)
                guard output.count == outputCount * MemoryLayout<Float>.size else { continue }
                let probabilities = output.withUnsafeBytes { bytes in
                    (0..<outputCount).map { index in
                        bytes.loadUnaligned(fromByteOffset: index * MemoryLayout<Float>.size, as: Float.self)
                    }
                }
                assertProbabilities(probabilities, equalTo: window.probabilities, label: label)
                for index in probabilities.indices { sums[index] += Double(probabilities[index]) }
            }
            let mean = sums.map { Float($0 / Double(clip.windows.count)) }
            let label = "Student \(variant), \(clip.file)"
            assertProbabilities(mean, equalTo: clip.probabilities, label: label)
            let top = try XCTUnwrap(mean.indices.max { mean[$0] < mean[$1] }, label)
            XCTAssertEqual(classes[top], clip.expectedTopClass, label)
        }
    }

    private func assertProbabilities(_ actual: [Float], equalTo expected: [Float], label: String) {
        XCTAssertEqual(actual.count, outputCount, label)
        XCTAssertEqual(expected.count, outputCount, label)
        XCTAssertEqual(actual.reduce(0, +), 1, accuracy: 0.001, label)
        for (index, pair) in zip(actual, expected).enumerated() {
            XCTAssertTrue((0...1).contains(pair.0), "\(label), \(classes[index])")
            XCTAssertEqual(pair.0, pair.1, accuracy: 0.001, "\(label), \(classes[index])")
        }
    }

    private func waveform(from url: URL) throws -> [Float] {
        let data = try Data(contentsOf: url)
        // Read the handoff's exact PCM frames. AVAudioFile may return fewer frames than requested.
        let samples = data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) -> [Float]? in
            guard bytes.count >= 44,
                  bytes[0..<4].elementsEqual("RIFF".utf8),
                  bytes[8..<16].elementsEqual("WAVEfmt ".utf8),
                  bytes[36..<40].elementsEqual("data".utf8),
                  UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: 16, as: UInt32.self)) == 16,
                  UInt16(littleEndian: bytes.loadUnaligned(fromByteOffset: 20, as: UInt16.self)) == 1,
                  UInt16(littleEndian: bytes.loadUnaligned(fromByteOffset: 22, as: UInt16.self)) == 1,
                  UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: 24, as: UInt32.self)) == 16_000,
                  UInt16(littleEndian: bytes.loadUnaligned(fromByteOffset: 34, as: UInt16.self)) == 16 else {
                return nil
            }
            let count = Int(UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: 40, as: UInt32.self)))
            guard count == bytes.count - 44, count.isMultiple(of: 2) else { return nil }
            return stride(from: 44, to: bytes.count, by: 2).map { offset in
                let sample = Int16(littleEndian: bytes.loadUnaligned(fromByteOffset: offset, as: Int16.self))
                return Float(sample) / 32_768
            }
        }
        return try XCTUnwrap(samples, "Expected a canonical 16 kHz mono PCM16 WAV: \(url.lastPathComponent)")
    }

    private func resource(_ name: String, _ ext: String, in directory: String) throws -> URL {
        let bundle = Bundle(for: AudioStudentTests.self)
        return try XCTUnwrap(
            bundle.url(forResource: name, withExtension: ext, subdirectory: directory)
                ?? bundle.url(forResource: name, withExtension: ext),
            "Missing test fixture \(directory)/\(name).\(ext)"
        )
    }
}

private struct Handoff: Decodable {
    let model: String
    let classes: [String]
    let clips: [Clip]

    struct Clip: Decodable {
        let file: String
        let expectedTopClass: String
        let probabilities: [Float]
        let windows: [Window]

        enum CodingKeys: String, CodingKey {
            case file, windows
            case expectedTopClass = "expected_top_class"
            case probabilities = "clip_probabilities"
        }
    }

    struct Window: Decodable {
        let startSample: Int
        let probabilities: [Float]

        enum CodingKeys: String, CodingKey {
            case startSample = "start_sample"
            case probabilities
        }
    }
}

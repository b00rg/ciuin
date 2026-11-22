import ComposeApp
import Foundation
import XCTest
@testable import iosApp

final class AudioStudentTests: XCTestCase {
    private let sampleRate: Int32 = 16_000
    private let sampleCount = 15_600
    private let outputCount = 9
    private let escalationMargin = 0.2204
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

    func testMicrophoneWindowsSelectStudentSOrMAndSkipQuietAudio() throws {
        let studentS = try handoff("expected", in: "test_clips")
        let studentM = try handoff("audio-student-m-expected", in: "Fixtures")
        let analyzer = IosAudioWindowAnalyzer(
            devices: MeasurementDevices(), modelVariant: "S", escalationMargin: escalationMargin
        )
        var studentSWindows = 0
        var studentMWindows = 0
        var quietWindows = 0
        XCTAssertEqual(studentS.clips.count, studentM.clips.count)
        for (clipS, clipM) in zip(studentS.clips, studentM.clips) {
            XCTAssertEqual(clipS.file, clipM.file)
            XCTAssertEqual(clipS.windows.count, clipM.windows.count, clipS.file)
            let samples = try waveform(from: resource(String(clipS.file.dropLast(4)), "wav", in: "test_clips"))
            for (windowS, windowM) in zip(clipS.windows, clipM.windows) {
                let label = "\(clipS.file) @ \(windowS.startSample)"
                XCTAssertEqual(windowS.startSample, windowM.startSample, label)
                let input = try microphoneWindow(from: samples, startingAt: windowS.startSample)
                let result = try XCTUnwrap(analyzer.analyze(samples: input, sampleRate: sampleRate), label)

                if clipS.file == "09_siren.wav" && windowS.startSample == 0 {
                    quietWindows += 1
                    XCTAssertEqual(result.modelVariant, "Student S", label)
                    XCTAssertTrue(try XCTUnwrap(result.soundClasses, label).isEmpty, label)
                    continue
                }

                let rankedS = windowS.probabilities.sorted(by: >)
                if Double(rankedS[0] - rankedS[1]) < escalationMargin {
                    studentMWindows += 1
                    try assertResult(result, variant: "M", matches: windowM, label: label)
                } else {
                    studentSWindows += 1
                    try assertResult(result, variant: "S", matches: windowS, label: label)
                }
            }
        }
        XCTAssertEqual(studentSWindows, 51)
        XCTAssertEqual(studentMWindows, 13)
        XCTAssertEqual(quietWindows, 1)
    }

    func testSelectingStudentMUsesItDirectly() throws {
        let studentM = try handoff("audio-student-m-expected", in: "Fixtures")
        let clip = try XCTUnwrap(studentM.clips.first { $0.file == "00_speech_crowd.wav" })
        // Student S is confident on this window, so S mode would keep S's scores.
        let window = try XCTUnwrap(clip.windows.first { $0.startSample == 0 })
        let samples = try waveform(from: resource("00_speech_crowd", "wav", in: "test_clips"))
        let input = try microphoneWindow(from: samples, startingAt: window.startSample)
        let analyzer = IosAudioWindowAnalyzer(
            devices: MeasurementDevices(), modelVariant: "M", escalationMargin: escalationMargin
        )
        let result = try XCTUnwrap(analyzer.analyze(samples: input, sampleRate: sampleRate))
        try assertResult(result, variant: "M", matches: window, label: clip.file)
    }

    private func assertResult(
        _ result: IosAnalyzedAudio, variant: String, matches window: Handoff.Window, label: String
    ) throws {
        XCTAssertEqual(result.modelVariant, "Student \(variant)", label)
        let classesByLabel = try XCTUnwrap(result.soundClasses, label)
        XCTAssertEqual(Set(classesByLabel.keys), Set(classes), label)
        let probabilities = try classes.map { try XCTUnwrap(classesByLabel[$0], "\(label), \($0)").floatValue }
        assertProbabilities(probabilities, equalTo: window.probabilities, label: label)
    }

    private func microphoneWindow(from samples: [Float], startingAt start: Int) throws -> Data {
        let modelWindow = try modelWindow(from: samples, startingAt: start)
        // MicrophoneMeasurement sends one second. Inference uses its last 15,600 samples.
        let input = Array(repeating: Float.zero, count: Int(sampleRate) - sampleCount) + modelWindow
        return input.withUnsafeBytes { Data($0) }
    }

    private func modelWindow(from samples: [Float], startingAt start: Int) throws -> [Float] {
        let startIndex = try XCTUnwrap(samples.indices.contains(start) ? start : nil, "Window starts outside clip: \(start)")
        var input = Array(samples.dropFirst(startIndex).prefix(sampleCount))
        input.append(contentsOf: repeatElement(0, count: sampleCount - input.count))
        return input
    }

    private func handoff(_ name: String, in directory: String) throws -> Handoff {
        let reference = try resource(name, "json", in: directory)
        return try JSONDecoder().decode(Handoff.self, from: Data(contentsOf: reference))
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
                let input = try modelWindow(from: samples, startingAt: window.startSample)
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

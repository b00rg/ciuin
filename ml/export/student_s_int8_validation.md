# Student S INT8 validation

Ciúin defaults to `audio-student-s.tflite` (FP32). The Android runtime comparison below used a standalone instrumentation APK and the exact same INT8 model file on both runtimes.

## Host and emulator comparison

The host used ai-edge-litert 2.4.0 on macOS arm64, one thread, optimized built-in CPU kernels and no delegates. Android used `emulator-5554` (API 35, arm64-v8a), built-in CPU kernels and XNNPACK disabled. The original Android 1.4.2 run used the interpreter's default thread setting. The controlled rerun used one thread on both platforms. The trace exposed each intermediate tensor as an extra output. It replayed the exact bytes captured after Kotlin preprocessing and checked that the extra outputs left the scores identical to the ordinary single-output model.

In the original Android LiteRT 1.4.2 run, the first differing tensor was the float32 STFT convolution (op 1, zero-based). All 399,360 codes at the first `QUANTIZE` (op 13) matched. At the first classifier `CONV_2D` (op 14), 13,003 of 9,584,640 output values differed by exactly one code across the 65 windows. In an isolated replay, all five Android INT8 convolutions matched the host reference convolutions. All four Android INT8 depthwise convolutions matched the host's optimized depthwise convolutions.

With one thread on Android 1.4.2, op 13 first differed in two windows of `04_construction.wav` at samples 8000 and 16000. Each had one normalized log-mel bin on opposite sides of a quantization threshold, producing host code −19 and Android code −20. The quantize kernels agreed when replayed with identical inputs. Op 14 first differed in the other 63 windows. Its isolated kernel difference was still 13,003 codes across all 65 windows.

Recomputing the first classifier convolution from the shared INT8 input, filters and biases reproduced every output code with single rounding for the host and double rounding for Android 1.4.2. The two rounding formulas are defined in TensorFlow's fixed-point kernel code. [TensorFlow requantization source](https://github.com/tensorflow/tensorflow/blob/v2.21.0/tensorflow/lite/kernels/internal/common.cc)

Android LiteRT 2.4.0 matched the host on every traced INT8 tensor and recovered probability parity. The float32 frontend and `serving_default` waveform/probability I/O come from the same unmodified model bytes.

| Android runtime | Host/Android max window delta | Window values > 0.001 | Host/Android max clip delta | Clip values > 0.001 |
|---|---:|---:|---:|---:|
| 1.4.2 | 0.0588690341 | 133/585 | 0.0297450423 | 24/90 |
| 2.4.0 | 1.86264515e-9 | 0/585 | 1.86264515e-9 | 0/90 |

The 2.4.0 rerun agreed with the host on all 65 window top classes and 10 clip top classes. Against Emma's FP32 fixture, the Android 2.4.0 maxima were 0.0781146956 per window and 0.0330985188 per clip. All clip top classes matched FP32. Two window top classes changed (see [results.json](results.json)).

## Reproduce the Android comparison

With the Python dependencies in `ml/requirements.txt` installed, use ai-edge-litert 2.4.0 on the host. Use JDK 17, Android SDK 36, and ADB with the target emulator booted. Run from the repository root.

```sh
parity_dir=$(mktemp -d)
student=ml/export/audio-student-s-int8.tflite
python3 ml/trace_student_s_int8.py expose "$student" "$parity_dir/trace.tflite"

for litert_version in 1.4.2 2.4.0; do
  bash ml/android_parity/run.sh emulator-5554 "$litert_version" "$student" "$parity_dir/scores-$litert_version"
  bash ml/android_parity/run.sh emulator-5554 "$litert_version" "$parity_dir/trace.tflite" "$parity_dir/trace-$litert_version" trace
  python3 ml/trace_student_s_int8.py compare "$student" "$parity_dir/trace.tflite" "$parity_dir/trace-$litert_version.json" \
    --android-probabilities "$parity_dir/scores-$litert_version.json" \
    --check-rounding --replay 13 14 18 19 23 24 28 29 33 34 37 38
  python3 ml/verify_student_s_int8.py "$student" --android-json "$parity_dir/scores-$litert_version.json"
done
```

To reproduce the original 1.4.2 thread setting, prefix both 1.4.2 runner calls with `CIUIN_ANDROID_PARITY_THREADS=default`.

## Paired held-out evaluation

Emma's Handoff 1 commit `5568e2f1d6a735e17e548f005febceece6a521ec` records 2,057 Student S clips and lists MUSAN and FSD50K as absent. The reconstructed split contains 400 ESC-50 fold-5 WAVs, 816 UrbanSound8K fold-9 WAVs, 837 UrbanSound8K fold-10 WAVs and four DEMAND ch01 segments from 270.004 s to 300.004 s (PCAFETER, PRESTO, PSTATION, SPSQUARE). Every ESC-50 test WAV matched its upstream Git blob ID. Each DEMAND ch01 WAV matched the size and CRC32 in its official 16 kHz archive. [Pinned ESC-50 tree](https://github.com/karolpiczak/ESC-50/tree/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6) · [UrbanSound8K release 1.0.0](https://zenodo.org/records/1203745) · [DEMAND release 1.0](https://zenodo.org/records/1227121)

The evaluator checks index membership against both published metadata CSVs and the original fold rules. The Soundata manifest supplies an MD5 check for every recovered UrbanSound8K WAV and the complete metadata CSV. The evaluator also reproduces the ten fixed-seed handoff selections and compares their PCM samples with the committed WAVs. It then scores both TFLite models on identical inputs using Emma's preprocessing, the cap of 24 evenly spaced active windows per clip, mean window probabilities, and the original clip-level metrics. [Published Soundata manifest](https://zenodo.org/records/11176928)

Both models scored 15,442 windows on macOS arm64 with ai-edge-litert 2.4.0, one built-in CPU thread and delegates disabled. The FP32 TFLite rerun reproduced Emma's accuracy, macro-F1 and every per-class F1. INT8 scored **70.37% macro-F1** (−0.09 percentage points) and **72.05% accuracy** (+0.05 percentage points), with 39 changed clip predictions. The [paired metrics and manifest hash](results.json) include class counts and per-class F1 for both models.

```sh
# Use the recovered held-out audio tree from the primary checkout. A separate checkout
# can fetch the full official archives with ml/download_datasets.py esc50 us8k demand.
audio_root=/Volumes/DeveloperStorage/GitHub/ciuin/data/raw/audio/.student-s-heldout
heldout_dir=$(mktemp -d)
index="$heldout_dir/audio_test_index.sqlite"
soundata_index="$heldout_dir/urbansound8k_index_1.0.json"
curl -fL 'https://zenodo.org/records/11176928/files/urbansound8k_index_1.0.json?download=1' -o "$soundata_index"
python3 ml/build_index.py --test-only --audio-root "$audio_root" --out "$index"
python3 ml/evaluate_student_s_heldout.py --audio-root "$audio_root" --index "$index" \
  --us8k-manifest "$soundata_index" --output "$heldout_dir/student_s_paired_metrics.json"
```

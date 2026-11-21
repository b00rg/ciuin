# Audio model contract

Every audio model we ship (the dummy now, then students S / M / L) has exactly this interface. Nothing about it changes between models, so app code written against `audio-dummy.tflite` works unchanged with the real ones.

## Signature

| | Name | Type | Shape | Meaning |
|---|---|---|---|---|
| Input | `waveform` | float32 | `[1, 15600]` | 0.975 s of 16 kHz mono audio, samples in [-1, 1] |
| Output | `probabilities` | float32 | `[1, 9]` | Softmax over the 9 classes; sums to 1 |

- TFLite signature key: `serving_default`. You can also use plain input/output index 0.
- Output index `i` = `SoundClass.entries[i]` = line `i` of [`labels.txt`](labels.txt):
  `speech_crowd, music, traffic, siren, construction, alarm_beep, dog, machinery_hum, other`.
- **The spectrogram is inside the model.** The app only has to resample to 16 kHz, downmix to mono, scale to [-1, 1] (16-bit PCM ÷ 32768) and cut 15,600-sample windows ([`preprocess.md`](preprocess.md)). No FFT or mel code is needed in Kotlin.

Kotlin, Android (LiteRT):

```kotlin
val interpreter = Interpreter(modelBuffer)
val input = arrayOf(window)                 // FloatArray(15600)
val output = arrayOf(FloatArray(9))
interpreter.run(input, output)
val probs = SoundClass.entries.associateWith { output[0][it.index] }
```

## What's inside

```
waveform [1, 15600]
  → STFT as Conv1D (fixed Hann-windowed cos/sin kernels, 400 taps, stride 160) → [1, 96, 514]
  → power = re² + im²                                                         → [1, 96, 257]
  → mel matrix multiply (64 HTK-mel bins, 125–7500 Hz)                        → [1, 96, 64]
  → log(mel + 0.001)
  → classifier                                                                → [1, 9] softmax
```

Frontend parameters match YAMNet (25 ms window, 10 ms hop, 64 mel bins), so one window gives one 96 × 64 patch. They're defined once in `ml/frontend.py`.

## Ops

The dummy uses only TFLite builtin ops:

`ADD, CONV_2D, FULLY_CONNECTED, LOG, MEAN, MUL, RESHAPE, SOFTMAX, STRIDED_SLICE`

There are no Flex / Select-TF ops and no variables. Real students add more of the same kinds (conv, depthwise conv, pooling, dense). `ml/verify_tflite.py` rejects any model that needs something else.

## For quantisation (B2)

- Keep the frontend (everything up to and including `LOG`) in float. The STFT power and the log span many orders of magnitude, so int8 there destroys the features. Quantise only the classifier's conv and dense layers.
- Keep the **input float32**, so the app always passes floats. The output may be float32 or int8; `verify_tflite.py` handles both.

### Student S INT8 candidate

From the repo root, use Python 3.13 with `ml/requirements.txt` installed. The export was reproduced byte-for-byte with TensorFlow 2.21.0 and ai-edge-litert 2.4.0.

```sh
python3 ml/quantize_student_s.py
python3 ml/verify_tflite.py ml/export/audio-student-s-int8.tflite
python3 ml/verify_student_s_int8.py ml/export/audio-student-s-int8.tflite
```

The exporter calibrates the handed-off FP32 TFLite directly with all 200 windows in `calibration.npy`. Five classifier convolutions, four depthwise convolutions and one dense op use INT8 activations and per-channel INT8 weights with INT32 biases. The waveform, frontend through `LOG`, input normalisation, pooling and softmax use float32. The same `serving_default` signature returns float32 probabilities in the original class order.

TensorFlow 2.21's selective quantizer propagates INT8 through max pools despite the exclusions. The exporter restores float pooling with dequantize and quantize ops at the calibrated scale on each side. These conversions add work at the float/INT8 boundaries. The model is 997,384 bytes, 14.79% smaller than FP32, because the fixed frontend stays float32. Inference speed is unmeasured. [TensorFlow selective quantizer](https://github.com/tensorflow/tensorflow/blob/v2.21.0/tensorflow/lite/python/convert.py#L185) · [LiteRT operator quantization spec](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/quantization_spec#int8_quantized_operator_specifications)

FP32 remains Ciúin's default. On Android `emulator-5554`, all 65 window starts and all 10 clip top classes matched the FP32 fixture. With LiteRT 2.4.0 on both platforms, the maximum Android-to-host INT8 probability delta was `1.86e-9`. INT8-to-FP32 probability deltas still exceeded 0.001. See the [operator trace and evaluation commands](student_s_int8_validation.md) and [results.json](results.json) for the counts and maxima.

## Files

| File | What |
|---|---|
| `audio-dummy.tflite` | Untrained (random weights, fixed seed), correct signature, ~0.9 MB. **Its predictions are meaningless**; superseded by `audio-student-s.tflite` |
| `labels.txt` | Class names, one per line, in output order |
| `audio-teacher.tflite` | Teacher (YAMNet + MLP head, ~4.3M params, 15 MB), same signature. **Reference only**, not for phones: compare a student against it on the desktop target. Matches the Python teacher to ~2e-6. TFLite instead of ONNX (tf2onnx doesn't support TF 2.21 / Python 3.13); LiteRT runs it on desktop too |
| `../verify_tflite.py` | `python3 ml/verify_tflite.py <model.tflite>` checks any model against this contract |

App-side preprocessing (capture, mono, 16 kHz, windowing, averaging): [`preprocess.md`](preprocess.md).

### Handoff 1 files

| File | What |
|---|---|
| `audio-student-s.tflite` | First real model: small student (~68k params), fp32, same signature as the dummy. Scores in `results.json` |
| `audio-student-s-int8.tflite` | Selectively quantised student S candidate, with float32 input and output. Android handoff comparison in `results.json` |
| `test_clips/` | 10 test-split clips (16 kHz mono 16-bit WAV) + `expected.json`: per-window and clip probabilities **from this exact `.tflite`**. On the phone, the top class must match and probabilities should agree within ~1e-3 |
| `calibration.npy` | 200 training windows, float32 `[200, 15600]`, spread over the 9 classes: the representative dataset for int8 post-training quantisation |
| `results.json` | Teacher and student scores (accuracy, macro-F1, per class); add per-device latency rows here |

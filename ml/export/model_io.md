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
| `test_clips/` | 10 test-split clips (16 kHz mono 16-bit WAV) + `expected.json`: per-window and clip probabilities **from this exact `.tflite`**. On the phone, the top class must match and probabilities should agree within ~1e-3 |
| `calibration.npy` | 200 training windows, float32 `[200, 15600]`, spread over the 9 classes: the representative dataset for int8 post-training quantisation |
| `results.json` | Teacher and student scores (accuracy, macro-F1, per class); add per-device latency rows here |

### Handoff 2 files

Same signature for every model: `waveform [1, 15600]` in, `probabilities [1, 9]` out. **Replaces the Handoff 1 S**: re-run the parity check and int8 quantisation on the new files.

| File | Params | MACs / window | What |
|---|---:|---:|---|
| `audio-student-s.tflite` | 68k | 29.5M | Retrained with FSD50K + augmentation. Test macro-F1 0.671 (Handoff 1 S: 0.532 on the same test split) |
| `audio-student-m.tflite` | 259k | 50.0M | Same architecture, 2× wide, 1.93 MB. Test macro-F1 0.650: **below S for now** (10 epochs wasn't enough; val was still rising). Use S as the default until the overnight retrain |
| `audio-student-l.tflite` | 1.0M | 128.3M | Same architecture, 4× wide. Training now; added to this handoff when it finishes (~22:25) |
| `audio-teacher.tflite` | 4.3M | | Reference only, retrained with FSD50K |
| `test_clips/expected.json` | | | Regenerated **from the new S**. Same 10 WAVs |
| `calibration.npy` | | | **New** 200 training windows (199 differ from Handoff 1): FSD50K is now in the pool, so the alarm / siren / crowd sounds are represented. Re-quantise with this one |

21.4M of each model's MACs are the in-graph log-mel frontend (the STFT is a plain conv). TFLite's `RFFT2D` builtin, which the teacher already uses, would cut that a lot: the biggest speed-up available for the always-on stage 1.

Scores, params, size and MACs for every model are in `results.json`. The test split grew from 2,057 to 3,116 clips when FSD50K joined, so compare models only within one handoff (or use `audio-student-s-handoff1`, the old S re-scored on the new split).

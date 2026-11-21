# Audio preprocessing (app side)

What the app does to audio **before** it calls the model, and how it combines the model's outputs. The spectrogram is inside the model (see [`model_io.md`](model_io.md)), so this is only capture, format, windowing and averaging.

Training uses exactly these steps: they're implemented in `ml/preprocess.py`. If the Kotlin port does something different, accuracy drops on the phone even though the model is fine.

## Constants

| Name | Value | |
|---|---|---|
| `SAMPLE_RATE` | 16 000 Hz | mono |
| `WINDOW` | 15 600 samples | 0.975 s, the model input |
| `HOP` | 8 000 samples | 0.5 s between windows when scoring a longer recording |
| `SILENCE_DBFS` | −60 dBFS | windows quieter than this are skipped |

## Steps

| # | Step | Rule |
|---|---|---|
| 1 | **Capture** | Android: `AudioRecord` at **16 000 Hz, `CHANNEL_IN_MONO`**, `ENCODING_PCM_FLOAT` (or `PCM_16BIT`). Then steps 2–4 are no-ops |
| 2 | **To float** | Samples in [−1, 1]: int16 ÷ 32 768, int24 ÷ 8 388 608, float as is. Clamp to [−1, 1] |
| 3 | **Mono** | Average the channels (not just channel 0) |
| 4 | **Resample to 16 kHz** | Only if the source isn't 16 kHz (desktop files, devices that refuse 16 kHz). Use a band-limited resampler with an anti-aliasing filter. **Not** linear interpolation and **not** dropping samples: those fold high frequencies (sirens, beeps) down into the wrong bins |
| 5 | **No normalisation** | Don't scale by peak or RMS, and don't apply AGC. The model was trained on audio at its recorded level (with random gain for robustness) |
| 6 | **Window** | Cut `WINDOW`-sample windows (below) |
| 7 | **Run the model** | One window → 9 probabilities |
| 8 | **Average** | For a recording longer than one window: average the **probabilities** (not logits) of the non-silent windows |

### Choosing the Android audio source

The model has to hear the noise as it is. Phone voice processing (automatic gain control, noise suppression) is designed to remove exactly the sounds we're measuring.

1. `MediaRecorder.AudioSource.UNPROCESSED` if `AudioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"`
2. otherwise `VOICE_RECOGNITION` (no AGC or noise suppression on most devices)
3. **never** `VOICE_COMMUNICATION`; avoid the default `MIC` (often has AGC)

Log which source was used in the measurement's `device` info, so odd results can be traced.

## Windowing

**Live listener (cascade stage 1 / 2):** once per second, run the model on the **most recent 15 600 samples**. No averaging. The cascade decides what to do with each result.

**Measurement (30 s "Measure here") and any recording longer than one window:**

```
starts = 0, HOP, 2·HOP, …   while start + WINDOW ≤ length
if the last window doesn't end exactly at the end: add one more window ending at the end
if length < WINDOW: one window, zero-padded at the end
```

Then drop silent windows and average:

```
level_dBFS(w) = 10 · log10( mean(w²) + 1e-12 )
active = windows with level_dBFS ≥ −60
if none are active: keep the single loudest window
sound_classes = mean of model(w) over active windows     → the 9-value feature vector
```

This silence gate is plain unweighted RMS, separate from stage 0's A-weighted dB. It only stops digital silence (gaps, muted mic) from being averaged in as if it were sound.

Kotlin sketch:

```kotlin
const val WINDOW = 15_600
const val HOP = 8_000

fun windows(x: FloatArray): List<FloatArray> {
    if (x.size <= WINDOW) return listOf(x.copyOf(WINDOW))          // zero-padded
    val starts = (0..x.size - WINDOW step HOP).toMutableList()
    if (starts.last() != x.size - WINDOW) starts += x.size - WINDOW
    return starts.map { x.copyOfRange(it, it + WINDOW) }
}

fun levelDbfs(w: FloatArray) = 10 * log10(w.sumOf { (it * it).toDouble() } / w.size + 1e-12)

fun clipProbabilities(x: FloatArray, classify: (FloatArray) -> FloatArray): FloatArray {
    val all = windows(x)
    val active = all.filter { levelDbfs(it) >= -60.0 }.ifEmpty { listOf(all.maxBy(::levelDbfs)) }
    val sum = FloatArray(9)
    active.forEach { w -> classify(w).forEachIndexed { i, p -> sum[i] += p } }
    return FloatArray(9) { sum[it] / active.size }
}
```

## Parity checks

The Handoff 1 `test_clips/` are already 16 kHz mono (16-bit PCM WAV: read as int16 ÷ 32 768, step 2). They test the model and windowing (steps 6–8) on the phone against Python's expected outputs. Steps 1–4 are checked separately:
- a 1 kHz tone recorded through the pipeline should come out as 1 kHz
- a 12 kHz tone in a 48 kHz file should come out near-silent after resampling, not as a 4 kHz tone

`ml/tests/test_preprocess.py` runs the same checks on the Python side.

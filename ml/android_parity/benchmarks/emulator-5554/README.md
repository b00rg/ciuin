# Student S Android benchmark on emulator-5554

See the [lower-load repeat](lower-load.md) for the follow-up comparison.

INT8 had a lower median single-window latency in this series. The p50 ranges overlapped and one of six pairs reversed. The data did not establish a lower tail latency, model load time or peak process RSS for INT8. FP32 remains Ciúin's default.

## Results

Latency percentiles pool 7,800 timed calls per model. The other headline values are medians of six fresh processes per model. The ranges in brackets are the smallest and largest corresponding per-run measurements. Deltas are INT8 minus FP32.

| Measurement | FP32 | INT8 | Delta |
|---|---:|---:|---:|
| File to interpreter-ready, ms | 5.583 [5.100–17.689] | 5.966 [4.998–7.669] | +0.383 |
| Warm single-window p50, ms | 0.744 [0.713–0.816] | 0.681 [0.647–0.735] | −0.063 (−8.52%) |
| Warm single-window p95, ms | 1.499 [0.780–2.724] | 1.640 [0.833–3.824] | +0.141 (+9.39%) |
| Pre-model RSS and high-water mark, MiB | 106.28 [106.19–106.39] | 106.25 [106.11–106.32] | −0.03 |
| Peak process RSS, MiB | 115.61 [115.45–115.81] | 115.39 [115.29–115.71] | −0.22 |
| High-water growth above pre-model baseline, MiB | 9.36 [9.05–9.52] | 9.19 [8.99–9.41] | −0.17 |

- Warm p50 was lower in five of six pairs. The paired median delta was −0.049 ms, with a range of −0.169 to +0.008 ms (two-sided exact sign-test p = 0.219). Warm p95 was lower in two of six pairs.
- Load time and peak RSS overlapped. The paired load delta ranged from −10.020 to +1.223 ms. The paired peak RSS delta ranged from −448 to +132 KiB and high-water growth was lower in three of six pairs.
- The full ADB instrumentation invocation, including launch, fixture preparation, warmup, all measured calls and report writing, took a median of 1.73 s for FP32 and 1.76 s for INT8. The ranges were 1.46–2.28 s and 1.42–3.08 s, respectively.

The host's one-minute load average was 16.51–18.14 across run starts on 10 logical CPUs. The guest's was 1.69–2.12 on four vCPUs. In the separate pilot, the inference thread spent about 96% of measured-loop wall time on CPU and calls to `Interpreter.run()` occupied about 99% of loop wall time. That identifies single-thread inference as the main timed work under steady scheduling. In the slowest INT8 tail run the thread was on CPU for 62% of loop wall time, consistent with scheduling noise on this host. The benchmark uses a diagnostic APK and applies only to this emulator and CPU configuration. [Host and guest load before the series](before-measurement-load.log) · [Pilot counters](pilot/summary.json)

## Configuration and verification

- `emulator-5554`, Android API 35, arm64-v8a, guest build `AE3A.240806.043/12960925`, macOS arm64 host. Both sides used the same pinned LiteRT 2.4.0 Android AARs, one built-in CPU thread, and XNNPACK disabled. [Environment and AAR digests](runs/environment.json)
- Six FP32/INT8 pairs with alternating order (AB, BA, AB, BA, AB, BA). Each model measurement started in a fresh process. The Kotlin preprocessor prepared 65 windows from all 10 handoff WAVs before timing. Each process performed 195 warmup and 1,300 measured calls. The latency timer brackets `Interpreter.run()` with preallocated Java input and output arrays, so it includes Java-to-native marshaling and any tensor copies inside that call. Output checks happen after the timer.
- The load timer starts immediately before `Interpreter(File, options)` and ends after `allocateTensors()`. It includes the native file-backed model load, including LiteRT's file mapping, model verification, interpreter construction and tensor allocation. WAV reads, fixture preparation, ADB staging and checksum verification are outside the timer. A separate ADB helper checksums the staged model before each launch, so the file cache is warmed and remains OS-managed. [LiteRT Interpreter API](https://developers.google.com/edge/api/tflite/java/org/tensorflow/lite/Interpreter) · [Native file loader](https://github.com/tensorflow/tensorflow/blob/v2.21.0/tensorflow/compiler/mlir/lite/core/model_builder_base.cc#L25-L37)
- `VmRSS` and `VmHWM` came from `/proc/self/status`. Baseline was sampled after fixture and array setup, before the model constructor. Both were sampled again after allocation and after inference. The latter `VmHWM` is peak process RSS through the measured workload, captured before report serialization. It includes Android runtime and fixture memory.
- All 12 measured launches succeeded on the first attempt. The pilot pair also succeeded and is excluded from the headline values. The APK validated all nine values after every call. FP32 was within `7.01e-7` of Emma's fixture and INT8 within `1.86e-9` of its verified LiteRT 2.4.0 reference. All window starts matched. [Attempt log](runs/events.tsv) · [Validated raw comparison](runs/summary.json)

| Model file | Bytes | SHA-256 |
|---|---:|---|
| `audio-student-s.tflite` | 1,170,564 | `250b5a9f0e4409315413c18230e71083591ad106715541d76f0edd9a4dc99022` |
| `audio-student-s-int8.tflite` | 997,384 | `e0b5d99bb757c015864dfb47cec66766ab23a009a869d4d7cc77e6321bf0e20e` |

## Reproduce

See the [runner setup](../../README.md) for JDK, Android SDK and Python prerequisites. Run from the repository root.

```sh
out=$(mktemp -d)
ml/android_parity/run.sh emulator-5554 2.4.0 ml/export/audio-student-s.tflite "$out" \
    benchmark ml/export/audio-student-s-int8.tflite
python3 ml/android_parity/compare_benchmark.py "$out" --output "$out/summary.json"
```

To regenerate the summary from the captured runs, point the comparison command at `ml/android_parity/benchmarks/emulator-5554/runs`.

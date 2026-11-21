# Android audio parity runner

Run from the repository root with JDK 17, Android SDK Platform 36, Android platform tools, Bash, curl, shasum and Python 3 on the host. Set `ANDROID_HOME` to the SDK path and pass a booted ADB device serial. Gradle uses the repository wrapper.

```sh
model=ml/export/audio-student-s-int8.tflite
out=$(mktemp -d)
ml/android_parity/run.sh emulator-5554 1.4.2 "$model" "$out/android14"
ml/android_parity/run.sh emulator-5554 2.4.0 "$model" "$out/android24"
python3 ml/verify_student_s_int8.py "$model" --android-json "$out/android24.json"
```

The runner verifies the downloaded Google Maven AARs against pinned SHA-256 digests and builds a temporary debug APK. It uses the repository Kotlin preprocessor and the ten bundled WAVs. It checks the float32 I/O signature and records each window's start and probabilities with the Kotlin clip average. Each run writes a JSON report and an ADB log. The interpreter uses one CPU thread with XNNPACK disabled because XNNPACK hit an illegal instruction on this emulator. The temporary 2.4.0 project bypasses the Kotlin metadata version check because LiteRT ships newer Kotlin metadata alongside the Java interpreter API used here.

To reuse downloaded AARs, set `CIUIN_ANDROID_PARITY_AAR_DIR` to a directory containing `litert-<version>.aar` and `litert-api-<version>.aar`. The runner checks the same digests before building.

Set `CIUIN_ANDROID_PARITY_THREADS=default` on both Android 1.4.2 commands to reproduce the original run with LiteRT's default thread count. The log and JSON record the selected setting.

Install the Python dependencies in `ml/requirements.txt` with LiteRT 2.4.0 to capture and compare intermediate tensors.

```sh
python3 ml/trace_student_s_int8.py expose "$model" "$out/trace.tflite"
ml/android_parity/run.sh emulator-5554 1.4.2 "$out/trace.tflite" "$out/trace14" trace
ml/android_parity/run.sh emulator-5554 2.4.0 "$out/trace.tflite" "$out/trace24" trace
python3 ml/trace_student_s_int8.py compare "$model" "$out/trace.tflite" "$out/trace14.json" \
    --android-probabilities "$out/android14.json" --replay 13 14
python3 ml/trace_student_s_int8.py compare "$model" "$out/trace.tflite" "$out/trace24.json" \
    --android-probabilities "$out/android24.json" --replay 13 14
```

Each trace writes a JSON manifest and about 193 MB of raw waveform and tensor bytes in the matching `.bin` file. The comparison reuses the exact captured waveform and checks whether exposing the tensors changed the original model's probability output.

## Student S benchmark

Use a Python 3.13 environment with `ml/requirements.txt` and ai-edge-litert 2.4.0. Run from the repository root with the same JDK, SDK, and ADB setup as the parity runner.

```sh
out=$(mktemp -d)
ml/android_parity/run.sh emulator-5554 2.4.0 ml/export/audio-student-s.tflite "$out" \
    benchmark ml/export/audio-student-s-int8.tflite
python3 ml/android_parity/compare_benchmark.py "$out" --output "$out/summary.json"
```

Set `CIUIN_BENCHMARK_PYTHON` to the Python executable if it differs from `python3`. The runner stages the exact model files and references from host LiteRT, builds one APK, and uses a fresh instrumentation process for each model run. It alternates pair order across six rounds. Each process warms up on three passes of the same 65 Kotlin-preprocessed windows, then times 20 passes. The runner verifies the staged model SHA-256 with ADB before each launch and records attempts in `events.tsv`. The checksum reads the staged file outside the benchmark process and warms the guest page cache.

The load timer covers the LiteRT file constructor and explicit tensor allocation. WAV decoding, Kotlin preprocessing, reference validation and report writing are outside the timed inference calls. The APK validates all nine scores after each call. It captures the process's pre-model `VmRSS` and `VmHWM` and reads the peak `VmHWM` after inference, before serializing the report. Use `CIUIN_ANDROID_BENCHMARK_PILOT=1` and `--rounds 1` on the comparison command for a single diagnostic pair.

#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 || $# -gt 5 || ${5:-trace} != trace ]]; then
    printf 'Usage: %s ADB_SERIAL {1.4.2|2.4.0} MODEL.tflite OUTPUT_PREFIX [trace]\n' "$0" >&2
    exit 2
fi

serial=$1
version=$2
model=$3
prefix=$4
mode=${5:-parity}
threads=${CIUIN_ANDROID_PARITY_THREADS:-1}
case "$threads" in
    1) thread_label='1 thread' ;;
    default) thread_label='default thread count' ;;
    *) printf 'Expected CIUIN_ANDROID_PARITY_THREADS to be 1 or default\n' >&2; exit 2 ;;
esac
case "$version" in
    1.4.2)
        litert_sha=9b02cdf9db0a698b341b59e75c54ab8221d6304577cc43830d01024caf1fca74
        api_sha=66c2ba8086bd5cbf89bec2bd81c3de6c1a852312829cd55bc30c9dd26ffa357c
        ;;
    2.4.0)
        litert_sha=13926c98f35417b6fe4f104f780ab869747b4a69f08a91ccd224013c6bf71615
        api_sha=96faeff454aedd64c3cbe375c0b507b6732053cf4f36ea013ffe90755edd5b91
        ;;
    *) printf 'Unsupported LiteRT version: %s\n' "$version" >&2; exit 2 ;;
esac
if [[ ! -f "$model" ]]; then
    printf 'Model file does not exist: %s\n' "$model" >&2
    exit 2
fi

runner_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(cd "$runner_dir/../.." && pwd)
stage=$(mktemp -d "${TMPDIR:-/tmp}/ciuin-android-parity.XXXXXXXXXX")
package="ch.ciuin.paritydiag.r${stage##*.}"
installed=false
cleanup() {
    if "$installed"; then
        adb -s "$serial" uninstall "$package" >/dev/null 2>&1 || true
    fi
    rm -rf -- "$stage"
}
trap cleanup EXIT

mkdir -p "$(dirname "$prefix")" "$stage/libs"
exec 3>&1
exec > "$prefix.log" 2>&1
trap 'printf "Android parity failed. See %s.log\n" "$prefix" >&3' ERR

device_abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
test -n "$device_abi"
test "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
printf 'ADB serial: %s\nABI: %s\nAPI level: ' "$serial" "$device_abi"
adb -s "$serial" shell getprop ro.build.version.sdk
printf 'LiteRT %s built-in CPU kernels (%s, XNNPACK disabled)\n' "$version" "$thread_label"
printf 'Source model SHA-256: '
shasum -a 256 "$model"

cp "$runner_dir"/{build.gradle,settings.gradle,gradle.properties} "$stage"
cp -R "$runner_dir/src" "$stage/src"
kotlin_dir="$stage/src/androidMain/kotlin/ch/ciuin/shared"
source_dir="$repo_dir/shared/src/commonMain/kotlin/ch/ciuin/shared"
assets_dir="$stage/src/androidMain/assets"
mkdir -p "$kotlin_dir"/{audio,model} "$assets_dir"
cp "$source_dir"/audio/{AudioClassifier,AudioPreprocessor,AudioResampler}.kt "$kotlin_dir/audio/"
cp "$source_dir/model/SoundClass.kt" "$kotlin_dir/model/"
cp "$repo_dir"/ml/export/test_clips/{expected.json,*.wav} "$assets_dir/"

download_aar() {
    local library=$1 digest=$2
    local artifact="$library-$version.aar"
    if [[ -n ${CIUIN_ANDROID_PARITY_AAR_DIR:-} ]]; then
        cp "$CIUIN_ANDROID_PARITY_AAR_DIR/$artifact" "$stage/libs/$library.aar"
    else
        curl --fail --silent --show-error --location --retry 2 --connect-timeout 10 --max-time 180 \
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/$library/$version/$artifact" \
            --output "$stage/libs/$library.aar"
    fi
    printf '%s  %s\n' "$digest" "$stage/libs/$library.aar" | shasum -a 256 --check
}
download_aar litert "$litert_sha"
download_aar litert-api "$api_sha"
"$repo_dir/gradlew" --project-dir "$stage" --console plain \
    "-PlitertVersion=$version" "-PdiagnosticPackage=$package" "-PdeviceAbi=$device_abi" assembleDebug

adb -s "$serial" install "$stage/build/outputs/apk/debug/android-parity-debug.apk"
installed=true
adb -s "$serial" shell run-as "$package" mkdir -p files
adb -s "$serial" shell -T "run-as $package sh -c 'cat > files/model.tflite'" < "$model"
adb -s "$serial" shell am instrument -r -w -e mode "$mode" -e version "$version" \
    -e threads "$threads" "$package/ch.ciuin.parity.AudioParity" | tee "$stage/instrumentation.log"
grep -Fxq 'INSTRUMENTATION_CODE: -1' "$stage/instrumentation.log"

adb -s "$serial" exec-out run-as "$package" cat files/output.json > "$stage/output.json"
if [[ $mode == trace ]]; then
    trace_bytes=$(python3 -c 'import json, sys; m = json.load(sys.stdin); print(m["record_bytes"] * len(m["records"]))' \
        < "$stage/output.json")
    trace_ready=false
    for attempt in 1 2 3; do
        if adb -s "$serial" shell -T run-as "$package" cat files/trace.bin > "$stage/trace.bin" &&
            [[ $(wc -c < "$stage/trace.bin") -eq $trace_bytes ]]; then
            trace_ready=true
            break
        fi
        printf 'Trace transfer attempt %s did not return %s bytes\n' "$attempt" "$trace_bytes"
    done
    "$trace_ready"
    mv "$stage/trace.bin" "$prefix.bin"
fi

python3 - "$model" "$serial" "$mode" "$stage/output.json" "$prefix" >&3 <<'PY'
from hashlib import sha256
import json
from pathlib import Path
import sys

model_file, serial, mode, device_json, prefix = sys.argv[1:]
model = Path(model_file)
data = json.loads(Path(device_json).read_text())
if data["model_sha256"] != sha256(model.read_bytes()).hexdigest():
    raise SystemExit("Android model SHA-256 did not match the local file")
if mode == "trace":
    record_count = len(data["records"])
    if Path(f"{prefix}.bin").stat().st_size != data["record_bytes"] * record_count:
        raise SystemExit("Android trace byte count did not match the manifest")
else:
    record_count = sum(len(clip["windows"]) for clip in data["clips"])
data["model"] = model.name
data["serial"] = serial
Path(f"{prefix}.json").write_text(json.dumps(data, indent=2) + "\n")
print(f"{serial}: {record_count} windows, model SHA-256 {data['model_sha256']}")
print(f"JSON: {prefix}.json")
print(f"Verification log: {prefix}.log")
PY

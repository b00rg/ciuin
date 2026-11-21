"""Drive a counterbalanced Student S benchmark through the Android parity APK."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
from datetime import datetime, timezone
from importlib.metadata import version
from pathlib import Path

EXPECTED_SHA256 = {
    "fp32": "250b5a9f0e4409315413c18230e71083591ad106715541d76f0edd9a4dc99022",
    "int8": "e0b5d99bb757c015864dfb47cec66766ab23a009a869d4d7cc77e6321bf0e20e",
}
WARMUP_PASSES = 3
MEASURED_PASSES = 20


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("package")
    parser.add_argument("fp32", type=Path)
    parser.add_argument("int8", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("litert_sha256")
    parser.add_argument("api_sha256")
    args = parser.parse_args()
    models = {"fp32": args.fp32.resolve(), "int8": args.int8.resolve()}
    output = args.output.resolve()
    rounds = 1 if os.environ.get("CIUIN_ANDROID_BENCHMARK_PILOT") == "1" else 6
    if version("ai-edge-litert") != "2.4.0":
        raise ValueError("benchmark reference inference requires host ai-edge-litert 2.4.0")

    def adb(*commands: str, stdin=None, timeout: int = 30) -> str:
        proc = subprocess.run(
            ["adb", "-s", args.serial, *commands],
            stdin=stdin, capture_output=True, text=True, timeout=timeout,
        )
        if proc.returncode:
            raise RuntimeError(f"{' '.join(commands)} exited {proc.returncode}\n{proc.stdout}{proc.stderr}")
        return proc.stdout.strip()

    def stage_file(source: Path, filename: str) -> None:
        with source.open("rb") as stream:
            adb("shell", "-T", f"run-as {args.package} sh -c 'cat > files/{filename}'", stdin=stream)

    def loads() -> dict:
        return {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            "host_loadavg": list(os.getloadavg()),
            "emulator_loadavg": [float(n) for n in adb("shell", "cat", "/proc/loadavg").split()[:3]],
        }

    settings = {
        "serial": args.serial,
        "device_api": int(adb("shell", "getprop", "ro.build.version.sdk")),
        "device_abi": adb("shell", "getprop", "ro.product.cpu.abi"),
        "device_model": adb("shell", "getprop", "ro.product.model"),
        "device_fingerprint": adb("shell", "getprop", "ro.build.fingerprint"),
        "device_logical_cpus": int(adb("shell", "nproc")),
        "host": f"{platform.system()} {platform.release()} {platform.machine()}",
        "host_logical_cpus": os.cpu_count(),
        "runtime_aars_sha256": {"litert": args.litert_sha256, "litert-api": args.api_sha256},
    }
    (output / "environment.json").write_text(json.dumps(settings, indent=2) + "\n")
    reference_script = Path(__file__).resolve().parents[1] / "verify_student_s_int8.py"
    for model, path in models.items():
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != EXPECTED_SHA256[model]:
            raise ValueError(f"{path} is not the pinned {model} Student S artifact")
        reference = output / f"reference-{model}.json"
        with (output / f"reference-{model}.log").open("w") as stream:
            subprocess.run([sys.executable, str(reference_script), str(path), "--host-json", str(reference)],
                           check=True, stdout=stream, stderr=subprocess.STDOUT)
        stage_file(path, f"{model}.tflite")
        stage_file(reference, f"reference-{model}.json")

    with (output / "events.tsv").open("x", newline="") as stream:
        events = csv.writer(stream, delimiter="\t")
        events.writerow(("round", "position", "model", "attempt", "status", "filename"))
        stream.flush()
        pids = set()
        for round_number in range(1, rounds + 1):
            order = ("fp32", "int8") if round_number % 2 else ("int8", "fp32")
            for position, model in enumerate(order, 1):
                for attempt in (1, 2):
                    name = f"round-{round_number:02}-pos-{position}-{model}-attempt-{attempt}"
                    log = output / f"{name}.log"
                    result = output / f"{name}.json"
                    try:
                        adb("shell", "am", "force-stop", args.package)
                        adb("shell", "run-as", args.package, "rm", "-f", "files/output.json")
                        adb("shell", "run-as", args.package, "ln", "-sf", f"{model}.tflite", "files/model.tflite")
                        adb("shell", "run-as", args.package, "ln", "-sf", f"reference-{model}.json",
                            "files/reference.json")
                        # This checksum reads the staged file in an ADB helper process.
                        digest = adb("shell", "run-as", args.package, "sha256sum", "files/model.tflite").split()[0]
                        if digest != EXPECTED_SHA256[model]:
                            raise ValueError(f"staged {model} SHA-256 does not match the source model")
                        before = loads()
                        start = time.monotonic_ns()
                        raw_log = adb(
                            "shell", "am", "instrument", "-r", "-w",
                            "-e", "mode", "benchmark", "-e", "version", "2.4.0", "-e", "threads", "1",
                            "-e", "model_sha256", digest, "-e", "reference", "device",
                            "-e", "warmup_passes", str(WARMUP_PASSES), "-e", "measured_passes", str(MEASURED_PASSES),
                            f"{args.package}/ch.ciuin.parity.AudioParity", timeout=240,
                        )
                        elapsed = time.monotonic_ns() - start
                        log.write_text(raw_log + "\n")
                        after = loads()
                        if "INSTRUMENTATION_CODE: -1" not in raw_log.splitlines():
                            raise RuntimeError("Android instrumentation did not report success")
                        data = json.loads(adb("exec-out", "run-as", args.package, "cat", "files/output.json"))
                        pid = data["benchmark"]["pid"]
                        if pid in pids or data["model_sha256"] != digest:
                            raise ValueError(f"non-fresh process or model hash mismatch (PID {pid})")
                        pids.add(pid)
                        data.update(settings, round=round_number, position=position, model=model, attempt=attempt,
                                    staged_model_sha256=digest, instrumentation_wall_ns=elapsed,
                                    load_conditions={"before": before, "after": after})
                        result.write_text(json.dumps(data, indent=2) + "\n")
                    except (RuntimeError, ValueError, subprocess.TimeoutExpired, KeyError) as error:
                        with log.open("a") as failure:
                            print(error, file=failure)
                        events.writerow((round_number, position, model, attempt, "failed", log.name))
                        stream.flush()
                        print(f"{name} failed. See {log.name}", flush=True)
                        if attempt == 2:
                            raise
                    else:
                        events.writerow((round_number, position, model, attempt, "success", result.name))
                        stream.flush()
                        print(f"{name} succeeded (PID {pid})", flush=True)
                        break


if __name__ == "__main__":
    main()

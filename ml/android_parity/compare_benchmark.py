"""Validate and summarize counterbalanced Student S benchmark runs."""

from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
from statistics import median
import struct

EXPORT = Path(__file__).resolve().parents[1] / "export"
FIXTURE = EXPORT / "test_clips" / "expected.json"
MODELS = {"fp32": EXPORT / "audio-student-s.tflite", "int8": EXPORT / "audio-student-s-int8.tflite"}
DEFAULT_SERIAL = "emulator-5554"
RUNTIME = "LiteRT 2.4.0 built-in CPU kernels (1 thread, XNNPACK disabled)"
REFERENCE_RUNTIME = "LiteRT 2.4.0 built-in CPU kernels"
FIXTURE_TOLERANCE = 0.001
REFERENCE_TOLERANCE = 0.000001
NS_PER_MS = 1_000_000
ENVIRONMENT_FIELDS = (
    "serial", "device_api", "device_abi", "device_model", "device_fingerprint", "device_logical_cpus",
    "host", "host_logical_cpus", "runtime_aars_sha256",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def check_probabilities(values: list, nclasses: int) -> None:
    require(len(values) == nclasses, "incorrect probability vector size")
    require(all(type(p) in (int, float) and math.isfinite(p) and 0 <= p <= 1 for p in values),
            "non-finite or out-of-range probability")
    require(abs(sum(values) - 1) <= 1e-4, "probabilities do not sum to one")


def probability_rows(report: dict, fixture: dict) -> list[list[float]]:
    classes = fixture["classes"]
    require(report["classes"] == classes, "class order differs from handoff fixture")
    rows = []
    for actual_clip, expected_clip in zip(report["clips"], fixture["clips"], strict=True):
        name = expected_clip["file"]
        require(actual_clip["file"] == name, f"clip order differs at {name}")
        clip_rows = []
        for actual, expected in zip(actual_clip["windows"], expected_clip["windows"], strict=True):
            start = expected["start_sample"]
            require(actual["start_sample"] == start, f"window start differs at {name}/{start}")
            probabilities = actual["probabilities"]
            check_probabilities(probabilities, len(classes))
            if "top_class" in actual:
                require(actual["top_class"] == classes[probabilities.index(max(probabilities))],
                        f"window top class disagrees with probabilities at {name}/{start}")
            clip_rows.append(probabilities)
        average = actual_clip["clip_probabilities"]
        check_probabilities(average, len(classes))
        observed_mean = [sum(column) / len(clip_rows) for column in zip(*clip_rows, strict=True)]
        require(max_delta([average], [observed_mean]) <= 2e-6,
                f"clip average disagrees with windows for {name}")
        top = actual_clip.get("top_class", actual_clip.get("expected_top_class"))
        if top is not None:
            require(top == classes[average.index(max(average))], f"clip top class disagrees for {name}")
        rows.extend(clip_rows)
    return rows


def max_delta(actual: list[list[float]], expected: list[list[float]]) -> float:
    return max(abs(a - b) for vector, reference in zip(actual, expected, strict=True)
               for a, b in zip(vector, reference, strict=True))


def output_hash(rows: list[list[float]]) -> str:
    digest = hashlib.sha256()
    for row in rows:
        digest.update(struct.pack(f"<{len(row)}f", *row))
    return digest.hexdigest()


def metric(values: list[float]) -> dict:
    return {"median": median(values), "min": min(values), "max": max(values)}


def latencies(values: list[int]) -> dict:
    ordered = sorted(values)
    # Linear interpolation at index (n - 1) * q, including both endpoints.
    position = (len(ordered) - 1) * 0.95
    floor, ceil = math.floor(position), math.ceil(position)
    p95 = ordered[floor] + (ordered[ceil] - ordered[floor]) * (position - floor)
    return {"samples": len(ordered), "p50_ms": median(ordered) / NS_PER_MS, "p95_ms": p95 / NS_PER_MS}


def positive_integer(number: int, label: str) -> None:
    require(type(number) is int and number > 0, f"{label} must be a positive integer")


def durations(values: list[int], count: int, label: str) -> None:
    require(len(values) == count, f"expected {count} {label} timings, got {len(values)}")
    for value in values:
        positive_integer(value, label)


def read_events(directory: Path, rounds: int) -> tuple[list[dict], list[dict]]:
    with (directory / "events.tsv").open(newline="") as source:
        reader = csv.DictReader(source, delimiter="\t")
        require(reader.fieldnames == ["round", "position", "model", "attempt", "status", "filename"],
                "unexpected events.tsv header")
        events = list(reader)
    require(events, "no measurements in events.tsv")
    seen_paths = set()
    expected_slots = [
        (round_, position, model)
        for round_ in range(1, rounds + 1)
        for position, model in enumerate(("fp32", "int8") if round_ % 2 else ("int8", "fp32"), 1)
    ]
    slots = iter(expected_slots)
    expected = next(slots, None)
    successes, failures = [], []
    attempt = 1
    for event in events:
        for field in ("round", "position", "attempt"):
            event[field] = int(event[field])
        slot = event["round"], event["position"], event["model"]
        require(slot == expected and event["attempt"] == attempt,
                f"unexpected run order or attempt index in events.tsv at {event}")
        filename = event["filename"]
        require(filename and Path(filename).name == filename and filename not in seen_paths,
                f"expected unique run filename for {event}")
        seen_paths.add(filename)
        if event["status"] == "success":
            successes.append(event)
            expected = next(slots, None)
            attempt = 1
        elif event["status"] == "failed":
            failures.append(event)
            attempt += 1
        else:
            raise ValueError(f"unknown benchmark attempt status {event['status']}")
    require(expected is None, f"incomplete schedule, missing successful run at {expected}")
    return successes, failures


def check_run(path: Path, event: dict, fixture: dict, fixture_rows: list[list[float]],
              expected_rows: list[list[float]], expected_sha: str, environment: dict,
              warmup: int, measured: int) -> tuple[dict, list[int]]:
    report = load(path)
    for field in ("round", "position", "attempt", "model"):
        require(report[field] == event[field], f"{field} disagrees with events.tsv")
    for field in ENVIRONMENT_FIELDS:
        require(report[field] == environment[field], f"{field} disagrees with environment.json")
    require(report["runtime"] == RUNTIME, "unexpected LiteRT runtime")
    require(report["model_sha256"] == report["staged_model_sha256"] == expected_sha,
            "staged model SHA does not match the repo artifact")
    load_conditions = report["load_conditions"]
    timestamps = []
    for phase in ("before", "after"):
        snapshot = load_conditions[phase]
        for key in ("host_loadavg", "emulator_loadavg"):
            averages = snapshot[key]
            require(len(averages) == 3 and all(math.isfinite(a) and a >= 0 for a in averages),
                    f"invalid {phase} {key}")
        timestamp = datetime.fromisoformat(snapshot["timestamp_utc"])
        require(timestamp.utcoffset() is not None and timestamp.utcoffset().total_seconds() == 0,
                f"expected UTC timestamp for {phase} load conditions")
        timestamps.append(timestamp)
    require(timestamps == sorted(timestamps), "load condition timestamps are out of order")

    nrows = len(expected_rows)
    benchmark = report["benchmark"]
    require(benchmark["android_build"] == environment["device_fingerprint"], "Android build fingerprint differs")
    for metric_name, expected in (
        ("warmup_passes", warmup),
        ("measured_passes", measured),
        ("windows_per_pass", nrows),
        ("warmup_inferences", warmup * nrows),
        ("measured_inferences", measured * nrows),
    ):
        require(benchmark[metric_name] == expected, f"{metric_name} must be {expected}")
    require(benchmark["reference"] == "device", "run did not validate against a same-model reference")
    for field in ("pid", "process_start_elapsed_ms", "load_ns", "loop_wall_ns", "loop_thread_cpu_ns"):
        positive_integer(benchmark[field], field)
    durations(benchmark["warmup_inference_ns"], warmup * nrows, "warmup")
    samples = benchmark["inference_ns"]
    durations(samples, measured * nrows, "measured inference")
    measured_ns = sum(samples)
    wall_ns = benchmark["loop_wall_ns"]
    thread_cpu_ns = benchmark["loop_thread_cpu_ns"]
    outer_ns = report["instrumentation_wall_ns"]
    positive_integer(outer_ns, "instrumentation_wall_ns")
    warmup_ns = sum(benchmark["warmup_inference_ns"])
    require(measured_ns <= wall_ns and warmup_ns + wall_ns + benchmark["load_ns"] <= outer_ns
            and thread_cpu_ns <= outer_ns, "instrumentation, loop and invoke durations disagree")
    process_cpu_ms = benchmark["loop_process_cpu_ms"]
    require(type(process_cpu_ms) is int and process_cpu_ms >= 0, "invalid measured process CPU milliseconds")

    rows = probability_rows(report, fixture)
    fixture_delta = max_delta(rows, fixture_rows)
    if event["model"] == "fp32":
        require(fixture_delta <= FIXTURE_TOLERANCE, f"FP32 output differs from Emma's fixture by {fixture_delta:g}")
    actual_delta = max_delta(rows, expected_rows)
    require(actual_delta <= REFERENCE_TOLERANCE, f"first-pass output differs from reference by {actual_delta:g}")
    reported_delta = benchmark["max_reference_delta"]
    require(0 <= reported_delta <= REFERENCE_TOLERANCE, f"invalid Android reference delta {reported_delta:g}")
    output_sha = output_hash(rows)
    require(benchmark["output_sha256"] == output_sha, "first-pass output SHA does not match the recorded probabilities")

    memory = benchmark["memory"]
    phases = ("before_load", "after_allocate", "after_inference")
    peak_values = []
    for phase in phases:
        hwm, rss = (memory[phase][name] for name in ("vm_hwm_kib", "vm_rss_kib"))
        positive_integer(hwm, f"{phase} VmHWM")
        positive_integer(rss, f"{phase} VmRSS")
        require(hwm >= rss, f"{phase} VmHWM is below current VmRSS")
        peak_values.append(hwm)
    require(peak_values == sorted(peak_values), "process high-water mark decreased")

    timings = latencies(samples)
    return {
        **event,
        "pid": benchmark["pid"],
        "process_start_elapsed_ms": benchmark["process_start_elapsed_ms"],
        "output_sha256": output_sha,
        "max_reference_delta": max(actual_delta, reported_delta),
        "max_fixture_delta": fixture_delta,
        "warmup_inferences": benchmark["warmup_inferences"],
        "measured_inferences": timings["samples"],
        "load_ms": benchmark["load_ns"] / NS_PER_MS,
        "inference_p50_ms": timings["p50_ms"],
        "inference_p95_ms": timings["p95_ms"],
        "last_warmup_pass_p50_ms": median(benchmark["warmup_inference_ns"][-nrows:]) / NS_PER_MS,
        "loop_wall_ms": wall_ns / NS_PER_MS,
        "loop_thread_cpu_ms": thread_cpu_ns / NS_PER_MS,
        "loop_process_cpu_ms": process_cpu_ms,
        "instrumentation_wall_ms": outer_ns / NS_PER_MS,
        "thread_cpu_pct": 100 * thread_cpu_ns / wall_ns,
        "invoke_wall_pct": 100 * measured_ns / wall_ns,
        "load_conditions": load_conditions,
        "memory": memory,
        "baseline_hwm_kib": peak_values[0],
        "after_allocate_hwm_kib": peak_values[1],
        "peak_hwm_kib": peak_values[-1],
        "hwm_growth_kib": peak_values[-1] - peak_values[0],
    }, samples


def summarize(
    directory: Path, rounds: int, warmup: int, measured: int, references: dict[str, Path], serial: str,
) -> dict:
    successes, failures = read_events(directory, rounds)
    environment = load(directory / "environment.json")
    require(environment["serial"] == serial, "unexpected ADB serial in environment.json")
    require(environment["device_abi"] in {"arm64-v8a", "armeabi-v7a", "x86_64"},
            "unsupported device ABI in environment.json")
    for field in ("device_api", "device_logical_cpus", "host_logical_cpus"):
        positive_integer(environment[field], field)
    artifacts = environment["runtime_aars_sha256"]
    require(set(artifacts) == {"litert", "litert-api"}
            and all(len(digest) == 64 and set(digest) <= set("0123456789abcdef") for digest in artifacts.values()),
            "invalid verified AAR hashes in environment.json")
    fixture = load(FIXTURE)
    fixture_rows = probability_rows(fixture, fixture)
    require(len(fixture["clips"]) == 10 and len(fixture_rows) == 65, "unexpected handoff fixture workload")
    model_hashes = {name: sha256(path) for name, path in MODELS.items()}
    expected_rows = {}
    for model, path in references.items():
        reference = load(path)
        require(reference["model_sha256"] == model_hashes[model], f"{model} reference uses different weights")
        require(reference["runtime"].startswith(REFERENCE_RUNTIME), f"{model} reference does not use LiteRT 2.4.0")
        expected_rows[model] = probability_rows(reference, fixture)
    fixture_delta = max_delta(expected_rows["fp32"], fixture_rows)
    require(fixture_delta <= FIXTURE_TOLERANCE, f"FP32 reference differs from Emma's fixture by {fixture_delta:g}")

    runs = {name: [] for name in MODELS}
    pooled = {name: [] for name in MODELS}
    pids = set()
    previous_start = -1
    for event in successes:
        filename, model = event["filename"], event["model"]
        try:
            run, samples = check_run(directory / filename, event, fixture, fixture_rows, expected_rows[model],
                                     model_hashes[model], environment, warmup, measured)
        except (ValueError, KeyError, TypeError) as error:
            raise ValueError(f"{filename}: {error}") from error
        require(run["pid"] not in pids and run["process_start_elapsed_ms"] > previous_start,
                f"{filename}: process was not fresh and later than the preceding one")
        pids.add(run["pid"])
        previous_start = run["process_start_elapsed_ms"]
        runs[model].append(run)
        pooled[model].extend(samples)

    metrics = (
        "load_ms", "inference_p50_ms", "inference_p95_ms", "last_warmup_pass_p50_ms",
        "loop_wall_ms", "instrumentation_wall_ms", "thread_cpu_pct", "invoke_wall_pct",
        "baseline_hwm_kib", "after_allocate_hwm_kib", "peak_hwm_kib", "hwm_growth_kib",
    )
    variants = {}
    for model, model_runs in runs.items():
        variants[model] = {
            "model_sha256": model_hashes[model],
            "model_bytes": MODELS[model].stat().st_size,
            "successful_runs": len(model_runs),
            "reference": str(references[model]),
            "max_reference_delta": max(run["max_reference_delta"] for run in model_runs),
            "max_fixture_delta": max(run["max_fixture_delta"] for run in model_runs),
            "output_sha256s": sorted({run["output_sha256"] for run in model_runs}),
            "pooled_inference": latencies(pooled[model]),
            "run_ranges": {name: metric([run[name] for run in model_runs]) for name in metrics},
            "runs": model_runs,
        }
    pairs = []
    for baseline, candidate in zip(runs["fp32"], runs["int8"], strict=True):
        require(baseline["round"] == candidate["round"], "paired runs are from different rounds")
        pairs.append({"round": baseline["round"],
                      "int8_minus_fp32": {name: candidate[name] - baseline[name] for name in metrics}})
    paired = {}
    for name in metrics:
        differences = [pair["int8_minus_fp32"][name] for pair in pairs]
        directions = Counter("lower" if diff < 0 else "higher" if diff > 0 else "equal" for diff in differences)
        paired[name] = {**metric(differences), **{direction: directions[direction]
                                                for direction in ("lower", "equal", "higher")}}
    pooled_differences = {}
    for name in ("p50_ms", "p95_ms"):
        before = variants["fp32"]["pooled_inference"][name]
        after = variants["int8"]["pooled_inference"][name]
        pooled_differences[name] = {"int8_minus_fp32": after - before, "percent_change": 100 * (after - before) / before}
    return {
        "environment": environment,
        "runtime": RUNTIME,
        "rounds": rounds,
        "warmup_passes_per_run": warmup,
        "measured_passes_per_run": measured,
        "latency_quantiles": "linear interpolation at sorted index (sample count - 1) * quantile",
        "clips_per_pass": len(fixture["clips"]),
        "windows_per_pass": len(fixture_rows),
        "successful_attempts": len(successes),
        "failed_attempt_count": len(failures),
        "retried_measurements": sum(event["attempt"] > 1 for event in successes),
        "failed_attempts": failures,
        "attempt_count": len(successes) + len(failures),
        "fp32_reference_vs_emma_max_delta": fixture_delta,
        "variants": variants,
        "paired_run_differences": paired,
        "round_pairs": pairs,
        "pooled_inference_differences": pooled_differences,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Benchmark output directory containing events.tsv")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="ADB serial used for the captured runs")
    parser.add_argument("--rounds", type=int, default=6, help="Number of rounds, 1 for a pilot")
    parser.add_argument("--warmup-passes", type=int, default=3)
    parser.add_argument("--measured-passes", type=int, default=20)
    parser.add_argument("--fp32-reference", type=Path)
    parser.add_argument("--int8-reference", type=Path)
    parser.add_argument("--output", type=Path, help="Write summary JSON to this path as well as stdout")
    args = parser.parse_args()
    try:
        for name in ("rounds", "warmup_passes", "measured_passes"):
            positive_integer(getattr(args, name), name)
        references = {
            "fp32": args.fp32_reference or args.directory / "reference-fp32.json",
            "int8": args.int8_reference or args.directory / "reference-int8.json",
        }
        report = summarize(args.directory, args.rounds, args.warmup_passes, args.measured_passes, references,
                           args.serial)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"Benchmark validation failed: {error}\n")
    serialized = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(serialized)
    print(serialized, end="")


if __name__ == "__main__":
    main()

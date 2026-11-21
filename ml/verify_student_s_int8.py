"""Compare a student candidate on the bundled WAVs with the FP32 and Android outputs.

    python3 ml/verify_student_s_int8.py model.tflite [--android-json android.json]

The Android JSON uses the same classes, clips, starts and probability fields
as export/test_clips/expected.json. Probability drift is reported as measured.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from importlib.metadata import version
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

import preprocess
from verify_tflite import run

CLIPS = Path(__file__).resolve().parent / "export" / "test_clips"
TOLERANCE = 0.001


def infer(model: Path, fixture: dict) -> dict:
    interpreter = Interpreter(
        model_path=str(model), num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    clips = []
    for clip in fixture["clips"]:
        name = clip["file"]
        starts, windows = preprocess.active_window_starts(preprocess.load(CLIPS / name))
        probs = np.array([run(interpreter, window[None])[0] for window in windows])
        # Match classifyClip's double-precision accumulation and float32 result.
        clip_probs = probs.mean(axis=0, dtype=np.float64).astype(np.float32)
        clips.append({
            "file": name,
            "clip_probabilities": clip_probs.tolist(),
            "windows": [
                {"start_sample": start, "probabilities": values.tolist()}
                for start, values in zip(starts, probs, strict=True)
            ],
        })
    return {
        "model": model.name,
        "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
        "runtime": f"LiteRT {version('ai-edge-litert')} built-in CPU kernels",
        "classes": fixture["classes"],
        "clips": clips,
    }


def clip_index(report: dict) -> dict:
    clips = {clip["file"]: clip for clip in report["clips"]}
    if len(clips) != len(report["clips"]):
        raise ValueError("Duplicate clip names")
    return clips


def probabilities(clip: dict, classes: list[str]) -> tuple[np.ndarray, np.ndarray]:
    windows = np.array([window["probabilities"] for window in clip["windows"]], dtype=np.float64)
    average = np.array(clip["clip_probabilities"], dtype=np.float64)
    if windows.shape != (len(clip["windows"]), len(classes)) or average.shape != (len(classes),):
        raise ValueError(f"{clip['file']} has incorrect probability dimensions")
    if not np.isfinite(windows).all() or (windows < 0).any() or (windows > 1).any():
        raise ValueError(f"{clip['file']} has invalid window probabilities")
    if not np.allclose(windows.sum(axis=1), 1, rtol=0, atol=0.02):
        raise ValueError(f"{clip['file']} has window probabilities that do not sum to one")
    if not np.allclose(average, windows.mean(axis=0), rtol=0, atol=2e-6):
        raise ValueError(f"{clip['file']} clip probabilities do not match its window mean")
    return windows, average


def report_deltas(kind: str, rows: list[tuple[str, np.ndarray]], classes: list[str]) -> None:
    deltas = np.stack([delta for _, delta in rows])
    row, column = np.unravel_index(deltas.argmax(), deltas.shape)
    exceeded = deltas > TOLERANCE
    print(f"  {kind}: max abs delta={deltas[row, column]:.9g} ({rows[row][0]}, {classes[column]})")
    print(f"  {kind}: {exceeded.sum()}/{deltas.size} scalar comparisons > {TOLERANCE:g}; "
          f"{exceeded.any(axis=1).sum()}/{len(rows)} vectors with any delta > {TOLERANCE:g}")


def compare(title: str, reference: dict, actual: dict, truth: dict) -> None:
    classes = reference["classes"]
    if actual["classes"] != classes:
        raise ValueError(f"{title}: class order differs")
    ref_clips, actual_clips = clip_index(reference), clip_index(actual)
    if ref_clips.keys() != actual_clips.keys():
        raise ValueError(f"{title}: clip names differ: {sorted(ref_clips.keys() ^ actual_clips.keys())}")

    print(title)
    window_deltas, clip_deltas = [], []
    changed_windows = changed_clips = correct_before = correct_after = 0
    for name, before in ref_clips.items():
        after = actual_clips[name]
        starts = [window["start_sample"] for window in before["windows"]]
        actual_starts = [window["start_sample"] for window in after["windows"]]
        if starts != actual_starts:
            raise ValueError(f"{title}: {name} starts differ: expected {starts}, got {actual_starts}")

        before_windows, before_clip = probabilities(before, classes)
        after_windows, after_clip = probabilities(after, classes)
        before_top = before.get("expected_top_class", classes[before_clip.argmax()])
        after_top = classes[after_clip.argmax()]
        print(f"  {name}: starts={starts}, clip top={before_top} -> {after_top}")
        changed_clips += before_top != after_top
        correct_before += before_top == truth[name]["true_class"]
        correct_after += after_top == truth[name]["true_class"]
        clip_deltas.append((name, np.abs(after_clip - before_clip)))
        for start, before_prob, after_prob in zip(starts, before_windows, after_windows, strict=True):
            location = f"{name} start={start}"
            window_deltas.append((location, np.abs(after_prob - before_prob)))
            ref_label, actual_label = classes[before_prob.argmax()], classes[after_prob.argmax()]
            if ref_label != actual_label:
                changed_windows += 1
                print(f"    start={start}: window top={ref_label} -> {actual_label}")

    print(f"  Starts matched for {len(window_deltas)} windows across {len(ref_clips)} clips")
    print(f"  Top class changed for {changed_windows}/{len(window_deltas)} windows and "
          f"{changed_clips}/{len(ref_clips)} clips")
    print(f"  Correct labels on handoff clips: {correct_before}/{len(ref_clips)} -> "
          f"{correct_after}/{len(ref_clips)}")
    report_deltas("Window probabilities", window_deltas, classes)
    report_deltas("Clip averages", clip_deltas, classes)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("model", type=Path)
    parser.add_argument("--android-json", type=Path)
    parser.add_argument("--host-json", type=Path, help="Write the full host scores for all clips")
    args = parser.parse_args()

    fixture = json.loads((CLIPS / "expected.json").read_text())
    truth = clip_index(fixture)
    if len(truth) != 10 or sorted(truth) != sorted(path.name for path in CLIPS.glob("*.wav")):
        raise ValueError("The FP32 fixture must describe all ten bundled WAVs")
    host = infer(args.model, fixture)
    if args.host_json:
        args.host_json.write_text(json.dumps(host, indent=2) + "\n")
    print(f"Model: {args.model} sha256={host['model_sha256']}")
    print(f"Host runtime: {host['runtime']}")
    compare("FP32 fixture -> host candidate", fixture, host, truth)
    if args.android_json:
        android = json.loads(args.android_json.read_text())
        if "model_sha256" in android and android["model_sha256"] != host["model_sha256"]:
            raise ValueError("Android ran a different model from the host candidate")
        print(f"Android scores: {args.android_json}")
        if "serial" in android:
            print(f"ADB serial: {android['serial']}")
        if "runtime" in android:
            print(f"Android runtime: {android['runtime']}")
        compare("FP32 fixture -> Android candidate", fixture, android, truth)
        compare("Host candidate -> Android candidate", host, android, truth)


if __name__ == "__main__":
    main()

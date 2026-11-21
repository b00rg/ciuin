"""Score the exact Student S FP32 and INT8 artifacts on the B1 held-out split."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import platform
import sqlite3
from collections import Counter
from contextlib import closing
from importlib.metadata import version
from pathlib import Path

import numpy as np
import soundfile as sf
from ai_edge_litert.interpreter import Interpreter, OpResolverType

import preprocess
import teacher
from build_index import DEMAND_CUTS, ESC_SPLIT, US8K_SPLIT
from check_labels import kotlin_labels, load as load_labels
from verify_tflite import run

EXPORT = Path(__file__).resolve().parent / "export"
TEST_COUNTS = {"demand": 4, "esc50": 400, "us8k": 1653}
FP32_SHA256 = "250b5a9f0e4409315413c18230e71083591ad106715541d76f0edd9a4dc99022"
US8K_MANIFEST_MD5 = "1c4940e08c1305c49b592f3d9c103e6f"


def read_split(index: Path, audio_root: Path, labels: dict) -> list[dict]:
    with closing(sqlite3.connect(index.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        index_hash = db.execute("SELECT value FROM meta WHERE key = 'labels_sha1'").fetchone()
        if not index_hash or index_hash[0] != hashlib.sha1((EXPORT / "labels.yaml").read_bytes()).hexdigest():
            raise ValueError("clip index does not use the handoff label mapping")
        # Emma's results list MUSAN and FSD50K as absent from the original run.
        clips = [dict(row) for row in db.execute(
            "SELECT dataset, path, start_s, end_s, source_label, class_index, fold FROM clips "
            "WHERE use = 'classify' AND split = 'test' AND dataset NOT IN ('musan', 'fsd50k') "
            "ORDER BY dataset, path, start_s"
        )]

    counts = Counter(clip["dataset"] for clip in clips)
    if counts != TEST_COUNTS:
        raise ValueError(f"expected original dataset counts {TEST_COUNTS}, got {dict(counts)}")
    indexed = {}
    for clip in clips:
        ds, label, fold = clip["dataset"], clip["source_label"], clip["fold"]
        if clip["class_index"] != labels["classes"].index(labels["mapping"][ds][label]):
            raise ValueError(f"class mapping differs for {clip['path']}")
        if ds in ("esc50", "us8k"):
            split = ESC_SPLIT if ds == "esc50" else US8K_SPLIT
            if (split[int(fold)] != "test" or clip["start_s"] != 0
                    or clip["end_s"] != sf.info(audio_root / clip["path"]).duration):
                raise ValueError(f"invalid test fold or segment for {clip['path']}")
            indexed[(ds, clip["path"])] = (int(fold), label)
        else:
            path = audio_root / clip["path"]
            if clip["path"] != f"DEMAND/{label}/ch01.wav" or fold != label:
                raise ValueError(f"invalid DEMAND path or environment for {clip['path']}")
            duration = sf.info(path).duration
            if clip["start_s"] != round(duration * DEMAND_CUTS[1], 3) or clip["end_s"] != duration:
                raise ValueError(f"invalid DEMAND test time range for {clip['path']}")
    if {clip["source_label"] for clip in clips if clip["dataset"] == "demand"} != set(labels["mapping"]["demand"]):
        raise ValueError("DEMAND test environments do not match the handoff")
    with (audio_root / "ESC-50/meta/esc50.csv").open(newline="") as stream:
        source = {
            ("esc50", f"ESC-50/audio/{row['filename']}"): (int(row["fold"]), row["category"])
            for row in csv.DictReader(stream) if ESC_SPLIT[int(row["fold"])] == "test"
        }
    with (audio_root / "UrbanSound8K/metadata/UrbanSound8K.csv").open(newline="") as stream:
        source.update({
            ("us8k", f"UrbanSound8K/audio/fold{row['fold']}/{row['slice_file_name']}"):
                (int(row["fold"]), row["class"])
            for row in csv.DictReader(stream) if US8K_SPLIT[int(row["fold"])] == "test"
        })
    if indexed != source:
        raise ValueError("indexed test clip paths, folds, or categories differ from the published metadata")
    return clips


def verify_us8k_audio(manifest_file: Path, clips: list[dict], audio_root: Path) -> None:
    contents = manifest_file.read_bytes()
    if hashlib.md5(contents).hexdigest() != US8K_MANIFEST_MD5:
        raise ValueError("Soundata manifest does not match the published UrbanSound8K index")
    manifest = json.loads(contents)
    checksums = {}
    for entry in manifest["clips"].values():
        path, digest = entry["audio"]
        if Path(path).parent.name in ("fold9", "fold10"):
            checksums[path] = digest
    paths = {c["path"].removeprefix("UrbanSound8K/") for c in clips if c["dataset"] == "us8k"}
    if paths != checksums.keys():
        raise ValueError("indexed UrbanSound8K paths differ from the Soundata manifest")
    metadata_path, metadata_digest = manifest["metadata"]["UrbanSound8K.csv"]
    checksums[metadata_path] = metadata_digest
    for path, expected in checksums.items():
        with (audio_root / "UrbanSound8K" / path).open("rb") as stream:
            if hashlib.file_digest(stream, "md5").hexdigest() != expected:
                raise ValueError(f"UrbanSound8K source bytes differ for {path}")


def verify_handoff_sources(clips: list[dict], audio_root: Path, classes: list[str]) -> None:
    fixture_dir = EXPORT / "test_clips"
    fixture = json.loads((fixture_dir / "expected.json").read_text())
    if fixture["classes"] != classes:
        raise ValueError("handoff fixture class order differs from labels.yaml")
    # Reproduce the fixed-seed sampling in student.write_test_clips.
    rng = np.random.default_rng(teacher.SEED)
    short = [c for c in clips if c["dataset"] in ("esc50", "us8k") and c["end_s"] - c["start_s"] <= 5]
    picked = []
    for index, recorded in zip(
        [*range(len(classes)), classes.index("siren")], fixture["clips"], strict=True
    ):
        pool = [c for c in short if c["class_index"] == index and c not in picked]
        clip = pool[int(rng.integers(len(pool)))]
        picked.append(clip)
        if (f"{clip['dataset']}:{clip['path']}", classes[index]) != (recorded["source"], recorded["true_class"]):
            raise ValueError(f"original handoff selection differs for {recorded['file']}")

        raw = preprocess.load(audio_root / clip["path"], clip["start_s"], clip["end_s"])
        with io.BytesIO() as wav:
            sf.write(wav, raw, preprocess.SAMPLE_RATE, format="WAV", subtype="PCM_16")
            wav.seek(0)
            recovered, _ = sf.read(wav, dtype="int16")
        expected, _ = sf.read(fixture_dir / recorded["file"], dtype="int16")
        if not np.array_equal(recovered, expected):
            raise ValueError(f"recovered audio differs from the original sample {recorded['file']}")


def interpreter(model: bytes) -> Interpreter:
    engine = Interpreter(
        model_content=model, num_threads=1,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    engine.allocate_tensors()
    return engine


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio-root", type=Path, default=teacher.AUDIO_RAW)
    parser.add_argument("--index", type=Path, default=teacher.INDEX.with_name("audio_test_index.sqlite"))
    parser.add_argument("--int8", type=Path, default=EXPORT / "audio-student-s-int8.tflite")
    parser.add_argument("--us8k-manifest", type=Path, help="verify every UrbanSound8K WAV against the published Soundata manifest")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.index.is_file():
        parser.error(f"index does not exist: {args.index}")

    labels = load_labels()
    classes = labels["classes"]
    if classes != (EXPORT / "labels.txt").read_text().splitlines() or classes != kotlin_labels():
        raise ValueError("labels.txt or Kotlin SoundClass order differs from the handoff label mapping")
    clips = read_split(args.index, args.audio_root, labels)
    if args.us8k_manifest:
        verify_us8k_audio(args.us8k_manifest, clips, args.audio_root)
        print("Verified all 1,653 UrbanSound8K WAVs and the original full CSV against Soundata.", flush=True)
    verify_handoff_sources(clips, args.audio_root, classes)
    print(f"Verified {len(clips)} test rows and all ten original handoff sources.", flush=True)

    fp32 = (EXPORT / "audio-student-s.tflite").read_bytes()
    int8 = args.int8.read_bytes()
    if hashlib.sha256(fp32).hexdigest() != FP32_SHA256:
        raise ValueError("FP32 file does not match Emma's exported weights")
    engines = [interpreter(model) for model in (fp32, int8)]
    manifest = json.dumps(clips, separators=(",", ":"), sort_keys=True).encode()
    predictions = [[], []]
    n_windows = 0
    teacher.AUDIO_RAW = args.audio_root
    for count, clip in enumerate(clips, 1):
        _, windows = teacher.clip_windows(clip)
        n_windows += len(windows)
        for engine, preds in zip(engines, predictions, strict=True):
            scores = np.stack([run(engine, window[None, :])[0] for window in windows])
            preds.append(int(scores.mean(axis=0, dtype=np.float64).argmax()))
        if count % 250 == 0:
            print(f"Scored {count}/{len(clips)} clips.", flush=True)

    true = np.array([clip["class_index"] for clip in clips])
    fp32_pred, int8_pred = map(np.array, predictions)
    fp32_metrics = teacher.metrics(fp32_pred, true, classes)
    int8_metrics = teacher.metrics(int8_pred, true, classes)
    recorded = json.loads((EXPORT / "results.json").read_text())["audio-student-s"]["test"]
    if any(abs(fp32_metrics[key] - recorded[key]) >= 0.01 for key in ("accuracy", "macro_f1")):
        raise ValueError(f"recovered FP32 scores {fp32_metrics} differ from Emma's recorded test run")

    result = {
        "handoff_commit": "5568e2f1d6a735e17e548f005febceece6a521ec",
        "runtime": f"ai-edge-litert {version('ai-edge-litert')}, built-in CPU, 1 thread, no delegates",
        "host_platform": f"{platform.system()} {platform.machine()}",
        "test_manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        "us8k_soundata_manifest_md5": US8K_MANIFEST_MD5 if args.us8k_manifest else None,
        "dataset_counts": TEST_COUNTS,
        "class_counts": {label: int((true == index).sum()) for index, label in enumerate(classes)},
        "n_windows_per_model": n_windows,
        "fp32_sha256": FP32_SHA256,
        "int8_sha256": hashlib.sha256(int8).hexdigest(),
        "fp32": fp32_metrics,
        "int8": int8_metrics,
        "macro_f1_delta_percentage_points": round(100 * (int8_metrics["macro_f1"] - fp32_metrics["macro_f1"]), 2),
        "accuracy_delta_percentage_points": round(100 * (int8_metrics["accuracy"] - fp32_metrics["accuracy"]), 2),
        "changed_clip_predictions": int((fp32_pred != int8_pred).sum()),
    }
    report = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(report)
    print(report, end="")


if __name__ == "__main__":
    main()

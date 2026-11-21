"""Per-class loss weights = importance (ml/export/labels.yaml) x data balance.

    python3 ml/class_weights.py   # count the train split, write ml/class_weights.json
                                  # and the loss-weight table in ml/README.md

    w_c ∝ importance_c * min((1 / (K * freq_c)) ** power, max_balance)

then normalised so the average weight over training examples is 1 (keeps
the loss scale unchanged). power=0.5 (square-root inverse frequency) boosts
rare classes without letting a few hundred clips dominate; max_balance caps
the boost.

Use these weights on the cross-entropy (hard-label) term only. Don't weight
the distillation KL term: it should copy the teacher's full distribution.

Train split (Claude/todo.md and ml/README.md): ESC-50 folds 1-3,
UrbanSound8K folds 1-7, FSD50K dev rows with split=train, ~80% of MUSAN
files, first ~80% of each DEMAND recording. MUSAN and DEMAND recordings are
minutes long, so they count as 5 s windows (MUSAN capped at --musan-cap per
file); the training sampler must draw the same way.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import wave
from collections import Counter
from pathlib import Path

from check_labels import AUDIO_RAW, ML, load

ESC_TRAIN_FOLDS = {1, 2, 3}
US8K_TRAIN_FOLDS = {1, 2, 3, 4, 5, 6, 7}
TRAIN_FRACTION = 0.8  # MUSAN (by file) and DEMAND (by time)
WINDOW_S = 5.0
README = ML / "README.md"
START, END = "<!-- class-weights:start -->", "<!-- class-weights:end -->"


def wav_seconds(path: Path) -> float:
    with wave.open(str(path)) as w:
        return w.getnframes() / w.getframerate()


def fsd50k_class(labels: str, mapping: dict) -> str | None:
    have = set(labels.split(","))
    return next((dst for src, dst in mapping.items() if src in have), None)  # yaml order = priority


def count_train(raw: Path, mapping: dict, musan_cap: int) -> tuple[Counter, list[str]]:
    counts: Counter = Counter()
    missing = []

    esc = raw / "ESC-50" / "meta" / "esc50.csv"
    if esc.exists():
        with open(esc, newline="") as f:
            for row in csv.DictReader(f):
                if int(row["fold"]) in ESC_TRAIN_FOLDS:
                    counts[mapping["esc50"][row["category"]]] += 1
    else:
        missing.append("ESC-50")

    us8k = raw / "UrbanSound8K" / "metadata" / "UrbanSound8K.csv"
    if us8k.exists():
        with open(us8k, newline="") as f:
            for row in csv.DictReader(f):
                if int(row["fold"]) in US8K_TRAIN_FOLDS:
                    counts[mapping["us8k"][row["class"]]] += 1
    else:
        missing.append("UrbanSound8K")

    musan = raw / "musan"
    if musan.is_dir():
        for folder, target in mapping["musan"].items():
            if target is None:
                continue
            n = sum(min(max(1, int(wav_seconds(w) // WINDOW_S)), musan_cap)
                    for w in (musan / folder).rglob("*.wav"))
            counts[target] += round(n * TRAIN_FRACTION)
    else:
        missing.append("MUSAN")

    fsd = raw / "FSD50K" / "FSD50K.ground_truth" / "dev.csv"
    if fsd.exists():
        with open(fsd, newline="") as f:
            for row in csv.DictReader(f):
                if row["split"] == "train" and (c := fsd50k_class(row["labels"], mapping["fsd50k"])):
                    counts[c] += 1
    else:
        missing.append("FSD50K")

    demand = raw / "DEMAND"
    if demand.is_dir():
        for env, target in mapping["demand"].items():
            ch = demand / env / "ch01.wav"
            if ch.exists():  # one channel: the other 15 are near-duplicates
                counts[target] += int(wav_seconds(ch) * TRAIN_FRACTION // WINDOW_S)
            else:
                missing.append(f"DEMAND/{env}")
    else:
        missing.append("DEMAND")

    return counts, missing


def compute_weights(counts: Counter, importance: dict, classes: list[str],
                    power: float = 0.5, max_balance: float = 5.0) -> dict[str, float]:
    total = sum(counts[c] for c in classes)
    k = len(classes)
    raw = {}
    for c in classes:
        if counts[c] == 0:
            raise ValueError(f"no training examples for class '{c}'")
        freq = counts[c] / total
        raw[c] = importance[c] * min((1 / (k * freq)) ** power, max_balance)
    mean = sum(counts[c] * raw[c] for c in classes) / total
    return {c: round(raw[c] / mean, 2) for c in classes}


def table(counts: Counter, importance: dict, weights: dict, classes: list[str]) -> str:
    total = sum(counts[c] for c in classes)
    rows = ["| Class | Train examples | Share | Importance | Loss weight |", "|---|---:|---:|---:|---:|"]
    rows += [f"| `{c}` | {counts[c]:,} | {counts[c] / total:.1%} | {importance[c]} | **{weights[c]:.2f}** |"
             for c in classes]
    return "\n".join(rows)


def update_readme(md_table: str, note: str) -> None:
    text = README.read_text()
    if START not in text or END not in text:
        raise RuntimeError(f"{README} is missing the {START} / {END} markers")
    head, rest = text.split(START, 1)
    _, tail = rest.split(END, 1)
    README.write_text(f"{head}{START}\n{note}\n\n{md_table}\n{END}{tail}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", type=Path, default=AUDIO_RAW)
    ap.add_argument("--musan-cap", type=int, default=4, help="max 5 s windows drawn per MUSAN file")
    ap.add_argument("--power", type=float, default=0.5)
    ap.add_argument("--max-balance", type=float, default=5.0)
    ap.add_argument("--out", type=Path, default=ML / "class_weights.json")
    args = ap.parse_args()

    cfg = load()
    classes, importance = cfg["classes"], cfg["importance"]
    counts, missing = count_train(args.raw, cfg["mapping"], args.musan_cap)
    if missing:
        print(f"error: not downloaded yet in {args.raw}: {', '.join(missing)}")
        return 1

    weights = compute_weights(counts, importance, classes, args.power, args.max_balance)
    md = table(counts, importance, weights, classes)
    print(md)

    args.out.write_text(json.dumps({
        "classes": classes,
        "weights": [weights[c] for c in classes],
        "train_counts": [counts[c] for c in classes],
        "params": {"power": args.power, "max_balance": args.max_balance,
                   "musan_cap": args.musan_cap, "window_s": WINDOW_S},
    }, indent=2) + "\n")
    update_readme(md, "Computed by `ml/class_weights.py` from the downloaded train split.")
    print(f"wrote {args.out.relative_to(ML.parent)} and the table in {README.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

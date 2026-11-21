"""Per-class loss weights = importance (ml/export/labels.yaml) x data balance.

    python3 ml/class_weights.py                 # count train split, print table, write ml/class_weights.json
    python3 ml/class_weights.py --raw /path/to/data/raw/audio

    w_c ∝ importance_c * min((1 / (K * freq_c)) ** power, max_balance)

then normalised so the average weight over training examples is 1 (keeps
the loss scale unchanged). power=0.5 (square-root inverse frequency) boosts
rare classes without letting 24 alarm clips dominate.

Use these weights on the cross-entropy (hard-label) term only. Don't weight
the distillation KL term: it should copy the teacher's full distribution.

Counts come from the train split defined in Claude/todo.md:
ESC-50 folds 1-3, UrbanSound8K folds 1-7, MUSAN ~80% of files. MUSAN files
are minutes long, so each counts as min(duration / window, --musan-cap)
examples; the training sampler must use the same cap.
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
MUSAN_TRAIN_FRACTION = 0.8
WINDOW_S = 5.0


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
            n = 0.0
            for wav in (musan / folder).rglob("*.wav"):
                with wave.open(str(wav)) as w:
                    dur = w.getnframes() / w.getframerate()
                n += min(max(1, int(dur // WINDOW_S)), musan_cap)
            counts[target] += round(n * MUSAN_TRAIN_FRACTION)
    else:
        missing.append("MUSAN")

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
    return {c: round(raw[c] / mean, 4) for c in classes}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", type=Path, default=AUDIO_RAW)
    ap.add_argument("--musan-cap", type=int, default=4, help="max examples drawn per MUSAN file")
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
    total = sum(counts.values())
    print(f"{'class':<15}{'train':>8}{'share':>8}{'importance':>12}{'weight':>9}")
    for c in classes:
        print(f"{c:<15}{counts[c]:>8}{counts[c] / total:>8.1%}{importance[c]:>12}{weights[c]:>9.3f}")

    args.out.write_text(json.dumps({
        "classes": classes,
        "weights": [weights[c] for c in classes],
        "train_counts": [counts[c] for c in classes],
        "params": {"power": args.power, "max_balance": args.max_balance,
                   "musan_cap": args.musan_cap, "window_s": WINDOW_S},
    }, indent=2) + "\n")
    print(f"wrote {args.out.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

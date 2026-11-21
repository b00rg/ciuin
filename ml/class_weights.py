"""Per-class loss weights = importance (ml/export/labels.yaml) x data balance.

    python3 ml/build_index.py && python3 ml/class_weights.py   # count the train split, write ml/class_weights.json
                                  # and the loss-weight table in ml/README.md

    w_c ∝ importance_c * min((1 / (K * freq_c)) ** power, max_balance)

then normalised so the average weight over training examples is 1 (keeps
the loss scale unchanged). power=0.5 (square-root inverse frequency) boosts
rare classes without letting a few hundred clips dominate; max_balance caps
the boost.

Use these weights on the cross-entropy (hard-label) term only. Don't weight
the distillation KL term: it should copy the teacher's full distribution.

Counts come from the train split of the clip index (ml/build_index.py),
so they always match what training sees. MUSAN files and DEMAND segments
are minutes long, so they count as 5 s windows (MUSAN capped at
--musan-cap per file); the training sampler must draw the same way.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from collections import Counter
from pathlib import Path

from check_labels import ML, load

INDEX = ML.parent / "data" / "processed" / "audio_index.sqlite"
WINDOW_S = 5.0
README = ML / "README.md"
START, END = "<!-- class-weights:start -->", "<!-- class-weights:end -->"


def count_train(index: Path, musan_cap: int) -> tuple[Counter, list[str]]:
    """Training examples per class, from the clip index (ml/build_index.py).

    Short clips count once. MUSAN files and DEMAND segments are minutes long,
    so they count as 5 s windows (MUSAN capped at musan_cap per file).
    """
    db = sqlite3.connect(index)
    missing = [m for m in db.execute("SELECT value FROM meta WHERE key='missing'").fetchone()[0].split(",") if m]
    counts: Counter = Counter()
    for dataset, cls, start, end in db.execute(
            "SELECT dataset, class, start_s, end_s FROM clips WHERE split='train' AND use='classify'"):
        if dataset in ("musan", "demand"):
            windows = max(1, int((end - start) // WINDOW_S))
            counts[cls] += min(windows, musan_cap) if dataset == "musan" else windows
        else:
            counts[cls] += 1
    db.close()
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
    ap.add_argument("--index", type=Path, default=INDEX, help="built by ml/build_index.py")
    ap.add_argument("--allow-missing", action="store_true", help="compute even if some datasets aren't indexed yet")
    ap.add_argument("--musan-cap", type=int, default=4, help="max 5 s windows drawn per MUSAN file")
    ap.add_argument("--power", type=float, default=0.5)
    ap.add_argument("--max-balance", type=float, default=5.0)
    ap.add_argument("--out", type=Path, default=ML / "class_weights.json")
    args = ap.parse_args()

    cfg = load()
    classes, importance = cfg["classes"], cfg["importance"]
    if not args.index.exists():
        print(f"error: {args.index} not found; run ml/build_index.py first")
        return 1
    counts, missing = count_train(args.index, args.musan_cap)
    if missing and not args.allow_missing:
        print(f"error: the index is missing {', '.join(missing)}; re-run ml/build_index.py once downloaded "
              "(or pass --allow-missing)")
        return 1

    try:
        weights = compute_weights(counts, importance, classes, args.power, args.max_balance)
    except ValueError as e:
        print(f"error: {e} (its datasets aren't indexed yet)")
        return 1
    md = table(counts, importance, weights, classes)
    print(md)

    args.out.write_text(json.dumps({
        "classes": classes,
        "weights": [weights[c] for c in classes],
        "train_counts": [counts[c] for c in classes],
        "params": {"power": args.power, "max_balance": args.max_balance,
                   "musan_cap": args.musan_cap, "window_s": WINDOW_S},
    }, indent=2) + "\n")
    if missing:
        print("not updating ml/README.md: the index is incomplete")
    else:
        update_readme(md, "Computed by `ml/class_weights.py` from the train split in the clip index.")
    print(f"wrote {args.out.relative_to(ML.parent)} and the table in {README.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

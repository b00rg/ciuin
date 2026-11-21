"""Stage 1 -> 2 escalation rule for the cascade listener (#6).

    python3 ml/cascade_rule.py                                   # S student, fp32
    python3 ml/cascade_rule.py --model ml/export/audio-student-s-int8.tflite   # re-check on int8

Stage 1 runs the smallest student on a 1 s window. It hands over to stage 2
(bigger model, camera) when the window looks overwhelming, *or* when stage 1
isn't sure what it heard. This script picks the "isn't sure" rule:

  margin   escalate if  p_top1 - p_top2 < threshold
  entropy  escalate if  H(p) / ln(9)    > threshold   (normalised to [0, 1])

Each rule's threshold is set so that a target share of validation windows
escalate (default 15%, issue says 10–20%). The better rule is the one whose
escalated windows contain more of stage 1's mistakes at that rate. That's the
point of escalating: send the windows stage 1 gets wrong to stage 2.

Runs the given .tflite on raw audio, so the same script re-checks the threshold
on Paramveer's int8 models (quantisation can shift confidence values).

Output: ml/export/cascade.json ("stage1_to_2"; one entry per model checked).
"""

from __future__ import annotations

import argparse
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import preprocess as pp  # noqa: E402
import teacher as T  # noqa: E402
from check_labels import load as load_labels  # noqa: E402

EXPORT = ML / "export"
CASCADE = EXPORT / "cascade.json"
N_CLASSES = 9


def uncertainty(p: np.ndarray) -> dict[str, np.ndarray]:
    """Higher = less sure, for both rules (margin is negated for ranking)."""
    s = np.sort(p, axis=1)
    margin = s[:, -1] - s[:, -2]
    ent = -(p * np.log(np.clip(p, 1e-12, 1))).sum(1) / np.log(p.shape[1])
    return {"margin": margin, "entropy": ent}


def threshold_for(rule: str, score: np.ndarray, rate: float) -> float:
    # margin escalates when small, entropy when large.
    return float(np.quantile(score, rate if rule == "margin" else 1 - rate))


def escalates(rule: str, score: np.ndarray, thr: float) -> np.ndarray:
    return score < thr if rule == "margin" else score > thr


def auroc(err: np.ndarray, unsure: np.ndarray) -> float:
    """P(an error window looks less sure than a correct one)."""
    order = np.argsort(unsure)
    ranks = np.empty(len(unsure))
    ranks[order] = np.arange(1, len(unsure) + 1)
    n_e, n_c = err.sum(), (~err).sum()
    return float((ranks[err].sum() - n_e * (n_e + 1) / 2) / (n_e * n_c))


def val_windows(split: str):
    """All active windows of the split's clips: (waveforms, labels)."""
    clips = [c for c in T.index_clips() if c["split"] == split]

    def one(c):
        x = pp.load(T.AUDIO_RAW / c["path"], c["start_s"], c["end_s"])
        if len(x) < pp.WINDOW:
            x = np.pad(x, (0, pp.WINDOW - len(x)))
        _, w = pp.active_window_starts(x)
        if len(w) > T.MAX_WINDOWS:  # same cap as the teacher, so long recordings don't dominate
            w = w[np.unique(np.linspace(0, len(w) - 1, T.MAX_WINDOWS).round().astype(int))]
        return w, c["class_index"]

    W, y = [], []
    with ThreadPoolExecutor(8) as pool:
        for w, ci in pool.map(one, clips):
            W.append(w)
            y += [ci] * len(w)
    return np.concatenate(W).astype(np.float32), np.array(y)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=EXPORT / "audio-student-s.tflite")
    ap.add_argument("--rate", type=float, default=0.15, help="target escalation rate on val windows")
    ap.add_argument("--split", default="val")
    args = ap.parse_args()

    from student import tflite_predict

    classes = load_labels()["classes"]
    print(f"loading {args.split} windows ...", flush=True)
    W, y = val_windows(args.split)
    print(f"{len(W)} windows; running {args.model.name} ...", flush=True)
    p = tflite_predict(args.model)(W)
    err = p.argmax(1) != y
    scores = uncertainty(p)

    rules = {}
    for rule, s in scores.items():
        thr = threshold_for(rule, s, args.rate)
        esc = escalates(rule, s, thr)
        rules[rule] = {
            "threshold": round(thr, 4),
            "escalation_rate": round(float(esc.mean()), 4),
            "errors_caught": round(float(esc[err].mean()), 4),       # recall of stage-1 mistakes
            "error_rate_escalated": round(float(err[esc].mean()), 4),
            "error_rate_kept": round(float(err[~esc].mean()), 4),
            "auroc": round(auroc(err, -s if rule == "margin" else s), 4),
            "per_rate": {f"{r:.2f}": round(float(escalates(rule, s, threshold_for(rule, s, r))[err].mean()), 4)
                         for r in (0.10, 0.15, 0.20)},
        }
        print(f"  {rule:8s} threshold {thr:.4f}: escalates {esc.mean():.1%}, catches {esc[err].mean():.1%} "
              f"of stage-1 errors (AUROC {rules[rule]['auroc']:.3f})")
    best = max(rules, key=lambda r: (rules[r]["errors_caught"], rules[r]["auroc"]))
    print(f"stage-1 window accuracy {1 - err.mean():.3f}; chosen rule: {best}")

    cfg = json.loads(CASCADE.read_text()) if CASCADE.exists() else {}
    cfg.setdefault("_doc", "Cascade listener config (todo.md §0). Stage 1 -> 2 from ml/cascade_rule.py (#6); "
                           "stage 0 thresholds come from #18.")
    cfg.setdefault("stage0", {"status": "pending #18 (needs venue recordings + the Kotlin dB/flux code)"})
    cfg.setdefault("stage1_to_2", {})
    cfg["stage1_to_2"]["p_overwhelm_threshold"] = 0.5
    cfg["stage1_to_2"].setdefault("models", {})
    cfg["stage1_to_2"]["models"][args.model.stem] = {
        "rule": best,
        "threshold": rules[best]["threshold"],
        "how": ("escalate if p_top1 - p_top2 < threshold" if best == "margin"
                else "escalate if entropy(p) / ln(9) > threshold"),
        "target_escalation_rate": args.rate,
        "evaluated_on": f"{args.split} split, {len(W)} windows, per window (not clip-averaged)",
        "window_accuracy": round(float(1 - err.mean()), 4),
        "rules": rules,
    }
    cfg["stage1_to_2"]["default_model"] = cfg["stage1_to_2"].get("default_model", args.model.stem)
    CASCADE.write_text(json.dumps(cfg, indent=2) + "\n")
    print(f"wrote {CASCADE.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

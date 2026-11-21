"""Cascade stage 0: A-weighted level + spectral flux, no model (#18).

    python3 ml/stage0.py                     # tune on DEMAND ambience + inserted test-split events
    python3 ml/stage0.py --ambient DIR       # tune on your own recordings (WAVs, e.g. the #17 venue audio)

Stage 0 runs every second on the phone and decides whether to wake stage 1
(the smallest student). It must stay asleep through ordinary ambience and
wake fast for sirens, alarms and drilling. This file is the reference for
the Kotlin port: every feature is defined on a 1 s block of 16 kHz mono
float audio, with nothing but an FFT.

Features per 1 s block (16 000 samples):
  level_dba   A-weighted level in dBFS(A). 1024-sample Hann frames, hop 512;
              power spectrum x A(f)^2, summed over bins, averaged over frames.
              The mic is uncalibrated, so this is relative to full scale.
  flux        Spectral flux: per frame, sum of positive magnitude increases
              over the previous frame, divided by the previous frame's total
              magnitude; the block's value is the maximum over frames, so a
              single onset (a 50 ms beep) counts. A percentile misses it.
  baseline    Exponential moving average of level_dba with time constant
              `tau_s` (default 30 s), updated only while asleep, so a loud
              event doesn't drag the baseline up while it's happening.

Wake rule (any of):
  level_dba - baseline > jump_db         sudden rise over the room's own level
  flux > flux_max                        sharp onsets / tonal beeping
  level_dba > abs_dba                    loud regardless of history

Thresholds are fit by simulation: event clips (siren, alarm_beep,
construction, test split) are inserted into long ambient recordings at
0-15 dB over the ambience. Objective: fewest wakes per hour on ambience
alone, subject to catching >= 95% of events within 2 s.

Writes "stage0" to ml/export/cascade.json and a summary to stdout.
"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import product
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import preprocess as pp  # noqa: E402
import teacher as T  # noqa: E402

CASCADE = ML / "export" / "cascade.json"
SR = 16_000
BLOCK = SR                 # 1 s
N_FFT, HOP = 1024, 512
EVENT_CLASSES = ("siren", "alarm_beep", "construction")


# ---------------------------------------------------------------- features (port these)

def a_weight_db(f: np.ndarray) -> np.ndarray:
    """IEC 61672 A-weighting in dB at frequency f (Hz); 0 dB at 1 kHz."""
    f2 = np.maximum(f, 1e-6) ** 2
    ra = (12194.0 ** 2 * f2 ** 2) / ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2))
                                    * (f2 + 12194.0 ** 2))
    return 20 * np.log10(np.maximum(ra, 1e-12)) + 2.0


HANN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N_FFT) / N_FFT)).astype(np.float64)
A_GAIN = 10 ** (a_weight_db(np.fft.rfftfreq(N_FFT, 1 / SR)) / 10)          # power gain per bin
# Normalise so a full-scale 1 kHz sine reads 0 dBFS(A) (sum over its bins with this window).
_t = np.arange(N_FFT) / SR
_ref = np.abs(np.fft.rfft(HANN * np.sin(2 * np.pi * 1000 * _t))) ** 2
NORM = float((_ref * A_GAIN).sum())


def block_features(x: np.ndarray) -> tuple[float, float]:
    """(level_dba, flux) for one 1 s block."""
    n = 1 + (len(x) - N_FFT) // HOP
    frames = np.stack([x[i * HOP:i * HOP + N_FFT] for i in range(n)]) * HANN
    mag = np.abs(np.fft.rfft(frames, axis=1))
    power = (mag ** 2 * A_GAIN).sum(1) / NORM
    level = 10 * np.log10(power.mean() + 1e-12)
    inc = np.maximum(mag[1:] - mag[:-1], 0).sum(1) / (mag[:-1].sum(1) + 1e-9)
    flux = float(inc.max()) if len(inc) else 0.0
    return float(level), flux


def stream_features(x: np.ndarray) -> np.ndarray:
    """[n_blocks, 2] features for consecutive 1 s blocks."""
    return np.array([block_features(x[i:i + BLOCK]) for i in range(0, len(x) - BLOCK + 1, BLOCK)])


def run_stage0(feats: np.ndarray, p: dict) -> np.ndarray:
    """Wake decision per block (bool), with the asleep-only baseline update."""
    alpha = 1.0 / p["tau_s"]
    base = feats[0, 0]
    wake = np.zeros(len(feats), bool)
    for i, (lvl, flux) in enumerate(feats):
        w = (lvl - base > p["jump_db"]) or (flux > p["flux_max"]) or (lvl > p["abs_dba"])
        wake[i] = w
        if not w:
            base += alpha * (lvl - base)
    return wake


# ---------------------------------------------------------------- simulation

def ambient_streams(ambient_dir: Path | None) -> dict[str, np.ndarray]:
    if ambient_dir:
        return {f.stem: pp.load(f) for f in sorted(ambient_dir.glob("*.wav"))}
    # DEMAND: full 5 min recordings, two mic positions per scene (ch01, ch09). Stage 0 has no
    # learned weights, so using the part the student trained on is fine here.
    out = {}
    for scene in sorted((T.AUDIO_RAW / "DEMAND").iterdir()):
        for ch in ("ch01", "ch09"):
            f = scene / f"{ch}.wav"
            if f.exists():
                out[f"{scene.name}-{ch}"] = pp.load(f)
    return out


def event_clips(n_per_class: int, rng) -> list[tuple[str, np.ndarray]]:
    classes = [l.strip() for l in (ML / "export" / "labels.txt").read_text().split()]
    want = {classes.index(c): c for c in EVENT_CLASSES}
    pool = [c for c in T.index_clips() if c["split"] == "test" and c["class_index"] in want
            and c["dataset"] in ("esc50", "us8k", "fsd50k") and c["end_s"] - c["start_s"] >= 1.5]
    out = []
    for ci, name in want.items():
        cs = [c for c in pool if c["class_index"] == ci]
        for k in rng.permutation(len(cs))[:n_per_class]:
            c = cs[k]
            out.append((name, pp.load(T.AUDIO_RAW / c["path"], c["start_s"], min(c["end_s"], c["start_s"] + 4))))
    return out


def rms(x):
    return float(np.sqrt(np.mean(x.astype(np.float64) ** 2) + 1e-12))


def insert_events(amb: np.ndarray, events, rng, gap_s=(8, 15), snr_db=(0, 15)):
    """Ambient copy with events added at random times; returns (signal, [(start_block, class)])."""
    x = amb.copy()
    marks, t = [], int(rng.uniform(*gap_s) * SR)
    order = rng.permutation(len(events))
    k = 0
    while t + 4 * SR < len(x) and k < len(order):
        name, ev = events[order[k]]
        seg = x[t:t + len(ev)]
        gain = rms(seg) / rms(ev) * 10 ** (rng.uniform(*snr_db) / 20)
        x[t:t + len(ev)] = np.clip(seg + gain * ev[:len(seg)], -1, 1)
        marks.append((t // BLOCK, name))
        t += len(ev) + int(rng.uniform(*gap_s) * SR)
        k += 1
    return x, marks


GRID = {"jump_db": [4, 6, 8, 10, 12], "flux_max": [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 99.0],
        "abs_dba": [-10, -15, -20], "tau_s": [30]}


def evaluate(p, amb_feats, event_runs, detect_within=2):
    false_wakes = sum(run_stage0(f, p).sum() for f in amb_feats.values())
    hours = sum(len(f) for f in amb_feats.values()) / 3600
    caught, total, by_class = 0, 0, {}
    for feats, marks in event_runs:
        wake = run_stage0(feats, p)
        for b, name in marks:
            hit = bool(wake[b:b + detect_within].any())
            caught += hit
            total += 1
            h, n = by_class.get(name, (0, 0))
            by_class[name] = (h + hit, n + 1)
    return {"wakes_per_hour_ambient": round(false_wakes / hours, 1),
            "ambient_awake_pct": round(100 * false_wakes / (hours * 3600), 1),
            "event_recall": round(caught / max(total, 1), 4),
            "recall_by_class": {k: round(h / n, 3) for k, (h, n) in by_class.items()},
            "n_events": total}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ambient", type=Path, help="folder of ambient WAVs (default: DEMAND val+test)")
    ap.add_argument("--min-recall", type=float, default=0.95)
    ap.add_argument("--events-per-class", type=int, default=40)
    args = ap.parse_args()

    rng = np.random.default_rng(0)
    amb = ambient_streams(args.ambient)
    print("ambient:", {k: f"{len(v) / SR:.0f}s" for k, v in amb.items()})
    events = event_clips(args.events_per_class, rng)
    print(f"events: {len(events)} clips ({', '.join(EVENT_CLASSES)}, test split)")

    amb_feats = {k: stream_features(v) for k, v in amb.items()}
    # Several insertion passes per stream, so each event is seen in different rooms at different SNRs.
    event_runs = []
    for rep in range(3):
        for k, v in amb.items():
            sig, marks = insert_events(v, events, np.random.default_rng(100 + rep * 10 + len(k)))
            event_runs.append((stream_features(sig), marks))

    rows = []
    for vals in product(*GRID.values()):
        p = dict(zip(GRID, vals))
        rows.append((p, evaluate(p, amb_feats, event_runs)))
    ok = [r for r in rows if r[1]["event_recall"] >= args.min_recall]
    pool = ok or rows
    best_p, best = min(pool, key=lambda r: (r[1]["wakes_per_hour_ambient"], -r[1]["event_recall"])) if ok else \
        max(pool, key=lambda r: (r[1]["event_recall"], -r[1]["wakes_per_hour_ambient"]))
    tradeoff = []
    for target in (0.85, 0.90, 0.95):
        cand = [r for r in rows if r[1]["event_recall"] >= target]
        if cand:
            tp, tr = min(cand, key=lambda r: r[1]["wakes_per_hour_ambient"])
            tradeoff.append({"min_recall": target, **tp, **{k: tr[k] for k in
                             ("event_recall", "ambient_awake_pct", "wakes_per_hour_ambient")}})
    print("trade-off (best setting per recall target):")
    for t in tradeoff:
        print(f"  recall >= {t['min_recall']:.0%}: jump {t['jump_db']} dB, flux {t['flux_max']}, abs {t['abs_dba']} "
              f"-> recall {t['event_recall']:.1%}, awake {t['ambient_awake_pct']}%")
    print(f"\nchosen: {best_p}")
    print(f"  ambient: awake {best['ambient_awake_pct']}% of seconds ({best['wakes_per_hour_ambient']} wakes/h)")
    print(f"  events:  {best['event_recall']:.1%} caught within 2 s  {best['recall_by_class']}  (n={best['n_events']})")
    if not ok:
        print(f"  WARNING: nothing reached {args.min_recall:.0%} recall; picked the highest-recall setting")

    cfg = json.loads(CASCADE.read_text()) if CASCADE.exists() else {}
    cfg["stage0"] = {
        "status": "provisional: tuned on DEMAND ambience (cafeteria, restaurant, station, square; 2 mics x 5 min each) "
                  "with inserted test-split events; re-tune on the venue recordings (#17) with --ambient" if not args.ambient
                  else f"tuned on {args.ambient}",
        "block_s": 1.0, "n_fft": N_FFT, "hop": HOP, "window": "hann (periodic)",
        "features": {"level_dba": "A-weighted dBFS(A), mean frame power, full-scale 1 kHz sine = 0",
                     "flux": "max over frames of sum(max(0, |X_t|-|X_t-1|)) / sum(|X_t-1|)",
                     "baseline": "EMA of level_dba, 1/tau_s per second, updated only while asleep"},
        "wake_if_any": {"level_dba - baseline >": best_p["jump_db"], "flux >": best_p["flux_max"],
                        "level_dba >": best_p["abs_dba"]},
        "tau_s": best_p["tau_s"],
        "simulation": best,
        "tradeoff": tradeoff,
        "grid_size": len(rows),
    }
    CASCADE.write_text(json.dumps(cfg, indent=2) + "\n")
    print(f"wrote stage0 to {CASCADE.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

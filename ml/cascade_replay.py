"""Replay recordings through the whole cascade offline (#7): the frugality evidence.

    python3 ml/cascade_replay.py                    # DEMAND ambience, with and without inserted events
    python3 ml/cascade_replay.py --ambient DIR      # your own recordings (e.g. the #17 venue audio)

Per second, exactly as the phone would:
  stage 0   A-weighted level + flux (ml/stage0.py, thresholds from cascade.json)
  stage 1   if stage 0 woke: the stage-1 model on the most recent 15,600 samples
  stage 2   if stage 1 is unsure (top-2 margin < cascade.json threshold): the stage-2 model
            on the same window

Reports per recording and overall: % of seconds with no model running,
stage-1 inferences per hour, escalation rate (stage 1 -> 2), stage-2
inferences per hour. With inserted events it also reports how many events
the cascade noticed (stage 0 woke within 2 s) and how many it identified
(stage 1 or 2's top class was the event's class within 2 s).

Not modelled: the personal model's p_overwhelm > 0.5 escalation (it needs
calibrated dB and camera features the recordings don't have), so stage-2
counts here are the uncertainty escalations only.

Output: ml/export/cascade_replay.json (separate from results.json so it
doesn't collide with the training PRs).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import stage0 as S0  # noqa: E402

EXPORT = ML / "export"
CASCADE = EXPORT / "cascade.json"
OUT = EXPORT / "cascade_replay.json"
WINDOW = 15_600


def tflite(path: Path):
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=str(path))
    it.allocate_tensors()
    i, o = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]

    def run(w):
        it.set_tensor(i, w[None].astype(np.float32))
        it.invoke()
        return it.get_tensor(o)[0].copy()

    return run


def stage0_params(cfg: dict) -> dict:
    w = cfg["stage0"]["wake_if_any"]
    return {"jump_db": w["level_dba - baseline >"], "flux_max": w["flux >"],
            "abs_dba": w["level_dba >"], "tau_s": cfg["stage0"]["tau_s"]}


def margin(p):
    s = np.sort(p)
    return float(s[-1] - s[-2])


def replay(x: np.ndarray, p0: dict, s1, s2, thr: float):
    """Per-second log: (woke, stage1 probs or None, escalated, stage2 probs or None)."""
    feats = S0.stream_features(x)
    wake = S0.run_stage0(feats, p0)
    log = []
    for b, w in enumerate(wake):
        if not w:
            log.append((False, None, False, None))
            continue
        end = (b + 1) * S0.BLOCK
        win = x[max(0, end - WINDOW):end]
        win = np.pad(win, (WINDOW - len(win), 0))
        p1 = s1(win)
        esc = margin(p1) < thr
        log.append((True, p1, esc, s2(win) if esc else None))
    return log


def summarise(log) -> dict:
    n = len(log)
    s1 = sum(w for w, *_ in log)
    s2 = sum(e for _, _, e, _ in log)
    hours = n / 3600
    return {"seconds": n, "no_model_pct": round(100 * (n - s1) / n, 1),
            "stage1_per_hour": round(s1 / hours), "stage2_per_hour": round(s2 / hours),
            "escalation_rate": round(s2 / s1, 3) if s1 else 0.0}


def event_scores(log, marks, classes, within: int = 2) -> dict:
    noticed = identified = 0
    by = {}
    for b, name in marks:
        span = log[b:b + within]
        n_ok = any(w for w, *_ in span)
        top = [classes[int(np.argmax(p2 if p2 is not None else p1))] for w, p1, _, p2 in span if w]
        i_ok = name in top
        noticed += n_ok
        identified += i_ok
        a, c, t = by.get(name, (0, 0, 0))
        by[name] = (a + n_ok, c + i_ok, t + 1)
    k = max(len(marks), 1)
    return {"n_events": len(marks), "noticed": round(noticed / k, 3), "identified": round(identified / k, 3),
            "by_class": {c: {"noticed": round(a / t, 3), "identified": round(i / t, 3)} for c, (a, i, t) in by.items()}}


def total(rows: list[dict]) -> dict:
    n = sum(r["seconds"] for r in rows)
    s1 = sum(r["stage1_per_hour"] * r["seconds"] / 3600 for r in rows)
    s2 = sum(r["stage2_per_hour"] * r["seconds"] / 3600 for r in rows)
    return {"seconds": n, "no_model_pct": round(100 * (1 - s1 / n), 1), "stage1_per_hour": round(s1 / n * 3600),
            "stage2_per_hour": round(s2 / n * 3600), "escalation_rate": round(s2 / s1, 3) if s1 else 0.0}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ambient", type=Path)
    ap.add_argument("--stage1", type=Path, default=EXPORT / "audio-student-s.tflite")
    ap.add_argument("--stage2", type=Path, default=None,
                    help="stage-2 model (default: audio-student-m.tflite if present, else the teacher)")
    args = ap.parse_args()

    cfg = json.loads(CASCADE.read_text())
    p0 = stage0_params(cfg)
    thr = cfg["stage1_to_2"]["models"][args.stage1.stem]["threshold"]
    stage2 = args.stage2 or next(p for p in (EXPORT / "audio-student-m.tflite", EXPORT / "audio-teacher.tflite")
                                 if p.exists())
    s1, s2 = tflite(args.stage1), tflite(stage2)
    classes = (EXPORT / "labels.txt").read_text().split()
    print(f"stage 0 {p0}; stage 1 {args.stage1.name} (margin < {thr}); stage 2 {stage2.name}")

    amb = S0.ambient_streams(args.ambient)
    out = {"stage0": p0, "stage1": args.stage1.name, "stage1_threshold": thr, "stage2": stage2.name,
           "ambient": {}, "with_events": {}}
    for name, x in amb.items():
        out["ambient"][name] = summarise(replay(x, p0, s1, s2, thr))
        print(f"  {name:16s} ambient: no model {out['ambient'][name]['no_model_pct']}% of the time, "
              f"{out['ambient'][name]['stage1_per_hour']} stage-1/h, {out['ambient'][name]['stage2_per_hour']} stage-2/h")
    out["ambient_total"] = total(list(out["ambient"].values()))

    # Same recordings with test-split events inserted (always: it's the detection check).
    for _ in (0,):
        events = S0.event_clips(40, np.random.default_rng(0))
        logs, all_marks = [], []
        for name, x in amb.items():
            sig, marks = S0.insert_events(x, events, np.random.default_rng(7 + len(name)))
            log = replay(sig, p0, s1, s2, thr)
            out["with_events"][name] = {**summarise(log), **event_scores(log, marks, classes)}
            logs.append(log)
            all_marks.append(marks)
        flat_log, flat_marks, off = [], [], 0
        for log, marks in zip(logs, all_marks):
            flat_log += log
            flat_marks += [(b + off, c) for b, c in marks]
            off += len(log)
        out["with_events_total"] = {**summarise(flat_log), **event_scores(flat_log, flat_marks, classes)}

    a, e = out["ambient_total"], out["with_events_total"]
    print(f"\nambient only ({a['seconds'] / 60:.0f} min): no model running {a['no_model_pct']}% of the time; "
          f"{a['stage1_per_hour']} stage-1 + {a['stage2_per_hour']} stage-2 inferences/h "
          f"(always-on S would be 3600/h)")
    print(f"with {e['n_events']} inserted events: noticed {e['noticed']:.1%}, identified {e['identified']:.1%}; "
          f"{e['by_class']}")
    OUT.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {OUT.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

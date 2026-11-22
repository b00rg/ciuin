"""Teacher boost: pick the best frozen-feature teacher head on VAL (#5 follow-up).

    CIUIN_TEACHER=concat python3 ml/teacher_boost.py

Needs the window embeddings from both backbones (run teacher.py with
CIUIN_TEACHER=yamnet and =panns first). Then, on the same windows, splits and
per-clip weights as teacher.py, trains and compares:

  panns        CNN14 embeddings (2048) -> head
  concat       CNN14 + YAMNet (3072)   -> head        different nets, different mistakes
  concat+clean as concat, after dropping FSD50K training windows whose label a
               5-fold out-of-fold head confidently contradicts (FSD50K's labels are weak:
               "this sound is somewhere in the clip")

The winner (by val clip macro-F1) is written as the concat teacher:
data/cache/teacher-concat/ (embedding shards + teacher_head.keras) and
results.json["teacher"], and its name to teacher_choice.txt so the students
use the right one. Test is scored once per variant, for the record only.
"""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import numpy as np

os.environ["CIUIN_TEACHER"] = "concat"
ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import teacher as T  # noqa: E402
from check_labels import load as load_labels  # noqa: E402
from class_weights import compute_weights, count_train  # noqa: E402

YAM, PAN = T.CACHE / "teacher", T.CACHE / "teacher-panns"


def build_concat(datasets):
    T.EMB_DIR.mkdir(parents=True, exist_ok=True)
    for ds in datasets:
        with np.load(YAM / f"emb_{ds}.npz") as a, np.load(PAN / f"emb_{ds}.npz") as b:
            for k in ("keys", "clip", "win_start"):
                assert np.array_equal(a[k], b[k]), f"{ds}: {k} differ between YAMNet and CNN14 caches"
            emb = np.concatenate([b["emb"], a["emb"]], axis=1).astype(np.float16)   # CNN14 first
            np.savez(T.shard_path(ds), keys=a["keys"], clip=a["clip"], win_start=a["win_start"], emb=emb)
        print(f"[concat] {ds}: {emb.shape}", flush=True)


def fit_head(X, y, sw, tr, va, d, classes, epochs=60, patience=8, seed=0):
    import keras

    keras.utils.set_random_seed(seed)
    head = keras.Sequential([
        keras.Input((X.shape[1],), name="embedding"),
        keras.layers.Dense(512, activation="relu"),
        keras.layers.Dropout(0.4),
        keras.layers.Dense(len(classes), activation="softmax", name="probabilities"),
    ], name="teacher_head")
    head.compile(keras.optimizers.Adam(1e-3), "sparse_categorical_crossentropy")
    best, best_f1, waited = None, -1.0, 0
    for _ in range(epochs):
        head.fit(X[tr], y[tr], sample_weight=sw[tr], batch_size=T.BATCH, epochs=1, shuffle=True, verbose=0)
        if va is None:
            continue
        p, t, _ = T.clip_scores(head.predict(X[va], batch_size=8192, verbose=0), d["clip"][va], y[va])
        f1 = T.metrics(p.argmax(1), t, classes)["macro_f1"]
        if f1 > best_f1:
            best, best_f1, waited = head.get_weights(), f1, 0
        else:
            waited += 1
            if waited >= patience:
                break
    if best is not None:
        head.set_weights(best)
    return head


def score(head, X, mask, d, classes):
    p, t, ids = T.clip_scores(head.predict(X[mask], batch_size=8192, verbose=0), d["clip"][mask], d["y"][mask])
    m = T.metrics(p.argmax(1), t, classes)
    first = {c: i for i, c in reversed(list(enumerate(d["clip"][mask])))}
    ds = np.array([d["dataset"][mask][first[i]] for i in ids])
    m["per_dataset_accuracy"] = {str(k): round(float((p.argmax(1)[ds == k] == t[ds == k]).mean()), 4)
                                 for k in sorted(set(ds))}
    return m


def main() -> int:
    t0 = time.time()
    labels = load_labels()
    classes = labels["classes"]
    clips = T.index_clips()
    datasets = sorted({c["dataset"] for c in clips})
    build_concat(datasets)
    d = T.load_windows(clips)
    X, y = d["X"], d["y"]
    tr, va, te = (d["split"] == s for s in ("train", "val", "test"))

    counts, missing = count_train(T.INDEX, T.MUSAN_CAP)
    cw = compute_weights(counts, labels["importance"], classes)
    wpc = np.bincount(d["clip"])[d["clip"]]
    ex = np.array([T.clip_examples(ds, s) for ds, s in zip(d["dataset"], d["seconds"])])
    sw = (np.array([cw[classes[c]] for c in y]) * ex / wpc).astype(np.float32)
    sw /= sw[tr].mean()

    feats = {"panns": X[:, :2048], "concat": X}
    results, heads = {}, {}
    for name in ("panns", "concat"):
        h = fit_head(feats[name], y, sw, tr, va, d, classes)
        heads[name] = h
        results[name] = {"val": score(h, feats[name], va, d, classes), "test": score(h, feats[name], te, d, classes)}
        print(f"[{name}] val acc {results[name]['val']['accuracy']:.4f} F1 {results[name]['val']['macro_f1']:.4f} | "
              f"test acc {results[name]['test']['accuracy']:.4f} F1 {results[name]['test']['macro_f1']:.4f} "
              f"({time.time() - t0:.0f}s)", flush=True)

    # Label cleaning: out-of-fold predictions on the training windows, folds by clip.
    rng = np.random.default_rng(0)
    tr_idx = np.flatnonzero(tr)
    tr_clips = np.unique(d["clip"][tr_idx])
    fold_of_clip = dict(zip(tr_clips, rng.integers(0, 5, len(tr_clips))))
    fold = np.array([fold_of_clip[c] for c in d["clip"][tr_idx]])
    oof = np.zeros((len(tr_idx), len(classes)), np.float32)
    for k in range(5):
        inner = np.zeros(len(X), bool)
        inner[tr_idx[fold != k]] = True
        h = fit_head(X, y, sw, inner, None, d, classes, epochs=12)
        oof[fold == k] = h.predict(X[tr_idx[fold == k]], batch_size=8192, verbose=0)
        print(f"[clean] fold {k + 1}/5 ({time.time() - t0:.0f}s)", flush=True)
    p_label = oof[np.arange(len(tr_idx)), y[tr_idx]]
    is_fsd = d["dataset"][tr_idx] == "fsd50k"
    drop = is_fsd & (p_label < 0.1) & (oof.max(1) > 0.7)
    sw_clean = sw.copy()
    sw_clean[tr_idx[drop]] = 0.0
    print(f"[clean] dropping {drop.sum()} of {is_fsd.sum()} FSD50K training windows "
          f"({drop.sum() / max(is_fsd.sum(), 1):.1%})", flush=True)
    h = fit_head(X, y, sw_clean, tr, va, d, classes)
    heads["concat+clean"] = h
    results["concat+clean"] = {"val": score(h, X, va, d, classes), "test": score(h, X, te, d, classes),
                               "dropped_fsd50k_windows": int(drop.sum())}
    r = results["concat+clean"]
    print(f"[concat+clean] val acc {r['val']['accuracy']:.4f} F1 {r['val']['macro_f1']:.4f} | "
          f"test acc {r['test']['accuracy']:.4f} F1 {r['test']['macro_f1']:.4f}", flush=True)

    best = max(results, key=lambda k: results[k]["val"]["macro_f1"])
    print(f"\nBEST on val: {best}", flush=True)
    for k, v in results.items():
        print(f"  {k:13s} val F1 {v['val']['macro_f1']:.4f}  test acc {v['test']['accuracy']:.4f}  "
              f"test F1 {v['test']['macro_f1']:.4f}  {v['test']['per_dataset_accuracy']}", flush=True)

    # The students read teacher-concat/: if CNN14-only won, store CNN14 features there instead.
    if best == "panns":
        for ds in datasets:
            with np.load(PAN / f"emb_{ds}.npz") as b:
                np.savez(T.shard_path(ds), keys=b["keys"], clip=b["clip"], win_start=b["win_start"], emb=b["emb"])
    heads[best].save(T.HEAD_PATH)
    (T.CACHE / "teacher_choice.txt").write_text(best + "\n")
    out = T.RESULTS
    res = json.loads(out.read_text()) if out.exists() else {}
    t = results[best]["test"]
    res["teacher"] = {
        "model": {"panns": "PANNs CNN14 embeddings (frozen) + MLP head 2048-512-9",
                  "concat": "PANNs CNN14 + YAMNet embeddings (frozen, concatenated) + MLP head 3072-512-9",
                  "concat+clean": "PANNs CNN14 + YAMNet embeddings (frozen) + MLP head 3072-512-9, "
                                  "FSD50K training labels cleaned with 5-fold out-of-fold predictions"}[best],
        "accuracy": t["accuracy"], "macro_f1": t["macro_f1"], "test": t, "val": results[best]["val"],
        "variants": {k: {"val_macro_f1": v["val"]["macro_f1"], "test_accuracy": v["test"]["accuracy"],
                         "test_macro_f1": v["test"]["macro_f1"]} for k, v in results.items()},
        "trained_without": missing, "class_weights": cw,
        "trained_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    out.write_text(json.dumps(res, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

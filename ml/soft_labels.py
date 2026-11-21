"""Cache the teacher's logits for every window, once (#4).

    python3 ml/soft_labels.py

Distillation runs (ml/student.py, every width, every alpha / temperature) read
these instead of calling the teacher again. One shard per dataset, in exactly
the window order of the embedding cache (data/cache/teacher/emb_<dataset>.npz),
keyed by clip ID ("<path>@<start_s>"):

    data/cache/teacher/logits_<dataset>.npz
        keys       [n_clips]           clip IDs, same as the embedding shard
        clip       [n_windows] int32   index into keys
        win_start  [n_windows] int32   sample offset of the window in its clip
        logits     [n_windows, 9]      float32, pre-softmax, SoundClass order
        head_mtime                     teacher head the logits came from

The logits are recomputed from the head's weights (Dense -> ReLU -> Dense; the
dropout is off at inference), so they are the real pre-softmax values, not
log-probabilities. A shard is rebuilt when the embeddings or the head change.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import teacher as T  # noqa: E402


def logits_path(dataset: str) -> Path:
    return T.EMB_DIR / f"logits_{dataset}.npz"


def head_weights():
    import keras

    head = keras.models.load_model(T.HEAD_PATH)
    dense = [l for l in head.layers if isinstance(l, keras.layers.Dense)]
    assert len(dense) == 2, "expected the 1024-512-9 head from teacher.py"
    return [w for l in dense for w in l.get_weights()]


def teacher_logits(emb: np.ndarray, w1, b1, w2, b2) -> np.ndarray:
    out = np.empty((len(emb), len(b2)), np.float32)
    for i in range(0, len(emb), 8192):
        h = np.maximum(emb[i:i + 8192].astype(np.float32) @ w1 + b1, 0.0)
        out[i:i + 8192] = h @ w2 + b2
    return out


def build(datasets: list[str] | None = None) -> None:
    head_mtime = T.HEAD_PATH.stat().st_mtime
    weights = None
    datasets = datasets or sorted({c["dataset"] for c in T.index_clips()})
    for dataset in datasets:
        with np.load(T.shard_path(dataset)) as s:
            keys, clip, win_start = s["keys"], s["clip"], s["win_start"]
            out = logits_path(dataset)
            if out.exists():
                with np.load(out) as old:
                    if (np.array_equal(old["keys"], keys) and len(old["logits"]) == len(clip)
                            and float(old["head_mtime"]) == head_mtime):
                        print(f"[soft-labels] {dataset}: cached")
                        continue
            t0 = time.time()
            weights = weights or head_weights()
            logits = teacher_logits(s["emb"], *weights)
        np.savez(out, keys=keys, clip=clip, win_start=win_start, logits=logits, head_mtime=head_mtime)
        print(f"[soft-labels] {dataset}: {len(logits)} windows in {time.time() - t0:.1f}s")


def load_logits(clips: list[dict]) -> np.ndarray:
    """Logits for every window, in the same order as teacher.load_windows(clips)."""
    datasets = sorted({c["dataset"] for c in clips})
    build(datasets)
    return np.concatenate([np.load(logits_path(ds))["logits"] for ds in datasets])


def soften(logits: np.ndarray, temperature: float) -> np.ndarray:
    z = logits / temperature
    z = z - z.max(1, keepdims=True)
    p = np.exp(z)
    return p / p.sum(1, keepdims=True)


def main() -> int:
    build()
    # Check: softmax of the cached logits == the Keras head's probabilities.
    import keras

    clips = T.index_clips()
    d = T.load_windows(clips)
    logits = load_logits(clips)
    head = keras.models.load_model(T.HEAD_PATH)
    idx = np.random.default_rng(0).choice(len(logits), 2000, replace=False)
    ref = head.predict(d["X"][idx], batch_size=2048, verbose=0)
    diff = float(np.abs(soften(logits[idx], 1.0) - ref).max())
    print(f"{len(logits)} windows cached; max |softmax(logits) - head| = {diff:.1e}")
    return 0 if diff < 1e-4 else 1


if __name__ == "__main__":
    sys.exit(main())

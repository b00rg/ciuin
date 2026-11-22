"""Teacher model (#14): frozen YAMNet embeddings + a trained MLP head.

    python3 ml/teacher.py            # embed (cached) + train + evaluate
    python3 ml/teacher.py embed      # only compute/refresh the embedding cache
    python3 ml/teacher.py train      # only train the head on cached embeddings

The teacher stays offline: size doesn't matter, accuracy does. It is the
reference the students are distilled from (#4), so it's trained on exactly
the windows the students see: ml/preprocess.py windows (0.975 s, 0.5 s hop,
silent windows skipped), from the clip index (ml/build_index.py).

1. embed: YAMNet (pretrained on AudioSet, frozen) turns each window into a
   1024-d embedding. Cached per dataset in data/cache/teacher/ and only
   recomputed when that dataset's clips change, so re-running after more
   downloads finish only embeds the new data.
2. train: a small MLP maps embeddings to our 9 classes, with the class
   weights from ml/class_weights.py. Model selection uses clip-level
   macro-F1 on the val split; the test split is scored once at the end.
3. Writes the head to data/cache/teacher/teacher_head.keras and the scores
   to ml/export/results.json under "teacher".
"""

from __future__ import annotations

import io
import os
import json
import sqlite3
import sys
import tarfile
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
REPO = ML.parent
sys.path.insert(0, str(ML))

import preprocess as pp  # noqa: E402
from check_labels import load as load_labels  # noqa: E402
from class_weights import compute_weights, count_train  # noqa: E402

AUDIO_RAW = REPO / "data" / "raw" / "audio"
INDEX = REPO / "data" / "processed" / "audio_index.sqlite"
CACHE = REPO / "data" / "cache"
YAMNET_DIR = CACHE / "models" / "yamnet"
YAMNET_URL = "https://www.kaggle.com/api/v1/models/google/yamnet/tensorFlow2/yamnet/1/download"
# Teacher backbone: YAMNet (default, TF) or PANNs CNN14 (CIUIN_TEACHER=panns, PyTorch; stronger, GPU job).
BACKBONE = os.environ.get("CIUIN_TEACHER", "yamnet")
EMB_DIR = CACHE / ("teacher" if BACKBONE == "yamnet" else f"teacher-{BACKBONE}")
HEAD_PATH = EMB_DIR / "teacher_head.keras"
RESULTS = ML / "export" / "results.json"

EMB_DIM = {"yamnet": 1024, "concat": 3072}.get(BACKBONE, 2048)  # concat = CNN14 2048 + YAMNet 1024
MAX_WINDOWS = 24     # per clip; long MUSAN/DEMAND recordings get evenly spaced windows
BATCH = 256
SEED = 0
MUSAN_CAP, WINDOW_S = 4, 5.0  # same example counting as class_weights.py


# ---------------------------------------------------------------- YAMNet

def fetch_yamnet() -> Path:
    if (YAMNET_DIR / "saved_model.pb").exists():
        return YAMNET_DIR
    print(f"downloading YAMNet -> {YAMNET_DIR}")
    req = urllib.request.Request(YAMNET_URL, headers={"User-Agent": "ciuin-ml"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    YAMNET_DIR.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf_:
        tf_.extractall(YAMNET_DIR, filter="data")
    return YAMNET_DIR


def yamnet_embedder():
    import tensorflow as tf

    model = tf.saved_model.load(str(fetch_yamnet()))

    def one(x):
        return model(x)[1][0]  # embeddings [1, 1024] for a 0.975 s window -> [1024]

    @tf.function(input_signature=[tf.TensorSpec([None, pp.WINDOW], tf.float32)])
    def embed(batch):
        return tf.map_fn(one, batch, fn_output_signature=tf.TensorSpec([EMB_DIM], tf.float32))

    return lambda b: embed(b).numpy()


def panns_embedder():
    """PANNs CNN14 (AudioSet mAP 0.431) embeddings: [n, 15600] @ 16 kHz -> [n, 2048].

    CNN14 expects 32 kHz, so each window is upsampled 2x (polyphase, as in
    preprocess.resample). The checkpoint (~330 MB) downloads on first use.
    """
    import torch
    from panns_inference import AudioTagging

    from gpu_audio import fp32_frontend, upsample2

    device = "cuda" if torch.cuda.is_available() else "cpu"
    at = AudioTagging(checkpoint_path=None, device=device)
    # Wrap the STFT / log-mel in fp32 *before* loading the fine-tuned weights: the checkpoint was
    # saved from a wrapped model, so its keys are "spectrogram_extractor.m.*".
    fp32_frontend(at.model)
    if BACKBONE == "panns-ft":  # fine-tuned by ml/teacher_finetune.py
        ckpt = CACHE / "models" / "cnn14_ft.pth"
        state = torch.load(ckpt, map_location=device)
        at.model.fc_audioset = torch.nn.Linear(at.model.fc_audioset.in_features,
                                               state["fc_audioset.weight"].shape[0]).to(device)
        at.model.load_state_dict(state)
    at.model.eval()

    torch.backends.cudnn.benchmark = True

    def embed(batch: np.ndarray) -> np.ndarray:
        with torch.no_grad(), torch.autocast(device, enabled=device == "cuda"):
            x = upsample2(torch.from_numpy(batch.astype(np.float32)).to(device))
            out = at.model(x, None)
        return out["embedding"].float().cpu().numpy()

    return embed


def backbone_embedder():
    return yamnet_embedder() if BACKBONE == "yamnet" else panns_embedder()


def backbone_params() -> int:
    if BACKBONE == "concat":
        return 80_753_615 + 3_751_369
    if BACKBONE == "yamnet":
        return yamnet_params()
    return 80_753_615  # CNN14 (Kong et al. 2020)


def backbone_mb() -> float:
    if BACKBONE == "concat":
        return 327.4 + 17.0
    if BACKBONE == "yamnet":
        return sum(f.stat().st_size for f in YAMNET_DIR.rglob("*") if f.is_file()) / 1e6
    return 327.4  # Cnn14_mAP=0.431.pth


def yamnet_params() -> int:
    import tensorflow as tf

    ckpt = str(fetch_yamnet() / "variables" / "variables")
    return int(sum(np.prod(shape) for name, shape in tf.train.list_variables(ckpt)
                   if not name.startswith("_CHECKPOINTABLE") and "OPTIMIZER" not in name))


# ---------------------------------------------------------------- index

def index_clips() -> list[dict]:
    db = sqlite3.connect(INDEX)
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute(
        "SELECT dataset, path, start_s, end_s, class_index, split FROM clips "
        "WHERE use = 'classify' ORDER BY dataset, path, start_s")]
    db.close()
    if os.environ.get("CIUIN_SMOKE"):  # quick end-to-end test: every 40th clip
        rows = rows[::40]
    return rows


def clip_examples(dataset: str, seconds: float) -> float:
    """How many 'examples' a clip counts as in class_weights.py."""
    if dataset == "musan":
        return min(max(1, int(seconds // WINDOW_S)), MUSAN_CAP)
    if dataset == "demand":
        return max(1, int(seconds // WINDOW_S))
    return 1


# ---------------------------------------------------------------- embed

def shard_path(dataset: str) -> Path:
    return EMB_DIR / f"emb_{dataset}.npz"


def clip_windows(clip: dict) -> tuple[list[int], np.ndarray]:
    x = pp.load(AUDIO_RAW / clip["path"], clip["start_s"], clip["end_s"])
    starts, w = pp.active_window_starts(x)
    if len(starts) > MAX_WINDOWS:
        keep = np.unique(np.linspace(0, len(starts) - 1, MAX_WINDOWS).round().astype(int))
        starts, w = [starts[i] for i in keep], w[keep]
    return starts, w


WIN_CACHE = CACHE / "windows16k"


def _decode(clip: dict):
    starts, w = clip_windows(clip)
    return starts, w.astype(np.float32)   # exact: int16 / float16 shift a few % of embeddings


def decode(clips: list[dict]) -> None:
    """Decode + resample every window once, in separate processes (all CPU cores), to
    data/cache/windows16k/<dataset>.npz. embed() then reads windows from here, so several
    backbones can embed without decoding the audio again (on Kaggle that was ~25 min each)."""
    from multiprocessing import Pool

    WIN_CACHE.mkdir(parents=True, exist_ok=True)
    by_dataset = defaultdict(list)
    for c in clips:
        by_dataset[c["dataset"]].append(c)
    with Pool(os.cpu_count()) as pool:
        for dataset, items in by_dataset.items():
            keys = np.array([f"{c['path']}@{c['start_s']:.3f}" for c in items])
            out = WIN_CACHE / f"{dataset}.npz"
            if out.exists():
                with np.load(out) as old:
                    if np.array_equal(old["keys"], keys):
                        print(f"[decode] {dataset}: cached", flush=True)
                        continue
            t0 = time.time()
            clip_of, win_start, wins = [], [], []
            for ci, (starts, w) in enumerate(pool.imap(_decode, items, chunksize=16)):
                clip_of += [ci] * len(starts)
                win_start += list(starts)
                wins.append(w)
                if (ci + 1) % 2000 == 0:
                    print(f"[decode] {dataset}: {ci + 1}/{len(items)} clips", flush=True)
            np.savez(out, keys=keys, clip=np.array(clip_of, np.int32), win_start=np.array(win_start, np.int32),
                     win=np.concatenate(wins))
            print(f"[decode] {dataset}: {len(items)} clips, {len(clip_of)} windows in {time.time() - t0:.0f}s",
                  flush=True)


def embed(clips: list[dict]) -> None:
    EMB_DIR.mkdir(parents=True, exist_ok=True)
    by_dataset = defaultdict(list)
    for c in clips:
        by_dataset[c["dataset"]].append(c)

    embedder = None
    for dataset, items in by_dataset.items():
        keys = np.array([f"{c['path']}@{c['start_s']:.3f}" for c in items])
        path = shard_path(dataset)
        if path.exists():
            with np.load(path) as old:
                if np.array_equal(old["keys"], keys):
                    print(f"[embed] {dataset}: {len(keys)} clips cached")
                    continue
        if embedder is None:
            embedder = backbone_embedder()

        cached = WIN_CACHE / f"{dataset}.npz"
        if cached.exists():
            with np.load(cached) as wc:
                if np.array_equal(wc["keys"], keys):
                    t0 = time.time()
                    win = wc["win"]
                    embs = [embedder(win[i:i + 1024].astype(np.float32)).astype(np.float16)
                            for i in range(0, len(win), 1024)]
                    np.savez(path, keys=keys, clip=wc["clip"], win_start=wc["win_start"], emb=np.concatenate(embs))
                    print(f"[embed] {dataset}: {len(keys)} clips, {len(win)} windows from the decode cache "
                          f"in {time.time() - t0:.0f}s", flush=True)
                    continue

        t0 = time.time()
        clip_of, win_start, embs = [], [], []
        buf, buf_meta = [], []

        def flush():
            if buf:
                embs.append(embedder(np.concatenate(buf)).astype(np.float16))
                for ci, ws in buf_meta:
                    clip_of.extend([ci] * len(ws))
                    win_start.extend(ws)
                buf.clear()
                buf_meta.clear()

        with ThreadPoolExecutor(8) as pool:  # decoding + resampling in parallel with YAMNet
            for ci, (starts, w) in enumerate(pool.map(clip_windows, items)):
                buf.append(w)
                buf_meta.append((ci, starts))
                if sum(len(b) for b in buf) >= BATCH:
                    flush()
                if (ci + 1) % 1000 == 0:
                    print(f"[embed] {dataset}: {ci + 1}/{len(items)} clips", flush=True)
        flush()
        np.savez(path, keys=keys, clip=np.array(clip_of, np.int32),
                 win_start=np.array(win_start, np.int32), emb=np.concatenate(embs))
        print(f"[embed] {dataset}: {len(items)} clips, {len(clip_of)} windows in {time.time() - t0:.0f}s")


# ---------------------------------------------------------------- train

def load_windows(clips: list[dict]) -> dict[str, np.ndarray]:
    """Stack all cached windows with their clip's label, split and weight info."""
    lookup = {f"{c['path']}@{c['start_s']:.3f}": c for c in clips}
    X, clip_key = [], []
    for dataset in sorted({c["dataset"] for c in clips}):
        with np.load(shard_path(dataset)) as s:
            keys = s["keys"]
            X.append(s["emb"].astype(np.float32))
            clip_key.extend(keys[s["clip"]])
    meta = [lookup[k] for k in clip_key]
    uniq = {k: i for i, k in enumerate(dict.fromkeys(clip_key))}
    return {
        "X": np.concatenate(X),
        "y": np.array([m["class_index"] for m in meta], np.int32),
        "split": np.array([m["split"] for m in meta]),
        "dataset": np.array([m["dataset"] for m in meta]),
        "clip": np.array([uniq[k] for k in clip_key], np.int32),
        "seconds": np.array([m["end_s"] - m["start_s"] for m in meta]),
    }


def clip_scores(probs: np.ndarray, clip: np.ndarray, y: np.ndarray):
    """Average window probabilities per clip (as the app does)."""
    ids, inv = np.unique(clip, return_inverse=True)
    sums = np.zeros((len(ids), probs.shape[1]))
    np.add.at(sums, inv, probs)
    counts = np.bincount(inv)
    labels = np.zeros(len(ids), np.int32)
    labels[inv] = y
    return sums / counts[:, None], labels, ids


def metrics(pred: np.ndarray, true: np.ndarray, classes: list[str]) -> dict:
    f1 = {}
    for i, c in enumerate(classes):
        if not (true == i).any():
            continue  # class absent from this split
        tp = int(((pred == i) & (true == i)).sum())
        fp = int(((pred == i) & (true != i)).sum())
        fn = int(((pred != i) & (true == i)).sum())
        f1[c] = round(2 * tp / (2 * tp + fp + fn), 4) if tp else 0.0
    return {"accuracy": round(float((pred == true).mean()), 4),
            "macro_f1": round(float(np.mean(list(f1.values()))), 4),
            "per_class_f1": f1, "n_clips": int(len(true))}


def train(clips: list[dict], epochs: int = 60, patience: int = 8) -> dict:
    import keras

    labels = load_labels()
    classes = labels["classes"]
    d = load_windows(clips)
    tr, va, te = (d["split"] == s for s in ("train", "val", "test"))

    counts, missing = count_train(INDEX, MUSAN_CAP)
    cw = compute_weights(counts, labels["importance"], classes)
    # Each clip contributes its class_weights example count, spread over its windows.
    win_per_clip = np.bincount(d["clip"])[d["clip"]]
    ex = np.array([clip_examples(ds, s) for ds, s in zip(d["dataset"], d["seconds"])])
    sw = np.array([cw[classes[c]] for c in d["y"]]) * ex / win_per_clip
    sw = (sw / sw[tr].mean()).astype(np.float32)

    keras.utils.set_random_seed(SEED)
    head = keras.Sequential([
        keras.Input((EMB_DIM,), name="embedding"),
        keras.layers.Dense(512, activation="relu"),
        keras.layers.Dropout(0.4),
        keras.layers.Dense(len(classes), activation="softmax", name="probabilities"),
    ], name="teacher_head")
    head.compile(keras.optimizers.Adam(1e-3), "sparse_categorical_crossentropy")

    best, best_f1, waited = None, -1.0, 0
    for epoch in range(1, epochs + 1):
        head.fit(d["X"][tr], d["y"][tr], sample_weight=sw[tr], batch_size=BATCH, epochs=1,
                 shuffle=True, verbose=0)
        p, t, _ = clip_scores(head.predict(d["X"][va], batch_size=4096, verbose=0), d["clip"][va], d["y"][va])
        f1 = metrics(p.argmax(1), t, classes)["macro_f1"]
        print(f"  epoch {epoch:2d}  val clip macro-F1 {f1:.4f}", flush=True)
        if f1 > best_f1:
            best, best_f1, waited = head.get_weights(), f1, 0
        else:
            waited += 1
            if waited >= patience:
                break
    head.set_weights(best)
    head.save(HEAD_PATH)

    out = {}
    for name, mask in (("val", va), ("test", te)):
        p, t, ids = clip_scores(head.predict(d["X"][mask], batch_size=4096, verbose=0), d["clip"][mask], d["y"][mask])
        out[name] = metrics(p.argmax(1), t, classes)
        first = {c: i for i, c in reversed(list(enumerate(d["clip"][mask])))}
        ds = np.array([d["dataset"][mask][first[i]] for i in ids])
        out[name]["per_dataset_accuracy"] = {
            str(k): round(float((p.argmax(1)[ds == k] == t[ds == k]).mean()), 4) for k in sorted(set(ds))}
    out["missing_datasets"] = missing
    out["class_weights"] = cw
    return out, head


def write_results(scores: dict, head, n_yamnet_params: int) -> None:
    results = json.loads(RESULTS.read_text()) if RESULTS.exists() else {}
    yamnet_mb = backbone_mb()
    name = {"yamnet": "YAMNet", "panns": "PANNs CNN14", "panns-ft": "PANNs CNN14 fine-tuned on our 9 classes",
            "concat": "PANNs CNN14 + YAMNet (concatenated)"}[BACKBONE]
    results["teacher"] = {
        "model": f"{name} embeddings (frozen, AudioSet-pretrained) + MLP head {EMB_DIM}-512-9",
        "params": int(n_yamnet_params + head.count_params()),
        "size_mb": round(yamnet_mb + HEAD_PATH.stat().st_size / 1e6, 1),
        "accuracy": scores["test"]["accuracy"],
        "macro_f1": scores["test"]["macro_f1"],
        "eval": "clip-level (mean of window probabilities), test split; selected on val macro-F1",
        "test": scores["test"],
        "val": scores["val"],
        "trained_without": scores["missing_datasets"],
        "class_weights": scores["class_weights"],
        "trained_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    RESULTS.write_text(json.dumps(results, indent=2) + "\n")


def main() -> int:
    step = sys.argv[1] if len(sys.argv) > 1 else "all"
    if not INDEX.exists():
        print(f"error: {INDEX} not found; run ml/build_index.py first")
        return 1
    clips = index_clips()
    if step == "decode":
        decode(clips)
        return 0
    if step in ("all", "embed"):
        embed(clips)
    if step in ("all", "train"):
        scores, head = train(clips)
        write_results(scores, head, backbone_params())
        t = scores["test"]
        print(f"\nteacher test: accuracy {t['accuracy']:.4f}, macro-F1 {t['macro_f1']:.4f} ({t['n_clips']} clips)")
        print("per class F1:", t["per_class_f1"])
        print("per dataset accuracy:", t["per_dataset_accuracy"])
        if scores["missing_datasets"]:
            print(f"note: trained without {', '.join(scores['missing_datasets'])}; re-run once downloaded")
    return 0


if __name__ == "__main__":
    sys.exit(main())

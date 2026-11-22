"""Student models: small CNNs distilled from the teacher, exported to TFLite.

    python3 ml/student.py              # S student: features + train + export
    python3 ml/student.py --width 2    # wider variant (M / L come in #5)
    python3 ml/student.py --hard       # hard-label baseline, no teacher (#15)

The shipped model takes raw audio: waveform -> LogMelFrontend (fixed, in
the graph) -> CNN -> softmax. Because the frontend never trains, training
runs on cached log-mels of exactly the windows the teacher saw
(data/cache/student/), which is much faster than decoding audio every epoch.

Loss = alpha * T^2 * KL(teacher_T || student_T)  +  (1 - alpha) * w_c * CE(label)
  teacher_T: the teacher's window probabilities softened with temperature T
  w_c:       class weights (ml/class_weights.py), on the hard-label term only

Outputs (ml/export/):
  audio-student-<size>.tflite    fp32, contract signature (model_io.md)
  test_clips/                    10 test clips + expected outputs from the .tflite (S only)
  calibration.npy                200 training windows for int8 calibration (S only)
  results.json                   scores under "audio-student-<size>"
"""

from __future__ import annotations

import argparse
import os
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import frontend as fe  # noqa: E402
import preprocess as pp  # noqa: E402
import soft_labels as SL  # noqa: E402
import teacher as T  # noqa: E402
from check_labels import load as load_labels  # noqa: E402
from class_weights import compute_weights, count_train  # noqa: E402

FEAT_DIR = T.CACHE / "student"
EXPORT = ML / "export"
SIZES = {1: "s", 2: "m", 4: "l"}
SEED = 0


# ---------------------------------------------------------------- features

def load_retry(path, start, end, tries: int = 5):
    """pp.load, retried: synced folders (iCloud, Drive) can fail a read transiently."""
    for attempt in range(tries):
        try:
            return pp.load(path, start, end)
        except Exception:
            if attempt == tries - 1:
                raise
            time.sleep(2 * (attempt + 1))


def mel_shard(dataset: str) -> Path:
    return FEAT_DIR / f"mel_{dataset}.npz"


def build_features(clips: list[dict]) -> None:
    """Log-mels for every window in the teacher's embedding cache, same order."""
    import keras

    FEAT_DIR.mkdir(parents=True, exist_ok=True)
    frontend = keras.Sequential([keras.Input((fe.WINDOW_SAMPLES,)), fe.LogMelFrontend()])
    lookup = {f"{c['path']}@{c['start_s']:.3f}": c for c in clips}

    for dataset in sorted({c["dataset"] for c in clips}):
        with np.load(T.shard_path(dataset)) as s:
            keys, clip_of, win_start = s["keys"], s["clip"], s["win_start"]
        out = mel_shard(dataset)
        if out.exists():
            with np.load(out) as old:
                if np.array_equal(old["keys"], keys) and len(old["mel"]) == len(clip_of):
                    print(f"[features] {dataset}: cached")
                    continue
        cached = T.WIN_CACHE / f"{dataset}.npz"
        if cached.exists():
            with np.load(cached) as wc:
                if np.array_equal(wc["keys"], keys) and np.array_equal(wc["win_start"], win_start):
                    t0 = time.time()
                    win = wc["win"]
                    mel = np.concatenate([frontend.predict(win[i:i + 4096].astype(np.float32),
                                                           batch_size=512, verbose=0).astype(np.float16)
                                          for i in range(0, len(win), 4096)])
                    np.savez(out, keys=keys, mel=mel)
                    print(f"[features] {dataset}: {len(mel)} windows from the decode cache in "
                          f"{time.time() - t0:.0f}s", flush=True)
                    continue
        t0 = time.time()
        per_clip = [[] for _ in keys]
        for i, (ci, ws) in enumerate(zip(clip_of, win_start)):
            per_clip[ci].append((i, int(ws)))

        def windows_of(ci):
            c = lookup[keys[ci]]
            x = load_retry(T.AUDIO_RAW / c["path"], c["start_s"], c["end_s"])
            if len(x) < pp.WINDOW:
                x = np.pad(x, (0, pp.WINDOW - len(x)))
            return [(i, x[s:s + pp.WINDOW]) for i, s in per_clip[ci]]

        mel = np.zeros((len(clip_of), fe.N_FRAMES, fe.N_MELS), np.float16)
        batch_idx, batch = [], []
        with ThreadPoolExecutor(8) as pool:
            for items in pool.map(windows_of, range(len(keys))):
                for i, w in items:
                    batch_idx.append(i)
                    batch.append(w)
                if len(batch) >= 1024:
                    mel[batch_idx] = frontend.predict(np.stack(batch), batch_size=256, verbose=0)
                    batch_idx, batch = [], []
        if batch:
            mel[batch_idx] = frontend.predict(np.stack(batch), batch_size=256, verbose=0)
        np.savez(out, keys=keys, mel=mel)
        print(f"[features] {dataset}: {len(mel)} windows in {time.time() - t0:.0f}s")


def load_features(clips: list[dict]) -> np.ndarray:
    return np.concatenate([np.load(mel_shard(ds))["mel"] for ds in sorted({c["dataset"] for c in clips})])


# ---------------------------------------------------------------- augmentation

NOISE_BANK = FEAT_DIR / "noise_bank.npz"


def noise_bank(d: dict, mel: np.ndarray, tr: np.ndarray, n: int = 3000) -> np.ndarray:
    """Log-mels of background-noise windows for augmentation.

    MUSAN's noise set (index use='noise', train split) if it's downloaded,
    otherwise DEMAND's training windows (real cafeteria / station / street
    ambience). Cached in data/cache/student/noise_bank.npz.
    """
    import sqlite3

    db = sqlite3.connect(T.INDEX)
    rows = db.execute("SELECT path, start_s, end_s FROM clips WHERE use = 'noise' AND split = 'train' "
                      "ORDER BY path").fetchall()
    db.close()
    source = f"musan-noise:{len(rows)}" if rows else "demand"
    if NOISE_BANK.exists():
        with np.load(NOISE_BANK) as b:
            if str(b["source"]) == source:
                return b["mel"]
    rng = np.random.default_rng(SEED)
    if rows:
        import keras

        frontend = keras.Sequential([keras.Input((fe.WINDOW_SAMPLES,)), fe.LogMelFrontend()])
        per_file = max(1, n // len(rows))
        wins = []
        for path, start, end in rows:
            x = load_retry(T.AUDIO_RAW / path, start, end)
            wins += [pp.random_window(x, rng) for _ in range(per_file)]
        bank = frontend.predict(np.stack(wins).astype(np.float32), batch_size=256, verbose=0)
    else:
        idx = np.flatnonzero(tr & (d["dataset"] == "demand"))
        bank = mel[rng.permutation(idx)[:n]].astype(np.float32)
    bank = bank.astype(np.float16)
    np.savez(NOISE_BANK, mel=bank, source=source)
    print(f"[augment] noise bank: {len(bank)} windows from {source}")
    return bank


AUG = {"gain_db": 6.0, "shift_frames": 24, "noise_p": 0.5, "snr_db": (3.0, 20.0),
       "mixup_p": 0.5, "mixup_beta": 0.4}


def make_augment(bank: np.ndarray, aug: dict = AUG):
    """Batch augmentation on log-mels, done in the power domain where sounds add.

    log-mel = log(P + 1e-3), and the power of two uncorrelated sounds adds, so
    gain, background noise and mixup are exact (in expectation) without
    decoding audio again. Targets are [soft | hard | weight]; mixup mixes
    them with the same lambda.
    """
    import tensorflow as tf

    noise = tf.constant(np.maximum(np.exp(bank.astype(np.float32)) - fe.LOG_OFFSET, 0.0))
    n_frames = fe.N_FRAMES

    def augment(m, y):
        b = tf.shape(m)[0]
        p = tf.maximum(tf.exp(m) - fe.LOG_OFFSET, 0.0)                         # [B, 96, 64]
        # Gain: +-gain_db.
        g = tf.random.uniform([b, 1, 1], -aug["gain_db"], aug["gain_db"])
        p = p * tf.pow(10.0, g / 10.0)
        # Time shift: circular roll by up to shift_frames, per example.
        k = tf.random.uniform([b, 1], -aug["shift_frames"], aug["shift_frames"] + 1, tf.int32)
        idx = tf.math.floormod(tf.range(n_frames)[None, :] + k, n_frames)
        p = tf.gather(p, idx, batch_dims=1)
        # Background noise at a random SNR, on noise_p of the batch.
        ni = tf.random.uniform([b], 0, tf.shape(noise)[0], tf.int32)
        nz = tf.gather(noise, ni)
        snr = tf.random.uniform([b, 1, 1], *aug["snr_db"])
        scale = tf.reduce_sum(p, [1, 2], keepdims=True) / (
            tf.reduce_sum(nz, [1, 2], keepdims=True) * tf.pow(10.0, snr / 10.0) + 1e-9)
        on = tf.cast(tf.random.uniform([b, 1, 1]) < aug["noise_p"], tf.float32)
        p = p + on * scale * nz
        # Mixup with a shuffled copy of the batch; lambda >= 0.5 so the original dominates.
        g1 = tf.random.gamma([b], aug["mixup_beta"])
        g2 = tf.random.gamma([b], aug["mixup_beta"])
        lam = g1 / (g1 + g2 + 1e-9)
        lam = tf.maximum(lam, 1.0 - lam)
        lam = tf.where(tf.random.uniform([b]) < aug["mixup_p"], lam, tf.ones_like(lam))
        perm = tf.random.shuffle(tf.range(b))
        p = lam[:, None, None] * p + (1 - lam[:, None, None]) * tf.gather(p, perm)
        y = lam[:, None] * y + (1 - lam[:, None]) * tf.gather(y, perm)
        return tf.math.log(p + fe.LOG_OFFSET)[..., None], y

    return augment


# ---------------------------------------------------------------- model

def build_backbone(width: int = 1, n_classes: int = 9):
    """Depthwise-separable CNN on [96, 64, 1] log-mel -> logits.

    Only conv / depthwise conv / batch norm / ReLU / pooling / dense, which
    export to TFLite builtins and quantise cleanly. width=1 is ~70k params.
    """
    import keras
    from keras import layers

    c = 24 * width
    inp = keras.Input((fe.N_FRAMES, fe.N_MELS, 1), name="log_mel")
    x = layers.BatchNormalization(name="input_norm")(inp)
    x = layers.Conv2D(c, 3, padding="same", use_bias=False)(x)
    x = layers.ReLU()(layers.BatchNormalization()(x))
    x = layers.MaxPooling2D(2)(x)
    for i, ch in enumerate((2 * c, 4 * c, 8 * c, 8 * c)):
        x = layers.DepthwiseConv2D(3, padding="same", use_bias=False)(x)
        x = layers.Conv2D(ch, 1, use_bias=False)(x)
        x = layers.ReLU()(layers.BatchNormalization()(x))
        if i < 3:
            x = layers.MaxPooling2D(2)(x)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    logits = layers.Dense(n_classes, name="logits")(x)
    return keras.Model(inp, logits, name=f"student_w{width}")


def deployable(backbone):
    """waveform [1, 15600] -> probabilities [1, 9], frontend included."""
    import keras

    wave = keras.Input((fe.WINDOW_SAMPLES,), name="waveform")
    x = fe.LogMelFrontend(name="log_mel_frontend")(wave)
    x = keras.layers.Reshape((fe.N_FRAMES, fe.N_MELS, 1))(x)
    out = keras.layers.Softmax(name="probabilities")(backbone(x))
    return keras.Model(wave, out, name=backbone.name)


def kd_loss(n_classes: int, alpha: float, temperature: float):
    from keras import ops

    def loss(y, logits):
        soft, hard, w = y[:, :n_classes], y[:, n_classes:2 * n_classes], y[:, 2 * n_classes]
        log_p_t = ops.log_softmax(logits / temperature, axis=-1)
        kl = ops.sum(soft * (ops.log(soft + 1e-8) - log_p_t), axis=-1) * temperature ** 2
        ce = -ops.sum(hard * ops.log_softmax(logits, axis=-1), axis=-1) * w
        return alpha * kl + (1 - alpha) * ce

    return loss


# ---------------------------------------------------------------- train

def train(width: int, epochs: int, alpha: float, temperature: float, patience: int = 5,
          augment: bool = True, lr_schedule: str = "constant", select: str = "epoch"):
    import keras
    import tensorflow as tf

    labels = load_labels()
    classes = labels["classes"]
    clips = T.index_clips()
    build_features(clips)
    d = T.load_windows(clips)
    mel = load_features(clips)
    assert len(mel) == len(d["y"]), "feature cache out of sync with the embedding cache"
    tr, va, te = (d["split"] == s for s in ("train", "val", "test"))

    soft = SL.soften(SL.load_logits(clips), temperature)  # cached teacher logits (#4)

    counts, missing = count_train(T.INDEX, T.MUSAN_CAP)
    cw = compute_weights(counts, labels["importance"], classes)
    wpc = np.bincount(d["clip"])[d["clip"]]
    ex = np.array([T.clip_examples(ds, s) for ds, s in zip(d["dataset"], d["seconds"])])
    sw = np.array([cw[classes[c]] for c in d["y"]]) * ex / wpc
    sw = sw / sw[tr].mean()
    target = np.concatenate([soft, np.eye(len(classes))[d["y"]], sw[:, None]], axis=1).astype(np.float32)

    keras.utils.set_random_seed(SEED)
    model = build_backbone(width, len(classes))
    lr = 2e-3
    if lr_schedule == "cosine":
        # Decay to ~0 over the whole run: with a constant LR the weights keep moving, val F1
        # swings +-3 points epoch to epoch, and "best on val" picks a lucky epoch.
        steps = epochs * int(np.ceil(tr.sum() / 128))
        lr = keras.optimizers.schedules.CosineDecay(2e-3, decay_steps=steps, alpha=0.01)
    model.compile(keras.optimizers.Adam(lr), kd_loss(len(classes), alpha, temperature))
    ds = (tf.data.Dataset.from_tensor_slices((mel[tr], target[tr]))
          .shuffle(20_000, seed=SEED)
          .map(lambda m, y: (tf.cast(m, tf.float32), y), num_parallel_calls=tf.data.AUTOTUNE)
          .batch(128))
    # Augmentation runs as a compiled step on the GPU, not in tf.data (which runs on the CPU:
    # Kaggle has 4 cores for 2 GPUs). Same maths, same random draws per batch.
    aug_step = tf.function(make_augment(noise_bank(d, mel, tr))) if augment else None
    if not augment:
        ds = ds.map(lambda m, y: (m[..., None], y))
    ds = ds.prefetch(tf.data.AUTOTUNE)

    def evaluate(mask):
        logits = model.predict(mel[mask].astype(np.float32)[..., None], batch_size=1024, verbose=0)
        probs = np.exp(logits - logits.max(1, keepdims=True))
        probs /= probs.sum(1, keepdims=True)
        p, t, _ = T.clip_scores(probs, d["clip"][mask], d["y"][mask])
        return T.metrics(p.argmax(1), t, classes)

    best, best_f1, best_epoch, waited, t0 = None, -1.0, 0, 0, time.time()
    # select="smooth3": score each epoch by the mean val F1 of it and its two neighbours, so a
    # one-epoch spike (a few val clips flipping in a small class) is not picked. The score for
    # epoch e is only known after epoch e+1, so the last two epochs' weights are kept.
    hist, recent = [], []  # val F1 per epoch; (epoch, weights) for the last 2 epochs
    for epoch in range(1, epochs + 1):
        if aug_step is None:
            model.fit(ds, epochs=1, verbose=0)
        else:
            for m_b, y_b in ds:
                model.train_on_batch(*aug_step(m_b, y_b))
        v = evaluate(va)
        print(f"  epoch {epoch:2d}  val acc {v['accuracy']:.4f}  macro-F1 {v['macro_f1']:.4f}  "
              f"({time.time() - t0:.0f}s)", flush=True)
        hist.append(v["macro_f1"])
        if select == "smooth3":
            recent = (recent + [(epoch, model.get_weights())])[-2:]
            if epoch == 1:
                continue
            centre = epoch - 1  # its window is epochs centre-1..centre+1 (clipped at epoch 1)
            score, cand = float(np.mean(hist[max(centre - 2, 0):centre + 1])), recent[0]
        else:
            score, cand = v["macro_f1"], (epoch, None)
        if score > best_f1:
            best_f1, best_epoch, waited = score, cand[0], 0
            best = cand[1] if cand[1] is not None else model.get_weights()
        else:
            waited += 1
            if waited >= patience:
                break
    if select == "smooth3" and not best:  # 1-epoch run: nothing smoothed yet
        best, best_epoch, best_f1 = model.get_weights(), epoch, hist[-1]
    print(f"  selected epoch {best_epoch} (val F1 {hist[best_epoch - 1]:.4f}"
          + (f", 3-epoch mean {best_f1:.4f}" if select == "smooth3" else "") + ")", flush=True)
    model.set_weights(best)
    return model, {"val": evaluate(va), "test": evaluate(te), "missing": missing,
                   "epochs": epoch, "alpha": alpha, "temperature": temperature,
                   "augment": AUG if augment else None, "lr_schedule": lr_schedule,
                   "select": select, "selected_epoch": best_epoch}


# ---------------------------------------------------------------- export

def tflite_predict(path: Path):
    from ai_edge_litert.interpreter import Interpreter

    interp = Interpreter(model_path=str(path))
    interp.allocate_tensors()
    i, o = interp.get_input_details()[0]["index"], interp.get_output_details()[0]["index"]

    def predict(windows: np.ndarray) -> np.ndarray:
        out = []
        for w in windows:
            interp.set_tensor(i, w[None].astype(np.float32))
            interp.invoke()
            out.append(interp.get_tensor(o)[0].copy())
        return np.array(out)

    return predict


def write_test_clips(tflite_path: Path, n: int = 10) -> None:
    """10 short test-split clips (>= one per class) + expected .tflite outputs."""
    import soundfile as sf

    classes = load_labels()["classes"]
    rng = np.random.default_rng(SEED)
    short = [c for c in T.index_clips()
             if c["split"] == "test" and c["dataset"] in ("esc50", "us8k") and c["end_s"] - c["start_s"] <= 5.0]
    picks = []
    for ci in list(range(len(classes))) + [classes.index("siren")]:
        pool = [c for c in short if c["class_index"] == ci and c not in picks]
        if pool:
            picks.append(pool[int(rng.integers(len(pool)))])
    picks = picks[:n]

    out_dir = EXPORT / "test_clips"
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.wav"):
        old.unlink()
    predict = tflite_predict(tflite_path)
    expected = []
    for k, c in enumerate(picks):
        name = f"{k:02d}_{classes[c['class_index']]}.wav"
        sf.write(out_dir / name, pp.load(T.AUDIO_RAW / c["path"], c["start_s"], c["end_s"]),
                 pp.SAMPLE_RATE, subtype="PCM_16")
        x = pp.load(out_dir / name)  # what the phone will read: 16-bit PCM
        starts, w = pp.active_window_starts(x)
        win_p = predict(w)
        clip_p = win_p.mean(0)
        expected.append({
            "file": name, "source": f"{c['dataset']}:{c['path']}", "true_class": classes[c["class_index"]],
            "expected_top_class": classes[int(clip_p.argmax())],
            "clip_probabilities": [round(float(v), 6) for v in clip_p],
            "windows": [{"start_sample": int(s), "probabilities": [round(float(v), 6) for v in p]}
                        for s, p in zip(starts, win_p)],
        })
    (out_dir / "expected.json").write_text(json.dumps({
        "model": tflite_path.name, "classes": classes,
        "how": "16 kHz mono 16-bit WAV -> float /32768 -> preprocess.md windows (0.5 s hop, skip < -60 dBFS) "
               "-> model per window -> mean of window probabilities. Match top class exactly and "
               "probabilities within ~1e-3.",
        "clips": expected}, indent=2) + "\n")
    hits = sum(e["true_class"] == e["expected_top_class"] for e in expected)
    print(f"wrote {len(expected)} test clips ({hits} predicted correctly) -> {out_dir.relative_to(ML.parent)}")


def write_calibration(n: int = 200) -> None:
    """~n training windows spread over classes, raw waveform [n, 15600] float32."""
    classes = load_labels()["classes"]
    rng = np.random.default_rng(SEED)
    train = [c for c in T.index_clips() if c["split"] == "train"]
    per_class = int(np.ceil(n / len(classes)))
    chosen = []
    for ci in range(len(classes)):
        pool = [c for c in train if c["class_index"] == ci]
        chosen += [pool[i] for i in rng.permutation(len(pool))[:per_class]]
    windows = []
    for c in chosen[:n]:
        x = pp.load(T.AUDIO_RAW / c["path"], c["start_s"], c["end_s"])
        windows.append(pp.random_window(x, rng))
    arr = np.stack(windows).astype(np.float32)
    np.save(EXPORT / "calibration.npy", arr)
    print(f"wrote calibration.npy {arr.shape} ({arr.nbytes / 1e6:.1f} MB)")


def prepare() -> None:
    """Build every shared cache once (log-mels, teacher logits, noise bank), so parallel
    student runs only read them."""
    clips = T.index_clips()
    build_features(clips)
    SL.load_logits(clips)
    d = T.load_windows(clips)
    noise_bank(d, load_features(clips), d["split"] == "train")
    print("caches ready", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--width", type=int, default=1, choices=sorted(SIZES))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--alpha", type=float, default=0.5, help="weight of the distillation term")
    ap.add_argument("--temperature", type=float, default=3.0)
    ap.add_argument("--no-augment", action="store_true", help="train without augmentation")
    ap.add_argument("--patience", type=int, default=5, help="early stop after this many epochs without val gain")
    ap.add_argument("--lr-schedule", choices=("constant", "cosine"), default="constant",
                    help="cosine: Adam 2e-3 decayed to 2e-5 over --epochs (use with a large --patience)")
    ap.add_argument("--select", choices=("epoch", "smooth3"), default="epoch",
                    help="smooth3: keep the epoch with the best 3-epoch mean val F1 (also used for --patience)")
    ap.add_argument("--hard", action="store_true",
                    help="baseline: hard labels only (alpha 0), saved as audio-student-<size>-hard; "
                         "the .tflite goes to data/cache/student/, not ml/export/")
    ap.add_argument("--prepare", action="store_true", help="only build the shared caches")
    ap.add_argument("--tag", default="", help="sweep variant name: saves audio-student-<size>-<tag> and "
                    "skips test clips / calibration, so variants don't overwrite the real export")
    args = ap.parse_args()
    if args.prepare:
        prepare()
        return 0
    if args.hard:
        args.alpha = 0.0

    from tflite_export import export
    from verify_tflite import verify

    size = SIZES[args.width]
    name = f"audio-student-{size}" + ("-hard" if args.hard else "") + (f"-{args.tag}" if args.tag else "")
    backbone, scores = train(args.width, args.epochs, args.alpha, args.temperature,
                             patience=args.patience, augment=not args.no_augment,
                             lr_schedule=args.lr_schedule, select=args.select)
    backbone.save(FEAT_DIR / f"{name}.keras")  # log-mel -> logits, for #6 / #7 analysis
    model = deployable(backbone)
    path = export(model, (FEAT_DIR if (args.hard or args.tag) else EXPORT) / f"{name}.tflite")
    errors = verify(path)
    if errors:
        print("export failed verification:", errors)
        return 1

    if size == "s" and not args.hard and not args.tag:
        write_test_clips(path)
        write_calibration()

    # CIUIN_RESULTS: write to a separate file (parallel runs merge them afterwards).
    results_path = Path(os.environ.get("CIUIN_RESULTS", T.RESULTS))
    results = json.loads(results_path.read_text()) if results_path.exists() else {}
    t = scores["test"]
    results[name] = {
        "model": f"depthwise-separable CNN, width x{args.width}, in-graph log-mel frontend"
                 + (" (hard-label baseline, no distillation)" if args.hard else ""),
        "params": int(backbone.count_params()),
        "size_mb": round(path.stat().st_size / 1e6, 2),
        "accuracy": t["accuracy"], "macro_f1": t["macro_f1"],
        "eval": "clip-level (mean of window probabilities), test split; fp32 Keras weights",
        "test": t, "val": scores["val"],
        "distillation": None if args.hard else {"teacher": "teacher", "alpha": scores["alpha"],
                                                "temperature": scores["temperature"]},
        "epochs": scores["epochs"],
        "augment": scores["augment"],
        "lr_schedule": scores["lr_schedule"],
        "select": scores["select"],
        "selected_epoch": scores["selected_epoch"],
        "trained_without": scores["missing"],
        "trained_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    results_path.write_text(json.dumps(results, indent=2) + "\n")
    print(f"\n{name}: test accuracy {t['accuracy']:.4f}, macro-F1 {t['macro_f1']:.4f}, "
          f"{backbone.count_params():,} params, {path.stat().st_size / 1e6:.2f} MB")
    print("per class F1:", t["per_class_f1"])
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Fine-tune PANNs CNN14 end to end on our 9 classes (GPU; the strong teacher).

    python3 ml/teacher_finetune.py --epochs 40

Starts from CNN14 pretrained on AudioSet (mAP 0.431), replaces the 527-way
AudioSet output with a 9-way head, and trains the whole network on the
same windows, splits and class weights as teacher.py, with:
  - mixup (alpha 0.4) and random gain +-6 dB
  - AdamW, backbone lr 1e-4 / head lr 1e-3, cosine decay, 1 warm-up epoch
  - epoch chosen on val clip-level macro-F1, early stop after `patience`

Every window is decoded once into a 16 kHz int16 memmap (data/cache/windows/),
so the GPU isn't waiting on audio decoding.

Writes data/cache/models/cnn14_ft.pth. Then embed + head with
`CIUIN_TEACHER=panns-ft python3 ml/teacher.py`, which loads this checkpoint.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import preprocess as pp  # noqa: E402
from gpu_audio import fp32_frontend, upsample2  # noqa: E402
import teacher as T  # noqa: E402
from check_labels import load as load_labels  # noqa: E402
from class_weights import compute_weights, count_train  # noqa: E402

WIN_DIR = T.CACHE / "windows"
CKPT = T.CACHE / "models" / "cnn14_ft.pth"


def build_window_cache(clips: list[dict]):
    """All teacher windows (same selection as teacher.clip_windows) as int16 [N, 15600]."""
    WIN_DIR.mkdir(parents=True, exist_ok=True)
    meta_path = WIN_DIR / "meta.npz"
    if meta_path.exists():
        m = np.load(meta_path)
        if int(m["n_clips"]) == len(clips):
            return np.load(WIN_DIR / "windows.npy"), m["clip"], m["y"], m["split"]   # ~5 GB, in RAM
    t0 = time.time()
    clip_of, wins = [], []
    with ThreadPoolExecutor(8) as pool:
        for ci, (starts, w) in enumerate(pool.map(T.clip_windows, clips)):
            wins.append((np.clip(w, -1, 1) * 32767).astype(np.int16))
            clip_of += [ci] * len(w)
            if (ci + 1) % 2000 == 0:
                print(f"[windows] {ci + 1}/{len(clips)} clips", flush=True)
    arr = np.concatenate(wins)
    np.save(WIN_DIR / "windows.npy", arr)
    clip_of = np.array(clip_of, np.int32)
    y = np.array([clips[c]["class_index"] for c in clip_of], np.int64)
    split = np.array([clips[c]["split"] for c in clip_of])
    np.savez(meta_path, clip=clip_of, y=y, split=split, n_clips=len(clips))
    print(f"[windows] {len(arr)} windows from {len(clips)} clips in {time.time() - t0:.0f}s", flush=True)
    return arr, clip_of, y, split


def make_model(n_classes: int):
    import torch
    from panns_inference import AudioTagging

    at = AudioTagging(checkpoint_path=None, device="cpu")   # downloads Cnn14_mAP=0.431.pth
    net = at.model
    net.fc_audioset = torch.nn.Linear(net.fc_audioset.in_features, n_classes)
    return fp32_frontend(net)


def logits_of(net, x):
    """CNN14 forward without its sigmoid: returns (logits, embedding)."""
    import torch.nn.functional as F

    # panns' Cnn14.forward applies sigmoid to fc_audioset; recompute the pre-activation.
    core = getattr(net, "module", net)          # unwrap DataParallel
    out = net(x, None)
    emb = out["embedding"]
    return core.fc_audioset(F.dropout(emb, p=0.5, training=net.training)), emb


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--patience", type=int, default=8)
    ap.add_argument("--batch", type=int, default=128, help="split across all visible GPUs")
    ap.add_argument("--windows-per-clip", type=int, default=0,
                    help="per epoch, train on this many random windows from each training clip "
                         "(0 = all windows). 2 cuts an epoch ~4-5x; long recordings stop dominating")
    args = ap.parse_args()

    import torch
    from concurrent.futures import ThreadPoolExecutor as Pool

    torch.manual_seed(0)
    torch.backends.cudnn.benchmark = True       # fixed input size: pick the fastest conv kernels
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    labels = load_labels()
    classes = labels["classes"]
    clips = T.index_clips()
    W, clip_of, y, split = build_window_cache(clips)
    tr, va = np.flatnonzero(split == "train"), np.flatnonzero(split == "val")

    counts, _ = count_train(T.INDEX, T.MUSAN_CAP)
    cw = compute_weights(counts, labels["importance"], classes)
    class_w = torch.tensor([cw[c] for c in classes], dtype=torch.float32, device=dev)
    # Per-window weight = class weight x (clip's example count / its window count), as in
    # teacher.py: every clip counts the same however long it is. Without this, 24-window
    # MUSAN / DEMAND recordings dominate training (worth ~2 points of val acc on YAMNet).
    meta = {i: c for i, c in enumerate(clips)}
    wpc = np.bincount(clip_of)
    ex = np.array([T.clip_examples(meta[c]["dataset"], meta[c]["end_s"] - meta[c]["start_s"]) for c in clip_of])
    win_w = ex / wpc[clip_of]
    win_w = torch.tensor(win_w / win_w[tr].mean(), dtype=torch.float32, device=dev)

    net = make_model(len(classes)).to(dev)
    head = list(net.fc_audioset.parameters())
    if dev == "cuda" and torch.cuda.device_count() > 1:
        print(f"using {torch.cuda.device_count()} GPUs (DataParallel)", flush=True)
    head_ids = {id(p) for p in head}
    body = [p for p in net.parameters() if id(p) not in head_ids]
    opt = torch.optim.AdamW([{"params": body, "lr": 1e-4}, {"params": head, "lr": 1e-3}], weight_decay=1e-4)
    # Windows of each training clip, for --windows-per-clip sampling.
    by_clip = {}
    for i in tr:
        by_clip.setdefault(int(clip_of[i]), []).append(int(i))
    by_clip = [np.array(v) for v in by_clip.values()]

    def epoch_rows():
        if not args.windows_per_clip:
            return rng.permutation(tr)
        k = args.windows_per_clip
        pick = [v if len(v) <= k else rng.choice(v, k, replace=False) for v in by_clip]
        return rng.permutation(np.concatenate(pick))

    n_epoch = len(tr) if not args.windows_per_clip else sum(min(len(v), args.windows_per_clip) for v in by_clip)
    steps_per_epoch = math.ceil(n_epoch / args.batch)
    print(f"{len(tr)} training windows from {len(by_clip)} clips; {n_epoch} per epoch "
          f"({steps_per_epoch} steps)", flush=True)
    total = steps_per_epoch * args.epochs
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, s / steps_per_epoch) * 0.5 * (1 + math.cos(math.pi * min(s, total) / total)))
    scaler = torch.amp.GradScaler(enabled=dev == "cuda")
    rng = np.random.default_rng(0)
    if dev == "cuda" and torch.cuda.device_count() > 1:
        net = torch.nn.DataParallel(net)

    def host_batch(idx):
        """CPU side: gather int16 rows into pinned memory (runs on a background thread)."""
        t = torch.from_numpy(W[np.sort(idx)])
        return t.pin_memory() if dev == "cuda" else t

    def to_gpu(t):
        """GPU side: int16 -> float, 16 -> 32 kHz for CNN14."""
        return upsample2(t.to(dev, non_blocking=True).float() / 32767.0)

    def batch_audio(idx):
        return to_gpu(host_batch(idx))

    def evaluate(rows):
        net.eval()
        probs = np.zeros((len(rows), len(classes)), np.float32)
        with torch.no_grad(), torch.autocast(dev, enabled=dev == "cuda"):
            for i in range(0, len(rows), 256):
                r = np.sort(rows[i:i + 256])
                lg, _ = logits_of(net, batch_audio(r))
                probs[i:i + len(r)] = torch.softmax(lg.float(), -1).cpu().numpy()
        rows_sorted = np.concatenate([np.sort(rows[i:i + 256]) for i in range(0, len(rows), 256)])
        p, t, _ = T.clip_scores(probs, clip_of[rows_sorted], y[rows_sorted])
        return T.metrics(p.argmax(1), t, classes)

    best, best_f1, waited, t0 = None, -1.0, 0, time.time()
    for epoch in range(1, args.epochs + 1):
        net.train()
        order = epoch_rows()
        batches = [np.sort(order[s * args.batch:(s + 1) * args.batch]) for s in range(steps_per_epoch)]
        loader = Pool(2)
        nxt = loader.submit(host_batch, batches[0])
        for s in range(steps_per_epoch):
            idx = batches[s]
            host = nxt.result()
            if s + 1 < steps_per_epoch:
                nxt = loader.submit(host_batch, batches[s + 1])     # prefetch while the GPU works
            x = to_gpu(host)
            x = x * torch.pow(10.0, torch.empty(len(idx), 1, device=dev).uniform_(-6, 6) / 20)
            yt = torch.nn.functional.one_hot(torch.from_numpy(y[idx]).to(dev), len(classes)).float()
            lam = float(np.random.beta(0.4, 0.4))
            lam = max(lam, 1 - lam)
            perm = torch.randperm(len(idx), device=dev)
            x = lam * x + (1 - lam) * x[perm]
            yt = lam * yt + (1 - lam) * yt[perm]
            with torch.autocast(dev, enabled=dev == "cuda"):
                lg, _ = logits_of(net, x)
                logp = torch.log_softmax(lg.float(), -1)
                ww = win_w[torch.from_numpy(idx).to(dev)]
                ww = lam * ww + (1 - lam) * ww[perm]
                loss = (-((yt * logp) * class_w).sum(-1) * ww).mean()
            if not torch.isfinite(loss):
                raise SystemExit(f"loss is {loss.item()} at epoch {epoch} step {s}: stopping instead of "
                                 "wasting the run (check the fp32 frontend / learning rate)")
            if s % 200 == 0:
                print(f"    epoch {epoch} step {s}/{steps_per_epoch}  loss {loss.item():.3f}  "
                      f"({time.time() - t0:.0f}s)", flush=True)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
        loader.shutdown()
        v = evaluate(va)
        print(f"  epoch {epoch:2d}  val acc {v['accuracy']:.4f}  macro-F1 {v['macro_f1']:.4f}  "
              f"loss {loss.item():.3f}  ({time.time() - t0:.0f}s)", flush=True)
        if v["macro_f1"] > best_f1:
            best_f1, waited = v["macro_f1"], 0
            CKPT.parent.mkdir(parents=True, exist_ok=True)
            torch.save(getattr(net, "module", net).state_dict(), CKPT)
        else:
            waited += 1
            if waited >= args.patience:
                break
    (CKPT.with_suffix(".json")).write_text(json.dumps({"best_val_macro_f1": best_f1, "epochs_run": epoch}))
    print(f"best val macro-F1 {best_f1:.4f}; saved {CKPT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

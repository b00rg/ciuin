"""Multiply-accumulates per inference for the student models (#16).

    python3 ml/macs.py            # print MACs for S / M / L
    python3 ml/macs.py --write    # also add "macs" to each student in export/results.json

Counts one 0.975 s window through the deployable model: the in-graph log-mel
frontend (STFT as a strided conv, power, mel matmul) plus the CNN backbone
(Conv2D, DepthwiseConv2D, Dense). Batch norm, ReLU, pooling and softmax are
left out: they're element-wise and small next to the convolutions.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import frontend as fe  # noqa: E402

RESULTS = ML / "export" / "results.json"
SIZES = {1: "s", 2: "m", 4: "l"}


def frontend_macs() -> int:
    stft = fe.N_FRAMES * fe.FRAME_LENGTH * 2 * fe.N_BINS   # conv: 400 taps x (cos | sin) x 257 bins x 96 frames
    power = fe.N_FRAMES * fe.N_BINS * 2                     # re^2 + im^2
    mel = fe.N_FRAMES * fe.N_BINS * fe.N_MELS               # [96, 257] @ [257, 64]
    return stft + power + mel


def backbone_macs(model) -> int:
    import keras

    total = 0
    for layer in model.layers:
        if isinstance(layer, keras.layers.DepthwiseConv2D):
            _, h, w, c = layer.output.shape
            kh, kw = layer.kernel_size
            total += h * w * c * kh * kw
        elif isinstance(layer, keras.layers.Conv2D):
            _, h, w, c_out = layer.output.shape
            kh, kw = layer.kernel_size
            total += h * w * c_out * kh * kw * layer.input.shape[-1]
        elif isinstance(layer, keras.layers.Dense):
            total += layer.input.shape[-1] * layer.units
    return int(total)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    from student import build_backbone

    fm = frontend_macs()
    rows = {}
    for width, size in SIZES.items():
        b = backbone_macs(build_backbone(width))
        rows[f"audio-student-{size}"] = {"frontend": fm, "backbone": b, "total": fm + b}
        print(f"student-{size}: frontend {fm / 1e6:.1f}M + CNN {b / 1e6:.1f}M = {(fm + b) / 1e6:.1f}M MACs")

    if args.write and RESULTS.exists():
        results = json.loads(RESULTS.read_text())
        for key, m in rows.items():
            for k in (key, f"{key}-hard"):
                if k in results:
                    results[k]["macs"] = m["total"]
                    results[k]["macs_breakdown"] = {"frontend": m["frontend"], "backbone": m["backbone"]}
        RESULTS.write_text(json.dumps(results, indent=2) + "\n")
        print(f"wrote macs to {RESULTS.relative_to(ML.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

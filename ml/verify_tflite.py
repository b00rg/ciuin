"""Check a .tflite file against the audio model contract (ml/export/model_io.md).

    python3 ml/verify_tflite.py ml/export/audio-dummy.tflite [more.tflite ...]

Checks: one input float32 [1, 15600]; one output float32 [1, 9]; only
builtin ops (no Flex / Select-TF ops, which the app doesn't ship); outputs
are probabilities summing to 1 on silence, noise and a tone; deterministic.
Works for fp32 and int8 models (int8 outputs are dequantised).
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType

INPUT_SHAPE = [1, 15_600]
N_CLASSES = 9
SAMPLE_RATE = 16_000


def test_inputs() -> dict[str, np.ndarray]:
    t = np.arange(INPUT_SHAPE[1]) / SAMPLE_RATE
    rng = np.random.default_rng(0)
    return {
        "silence": np.zeros(INPUT_SHAPE, np.float32),
        "white noise": rng.uniform(-1, 1, INPUT_SHAPE).astype(np.float32),
        "1 kHz tone": (0.5 * np.sin(2 * np.pi * 1000 * t)).astype(np.float32)[None],
        "full scale": np.ones(INPUT_SHAPE, np.float32),
    }


def run(interp: Interpreter, x: np.ndarray) -> np.ndarray:
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    if inp["dtype"] == np.int8:  # fully-int8 input: quantise
        scale, zero = inp["quantization"]
        x = np.clip(np.round(x / scale + zero), -128, 127).astype(np.int8)
    interp.set_tensor(inp["index"], x)
    interp.invoke()
    y = interp.get_tensor(out["index"])
    if out["dtype"] == np.int8:
        scale, zero = out["quantization"]
        y = (y.astype(np.float32) - zero) * scale
    return y


def verify(path: Path) -> list[str]:
    errors = []
    # No default delegate (XNNPACK): we want the model's own ops, as the app's
    # interpreter sees them before any delegate rewrites the graph.
    interp = Interpreter(model_path=str(path),
                         experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    interp.allocate_tensors()
    ins, outs = interp.get_input_details(), interp.get_output_details()

    if len(ins) != 1:
        errors.append(f"expected 1 input, got {len(ins)}")
    if len(outs) != 1:
        errors.append(f"expected 1 output, got {len(outs)}")
    if errors:
        return errors
    if list(ins[0]["shape"]) != INPUT_SHAPE:
        errors.append(f"input shape {list(ins[0]['shape'])} != {INPUT_SHAPE}")
    if list(outs[0]["shape"]) != [1, N_CLASSES]:
        errors.append(f"output shape {list(outs[0]['shape'])} != [1, {N_CLASSES}]")
    if ins[0]["dtype"] != np.float32:
        errors.append(f"input dtype {ins[0]['dtype'].__name__} != float32 (app passes floats)")
    if outs[0]["dtype"] not in (np.float32, np.int8):
        errors.append(f"output dtype {outs[0]['dtype'].__name__} not float32/int8")

    ops = sorted({op["op_name"] for op in interp._get_ops_details()})
    custom = [o for o in ops if o.startswith("Flex") or o in ("CUSTOM", "DELEGATE")
              or o in ("VAR_HANDLE", "READ_VARIABLE", "ASSIGN_VARIABLE")]
    if custom:
        errors.append(f"non-builtin or variable ops (weights must be baked in): {custom}")
    print(f"  ops: {', '.join(ops)}")
    if errors:
        return errors

    for name, x in test_inputs().items():
        y = run(interp, x)
        if not np.all(np.isfinite(y)):
            errors.append(f"{name}: non-finite output")
            continue
        tol = 0.02 if outs[0]["dtype"] == np.int8 else 1e-4
        if np.any(y < -tol) or abs(float(y.sum()) - 1) > tol:
            errors.append(f"{name}: output is not a probability distribution (sum={y.sum():.4f})")
        if not np.array_equal(y, run(interp, x)):
            errors.append(f"{name}: output differs between two identical runs")
        print(f"  {name:<12} top={int(y.argmax())} sum={float(y.sum()):.5f}")
    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    failed = False
    for arg in sys.argv[1:]:
        path = Path(arg)
        print(f"{path} ({path.stat().st_size / 1e6:.2f} MB)")
        errors = verify(path)
        for e in errors:
            print(f"  error: {e}")
        print("  OK" if not errors else "  FAILED")
        failed |= bool(errors)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

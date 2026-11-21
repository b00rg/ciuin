"""Handoff 0: build the dummy model and prove the in-graph frontend exports.

    python3 ml/export_dummy.py

Writes ml/export/audio-dummy.tflite: untrained (random, fixed seed) but with
the exact contract signature, so B2/C can wire up loading, inference and
the SoundClass mapping before real models exist. Its predictions are
meaningless.

Also checks that the log-mel frontend gives the same numbers as a plain
numpy STFT, both in Keras and after TFLite conversion.
"""

from __future__ import annotations

import sys
from pathlib import Path

import keras
import numpy as np
import tensorflow as tf
from ai_edge_litert.interpreter import Interpreter

import frontend as fe
from tflite_export import export, to_tflite
from verify_tflite import verify

ML = Path(__file__).resolve().parent
OUT = ML / "export" / "audio-dummy.tflite"
N_CLASSES = 9


def build_dummy(seed: int = 0) -> keras.Model:
    keras.utils.set_random_seed(seed)
    wave = keras.Input(shape=(fe.WINDOW_SAMPLES,), name="waveform")
    x = fe.LogMelFrontend(name="log_mel")(wave)
    x = keras.layers.Reshape((fe.N_FRAMES, fe.N_MELS, 1))(x)
    x = keras.layers.Conv2D(8, 3, activation="relu")(x)
    x = keras.layers.GlobalAveragePooling2D()(x)
    out = keras.layers.Dense(N_CLASSES, activation="softmax", name="probabilities")(x)
    return keras.Model(wave, out, name="audio_dummy")


def check_frontend() -> list[str]:
    errors = []
    tf_mel = tf.signal.linear_to_mel_weight_matrix(
        fe.N_MELS, fe.N_BINS, fe.SAMPLE_RATE, fe.F_MIN, fe.F_MAX).numpy()
    if not np.allclose(fe.mel_matrix(), tf_mel, atol=1e-5):
        errors.append("mel matrix differs from tf.signal.linear_to_mel_weight_matrix")

    rng = np.random.default_rng(1)
    t = np.arange(fe.WINDOW_SAMPLES) / fe.SAMPLE_RATE
    signals = {
        "noise": rng.uniform(-1, 1, fe.WINDOW_SAMPLES),
        "chirp": 0.8 * np.sin(2 * np.pi * (200 + 3000 * t) * t),
        "quiet": 1e-4 * rng.standard_normal(fe.WINDOW_SAMPLES),
    }

    # The frontend on its own, in Keras and converted to TFLite.
    model = keras.Sequential([keras.Input((fe.WINDOW_SAMPLES,)), fe.LogMelFrontend()])
    interp = Interpreter(model_content=_frontend_tflite(model))
    interp.allocate_tensors()
    i, o = interp.get_input_details()[0], interp.get_output_details()[0]

    for name, s in signals.items():
        x = s.astype(np.float32)[None]
        ref = fe.log_mel_reference(x)
        got_keras = np.asarray(model(x))[0]
        interp.set_tensor(i["index"], x)
        interp.invoke()
        got_tflite = interp.get_tensor(o["index"]).reshape(fe.N_FRAMES, fe.N_MELS)
        for label, got in (("keras", got_keras), ("tflite", got_tflite)):
            err = float(np.max(np.abs(got - ref)))
            print(f"  frontend {label:<6} vs numpy, {name:<5}: max |diff| = {err:.2e} (log-mel units)")
            if err > 1e-2:
                errors.append(f"{label} frontend differs from numpy on {name} (max diff {err:.3g})")
    return errors


def _frontend_tflite(model: keras.Model) -> bytes:
    @tf.function(input_signature=[tf.TensorSpec([1, fe.WINDOW_SAMPLES], tf.float32)])
    def serve(w):
        return model(w)

    conv = tf.lite.TFLiteConverter.from_concrete_functions([serve.get_concrete_function()], model)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    return conv.convert()


def main() -> int:
    print("frontend parity:")
    errors = check_frontend()

    model = build_dummy()
    export(model, OUT)
    print(f"\nwrote {OUT.relative_to(ML.parent)}")
    errors += verify(OUT)

    # The TFLite model must agree with Keras.
    interp = Interpreter(model_path=str(OUT))
    interp.allocate_tensors()
    x = np.random.default_rng(2).uniform(-1, 1, (1, fe.WINDOW_SAMPLES)).astype(np.float32)
    interp.set_tensor(interp.get_input_details()[0]["index"], x)
    interp.invoke()
    diff = float(np.max(np.abs(interp.get_tensor(interp.get_output_details()[0]["index"]) - np.asarray(model(x)))))
    print(f"  keras vs tflite probabilities: max |diff| = {diff:.2e}")
    if diff > 1e-4:
        errors.append(f"tflite output differs from keras by {diff:.3g}")

    sig = interp.get_signature_list()
    print(f"  signatures: {sig}")
    if sig.get("serving_default") != {"inputs": ["waveform"], "outputs": ["probabilities"]}:
        errors.append(f"unexpected signature {sig}")

    for e in errors:
        print(f"error: {e}")
    print("OK" if not errors else "FAILED")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

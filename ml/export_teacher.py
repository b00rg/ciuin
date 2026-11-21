"""Export the teacher (YAMNet + MLP head) as a reference model for the app side.

    python3 ml/export_teacher.py

Same contract as the students (model_io.md): waveform float32 [1, 15600] in,
probabilities float32 [1, 9] out. Reference only: ~16 MB and ~4M params, it's
there so B2 can compare a student's outputs against the teacher on the same
window, on the desktop target. Not meant to ship on phones.

The head's weights are baked in as constants instead of calling the Keras
model inside a tf.function (see tflite_export.py for why that breaks).

Output: ml/export/audio-teacher.tflite
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
sys.path.insert(0, str(ML))

import teacher as T  # noqa: E402
from frontend import WINDOW_SAMPLES  # noqa: E402

OUT = ML / "export" / "audio-teacher.tflite"


def build():
    import keras
    import tensorflow as tf

    head = keras.models.load_model(T.HEAD_PATH)
    dense = [l for l in head.layers if isinstance(l, keras.layers.Dense)]
    assert len(dense) == 2, "expected the 1024-512-9 head from teacher.py"
    (w1, b1), (w2, b2) = (l.get_weights() for l in dense)

    class Teacher(tf.Module):
        def __init__(self):
            super().__init__()
            self.yamnet = tf.saved_model.load(str(T.fetch_yamnet()))
            self.w1, self.b1 = tf.constant(w1), tf.constant(b1)
            self.w2, self.b2 = tf.constant(w2), tf.constant(b2)

        @tf.function(input_signature=[tf.TensorSpec([1, WINDOW_SAMPLES], tf.float32, name="waveform")])
        def serve(self, waveform):
            emb = self.yamnet(waveform[0])[1]          # [1, 1024] for one 0.975 s window
            emb = emb[:1]
            h = tf.nn.relu(emb @ self.w1 + self.b1)
            return {"probabilities": tf.nn.softmax(h @ self.w2 + self.b2)}

    return Teacher(), head


def main() -> int:
    import tensorflow as tf

    from verify_tflite import verify

    module, head = build()
    with tempfile.TemporaryDirectory() as d:
        tf.saved_model.save(module, d, signatures={"serving_default": module.serve})
        conv = tf.lite.TFLiteConverter.from_saved_model(d)
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        OUT.write_bytes(conv.convert())

    errors = verify(OUT)
    if errors:
        print("export failed verification:", errors)
        return 1

    # Parity: .tflite vs the Python teacher (YAMNet embedder + Keras head) on real windows.
    with np.load(T.shard_path("esc50")) as s:
        keys = s["keys"]
    clips = {f"{c['path']}@{c['start_s']:.3f}": c for c in T.index_clips()}
    _, w = T.clip_windows(clips[keys[0]])
    w = w[:4]
    ref = head.predict(T.yamnet_embedder()(w.astype(np.float32)), verbose=0)
    from ai_edge_litert.interpreter import Interpreter

    interp = Interpreter(model_path=str(OUT))
    interp.allocate_tensors()
    i, o = interp.get_input_details()[0]["index"], interp.get_output_details()[0]["index"]
    got = []
    for win in w:
        interp.set_tensor(i, win[None].astype(np.float32))
        interp.invoke()
        got.append(interp.get_tensor(o)[0].copy())
    got = np.array(got)
    diff = float(np.abs(ref - got).max())
    print(f"wrote {OUT.relative_to(ML.parent)} ({OUT.stat().st_size / 1e6:.1f} MB); "
          f"max |tflite - python| = {diff:.2e}")
    return 0 if diff < 1e-3 else 1


if __name__ == "__main__":
    sys.exit(main())

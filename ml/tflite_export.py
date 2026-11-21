"""Export a Keras audio model (waveform in, 9 probabilities out) to TFLite.

Used for the dummy model now and for every student later, so all exports
share one signature:

    signature "serving_default"
      input  "waveform"       float32 [1, 15600]   16 kHz mono, [-1, 1]
      output "probabilities"  float32 [1, 9]       softmax, SoundClass order

Only TFLite builtin ops are allowed: conversion fails instead of silently
falling back to Flex ops the app doesn't ship.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

import keras
import tensorflow as tf

from frontend import WINDOW_SAMPLES

INPUT_NAME, OUTPUT_NAME = "waveform", "probabilities"


def to_tflite(model: keras.Model) -> bytes:
    """fp32 TFLite flatbuffer. (int8 quantisation is B2's job, from these.)

    Goes through Keras's own ExportArchive (a SavedModel with the weights
    tracked properly). Converting a tf.function that closes over a Keras 3
    model leaves the weights as uninitialised resource variables
    (VAR_HANDLE / READ_VARIABLE ops) and wrong outputs.
    """
    archive = keras.export.ExportArchive()
    archive.track(model)
    archive.add_endpoint(
        name="serving_default",
        fn=lambda waveform: {OUTPUT_NAME: model(waveform, training=False)},
        input_signature=[tf.TensorSpec([1, WINDOW_SAMPLES], tf.float32, name=INPUT_NAME)],
    )
    with tempfile.TemporaryDirectory() as d:
        archive.write_out(d, verbose=False)
        converter = tf.lite.TFLiteConverter.from_saved_model(d)
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        return converter.convert()


def export(model: keras.Model, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(to_tflite(model))
    return path

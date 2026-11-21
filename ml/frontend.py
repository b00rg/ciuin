"""Log-mel spectrogram built from plain ops, so it lives inside the model graph.

The app hands the model raw audio and never computes a spectrogram itself.
The STFT is a strided 1-D convolution with fixed cosine/sine weights, and the
mel step is a matrix multiply. Both export to TFLite as builtin ops
(CONV_2D, MUL, ADD, BATCH_MATMUL/FULLY_CONNECTED, LOG), so no Flex/Select-TF
ops are needed on the phone.

Parameters match YAMNet's frontend: 25 ms Hann window, 10 ms hop, 64 mel
bins over 125-7500 Hz, log(mel + 0.001). One 0.975 s window (15,600 samples)
gives a 96 x 64 log-mel patch.
"""

from __future__ import annotations

import keras
import numpy as np
from keras import ops

SAMPLE_RATE = 16_000
WINDOW_SAMPLES = 15_600       # 0.975 s, the model input length
FRAME_LENGTH = 400            # 25 ms
HOP_LENGTH = 160              # 10 ms
N_FFT = 512
N_BINS = N_FFT // 2 + 1       # 257
N_MELS = 64
F_MIN, F_MAX = 125.0, 7500.0
LOG_OFFSET = 1e-3
N_FRAMES = (WINDOW_SAMPLES - FRAME_LENGTH) // HOP_LENGTH + 1  # 96


def hann_window(n: int = FRAME_LENGTH) -> np.ndarray:
    """Periodic Hann window (same as tf.signal.hann_window's default)."""
    return (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)).astype(np.float32)


def stft_kernel() -> np.ndarray:
    """Conv1D weights [FRAME_LENGTH, 1, 2 * N_BINS]: windowed cos | -sin basis.

    The frame is zero-padded to N_FFT, so only the first FRAME_LENGTH taps of
    each basis function are non-zero and the kernel can stop there.
    """
    n = np.arange(FRAME_LENGTH)[:, None]
    k = np.arange(N_BINS)[None, :]
    angle = 2 * np.pi * n * k / N_FFT
    w = hann_window()[:, None]
    kernel = np.concatenate([w * np.cos(angle), -w * np.sin(angle)], axis=1)
    return kernel[:, None, :].astype(np.float32)


def _hz_to_mel(f):
    return 1127.0 * np.log1p(np.asarray(f, dtype=np.float64) / 700.0)


def mel_matrix() -> np.ndarray:
    """[N_BINS, N_MELS] triangular HTK-mel filterbank.

    Same construction as tf.signal.linear_to_mel_weight_matrix (which YAMNet
    uses): the DC bin is zeroed, triangles are spaced evenly in mel.
    """
    bin_hz = np.linspace(0.0, SAMPLE_RATE / 2, N_BINS)[1:]
    bin_mel = _hz_to_mel(bin_hz)[:, None]
    edges = np.linspace(_hz_to_mel(F_MIN), _hz_to_mel(F_MAX), N_MELS + 2)
    lower, center, upper = edges[:-2], edges[1:-1], edges[2:]
    up = (bin_mel - lower) / (center - lower)
    down = (upper - bin_mel) / (upper - center)
    weights = np.maximum(0.0, np.minimum(up, down))
    return np.pad(weights, [[1, 0], [0, 0]]).astype(np.float32)


@keras.saving.register_keras_serializable(package="ciuin")
class LogMelFrontend(keras.layers.Layer):
    """[batch, 15600] waveform in [-1, 1] -> [batch, 96, 64] log-mel.

    The STFT and mel weights are constants, not layer weights: variables
    would export as VAR_HANDLE/READ_VARIABLE ops instead of being baked into
    the .tflite, and they are never trained anyway.
    """

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._kernel = stft_kernel()
        self._mel = mel_matrix()

    def call(self, waveform):
        kernel = ops.convert_to_tensor(self._kernel)
        mel = ops.convert_to_tensor(self._mel)
        x = ops.expand_dims(waveform, -1)                                  # [B, T, 1]
        spec = ops.conv(x, kernel, strides=HOP_LENGTH, padding="valid")    # [B, 96, 514]
        real, imag = spec[..., :N_BINS], spec[..., N_BINS:]
        power = real * real + imag * imag                                  # [B, 96, 257]
        return ops.log(ops.matmul(power, mel) + LOG_OFFSET)               # [B, 96, 64]

    def compute_output_shape(self, input_shape):
        return (input_shape[0], N_FRAMES, N_MELS)


def log_mel_reference(waveform: np.ndarray) -> np.ndarray:
    """Plain numpy version (rfft), used to check the in-graph frontend."""
    waveform = np.asarray(waveform, dtype=np.float64).reshape(-1)
    frames = np.stack([waveform[i * HOP_LENGTH:i * HOP_LENGTH + FRAME_LENGTH] for i in range(N_FRAMES)])
    spectrum = np.fft.rfft(frames * hann_window(), n=N_FFT)
    power = np.abs(spectrum) ** 2
    return np.log(power @ mel_matrix() + LOG_OFFSET).astype(np.float32)

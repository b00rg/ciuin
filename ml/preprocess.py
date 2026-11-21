"""Audio preprocessing: file -> 16 kHz mono float -> model windows -> clip score.

This module is the single definition used by training and evaluation, and
ml/export/preprocess.md describes the same steps for the Kotlin port. If
you change a constant or a rule here, update preprocess.md in the same
commit.

Everything else (spectrogram, mel, log) is inside the model graph
(ml/frontend.py), so the app never computes it.
"""

from __future__ import annotations

from math import gcd
from pathlib import Path
from typing import Callable

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

SAMPLE_RATE = 16_000
WINDOW = 15_600          # 0.975 s, the model input length
HOP = 8_000              # 0.5 s between windows when scoring a whole clip
SILENCE_DBFS = -60.0     # windows quieter than this are skipped


def to_mono(x: np.ndarray) -> np.ndarray:
    """[frames, channels] -> [frames], by averaging the channels."""
    return x if x.ndim == 1 else x.mean(axis=1)


def resample(x: np.ndarray, sr: int, target: int = SAMPLE_RATE) -> np.ndarray:
    """Band-limited polyphase resampling (anti-aliasing included)."""
    if sr == target:
        return x
    g = gcd(sr, target)
    return resample_poly(x, target // g, sr // g).astype(np.float32)


def load(path: str | Path, start_s: float = 0.0, end_s: float | None = None) -> np.ndarray:
    """Any audio file (or a segment of it) -> float32 mono 16 kHz in [-1, 1].

    soundfile decodes int16/int24/int32/float WAVs to float32 already scaled
    to [-1, 1] (int16 / 32768, and so on). No gain normalisation: levels are
    left as recorded, and training adds random gain instead.
    """
    with sf.SoundFile(str(path)) as f:
        sr = f.samplerate
        f.seek(int(round(start_s * sr)))
        frames = -1 if end_s is None else int(round((end_s - start_s) * sr))
        x = f.read(frames, dtype="float32", always_2d=True)
    x = resample(to_mono(x), sr)
    return np.clip(x, -1.0, 1.0).astype(np.float32)


def rms_dbfs(x: np.ndarray) -> np.ndarray:
    """RMS level in dB relative to full scale, per row (or for a 1-D signal)."""
    return 10 * np.log10(np.mean(np.square(x, dtype=np.float64), axis=-1) + 1e-12)


def window_starts(n: int, hop: int = HOP) -> list[int]:
    """Start sample of each window for a clip of n samples (see windows())."""
    if n <= WINDOW:
        return [0]
    starts = list(range(0, n - WINDOW + 1, hop))
    if starts[-1] != n - WINDOW:
        starts.append(n - WINDOW)
    return starts


def windows(x: np.ndarray, hop: int = HOP) -> np.ndarray:
    """[n, WINDOW] windows every `hop` samples.

    Clips shorter than one window are zero-padded at the end. The last
    window is aligned to the end of the clip, so no audio is dropped.
    """
    if len(x) <= WINDOW:
        return np.pad(x, (0, WINDOW - len(x)))[None].astype(np.float32)
    return np.stack([x[s:s + WINDOW] for s in window_starts(len(x), hop)]).astype(np.float32)


def active_window_starts(x: np.ndarray, hop: int = HOP) -> tuple[list[int], np.ndarray]:
    """Like active_windows(), but also returns where each kept window starts."""
    starts, w = window_starts(len(x), hop), windows(x, hop)
    level = rms_dbfs(w)
    keep = level >= SILENCE_DBFS
    idx = np.flatnonzero(keep) if keep.any() else np.array([int(level.argmax())])
    return [starts[i] for i in idx], w[idx]


def active_windows(x: np.ndarray, hop: int = HOP) -> np.ndarray:
    """Windows above SILENCE_DBFS. If every window is silent, keep the loudest one."""
    return active_window_starts(x, hop)[1]


def clip_probabilities(predict: Callable[[np.ndarray], np.ndarray], x: np.ndarray, hop: int = HOP) -> np.ndarray:
    """Clip-level class probabilities: the mean of the window probabilities.

    `predict` maps [n, WINDOW] float32 -> [n, 9] softmax. Averaging
    probabilities (not logits) keeps the result a distribution, and matches
    how the app averages a 30 s measurement into `sound_classes`.
    """
    return predict(active_windows(x, hop)).mean(axis=0)


def random_window(x: np.ndarray, rng: np.random.Generator, tries: int = 10) -> np.ndarray:
    """One random training crop, retried to avoid silent stretches."""
    if len(x) <= WINDOW:
        return windows(x)[0]
    best, best_level = None, -np.inf
    for _ in range(tries):
        s = int(rng.integers(0, len(x) - WINDOW + 1))
        w = x[s:s + WINDOW]
        level = float(rms_dbfs(w))
        if level >= SILENCE_DBFS:
            return w
        if level > best_level:
            best, best_level = w, level
    return best

"""Tests for ml/preprocess.py.   Run: python -m pytest ml/tests"""

import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import preprocess as pp  # noqa: E402

AUDIO_RAW = Path(__file__).resolve().parents[2] / "data" / "raw" / "audio"


def tone(freq, sr, seconds, amp=0.5):
    t = np.arange(int(sr * seconds)) / sr
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def peak_hz(x, sr=pp.SAMPLE_RATE):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x))))
    return np.fft.rfftfreq(len(x), 1 / sr)[spec.argmax()]


@pytest.mark.parametrize("sr", [8000, 22050, 24000, 44100, 48000, 96000])
def test_resample_keeps_pitch_and_length(sr):
    y = pp.resample(tone(1000, sr, 2.0), sr)
    assert abs(len(y) - 2 * pp.SAMPLE_RATE) <= 1
    assert abs(peak_hz(y) - 1000) < 5


def test_resample_removes_content_above_nyquist():
    # 12 kHz can't exist at 16 kHz; it must be filtered out, not folded to 4 kHz.
    y = pp.resample(tone(12_000, 48_000, 1.0), 48_000)
    assert pp.rms_dbfs(y) < pp.rms_dbfs(tone(12_000, 48_000, 1.0)) - 40


def test_to_mono_averages_channels():
    x = np.stack([np.ones(10), -np.ones(10) * 0.5], axis=1)
    assert np.allclose(pp.to_mono(x), 0.25)


@pytest.mark.parametrize("subtype", ["PCM_16", "PCM_24", "PCM_32", "FLOAT"])
def test_load_decodes_formats_to_unit_range(tmp_path, subtype):
    x = tone(440, 44_100, 1.0, amp=0.9)
    stereo = np.stack([x, x], axis=1)
    path = tmp_path / f"t_{subtype}.wav"
    sf.write(path, stereo, 44_100, subtype=subtype)
    y = pp.load(path)
    assert y.dtype == np.float32 and y.ndim == 1
    assert abs(len(y) - pp.SAMPLE_RATE) <= 1
    assert 0.85 < np.abs(y).max() <= 1.0
    assert abs(peak_hz(y) - 440) < 5


def test_load_segment(tmp_path):
    sr = 16_000
    x = np.concatenate([np.zeros(sr), tone(1000, sr, 1.0), np.zeros(sr)])
    path = tmp_path / "seg.wav"
    sf.write(path, x, sr)
    seg = pp.load(path, start_s=1.0, end_s=2.0)
    assert len(seg) == sr
    assert pp.rms_dbfs(seg) > -10


def test_windows_short_clip_is_padded():
    w = pp.windows(np.ones(1000, np.float32))
    assert w.shape == (1, pp.WINDOW)
    assert w[0, :1000].sum() == 1000 and w[0, 1000:].sum() == 0


def test_windows_cover_the_whole_clip():
    x = np.arange(5 * pp.SAMPLE_RATE, dtype=np.float32)
    w = pp.windows(x)
    assert w.shape[1] == pp.WINDOW
    assert w[0, 0] == 0
    assert w[-1, -1] == x[-1]  # last window ends at the clip's end
    assert len(w) == len(range(0, len(x) - pp.WINDOW + 1, pp.HOP)) + 1


def test_active_windows_drop_silence():
    x = np.concatenate([tone(500, pp.SAMPLE_RATE, 2.0), np.zeros(3 * pp.SAMPLE_RATE, np.float32)])
    active = pp.active_windows(x)
    assert len(active) < len(pp.windows(x))
    assert (pp.rms_dbfs(active) >= pp.SILENCE_DBFS).all()


def test_active_windows_all_silent_keeps_one():
    assert pp.active_windows(np.zeros(3 * pp.SAMPLE_RATE, np.float32)).shape == (1, pp.WINDOW)


def test_clip_probabilities_is_mean_of_windows():
    x = tone(500, pp.SAMPLE_RATE, 3.0)
    n = len(pp.active_windows(x))
    fake = np.tile(np.eye(9, dtype=np.float32), (n // 9 + 1, 1))[:n]
    p = pp.clip_probabilities(lambda w: fake[:len(w)], x)
    assert np.isclose(p.sum(), 1) and np.allclose(p, fake.mean(axis=0))


def test_random_window_avoids_silence():
    x = np.concatenate([np.zeros(10 * pp.SAMPLE_RATE, np.float32), tone(500, pp.SAMPLE_RATE, 3.0)])
    rng = np.random.default_rng(0)
    hits = [pp.rms_dbfs(pp.random_window(x, rng, tries=20)) >= pp.SILENCE_DBFS for _ in range(50)]
    assert np.mean(hits) > 0.8


@pytest.mark.skipif(not (AUDIO_RAW / "UrbanSound8K").is_dir(), reason="UrbanSound8K not downloaded")
def test_real_urbansound8k_files():
    # US8K mixes 44.1/48/96/24 kHz, mono and stereo, 16/24-bit and float.
    for f in sorted((AUDIO_RAW / "UrbanSound8K" / "audio").rglob("*.wav"))[:200:20]:
        info = sf.info(str(f))
        y = pp.load(f)
        assert y.dtype == np.float32 and y.ndim == 1
        assert abs(len(y) - info.frames / info.samplerate * pp.SAMPLE_RATE) <= 2
        assert np.abs(y).max() <= 1.0


def test_active_window_starts_match_windows():
    x = np.concatenate([tone(500, pp.SAMPLE_RATE, 2.0), np.zeros(2 * pp.SAMPLE_RATE, np.float32),
                        tone(800, pp.SAMPLE_RATE, 1.5)])
    starts, w = pp.active_window_starts(x)
    assert len(starts) == len(w)
    for s, win in zip(starts, w):
        assert np.array_equal(win, x[s:s + pp.WINDOW])

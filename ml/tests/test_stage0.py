"""Reference values for the Kotlin port of cascade stage 0 (ml/stage0.py)."""

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import stage0 as s  # noqa: E402

T = np.arange(s.BLOCK) / s.SR


@pytest.mark.parametrize("f,db", [(100, -19.1), (1000, 0.0), (4000, 1.0), (6300, -0.1)])
def test_a_weighting_matches_iec_61672(f, db):
    assert abs(float(s.a_weight_db(np.array([f]))[0]) - db) < 0.1


@pytest.mark.parametrize("amp,db", [(1.0, 0.0), (0.1, -20.0), (0.01, -40.0)])
def test_level_of_1khz_sine(amp, db):
    level, _ = s.block_features(amp * np.sin(2 * np.pi * 1000 * T))
    assert abs(level - db) < 0.05


def test_level_a_weights_low_frequencies():
    level, _ = s.block_features(np.sin(2 * np.pi * 100 * T))
    assert abs(level - (-19.1)) < 0.3


def test_silence_is_very_quiet():
    level, flux = s.block_features(np.zeros(s.BLOCK))
    assert level < -100 and flux == 0.0


def test_flux_catches_a_short_beep():
    rng = np.random.default_rng(0)
    noise = 0.01 * rng.standard_normal(s.BLOCK)
    beep = noise.copy()
    beep[8000:8800] += 0.05 * np.sin(2 * np.pi * 3000 * T[:800])
    assert s.block_features(beep)[1] > 1.5 * s.block_features(noise)[1]


def test_baseline_only_moves_while_asleep():
    p = {"jump_db": 6, "flux_max": 99.0, "abs_dba": 0, "tau_s": 30}
    feats = np.array([[-40.0, 0.1]] * 10 + [[-20.0, 0.1]] * 5 + [[-40.0, 0.1]] * 5)
    wake = s.run_stage0(feats, p)
    assert not wake[:10].any() and wake[10:15].all() and not wake[15:].any()

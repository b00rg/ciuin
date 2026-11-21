"""Per-place aggregation: mean feature vector + generic [0, 1] scores (todo.md section A).

Scores: 1 = calmest / most accessible, 0 = worst. The thresholds below are first guesses;
tune them with the field measurements in Phase 2 (and tell the team: the map colours depend on them).
"""

from __future__ import annotations

import math

from models import SOUND_CLASSES, FeatureVector, PlaceScore

NOISE_QUIET_DB, NOISE_LOUD_DB = 40.0, 75.0  # dB(A): 40 -> 1.0, 75 -> 0.0
LUX_COMFORT, LUX_HARSH = 300.0, 3000.0  # log scale: <=300 lx -> 1.0, >=3000 lx -> 0.0
FLICKER_BAD = 0.2  # flicker index: 0 -> 1.0, >=0.2 -> 0.0
CROWD_BUSY = 30  # people in view: 0 -> 1.0, >=30 -> 0.0
ACCESS = {"yes": 1.0, "designated": 1.0, "limited": 0.5, "no": 0.0}
ACCESS_UNKNOWN = 0.5


def _clamp01(x: float) -> float:
    return max(0.0, min(1.0, x))


def mean_features(vectors: list[FeatureVector]) -> FeatureVector:
    n = len(vectors)
    if n == 0:
        raise ValueError("no vectors")
    return FeatureVector(
        db_mean=round(sum(v.db_mean for v in vectors) / n, 2),
        db_peak=round(sum(v.db_peak for v in vectors) / n, 2),
        sound_classes={c: round(sum(v.sound_classes[c] for v in vectors) / n, 4) for c in SOUND_CLASSES},
        lux_mean=round(sum(v.lux_mean for v in vectors) / n, 1),
        flicker_index=round(sum(v.flicker_index for v in vectors) / n, 4),
        people_count=round(sum(v.people_count for v in vectors) / n),
    )


def score(f: FeatureVector, wheelchair: str | None) -> PlaceScore:
    noise = 1 - (f.db_mean - NOISE_QUIET_DB) / (NOISE_LOUD_DB - NOISE_QUIET_DB)
    lux = max(f.lux_mean, 1.0)
    light = 1 - (math.log10(lux) - math.log10(LUX_COMFORT)) / (math.log10(LUX_HARSH) - math.log10(LUX_COMFORT))
    return PlaceScore(
        noise=round(_clamp01(noise), 3),
        light=round(_clamp01(light), 3),
        flicker=round(_clamp01(1 - f.flicker_index / FLICKER_BAD), 3),
        crowd=round(_clamp01(1 - f.people_count / CROWD_BUSY), 3),
        access=ACCESS.get(wheelchair or "", ACCESS_UNKNOWN),
    )

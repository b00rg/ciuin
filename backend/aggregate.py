"""Per-place aggregation: mean feature vector + generic [0, 1] scores (todo.md section A).

Scores: 1 = calmest / most accessible, 0 = worst. The thresholds below are first guesses;
tune them with the field measurements in Phase 2 (and tell the team: the map colours depend on them).
"""

from __future__ import annotations

import math
from collections.abc import Iterable

from models import SOUND_CLASSES, FeatureVector, PlaceScore

NOISE_QUIET_DB, NOISE_LOUD_DB = 40.0, 75.0  # dB(A): 40 -> 1.0, 75 -> 0.0
LUX_COMFORT, LUX_HARSH = 300.0, 3000.0  # log scale: <=300 lx -> 1.0, >=3000 lx -> 0.0
FLICKER_BAD = 0.2  # flicker index: 0 -> 1.0, >=0.2 -> 0.0
CROWD_BUSY = 30  # people (estimates / older data): 0 -> 1.0, >=30 -> 0.0
CROWD_BUSY_DEVICES = 60  # nearby Bluetooth devices (~2 per person): 0 -> 1.0, >=60 -> 0.0
ACCESS = {"yes": 1.0, "designated": 1.0, "limited": 0.5, "no": 0.0}
ACCESS_UNKNOWN = 0.5


def _clamp01(x: float) -> float:
    return max(0.0, min(1.0, x))


def _mean(readings: Iterable[float | None], digits: int | None = None) -> float | None:
    present = [reading for reading in readings if reading is not None]
    return round(sum(present) / len(present), digits) if present else None


def mean_features(vectors: list[FeatureVector]) -> FeatureVector:
    if not vectors:
        raise ValueError("no vectors")
    mixes = [v.sound_classes for v in vectors if v.sound_classes is not None]
    return FeatureVector(
        db_mean=_mean((v.db_mean for v in vectors), 2),
        db_peak=_mean((v.db_peak for v in vectors), 2),
        sound_classes={c: round(sum(m[c] for m in mixes) / len(mixes), 4) for c in SOUND_CLASSES} if mixes else None,
        lux_mean=_mean((v.lux_mean for v in vectors), 1),
        flicker_index=_mean((v.flicker_index for v in vectors), 4),
        people_count=_mean(v.people_count for v in vectors),
        nearby_devices=_mean(v.nearby_devices for v in vectors),
    )


def _dimension_score(value: float | None, calm: float, busy: float) -> float | None:
    return None if value is None else round(_clamp01(1 - (value - calm) / (busy - calm)), 3)


def score(f: FeatureVector, wheelchair: str | None) -> PlaceScore:
    log_lux = None if f.lux_mean is None else math.log10(max(f.lux_mean, 1.0))
    return PlaceScore(
        noise=_dimension_score(f.db_mean, NOISE_QUIET_DB, NOISE_LOUD_DB),
        light=_dimension_score(log_lux, math.log10(LUX_COMFORT), math.log10(LUX_HARSH)),
        flicker=_dimension_score(f.flicker_index, 0, FLICKER_BAD),
        crowd=_dimension_score(f.nearby_devices, 0, CROWD_BUSY_DEVICES)
        if f.nearby_devices is not None
        else _dimension_score(f.people_count, 0, CROWD_BUSY),
        access=ACCESS.get(wheelchair or "", ACCESS_UNKNOWN),
    )

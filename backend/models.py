"""API models: the shared contracts from Claude/todo.md section 0, mirrored from shared/commonMain.

Field names are the JSON names. Keep these in sync with the Kotlin data classes; the tests check
the key sets against the section 0 examples.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

# Same order as SoundClass in shared/commonMain (= model output index). Never reorder.
SOUND_CLASSES = (
    "speech_crowd",
    "music",
    "traffic",
    "siren",
    "construction",
    "alarm_beep",
    "dog",
    "machinery_hum",
    "other",
)


class Strict(BaseModel):
    # Unknown fields are rejected: the phone may only send the aggregates in the contract,
    # never raw audio, images, embeddings, trigger taps or identity.
    model_config = ConfigDict(extra="forbid")


class FeatureVector(Strict):
    db_mean: float
    db_peak: float
    sound_classes: dict[str, float]
    lux_mean: float
    flicker_index: float
    people_count: int = Field(ge=0)

    @field_validator("sound_classes")
    @classmethod
    def exactly_the_nine_classes(cls, v: dict[str, float]) -> dict[str, float]:
        if set(v) != set(SOUND_CLASSES):
            missing = sorted(set(SOUND_CLASSES) - set(v))
            extra = sorted(set(v) - set(SOUND_CLASSES))
            raise ValueError(f"sound_classes must have exactly the 9 classes; missing {missing}, unexpected {extra}")
        if any(p < 0 or p > 1 for p in v.values()):
            raise ValueError("sound_classes values must be probabilities in [0, 1]")
        return v


EXAMPLE_FEATURES = {
    "db_mean": 62.4,
    "db_peak": 78.1,
    "sound_classes": {
        "speech_crowd": 0.71, "music": 0.12, "traffic": 0.08, "siren": 0.0, "construction": 0.0,
        "alarm_beep": 0.0, "dog": 0.0, "machinery_hum": 0.03, "other": 0.06,
    },
    "lux_mean": 340,
    "flicker_index": 0.04,
    "people_count": 7,
}


class Measurement(Strict):
    """One on-device measurement. Aggregates only: unknown fields are rejected with 422."""

    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "examples": [
                {
                    # a real café from places_lausanne.geojson, so "Try it out" updates a place
                    "place_id": "osm:node/911106747",
                    "lat": 46.522522,
                    "lon": 6.630265,
                    "timestamp": "2026-11-21T14:05:00Z",
                    "duration_s": 30,
                    "features": EXAMPLE_FEATURES,
                    "flicker_hz": 100,
                    "model_variant": "audio-student-s",
                    "device": "pixel-7",
                }
            ]
        },
    )

    place_id: str = Field(min_length=1)
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    timestamp: str  # ISO-8601 UTC, e.g. "2026-11-21T14:05:00Z"
    duration_s: int = Field(gt=0)
    features: FeatureVector
    flicker_hz: float | None = None
    model_variant: str
    device: str


class MeasuredAggregate(BaseModel):
    n: int
    features: FeatureVector


class PlaceScore(BaseModel):
    """Generic per-dimension scores in [0, 1] for users without a personal model.
    1 = calmest / most accessible, 0 = worst. Heuristics live in aggregate.py."""

    noise: float
    light: float
    flicker: float
    crowd: float
    access: float


class Place(BaseModel):
    place_id: str
    name: str
    lat: float
    lon: float
    category: str
    wheelchair: str | None = None
    sensory_friendly: str | None = None
    quiet_room: bool = False
    baseline_noise_db: float | None = None
    baseline_source: Literal["sonbase", "eea"] | None = None
    measured: MeasuredAggregate | None = None
    score: PlaceScore | None = None


class Stats(BaseModel):
    places_mapped: int
    measurements_taken: int
    sensory_friendly_tags: int


class CoverageCell(BaseModel):
    lat: float
    lon: float
    wheelchair: int = 0
    sensory_friendly: int = 0
    noise_source: Literal["sonbase", "eea"] | None = None
    measurements: int = 0


class Coverage(BaseModel):
    res_deg: float
    cells: list[CoverageCell]


class MeasurementResult(BaseModel):
    """Response to POST /measurements. `place` is null when place_id isn't a known place:
    the measurement is still stored (it counts in /stats and /coverage)."""

    stored: bool
    place: Place | None

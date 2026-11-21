"""API tests against a fresh temporary database seeded from places_lausanne.geojson.
Run from backend/:  python -m pytest -q
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

LAUSANNE = "6.58,46.50,6.67,46.54"
# Section 0 key sets (Claude/todo.md). If these change, the Kotlin classes must change too.
PLACE_KEYS = {
    "place_id", "name", "lat", "lon", "category", "wheelchair", "sensory_friendly", "quiet_room",
    "baseline_noise_db", "baseline_noise_night_db", "baseline_source", "osm_tags", "measured", "score",
}
FEATURE_KEYS = {"db_mean", "db_peak", "sound_classes", "lux_mean", "flicker_index", "people_count"}
SOUND = ["speech_crowd", "music", "traffic", "siren", "construction", "alarm_beep", "dog", "machinery_hum", "other"]


def features(db_mean: float = 62.4, people: int = 7, lux: float = 340, flicker: float = 0.04) -> dict:
    probs = dict.fromkeys(SOUND, 0.0) | {"speech_crowd": 0.71, "music": 0.12, "traffic": 0.08,
                                          "machinery_hum": 0.03, "other": 0.06}
    return {"db_mean": db_mean, "db_peak": db_mean + 15, "sound_classes": probs, "lux_mean": lux,
            "flicker_index": flicker, "people_count": people}


def measurement(place_id: str, lat: float, lon: float, **kw) -> dict:
    return {"place_id": place_id, "lat": lat, "lon": lon, "timestamp": "2026-11-21T14:05:00Z",
            "duration_s": 30, "features": features(**kw), "flicker_hz": 100,
            "model_variant": "audio-student-s", "device": "pixel-7"}


@pytest.fixture()
def client(tmp_path):
    os.environ["CIUIN_DB"] = str(tmp_path / "test.db")
    from fastapi.testclient import TestClient

    import main

    with TestClient(main.app) as c:  # runs lifespan -> creates + seeds the DB
        yield c


@pytest.fixture()
def a_place(client) -> dict:
    return client.get("/places", params={"bbox": LAUSANNE}).json()[0]


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_places_bbox_returns_seeded_places_with_contract_keys(client):
    places = client.get("/places", params={"bbox": LAUSANNE}).json()
    assert len(places) > 2000
    assert set(places[0]) == PLACE_KEYS
    assert all(p["baseline_source"] == "sonbase" for p in places if p["baseline_noise_db"] is not None)


def test_places_small_bbox_filters(client):
    # ~200 m around Place de la Riponne
    small = client.get("/places", params={"bbox": "6.6310,46.5225,6.6345,46.5245"}).json()
    assert 0 < len(small) < 200
    assert all(6.6310 <= p["lon"] <= 6.6345 and 46.5225 <= p["lat"] <= 46.5245 for p in small)


def test_places_bbox_is_exact(client):
    """Exactly the seeded places inside the box: none missing at the edges (R-Tree float32
    rounding), none outside. A few OSM ways overlap the box but have their centre outside it."""
    import json

    seeded = [f["properties"] for f in json.loads(
        (Path(__file__).resolve().parents[2] / "data" / "processed" / "places_lausanne.geojson").read_text(encoding="utf-8")
    )["features"]]
    for bbox in (LAUSANNE, "6.6300,46.5220,6.6310,46.5230"):
        w, s, e, n = (float(x) for x in bbox.split(","))
        expected = {p["place_id"] for p in seeded if w <= p["lon"] <= e and s <= p["lat"] <= n}
        got = {p["place_id"] for p in client.get("/places", params={"bbox": bbox}).json()}
        assert got == expected
    # an edge case: a box whose edge sits exactly on a place
    p = seeded[0]
    edge = f"{p['lon']},{p['lat']},{p['lon'] + 0.001},{p['lat'] + 0.001}"
    assert p["place_id"] in {x["place_id"] for x in client.get("/places", params={"bbox": edge}).json()}


def test_bad_bbox_is_422(client):
    assert client.get("/places", params={"bbox": "1,2,3"}).status_code == 422
    assert client.get("/places", params={"bbox": "6.7,46.5,6.6,46.6"}).status_code == 422  # min > max


def test_nearby_sorted_and_within_radius(client):
    from main import distance_m

    lat, lon = 46.5236, 6.6327
    res = client.get("/places/nearby", params={"lat": lat, "lon": lon, "radius": 300}).json()
    assert res
    d = [distance_m(lat, lon, p["lat"], p["lon"]) for p in res]
    assert d == sorted(d)
    assert max(d) <= 300


def test_post_measurement_aggregates_mean_and_scores(client, a_place):
    pid, lat, lon = a_place["place_id"], a_place["lat"], a_place["lon"]
    r1 = client.post("/measurements", json=measurement(pid, lat, lon, db_mean=60, people=10))
    assert r1.status_code == 201
    r2 = client.post("/measurements", json=measurement(pid, lat, lon, db_mean=70, people=20)).json()

    place = r2["place"]
    assert set(place) == PLACE_KEYS
    assert place["measured"]["n"] == 2
    f = place["measured"]["features"]
    assert set(f) == FEATURE_KEYS
    assert f["db_mean"] == 65 and f["people_count"] == 15
    assert set(f["sound_classes"]) == set(SOUND)
    s = place["score"]
    assert set(s) == {"noise", "light", "flicker", "crowd", "access"}
    assert all(0 <= v <= 1 for v in s.values())
    assert s["noise"] == pytest.approx(1 - (65 - 40) / 35, abs=1e-3)

    # visible through /places too
    again = [p for p in client.get("/places", params={"bbox": LAUSANNE}).json() if p["place_id"] == pid][0]
    assert again["measured"]["n"] == 2


def test_measurement_rejects_extra_fields(client, a_place):
    """Privacy: the phone may only send the contract's aggregates."""
    m = measurement(a_place["place_id"], a_place["lat"], a_place["lon"])
    m["raw_audio"] = "AAAA"
    assert client.post("/measurements", json=m).status_code == 422
    m = measurement(a_place["place_id"], a_place["lat"], a_place["lon"])
    m["user_id"] = "someone"
    assert client.post("/measurements", json=m).status_code == 422


def test_measurement_requires_exactly_nine_sound_classes(client, a_place):
    m = measurement(a_place["place_id"], a_place["lat"], a_place["lon"])
    m["features"]["sound_classes"]["quiet"] = 0.1
    assert client.post("/measurements", json=m).status_code == 422
    m = measurement(a_place["place_id"], a_place["lat"], a_place["lon"])
    del m["features"]["sound_classes"]["dog"]
    assert client.post("/measurements", json=m).status_code == 422


def test_measurement_for_unknown_place_is_stored_not_attached(client):
    r = client.post("/measurements", json=measurement("osm:node/1", 40.7, -74.0)).json()
    assert r == {"stored": True, "place": None}
    assert client.get("/stats").json()["measurements_taken"] == 1


def test_stats(client):
    s = client.get("/stats").json()
    assert set(s) == {"places_mapped", "measurements_taken", "sensory_friendly_tags"}
    assert s["places_mapped"] > 2000 and s["measurements_taken"] == 0


def test_coverage_counts_places_and_live_measurements(client):
    client.post("/measurements", json=measurement("osm:node/1", 40.7, -74.0))  # New York
    cov = client.get("/coverage", params={"res_deg": 2}).json()
    assert cov["res_deg"] == 2
    cells = {(c["lat"], c["lon"]): c for c in cov["cells"]}
    lausanne = cells[(47.0, 7.0)]  # 2° cell containing Lausanne
    assert lausanne["wheelchair"] > 500 and lausanne["noise_source"] == "sonbase"
    assert cells[(41.0, -73.0)]["measurements"] == 1


def test_places_carry_night_noise_and_osm_tags(client):
    places = client.get("/places", params={"bbox": LAUSANNE}).json()
    both = [p for p in places if p["baseline_noise_db"] is not None and p["baseline_noise_night_db"] is not None]
    assert len(both) > 2000
    assert sum(p["baseline_noise_night_db"] < p["baseline_noise_db"] for p in both) > 0.95 * len(both)
    assert sum("opening_hours" in p["osm_tags"] for p in places) > 1000


def test_heatmap_noise_day_and_night(client):
    import statistics

    day = client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "noise", "period": "day"}).json()
    night = client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "noise", "period": "night"}).json()
    assert day["type"] == "FeatureCollection" and day["cell_m"] == 50
    assert len(day["features"]) > 5000
    f = day["features"][0]
    assert f["geometry"]["type"] == "Point" and set(f["properties"]) == {"value", "source"}
    assert f["properties"]["source"] == "sonbase"
    dv = statistics.median(x["properties"]["value"] for x in day["features"])
    nv = statistics.median(x["properties"]["value"] for x in night["features"])
    assert nv < dv - 5  # nights are clearly quieter

    small = client.get("/heatmap", params={"bbox": "6.6300,46.5220,6.6310,46.5230"}).json()
    assert 0 < len(small["features"]) < 20
    assert client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "smell"}).status_code == 422
    assert client.get("/heatmap", params={"bbox": LAUSANNE, "period": "dawn"}).status_code == 422


def test_heatmap_measured_dims_from_measurements(client, a_place):
    assert client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "light"}).json()["features"] == []
    pid, lat, lon = a_place["place_id"], a_place["lat"], a_place["lon"]
    client.post("/measurements", json=measurement(pid, lat, lon, lux=200, people=4))
    client.post("/measurements", json=measurement(pid, lat, lon, lux=400, people=8))
    light = client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "light"}).json()["features"]
    crowd = client.get("/heatmap", params={"bbox": LAUSANNE, "dim": "crowd"}).json()["features"]
    assert len(light) == 1 and light[0]["properties"] == {"value": 300.0, "source": "measured", "n": 2}
    assert crowd[0]["properties"]["value"] == 6.0
    (clon, clat) = light[0]["geometry"]["coordinates"]
    assert abs(clat - lat) < 0.0005 and abs(clon - lon) < 0.0007  # within the 50 m cell


def test_existing_db_is_migrated_in_place(tmp_path):
    """An old ciuin.db without the new columns upgrades on start and keeps its measurements."""
    import sqlite3

    import db

    old = tmp_path / "old.db"
    conn = sqlite3.connect(old)
    conn.executescript("""
        CREATE TABLE places (id INTEGER PRIMARY KEY, place_id TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
          lat REAL NOT NULL, lon REAL NOT NULL, category TEXT NOT NULL, wheelchair TEXT, sensory_friendly TEXT,
          quiet_room INTEGER NOT NULL DEFAULT 0, baseline_noise_db REAL, baseline_source TEXT,
          n INTEGER NOT NULL DEFAULT 0, mean_features TEXT, score TEXT);
        CREATE TABLE measurements (id INTEGER PRIMARY KEY, place_id TEXT NOT NULL, lat REAL NOT NULL,
          lon REAL NOT NULL, timestamp TEXT NOT NULL, duration_s INTEGER NOT NULL, features TEXT NOT NULL,
          flicker_hz REAL, model_variant TEXT NOT NULL, device TEXT NOT NULL, received_at TEXT);
        INSERT INTO measurements (place_id, lat, lon, timestamp, duration_s, features, model_variant, device)
          VALUES ('osm:node/1', 46.5, 6.6, 't', 30, '{}', 'm', 'd');
    """)
    conn.commit()
    conn.close()
    os.environ["CIUIN_DB"] = str(old)
    db.ensure_ready()
    conn = sqlite3.connect(old)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(places)")}
    assert {"baseline_noise_night_db", "osm_tags"} <= cols
    assert conn.execute("SELECT COUNT(*) FROM measurements").fetchone()[0] == 1
    conn.close()

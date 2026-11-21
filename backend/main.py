"""Sensory Map backend: the API from Claude/todo.md section 0.

Run:  uvicorn main:app --reload --host 0.0.0.0 --port 8000
The database is created and seeded from data/processed/places_lausanne.geojson on first start.
Interactive docs at /docs.
"""

from __future__ import annotations

import json
import math
import sqlite3
from collections.abc import Iterator
from contextlib import asynccontextmanager, closing
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, status

import db
from aggregate import mean_features, score
from models import (
    Coverage,
    CoverageCell,
    FeatureVector,
    MeasuredAggregate,
    Measurement,
    MeasurementResult,
    Place,
    PlaceScore,
    Stats,
)

PROCESSED = db.REPO / "data" / "processed"
EARTH_M = 6_371_000.0


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.ensure_ready()
    yield


DESCRIPTION = """
Backend for **Ciúin**, the sensory map. Contracts: `Claude/todo.md` section 0.

* **Places**: OSM venues in Lausanne with baseline road+rail noise (sonBASE), plus aggregates from measurements.
* **Measurements**: phones post aggregates only. Any field outside the contract is rejected (privacy guard).
* **Map layers**: heatmap grid, global/Europe coverage for the 3D globe, gap stats.

Scores are in [0, 1] with **1 = calmest / most accessible**.

Try it: expand an endpoint → **Try it out** → **Execute**. The example bbox covers the Lausanne demo area.
"""

TAGS = [
    {"name": "places", "description": "Venues with accessibility tags, baseline noise and measured aggregates."},
    {"name": "measurements", "description": "On-device measurements (aggregates only)."},
    {"name": "map layers", "description": "Heatmap grid, globe coverage and stats."},
    {"name": "meta", "description": "Health check."},
]

LAUSANNE_BBOX = "6.58,46.50,6.67,46.54"
BBOX_QUERY = Query(description="minLon,minLat,maxLon,maxLat (WGS84)", examples=[LAUSANNE_BBOX])

app = FastAPI(
    title="Sensory Map API",
    version="0.1",
    description=DESCRIPTION,
    openapi_tags=TAGS,
    lifespan=lifespan,
    swagger_ui_parameters={"tryItOutEnabled": True, "displayRequestDuration": True},
)


def get_conn() -> Iterator[sqlite3.Connection]:
    with closing(db.connect()) as conn:
        yield conn


Conn = Annotated[sqlite3.Connection, Depends(get_conn)]


# ---------- helpers ----------

def parse_bbox(bbox: str) -> tuple[float, float, float, float]:
    """'minLon,minLat,maxLon,maxLat' -> tuple, validated."""
    try:
        min_lon, min_lat, max_lon, max_lat = (float(x) for x in bbox.split(","))
    except ValueError:
        raise HTTPException(422, "bbox must be minLon,minLat,maxLon,maxLat") from None
    if not (-180 <= min_lon <= max_lon <= 180 and -90 <= min_lat <= max_lat <= 90):
        raise HTTPException(422, "bbox out of range or min > max")
    return min_lon, min_lat, max_lon, max_lat


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_M * math.asin(math.sqrt(a))


def rows_in_bbox(conn: sqlite3.Connection, min_lon: float, min_lat: float, max_lon: float, max_lat: float):
    if db.has_rtree(conn):
        # The R-Tree stores 32-bit floats rounded outward, so use it as a coarse overlap filter
        # and do the exact containment test on the real coordinates.
        return conn.execute(
            """SELECT p.* FROM places p JOIN places_rtree r ON r.id = p.id
               WHERE r.max_lon >= ? AND r.min_lon <= ? AND r.max_lat >= ? AND r.min_lat <= ?
                 AND p.lon BETWEEN ? AND ? AND p.lat BETWEEN ? AND ?""",
            (min_lon, max_lon, min_lat, max_lat, min_lon, max_lon, min_lat, max_lat),
        ).fetchall()
    return conn.execute(
        "SELECT * FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        (min_lat, max_lat, min_lon, max_lon),
    ).fetchall()


def to_place(row: sqlite3.Row) -> Place:
    measured = None
    if row["n"] and row["mean_features"]:
        measured = MeasuredAggregate(n=row["n"], features=FeatureVector.model_validate_json(row["mean_features"]))
    return Place(
        place_id=row["place_id"],
        name=row["name"],
        lat=row["lat"],
        lon=row["lon"],
        category=row["category"],
        wheelchair=row["wheelchair"],
        sensory_friendly=row["sensory_friendly"],
        quiet_room=bool(row["quiet_room"]),
        baseline_noise_db=row["baseline_noise_db"],
        baseline_source=row["baseline_source"],
        measured=measured,
        score=PlaceScore.model_validate_json(row["score"]) if row["score"] else None,
    )


def recompute_place(conn: sqlite3.Connection, place_id: str) -> None:
    rows = conn.execute("SELECT features FROM measurements WHERE place_id = ?", (place_id,)).fetchall()
    place = conn.execute("SELECT wheelchair FROM places WHERE place_id = ?", (place_id,)).fetchone()
    if not rows or place is None:
        return
    mean = mean_features([FeatureVector.model_validate_json(r["features"]) for r in rows])
    conn.execute(
        "UPDATE places SET n = ?, mean_features = ?, score = ? WHERE place_id = ?",
        (len(rows), mean.model_dump_json(), score(mean, place["wheelchair"]).model_dump_json(), place_id),
    )


# ---------- endpoints ----------

@app.get("/health", tags=["meta"], summary="Health check")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/places", response_model=list[Place], tags=["places"], summary="Places in a bounding box")
def places(conn: Conn, bbox: Annotated[str, BBOX_QUERY]) -> list[Place]:
    """All places inside the box. The whole Lausanne demo area is ~2,900 places (~740 KB)."""
    return [to_place(r) for r in rows_in_bbox(conn, *parse_bbox(bbox))]


@app.get("/places/nearby", response_model=list[Place], tags=["places"], summary="Places near a point, nearest first")
def nearby(
    conn: Conn,
    lat: Annotated[float, Query(ge=-90, le=90, examples=[46.5236])],
    lon: Annotated[float, Query(ge=-180, le=180, examples=[6.6327])],
    radius: Annotated[float, Query(gt=0, le=5000, description="metres")] = 300,
) -> list[Place]:
    """Places within `radius` m, nearest first, with mean features (for the quiet-place pick).
    The example point is Place de la Riponne."""
    dlat = math.degrees(radius / EARTH_M)
    dlon = dlat / max(math.cos(math.radians(lat)), 1e-6)
    rows = rows_in_bbox(conn, lon - dlon, lat - dlat, lon + dlon, lat + dlat)
    with_d = [(distance_m(lat, lon, r["lat"], r["lon"]), r) for r in rows]
    return [to_place(r) for d, r in sorted(with_d, key=lambda x: x[0]) if d <= radius]


@app.post(
    "/measurements",
    response_model=MeasurementResult,
    status_code=status.HTTP_201_CREATED,
    tags=["measurements"],
    summary="Post one measurement",
    responses={422: {"description": "A field outside the contract, or sound_classes isn't exactly the 9 classes"}},
)
def post_measurement(conn: Conn, m: Measurement) -> MeasurementResult:
    """Store one Measurement and recompute that place's mean features and scores.

    A measurement for an unknown `place_id` is still stored (counts in `/stats` and `/coverage`),
    and `place` comes back null. **Note:** "Try it out" writes to the real database."""
    conn.execute(
        """INSERT INTO measurements (place_id, lat, lon, timestamp, duration_s, features, flicker_hz,
                                     model_variant, device) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (m.place_id, m.lat, m.lon, m.timestamp, m.duration_s, m.features.model_dump_json(), m.flicker_hz,
         m.model_variant, m.device),
    )
    recompute_place(conn, m.place_id)
    conn.commit()
    row = conn.execute("SELECT * FROM places WHERE place_id = ?", (m.place_id,)).fetchone()
    return MeasurementResult(stored=True, place=to_place(row) if row else None)


@app.get("/heatmap", tags=["map layers"], summary="Heatmap grid (GeoJSON)")
def heatmap(
    bbox: Annotated[str, BBOX_QUERY],
    dim: Annotated[str, Query(pattern="^(noise|light|flicker|crowd)$")] = "noise",
) -> dict:
    """GeoJSON grid for the heatmap layer, from data/processed/heatmap_lausanne_<dim>.geojson.
    Returns an empty FeatureCollection until that file is built (todo.md: 50 m grid task)."""
    min_lon, min_lat, max_lon, max_lat = parse_bbox(bbox)
    f = PROCESSED / f"heatmap_lausanne_{dim}.geojson"
    if not f.is_file():
        return {"type": "FeatureCollection", "features": []}
    grid = json.loads(f.read_text(encoding="utf-8"))

    def inside(feat: dict) -> bool:
        coords = feat["geometry"]["coordinates"]
        lon, lat = coords[:2] if feat["geometry"]["type"] == "Point" else coords[0][0][:2]
        return min_lon <= lon <= max_lon and min_lat <= lat <= max_lat

    return {"type": "FeatureCollection", "features": [x for x in grid["features"] if inside(x)]}


@app.get("/stats", response_model=Stats, tags=["map layers"], summary="Counts for the gap badge")
def stats(conn: Conn) -> Stats:
    """Places mapped, measurements taken, and how many places carry an OSM `sensory_friendly` tag."""
    q = lambda sql: conn.execute(sql).fetchone()[0]  # noqa: E731
    return Stats(
        places_mapped=q("SELECT COUNT(*) FROM places"),
        measurements_taken=q("SELECT COUNT(*) FROM measurements"),
        sensory_friendly_tags=q("SELECT COUNT(*) FROM places WHERE sensory_friendly IS NOT NULL"),
    )


def _cell(lat: float, lon: float, res: float) -> tuple[float, float]:
    """Centre of the res-degree cell containing the point."""
    return (math.floor(lat / res) * res + res / 2, math.floor(lon / res) * res + res / 2)


@app.get("/coverage", response_model=Coverage, tags=["map layers"], summary="Coverage grid for the 3D globe")
def coverage(
    conn: Conn,
    res_deg: Annotated[float, Query(gt=0, le=10, description="cell size in degrees: 2 = global tier, 0.5 = Europe tier")] = 2.0,
) -> Coverage:
    """Grid for the 3D globe. Uses the precomputed data/processed/coverage_<res>deg.json when it
    exists (global / Europe tiers), otherwise counts the places in the database. Live measurement
    counts are always added on top."""
    cells: dict[tuple[float, float], CoverageCell] = {}
    precomputed = PROCESSED / f"coverage_{res_deg:g}deg.json"
    if precomputed.is_file():
        for c in Coverage.model_validate_json(precomputed.read_text(encoding="utf-8")).cells:
            cells[(round(c.lat, 6), round(c.lon, 6))] = c.model_copy(update={"measurements": 0})
    else:
        for r in conn.execute("SELECT lat, lon, wheelchair, sensory_friendly, baseline_source FROM places"):
            key = tuple(round(v, 6) for v in _cell(r["lat"], r["lon"], res_deg))
            c = cells.setdefault(key, CoverageCell(lat=key[0], lon=key[1]))
            c.wheelchair += r["wheelchair"] is not None
            c.sensory_friendly += r["sensory_friendly"] is not None
            c.noise_source = c.noise_source or r["baseline_source"]
    for r in conn.execute("SELECT lat, lon FROM measurements"):
        key = tuple(round(v, 6) for v in _cell(r["lat"], r["lon"], res_deg))
        cells.setdefault(key, CoverageCell(lat=key[0], lon=key[1])).measurements += 1
    return Coverage(res_deg=res_deg, cells=sorted(cells.values(), key=lambda c: (c.lat, c.lon)))

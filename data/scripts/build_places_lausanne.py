"""Build data/processed/places_lausanne.geojson from OSM + sonBASE (todo.md section A).

Each feature's `properties` is exactly a Place (todo.md section 0), so the app's offline fallback
can decode it straight into the Kotlin `Place` class. `measured` and `score` stay null until
measurements arrive (backend aggregation fills them).

Steps
  1. Load data/raw/osm_lausanne.json (fetch_osm_lausanne.py).
  2. Drop non-venues (parking, car sharing, ATMs, ...). Parks, viewpoints, sports centres and
     playgrounds stay: they matter on a sensory map (quiet refuges / loud spots).
  3. Map OSM tags to the Place schema.
  4. Dedupe: same normalised name + category within 25 m (OSM often has a shop as node + building).
  5. baseline_noise_db = median of road+rail daytime noise (energetic sum per 10 m cell) over cells
     within 25 m; widen to 50 m if none. Cells without road data (mostly building footprints) are
     skipped; missing rail = no rail contribution.

Needs data/requirements.txt. Run from the repo root:
  data/.venv/Scripts/python data/scripts/build_places_lausanne.py   (Windows)
  data/.venv/bin/python data/scripts/build_places_lausanne.py       (macOS/Linux)
"""

from __future__ import annotations

import json
import math
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

import raster_env  # noqa: F401  (must come before rasterio; see raster_env.py)
import numpy as np
import rasterio
from rasterio.warp import transform
from rasterio.windows import from_bounds

DATA = Path(__file__).resolve().parents[1]
OSM = DATA / "raw" / "osm_lausanne.json"
ROAD = DATA / "raw" / "sonbase" / "road_day_2056.tif"
RAIL = DATA / "raw" / "sonbase" / "rail_day_2056.tif"
OUT = DATA / "processed" / "places_lausanne.geojson"

CATEGORY_KEYS = ("amenity", "shop", "leisure", "tourism")

# category values that aren't places people spend time in
DROP = {
    "amenity": {
        "atm", "bench", "bicycle_parking", "bicycle_rental", "car_rental", "car_sharing", "car_wash",
        "charging_station", "clock", "compressed_air", "drinking_water", "fountain", "fuel",
        "grave_yard", "loading_dock", "motorcycle_parking", "parcel_locker", "parking",
        "parking_entrance", "parking_space", "post_box", "recycling", "shelter", "taxi", "telephone",
        "ticket_validator", "vending_machine", "waste_basket", "waste_disposal", "water_point",
        "luggage_locker", "vehicle_inspection", "dog_toilet",
    },
    "tourism": {"artwork", "information"},
    "leisure": {"picnic_table", "outdoor_seating", "slipway", "firepit"},
    "shop": {"vacant"},
}

DEDUPE_M = 25.0
RADIUS_M = 25.0
RADIUS_FALLBACK_M = 50.0
MARGIN_M = 200.0  # read the rasters a bit beyond the bbox so edge places get full neighbourhoods


def category_of(tags: dict) -> tuple[str, str] | None:
    for k in CATEGORY_KEYS:
        if k in tags:
            return k, tags[k]
    return None


def keep(tags: dict) -> bool:
    kv = category_of(tags)
    return kv is not None and kv[1] not in DROP.get(kv[0], set())


def to_place(el: dict) -> dict:
    tags = el["tags"]
    c = el.get("center") or el
    key, value = category_of(tags)  # type: ignore[misc]
    return {
        "place_id": f"osm:{el['type']}/{el['id']}",
        "name": tags["name"].strip(),
        "lat": round(c["lat"], 6),
        "lon": round(c["lon"], 6),
        "category": value,
        "wheelchair": tags.get("wheelchair"),
        "sensory_friendly": tags.get("sensory_friendly"),
        "quiet_room": tags.get("amenity") == "quiet_room" or tags.get("quiet_room") == "yes",
        "baseline_noise_db": None,
        "baseline_source": None,
        "measured": None,
        "score": None,
        # not part of Place; stripped before writing, used for dedupe priority only
        "_n_tags": len(tags),
        "_category_key": key,
    }


def norm_name(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def metres(a: dict, b: dict) -> float:
    lat = math.radians((a["lat"] + b["lat"]) / 2)
    dx = math.radians(b["lon"] - a["lon"]) * math.cos(lat) * 6_371_000
    dy = math.radians(b["lat"] - a["lat"]) * 6_371_000
    return math.hypot(dx, dy)


def dedupe(places: list[dict]) -> tuple[list[dict], int]:
    """Keep the richest-tagged element among same name + category within DEDUPE_M."""
    groups: dict[tuple[str, str], list[dict]] = {}
    for p in places:
        groups.setdefault((norm_name(p["name"]), p["category"]), []).append(p)
    kept: list[dict] = []
    for group in groups.values():
        group.sort(key=lambda p: -p["_n_tags"])
        chosen: list[dict] = []
        for p in group:
            if all(metres(p, q) > DEDUPE_M for q in chosen):
                chosen.append(p)
            else:  # merge useful tags the duplicate has and the kept one lacks
                q = next(q for q in chosen if metres(p, q) <= DEDUPE_M)
                for f in ("wheelchair", "sensory_friendly"):
                    q[f] = q[f] or p[f]
                q["quiet_room"] = q["quiet_room"] or p["quiet_room"]
        kept.extend(chosen)
    return kept, len(places) - len(kept)


class NoiseGrid:
    """Road + rail daytime noise over the bbox, combined per 10 m cell (both grids are aligned)."""

    def __init__(self, bbox: tuple[float, float, float, float]):
        s, w, n, e = bbox
        with rasterio.open(ROAD) as road, rasterio.open(RAIL) as rail:
            if road.res != rail.res:
                raise SystemExit(f"road/rail resolution differ: {road.res} vs {rail.res}")
            self.crs = road.crs
            xs, ys = transform("EPSG:4326", road.crs, [w, e, w, e], [s, s, n, n])
            left, right = min(xs) - MARGIN_M, max(xs) + MARGIN_M
            bottom, top = min(ys) - MARGIN_M, max(ys) + MARGIN_M
            # Snap to the road grid so both windows cover the same cells
            win = from_bounds(left, bottom, right, top, road.transform).round_offsets().round_lengths()
            self.transform = road.window_transform(win)
            r = road.read(1, window=win, boundless=True, fill_value=road.nodata).astype("float64")
            l, b, rt, t = rasterio.windows.bounds(win, road.transform)
            rwin = from_bounds(l, b, rt, t, rail.transform).round_offsets().round_lengths()
            q = rail.read(1, window=rwin, boundless=True, fill_value=rail.nodata).astype("float64")
            if q.shape != r.shape:
                raise SystemExit(f"road/rail windows not aligned: {r.shape} vs {q.shape}")
            road_ok = (r != road.nodata) & np.isfinite(r) & (r > 0)
            rail_ok = (q != rail.nodata) & np.isfinite(q) & (q > 0)
        energy = np.where(road_ok, 10 ** (np.where(road_ok, r, 0) / 10), 0.0)
        energy += np.where(road_ok & rail_ok, 10 ** (np.where(rail_ok, q, 0) / 10), 0.0)
        self.db = np.where(road_ok, 10 * np.log10(np.maximum(energy, 1e-12)), np.nan)
        self.res = abs(self.transform.a)

    def median_near(self, lat: float, lon: float) -> float | None:
        (x,), (y,) = transform("EPSG:4326", self.crs, [lon], [lat])
        col, row = ~self.transform * (x, y)
        for radius in (RADIUS_M, RADIUS_FALLBACK_M):
            k = int(math.ceil(radius / self.res))
            r0, r1 = int(row) - k, int(row) + k + 1
            c0, c1 = int(col) - k, int(col) + k + 1
            if r0 < 0 or c0 < 0 or r1 > self.db.shape[0] or c1 > self.db.shape[1]:
                return None
            block = self.db[r0:r1, c0:c1]
            rr, cc = np.mgrid[r0:r1, c0:c1]
            # distance from the point to each cell centre
            d = np.hypot((cc + 0.5 - col) * self.res, (rr + 0.5 - row) * self.res)
            vals = block[(d <= radius) & np.isfinite(block)]
            if vals.size:
                return round(float(np.median(vals)), 1)
        return None


def main() -> int:
    osm = json.loads(OSM.read_text(encoding="utf-8"))
    bbox = tuple(osm["ciuin"]["bbox_south_west_north_east"])
    elements = [el for el in osm["elements"] if "name" in el.get("tags", {})]

    kept_els = [el for el in elements if keep(el["tags"])]
    dropped = Counter("=".join(category_of(el["tags"]) or ("?", "?")) for el in elements if not keep(el["tags"]))
    places, n_dupes = dedupe([to_place(el) for el in kept_els])

    grid = NoiseGrid(bbox)  # type: ignore[arg-type]
    for p in places:
        db = grid.median_near(p["lat"], p["lon"])
        p["baseline_noise_db"] = db
        p["baseline_source"] = "sonbase" if db is not None else None

    places.sort(key=lambda p: p["place_id"])
    features = []
    for p in places:
        props = {k: v for k, v in p.items() if not k.startswith("_")}
        features.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [p["lon"], p["lat"]]}, "properties": props})

    out = {
        "type": "FeatureCollection",
        "name": "places_lausanne",
        "attribution": "© OpenStreetMap contributors (ODbL); noise: sonBASE © FOEN/BAFU",
        "osm_timestamp": osm.get("osm3s", {}).get("timestamp_osm_base"),
        "features": features,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

    noise = np.array([p["baseline_noise_db"] for p in places if p["baseline_noise_db"] is not None])
    cats = Counter(p["category"] for p in places)
    print(f"OSM named elements: {len(elements):,}")
    print(f"dropped non-venues: {sum(dropped.values()):,}  (top: {', '.join(f'{k} {v}' for k, v in dropped.most_common(6))})")
    print(f"merged duplicates:  {n_dupes:,}")
    print(f"places written:     {len(places):,}")
    print(f"with wheelchair:    {sum(1 for p in places if p['wheelchair']):,}")
    print(f"with noise:         {noise.size:,} ({100 * noise.size / len(places):.1f}%)")
    if noise.size:
        p5, p50, p95 = np.percentile(noise, [5, 50, 95])
        print(f"noise dB(A):        min {noise.min():.1f}  p5 {p5:.1f}  median {p50:.1f}  p95 {p95:.1f}  max {noise.max():.1f}")
    print(f"top categories:     {', '.join(f'{c} {n}' for c, n in cats.most_common(8))}")
    print(f"wrote {OUT.relative_to(DATA.parent)} ({OUT.stat().st_size / 1e6:.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

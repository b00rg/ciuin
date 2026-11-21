"""Build data/processed/places_lausanne.geojson from OSM + sonBASE (todo.md section A).

Each feature's `properties` is exactly a Place (todo.md section 0), so the app's offline fallback
can decode it straight into the Kotlin `Place` class. `measured` and `score` stay null until
measurements arrive (backend aggregation fills them).

Steps
  1. Load data/raw/osm_lausanne.json (fetch_osm_lausanne.py).
  2. Drop non-venues (parking, car sharing, ATMs, ...). Parks, viewpoints, sports centres and
     playgrounds stay: they matter on a sensory map (quiet refuges / loud spots).
  3. Map OSM tags to the Place schema; keep the sensory/access-relevant OSM tags in `osm_tags`.
  4. Dedupe: same normalised name + category within 25 m (OSM often has a shop as node + building).
  5. baseline_noise_db (day, 06-22) and baseline_noise_night_db (22-06) = median of road+rail noise
     (energetic sum per 10 m cell) over cells within 25 m, widened to 50 m if none (noise_grid.py).
  6. prior = ESTIMATED feature vector from category group + baseline noise + capacity (priors.py),
     so the personal model can score unmeasured places. Never shown as measured data.

Needs data/requirements.txt and all four sonBASE rasters (fetch_sonbase.py). Run from the repo root:
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

import numpy as np
from noise_grid import NoiseGrid
from priors import group_of, prior_for

DATA = Path(__file__).resolve().parents[1]
OSM = DATA / "raw" / "osm_lausanne.json"
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

# OSM tags kept per place (sensory / access / "when is it busy"). Coverage in Lausanne, 21 Nov:
# opening_hours 51%, website 38%, cuisine 12%, brand 11%, outdoor_seating 10%, indoor_seating 9%,
# level 6%, capacity 3%, smoking 2%, toilets:wheelchair 1%, air_conditioning 0.5%, live_music <0.1%.
KEEP_TAGS = (
    "opening_hours", "cuisine", "brand", "outdoor_seating", "indoor_seating", "capacity", "level",
    "smoking", "air_conditioning", "live_music", "toilets:wheelchair", "wheelchair:description", "website",
)

DEDUPE_M = 25.0


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
    _, value = category_of(tags)  # type: ignore[misc]
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
        "baseline_noise_night_db": None,
        "baseline_source": None,
        "osm_tags": {k: tags[k] for k in KEEP_TAGS if k in tags},
        "prior": None,  # estimated feature vector, filled after noise sampling (priors.py)
        "measured": None,
        "score": None,
        "_n_tags": len(tags),  # dedupe priority only; stripped before writing
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
    """Keep the richest-tagged element among same name + category within DEDUPE_M; merge the rest in."""
    groups: dict[tuple[str, str], list[dict]] = {}
    for p in places:
        groups.setdefault((norm_name(p["name"]), p["category"]), []).append(p)
    kept: list[dict] = []
    for group in groups.values():
        group.sort(key=lambda p: -p["_n_tags"])
        chosen: list[dict] = []
        for p in group:
            q = next((q for q in chosen if metres(p, q) <= DEDUPE_M), None)
            if q is None:
                chosen.append(p)
                continue
            for f in ("wheelchair", "sensory_friendly"):
                q[f] = q[f] or p[f]
            q["quiet_room"] = q["quiet_room"] or p["quiet_room"]
            q["osm_tags"] = p["osm_tags"] | q["osm_tags"]  # kept element's values win
        kept.extend(chosen)
    return kept, len(places) - len(kept)


def pct(values: list[float]) -> str:
    a = np.array(values)
    p5, p50, p95 = np.percentile(a, [5, 50, 95])
    return f"min {a.min():.1f}  p5 {p5:.1f}  median {p50:.1f}  p95 {p95:.1f}  max {a.max():.1f}"


def main() -> int:
    osm = json.loads(OSM.read_text(encoding="utf-8"))
    bbox = tuple(osm["ciuin"]["bbox_south_west_north_east"])
    elements = [el for el in osm["elements"] if "name" in el.get("tags", {})]

    kept_els = [el for el in elements if keep(el["tags"])]
    dropped = Counter("=".join(category_of(el["tags"]) or ("?", "?")) for el in elements if not keep(el["tags"]))
    places, n_dupes = dedupe([to_place(el) for el in kept_els])

    day, night = NoiseGrid(bbox, "day"), NoiseGrid(bbox, "night")  # type: ignore[arg-type]
    for p in places:
        p["baseline_noise_db"] = day.median_near(p["lat"], p["lon"])
        p["baseline_noise_night_db"] = night.median_near(p["lat"], p["lon"])
        p["baseline_source"] = "sonbase" if p["baseline_noise_db"] is not None else None
        p["prior"] = prior_for(p)

    places.sort(key=lambda p: p["place_id"])
    features = [
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [p["lon"], p["lat"]]},
            "properties": {k: v for k, v in p.items() if not k.startswith("_")},
        }
        for p in places
    ]
    out = {
        "type": "FeatureCollection",
        "name": "places_lausanne",
        "attribution": "© OpenStreetMap contributors (ODbL); noise: sonBASE © FOEN/BAFU",
        "osm_timestamp": osm.get("osm3s", {}).get("timestamp_osm_base"),
        "features": features,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

    d = [p["baseline_noise_db"] for p in places if p["baseline_noise_db"] is not None]
    nn = [p["baseline_noise_night_db"] for p in places if p["baseline_noise_night_db"] is not None]
    drop = [p["baseline_noise_db"] - p["baseline_noise_night_db"] for p in places
            if p["baseline_noise_db"] is not None and p["baseline_noise_night_db"] is not None]
    tag_cov = Counter(k for p in places for k in p["osm_tags"])
    print(f"OSM named elements: {len(elements):,}")
    print(f"dropped non-venues: {sum(dropped.values()):,}  (top: {', '.join(f'{k} {v}' for k, v in dropped.most_common(6))})")
    print(f"merged duplicates:  {n_dupes:,}")
    print(f"places written:     {len(places):,}  (with wheelchair: {sum(1 for p in places if p['wheelchair']):,})")
    print(f"noise day   ({len(d):,}): {pct(d)}")
    print(f"noise night ({len(nn):,}): {pct(nn)}")
    print(f"day - night:        median {np.median(drop):.1f} dB quieter at night")
    print(f"osm_tags kept:      {', '.join(f'{k} {v}' for k, v in tag_cov.most_common())}")
    groups = Counter(group_of(p["category"]) for p in places)
    print(f"prior groups:       {', '.join(f'{g} {n}' for g, n in groups.most_common())}")
    pd = [p["prior"]["db_mean"] for p in places]
    print(f"prior dB:           {pct(pd)}")
    print(f"wrote {OUT.relative_to(DATA.parent)} ({OUT.stat().st_size / 1e6:.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

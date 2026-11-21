"""Build the globe coverage grids (todo.md §0 "Scope and coverage") from OSM tag statistics.

Outputs (committed, small):
  data/processed/coverage_2deg.json   global tier, 2° cells   -> GET /coverage?res_deg=2
  data/processed/coverage_1deg.json   Europe tier, 1° cells   -> GET /coverage?res_deg=1

Method (no planet download needed):
  1. WHERE: taginfo's distribution maps (planet, 360x180 px = 1° cells) mark the cells where a key is
     used on nodes or ways. Presence only, no counts.
  2. HOW MANY: Geofabrik runs a taginfo instance per country/region; key/stats gives the real
     per-country count of objects with the key.
  3. Spread each country's count evenly over its 1° cells that have the key (countries rasterised from
     Geofabrik's region polygons), then sum into the output cells. `wheelchair` counts per cell are
     therefore ESTIMATES (country total spread over its tagged cells); per-country totals are exact.
  4. `sensory_friendly` is rare (~150 objects worldwide), so it comes EXACT from one Overpass query
     with coordinates instead.
  Switzerland's cells get noise_source "sonbase". EEA (EU) noise coverage isn't included yet.
  `measurements` is 0 here; the backend adds live counts per cell.

The Europe tier is 1°, not 0.5°: the presence maps are 1° so anything finer would be fake detail.

Network results are cached in data/raw/coverage_cache/ (gitignored); delete it to refresh.
Needs data/requirements.txt (rasterio for PNG reading + polygon rasterising). Run from the repo root:
  data/.venv/Scripts/python data/scripts/build_coverage.py   (Windows)
  data/.venv/bin/python data/scripts/build_coverage.py       (macOS/Linux)
"""

from __future__ import annotations

import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import raster_env  # noqa: F401  (must come before rasterio; see raster_env.py)
import numpy as np
from rasterio.features import rasterize
from rasterio.io import MemoryFile
from rasterio.transform import from_origin

USER_AGENT = "ciuin-hackathon/0.1 (sensory map coverage grid)"
DATA = Path(__file__).resolve().parents[1]
CACHE = DATA / "raw" / "coverage_cache"
OUT_GLOBAL = DATA / "processed" / "coverage_2deg.json"
OUT_EUROPE = DATA / "processed" / "coverage_1deg.json"
PLANET_TAGINFO = "https://taginfo.openstreetmap.org"
GEOFABRIK_INDEX = "https://download.geofabrik.de/index-v1.json"
KEYS = ("wheelchair", "sensory_friendly")
EUROPE = (34.0, -25.0, 72.0, 45.0)  # south, west, north, east
GRID = from_origin(-180, 90, 1, 1)  # 1° cells, row 0 = 90..89°N, col 0 = 180..179°W


def fetch(url: str, binary: bool = False, retries: int = 3):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
                return data if binary else json.loads(data)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == retries - 1:
                raise
        except Exception:  # noqa: BLE001 - transient network error
            if attempt == retries - 1:
                raise
        time.sleep(5 * (attempt + 1))
    return None


def cached(name: str, producer):
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / name
    if f.exists():
        return f.read_bytes() if f.suffix == ".png" else json.loads(f.read_text(encoding="utf-8"))
    value = producer()
    if f.suffix == ".png":
        f.write_bytes(value)
    else:
        f.write_text(json.dumps(value), encoding="utf-8")
    return value


def presence(key: str) -> np.ndarray:
    """360x180 bool: 1° cells where `key` is used on nodes or ways (planet taginfo distribution maps)."""
    grid = np.zeros((180, 360), dtype=bool)
    for kind in ("nodes", "ways"):
        png = cached(f"dist_{key}_{kind}.png", lambda: fetch(
            f"{PLANET_TAGINFO}/api/4/key/distribution/{kind}?key={urllib.parse.quote(key)}", binary=True))
        with MemoryFile(png) as m, m.open() as ds:
            a = ds.read(1)
        if a.shape != (180, 360):
            raise SystemExit(f"unexpected distribution map size {a.shape} for {key}/{kind}")
        grid |= a > 0
    return grid


def countries(index: dict) -> list[dict]:
    """Country-level Geofabrik regions: children of continents with an ISO code, plus Russia."""
    feats = index["features"]
    continents = {f["properties"]["id"] for f in feats if not f["properties"].get("parent")}
    out = []
    for f in feats:
        p = f["properties"]
        is_country = p.get("parent") in continents and "iso3166-1:alpha2" in p
        if (is_country or p["id"] == "russia") and p.get("urls", {}).get("taginfo") and f.get("geometry"):
            out.append(f)
    return out


def overpass(query: str) -> dict:
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request("https://overpass-api.de/api/interpreter", data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=240) as r:
        return json.load(r)


def region_count(taginfo_url: str, key: str) -> int:
    d = fetch(f"{taginfo_url.rstrip('/')}/api/4/key/stats?key={urllib.parse.quote(key)}")
    if not d:
        return 0
    return next((x["count"] for x in d.get("data", []) if x["type"] == "all"), 0)


def main() -> int:
    index = cached("geofabrik_index.json", lambda: fetch(GEOFABRIK_INDEX))
    regions = countries(index)
    print(f"{len(regions)} country-level regions", file=sys.stderr)

    # region id per 1° cell (0 = none); later regions overwrite earlier ones at shared borders
    shapes = [(r["geometry"], i + 1) for i, r in enumerate(regions)]
    region_grid = rasterize(shapes, out_shape=(180, 360), transform=GRID, fill=0, all_touched=True, dtype="int32")

    # wheelchair: real per-country totals from Geofabrik taginfo (~6 s per call; 3 at a time, cached)
    counts_cache = cached("region_counts.json", lambda: {})
    todo = [r for r in regions if "wheelchair" not in counts_cache.get(r["properties"]["id"], {})]
    lock = threading.Lock()

    def count_one(r: dict) -> None:
        n = region_count(r["properties"]["urls"]["taginfo"], "wheelchair")
        with lock:
            counts_cache.setdefault(r["properties"]["id"], {})["wheelchair"] = n
            (CACHE / "region_counts.json").write_text(json.dumps(counts_cache), encoding="utf-8")
            done = len(regions) - sum(1 for x in regions if "wheelchair" not in counts_cache.get(x["properties"]["id"], {}))
            if done % 20 == 0:
                print(f"  wheelchair counts {done}/{len(regions)}", file=sys.stderr)

    with ThreadPoolExecutor(max_workers=3) as pool:  # volunteer-run servers: stay gentle
        list(pool.map(count_one, todo))

    # sensory_friendly: exact, from one Overpass query (rare tag)
    sf = cached("sensory_friendly_overpass.json", lambda: overpass('[out:json][timeout:180];nwr["sensory_friendly"];out center;'))
    if sf.get("remark"):
        raise SystemExit(f"Overpass returned partial results: {sf['remark']}")
    sf_grid = np.zeros((180, 360))
    for el in sf["elements"]:
        c = el.get("center") or el
        sf_grid[min(int(90 - c["lat"]), 179), min(int(c["lon"] + 180), 359)] += 1

    est = {"wheelchair": np.zeros((180, 360)), "sensory_friendly": sf_grid}
    unplaced = {"wheelchair": 0}
    for i, r in enumerate(regions, 1):
        rid = r["properties"]["id"]
        in_region = region_grid == i
        for k in ("wheelchair",):
            total = counts_cache[rid][k]
            if not total:
                continue
            cells = in_region & presence(k)
            if not cells.any():
                cells = in_region  # tiny/odd countries: spread over the whole country
            if not cells.any():  # region smaller than a pixel after overlaps
                unplaced[k] += total
                continue
            est[k][cells] += total / cells.sum()

    swiss = next((i + 1 for i, r in enumerate(regions) if r["properties"]["id"] == "switzerland"), None)
    sonbase = region_grid == swiss if swiss else np.zeros((180, 360), dtype=bool)

    def cells_for(res: int, bounds: tuple[float, float, float, float] | None) -> list[dict]:
        out = []
        for r0 in range(0, 180, res):
            for c0 in range(0, 360, res):
                lat_c = 90 - r0 - res / 2
                lon_c = -180 + c0 + res / 2
                if bounds and not (bounds[0] <= lat_c <= bounds[2] and bounds[1] <= lon_c <= bounds[3]):
                    continue
                w = int(round(est["wheelchair"][r0:r0 + res, c0:c0 + res].sum()))
                s = int(round(est["sensory_friendly"][r0:r0 + res, c0:c0 + res].sum()))
                if w == 0 and s == 0:
                    continue
                cell = {"lat": lat_c, "lon": lon_c, "wheelchair": w, "sensory_friendly": s,
                        "noise_source": "sonbase" if sonbase[r0:r0 + res, c0:c0 + res].any() else None,
                        "measurements": 0}
                out.append(cell)
        return out

    note = ("wheelchair per cell is an estimate: each country's real OSM total (Geofabrik taginfo) spread "
            "evenly over its 1-degree cells where the key is used (planet taginfo distribution maps). "
            "sensory_friendly per cell is exact (Overpass).")
    for path, res, bounds, tier in ((OUT_GLOBAL, 2, None, "global"), (OUT_EUROPE, 1, EUROPE, "europe")):
        cells = cells_for(res, bounds)
        path.write_text(json.dumps({"res_deg": float(res), "tier": tier, "note": note,
                                    "attribution": "© OpenStreetMap contributors (ODbL); counts via taginfo",
                                    "cells": cells}, separators=(",", ":")) + "\n", encoding="utf-8")
        print(f"{tier}: {len(cells):,} cells at {res}°  "
              f"wheelchair {sum(c['wheelchair'] for c in cells):,}  "
              f"sensory_friendly {sum(c['sensory_friendly'] for c in cells):,}  -> {path.relative_to(DATA.parent)}")

    wtot = sum(counts_cache[r["properties"]["id"]]["wheelchair"] for r in regions)
    print(f"sum of country wheelchair totals: {wtot:,} (unplaced {unplaced['wheelchair']:,}); "
          f"sensory_friendly objects: {len(sf['elements'])}")
    for rid in ("switzerland", "france", "germany", "united-kingdom", "us", "japan"):
        if rid in counts_cache:
            print(f"  {rid:15} {counts_cache[rid]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

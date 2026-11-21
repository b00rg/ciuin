"""Sanity-check the sonBASE rasters: metadata + noise at known Lausanne spots.

Reads data/raw/sonbase/*.tif if downloaded, otherwise the remote cloud-optimised GeoTIFF
(only the needed bytes). Needs data/requirements.txt (rasterio).
  data/.venv/Scripts/python data/scripts/check_sonbase.py   (Windows)
  data/.venv/bin/python data/scripts/check_sonbase.py       (macOS/Linux)
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import raster_env  # noqa: F401  (must come before rasterio; see raster_env.py)
import numpy as np
import rasterio
from rasterio.warp import transform
from rasterio.windows import from_bounds

RAW = Path(__file__).resolve().parents[1] / "raw" / "sonbase"
REMOTE = {
    "road_day": "https://data.geo.admin.ch/ch.bafu.laerm-strassenlaerm_tag/laerm-strassenlaerm_tag/laerm-strassenlaerm_tag_2056.tif",
    "rail_day": "https://data.geo.admin.ch/ch.bafu.laerm-bahnlaerm_tag/laerm-bahnlaerm_tag/laerm-bahnlaerm_tag_2056.tif",
}

# (name, lat, lon, what we'd expect). Park coordinates are OSM centres from osm_lausanne.json;
# a hand-typed point a few metres onto a road reads like the road (10 m cells).
SPOTS = [
    ("Gare de Lausanne (forecourt)", 46.5170, 6.6293, "loud: road + rail"),
    ("Avenue de la Gare (street)", 46.5185, 6.6310, "loud road"),
    ("Esplanade de Montbenon", 46.52032, 6.62674, "moderate"),
    ("Parc de Mon-Repos (OSM centre)", 46.51915, 6.64306, "quieter"),
    ("Parc de l'Hermitage (OSM centre)", 46.52867, 6.63728, "quiet"),
    ("Sauvabelin forest", 46.5355, 6.6385, "quiet"),
]

# Lausanne demo bbox: south, west, north, east (same as BBox.LAUSANNE)
BBOX = (46.50, 6.58, 46.54, 6.67)


def source(name: str) -> str:
    local = RAW / f"{name}_2056.tif"
    return str(local) if local.is_file() else f"/vsicurl/{REMOTE[name]}"


def combine_db(levels: list[float | None]) -> float | None:
    """Energetic sum of dB levels (road + rail); None/absent sources are skipped."""
    present = [l for l in levels if l is not None]
    if not present:
        return None
    return 10 * math.log10(sum(10 ** (l / 10) for l in present))


def main() -> int:
    lats = [s[1] for s in SPOTS]
    lons = [s[2] for s in SPOTS]
    sampled: dict[str, list[float | None]] = {}

    for name in REMOTE:
        src_path = source(name)
        with rasterio.open(src_path) as ds:
            print(f"== {name}  ({'local' if not src_path.startswith('/vsicurl') else 'remote'})")
            print(f"   CRS EPSG:{ds.crs.to_epsg()}  res {ds.res}  size {ds.width}x{ds.height}  dtype {ds.dtypes[0]}  nodata {ds.nodata}")

            xs, ys = transform("EPSG:4326", ds.crs, lons, lats)
            vals = []
            for v in ds.sample(zip(xs, ys)):
                x = float(v[0])
                vals.append(None if (ds.nodata is not None and x == ds.nodata) or not math.isfinite(x) or x <= 0 else x)
            sampled[name] = vals

            # Value distribution over the Lausanne bbox
            s, w, n, e = BBOX
            (x0, x1), (y0, y1) = transform("EPSG:4326", ds.crs, [w, e], [s, n])
            win = from_bounds(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), ds.transform)
            arr = ds.read(1, window=win, masked=True).astype("float64")
            valid = arr.compressed()
            valid = valid[np.isfinite(valid) & (valid > 0)]
            pct = 100 * valid.size / arr.size if arr.size else 0
            if valid.size:
                p = np.percentile(valid, [5, 50, 95])
                print(
                    f"   Lausanne bbox: {pct:.0f}% of cells have a value; "
                    f"min {valid.min():.1f}  p5 {p[0]:.1f}  median {p[1]:.1f}  p95 {p[2]:.1f}  max {valid.max():.1f}"
                )
            else:
                print("   Lausanne bbox: no valid values!")

    print("\nSpot checks (dB(A), day):")
    print(f"   {'place':32} {'road':>6} {'rail':>6} {'combined':>9}  expected")
    for i, (place, _, _, expect) in enumerate(SPOTS):
        road, rail = sampled["road_day"][i], sampled["rail_day"][i]
        comb = combine_db([road, rail])
        fmt = lambda v: f"{v:6.1f}" if v is not None else "     -"  # noqa: E731
        print(f"   {place:32} {fmt(road)} {fmt(rail)} {fmt(comb):>9}  {expect}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

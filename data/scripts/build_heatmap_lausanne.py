"""Build the 50 m noise heatmap grid for Lausanne -> data/processed/heatmap_lausanne_noise.geojson.

Each 50 m cell (aligned to the LV95 grid) = median of its 25 sonBASE 10 m cells (road+rail combined,
see noise_grid.py), for day (06-22) and night (22-06). Cells with fewer than MIN_VALID valid 10 m cells
(mostly building blocks) are left out. Features are cell-centre Points; the app draws 50 m squares.

The backend's GET /heatmap?dim=noise&period=day|night serves this file (backend/main.py).

Run from the repo root:
  data/.venv/Scripts/python data/scripts/build_heatmap_lausanne.py   (Windows)
  data/.venv/bin/python data/scripts/build_heatmap_lausanne.py       (macOS/Linux)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from noise_grid import NoiseGrid
from rasterio.warp import transform

DATA = Path(__file__).resolve().parents[1]
OUT = DATA / "processed" / "heatmap_lausanne_noise.geojson"
BBOX = (46.50, 6.55, 46.54, 6.67)  # south, west, north, east; same as BBox.LAUSANNE
CELL_M = 50
MIN_VALID = 5  # of 25 sub-cells


def block_median(db: np.ndarray, k: int) -> tuple[np.ndarray, np.ndarray]:
    """Median and valid-count over non-overlapping k x k blocks (NaN-aware)."""
    h, w = (db.shape[0] // k) * k, (db.shape[1] // k) * k
    blocks = db[:h, :w].reshape(h // k, k, w // k, k).swapaxes(1, 2).reshape(h // k, w // k, k * k)
    valid = np.isfinite(blocks).sum(axis=2)
    with np.errstate(all="ignore"):
        import warnings

        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)  # all-NaN blocks -> NaN, filtered below
            med = np.nanmedian(blocks, axis=2)
    return med, valid


def main() -> int:
    day, night = NoiseGrid(BBOX, "day"), NoiseGrid(BBOX, "night")
    if day.db.shape != night.db.shape or day.transform != night.transform:
        raise SystemExit("day and night grids don't line up")
    k = int(round(CELL_M / day.res))

    # Align 50 m blocks to the LV95 grid: skip leading 10 m rows/cols until the origin is a multiple of 50 m
    x0, y0 = day.transform.c, day.transform.f
    c_off = int(round(((-x0) % CELL_M) / day.res)) % k
    r_off = int(round(((y0) % CELL_M) / day.res)) % k
    d_med, d_n = block_median(day.db[r_off:, c_off:], k)
    n_med, _ = block_median(night.db[r_off:, c_off:], k)
    # road and rail separately (Christine's road-vs-rail breakdown); rail is NaN where there's none
    sources = {
        (period, src): block_median(getattr(g, src)[r_off:, c_off:], k)[0]
        for period, g in (("day", day), ("night", night)) for src in ("road", "rail")
    }

    def val(a: np.ndarray, r: int, c: int) -> float | None:
        return round(float(a[r, c]), 1) if np.isfinite(a[r, c]) else None

    rows, cols = np.nonzero((d_n >= MIN_VALID) & np.isfinite(d_med))
    # cell centres in LV95 -> WGS84
    xs = x0 + (c_off + cols * k + k / 2) * day.res
    ys = y0 - (r_off + rows * k + k / 2) * day.res
    lons, lats = transform(day.crs, "EPSG:4326", xs.tolist(), ys.tolist())

    s, w, n, e = BBOX
    features = []
    for lon, lat, r, c in zip(lons, lats, rows, cols):
        if not (w <= lon <= e and s <= lat <= n):
            continue
        nv = n_med[r, c]
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [round(lon, 6), round(lat, 6)]},
            "properties": {
                "noise_day_db": round(float(d_med[r, c]), 1),
                "noise_night_db": round(float(nv), 1) if np.isfinite(nv) else None,
                **{f"{src}_{period}_db": val(sources[(period, src)], r, c)
                   for period in ("day", "night") for src in ("road", "rail")},
            },
        })

    out = {
        "type": "FeatureCollection",
        "name": "heatmap_lausanne_noise",
        "cell_m": CELL_M,
        "res_m": CELL_M,
        "crs_note": "cells are 50 m squares on the Swiss LV95 grid; points are cell centres in WGS84",
        "attribution": "noise: sonBASE © FOEN/BAFU",
        "features": features,
    }
    OUT.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8")

    dv = np.array([f["properties"]["noise_day_db"] for f in features])
    nv = np.array([f["properties"]["noise_night_db"] for f in features if f["properties"]["noise_night_db"] is not None])
    print(f"cells: {len(features):,} ({CELL_M} m)")
    print(f"day   dB: p5 {np.percentile(dv, 5):.1f}  median {np.median(dv):.1f}  p95 {np.percentile(dv, 95):.1f}")
    print(f"night dB: p5 {np.percentile(nv, 5):.1f}  median {np.median(nv):.1f}  p95 {np.percentile(nv, 95):.1f}")
    print(f"wrote {OUT.relative_to(DATA.parent)} ({OUT.stat().st_size / 1e6:.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""sonBASE road + rail noise over a bbox, combined per 10 m cell. Shared by the place and heatmap builders.

Road and rail rasters are both 10 m LV95 grids offset by whole cells, so a window read over the same
LV95 bounds lines up cell for cell. Combination is energetic: 10*log10(10^(road/10) + 10^(rail/10)).
Cells without road data (mostly building footprints) are NaN; missing rail = no rail contribution.
"""

from __future__ import annotations

import math
from pathlib import Path

import raster_env  # noqa: F401  (must come before rasterio; see raster_env.py)
import numpy as np
import rasterio
from rasterio.warp import transform
from rasterio.windows import bounds as window_bounds
from rasterio.windows import from_bounds

SONBASE = Path(__file__).resolve().parents[1] / "raw" / "sonbase"
PERIODS = ("day", "night")


class NoiseGrid:
    def __init__(self, bbox: tuple[float, float, float, float], period: str = "day", margin_m: float = 200.0):
        """bbox = (south, west, north, east) in WGS84. period = "day" (06-22) or "night" (22-06)."""
        if period not in PERIODS:
            raise ValueError(f"period must be one of {PERIODS}")
        road_path, rail_path = SONBASE / f"road_{period}_2056.tif", SONBASE / f"rail_{period}_2056.tif"
        for p in (road_path, rail_path):
            if not p.is_file():
                raise SystemExit(f"missing {p}; run data/scripts/fetch_sonbase.py")
        s, w, n, e = bbox
        with rasterio.open(road_path) as road, rasterio.open(rail_path) as rail:
            if road.res != rail.res:
                raise SystemExit(f"road/rail resolution differ: {road.res} vs {rail.res}")
            self.crs = road.crs
            xs, ys = transform("EPSG:4326", road.crs, [w, e, w, e], [s, s, n, n])
            win = from_bounds(
                min(xs) - margin_m, min(ys) - margin_m, max(xs) + margin_m, max(ys) + margin_m, road.transform
            ).round_offsets().round_lengths()
            self.transform = road.window_transform(win)
            r = road.read(1, window=win, boundless=True, fill_value=road.nodata).astype("float64")
            rwin = from_bounds(*window_bounds(win, road.transform), rail.transform).round_offsets().round_lengths()
            q = rail.read(1, window=rwin, boundless=True, fill_value=rail.nodata).astype("float64")
            if q.shape != r.shape:
                raise SystemExit(f"road/rail windows not aligned: {r.shape} vs {q.shape}")
            road_ok = (r != road.nodata) & np.isfinite(r) & (r > 0)
            rail_ok = (q != rail.nodata) & np.isfinite(q) & (q > 0)
        energy = np.where(road_ok, 10 ** (np.where(road_ok, r, 0) / 10), 0.0)
        energy += np.where(road_ok & rail_ok, 10 ** (np.where(rail_ok, q, 0) / 10), 0.0)
        self.db = np.where(road_ok, 10 * np.log10(np.maximum(energy, 1e-12)), np.nan)
        self.res = abs(self.transform.a)

    def to_pixel(self, lat: float, lon: float) -> tuple[float, float]:
        (x,), (y,) = transform("EPSG:4326", self.crs, [lon], [lat])
        col, row = ~self.transform * (x, y)
        return row, col

    def median_near(self, lat: float, lon: float, radii_m: tuple[float, ...] = (25.0, 50.0)) -> float | None:
        """Median dB of valid cells whose centres are within the first radius that has any."""
        row, col = self.to_pixel(lat, lon)
        for radius in radii_m:
            k = int(math.ceil(radius / self.res))
            r0, r1, c0, c1 = int(row) - k, int(row) + k + 1, int(col) - k, int(col) + k + 1
            if r0 < 0 or c0 < 0 or r1 > self.db.shape[0] or c1 > self.db.shape[1]:
                return None
            block = self.db[r0:r1, c0:c1]
            rr, cc = np.mgrid[r0:r1, c0:c1]
            d = np.hypot((cc + 0.5 - col) * self.res, (rr + 0.5 - row) * self.res)
            vals = block[(d <= radius) & np.isfinite(block)]
            if vals.size:
                return round(float(np.median(vals)), 1)
        return None

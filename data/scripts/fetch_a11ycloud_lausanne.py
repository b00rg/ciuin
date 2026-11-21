"""Download accessibility.cloud places for the Lausanne demo bbox (todo.md section A).

Uses the tile query (x/y/z), which the API docs recommend for bulk use; their lat/lon/accuracy
search is meant for end-user lookups, not exports. Pages through each tile 1000 at a time.

Needs an app token (free for non-commercial use): sign up at accessibility.cloud, create an
organization, add an app, copy its token. Never commit it. Provide it as an env var:
  PowerShell:  $env:ACCESSIBILITY_CLOUD_TOKEN = "..."
  bash:        export ACCESSIBILITY_CLOUD_TOKEN=...

Stdlib only. Run from the repo root:
  python data/scripts/fetch_a11ycloud_lausanne.py            # download
  python data/scripts/fetch_a11ycloud_lausanne.py --dry-run  # just list the tiles, no token needed
Writes data/raw/a11ycloud_lausanne.json (gitignored).
"""

from __future__ import annotations

import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

USER_AGENT = "ciuin-hackathon/0.1 (sensory map accessibility download)"
BASE = "https://accessibility-cloud-v2.freetls.fastly.net/place-infos.json"
OUT = Path(__file__).resolve().parents[1] / "raw" / "a11ycloud_lausanne.json"
TOKEN_ENV = "ACCESSIBILITY_CLOUD_TOKEN"

# Same bbox as BBox.LAUSANNE in shared/ and the other scripts: south, west, north, east
LAUSANNE_BBOX = (46.50, 6.58, 46.54, 6.67)
ZOOM = 14  # ~1.7 km tiles at this latitude -> a handful of tiles for the bbox
PAGE = 1000  # API maximum


def lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[int, int]:
    """Standard slippy-map tile containing the point."""
    n = 2**z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_r)) / math.pi) / 2.0 * n)
    return x, y


def tiles_for_bbox(bbox: tuple[float, float, float, float], z: int) -> list[tuple[int, int]]:
    s, w, n, e = bbox
    x0, y0 = lonlat_to_tile(w, n, z)  # top-left: tile y grows southwards
    x1, y1 = lonlat_to_tile(e, s, z)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def get_page(token: str, x: int, y: int, z: int, skip: int) -> dict:
    params = {
        "appToken": token,
        "x": x,
        "y": y,
        "z": z,
        "limit": PAGE,
        "skip": skip,
        "includeRelated": "source,source.license",  # per-source licences, needed for REUSED.md
    }
    req = urllib.request.Request(f"{BASE}?{urllib.parse.urlencode(params)}", headers={"User-Agent": USER_AGENT})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (401, 402):
                raise SystemExit(
                    f"HTTP {e.code}: token missing, invalid or lacking access. Check ${TOKEN_ENV}."
                ) from None
            if attempt == 2:
                raise
        except Exception:  # noqa: BLE001 - network hiccup, retry
            if attempt == 2:
                raise
        time.sleep(5 * (attempt + 1))
    raise RuntimeError("unreachable")


def in_bbox(feature: dict, bbox: tuple[float, float, float, float]) -> bool:
    s, w, n, e = bbox
    try:
        lon, lat = feature["geometry"]["coordinates"][:2]
    except (KeyError, TypeError, ValueError):
        return False
    return s <= lat <= n and w <= lon <= e


def main() -> int:
    tiles = tiles_for_bbox(LAUSANNE_BBOX, ZOOM)
    if "--dry-run" in sys.argv:
        print(f"{len(tiles)} tiles at z={ZOOM}: {tiles}")
        return 0

    token = os.environ.get(TOKEN_ENV, "").strip()
    if not token:
        print(f"Set ${TOKEN_ENV} first (see the docstring at the top of this script).", file=sys.stderr)
        return 2

    features: dict[str, dict] = {}  # keyed by _id: tiles can overlap at edges
    related: dict[str, dict] = {}
    for i, (x, y) in enumerate(tiles, 1):
        skip = 0
        while True:
            page = get_page(token, x, y, ZOOM, skip)
            got = page.get("features", [])
            for f in got:
                fid = f.get("_id") or f.get("properties", {}).get("_id") or json.dumps(f["geometry"])
                features[fid] = f
            for kind, docs in (page.get("related") or {}).items():
                related.setdefault(kind, {}).update(docs if isinstance(docs, dict) else {})
            if len(got) < PAGE:
                break
            skip += PAGE
            time.sleep(0.5)  # be polite to a free service
        print(f"  tile {i}/{len(tiles)} ({x},{y}): {len(features):,} places so far", file=sys.stderr)
        time.sleep(0.5)

    # Tiles overhang the bbox; keep only places inside it so counts line up with the OSM file.
    inside = [f for f in features.values() if in_bbox(f, LAUSANNE_BBOX)]
    out = {
        "type": "FeatureCollection",
        "features": inside,
        "related": related,
        "ciuin": {"bbox_south_west_north_east": list(LAUSANNE_BBOX), "zoom": ZOOM, "tiles": tiles},
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")

    wheel = Counter(
        str(f.get("properties", {}).get("accessibility", {}).get("accessibleWith", {}).get("wheelchair"))
        for f in inside
    )
    sources = Counter(f.get("properties", {}).get("sourceId", "?") for f in inside)
    print(f"{len(inside):,} places in bbox ({len(features):,} across all tiles)")
    print(f"accessibleWith.wheelchair: {dict(wheel)}")
    print(f"sources: {len(sources)} ({', '.join(f'{s} {n}' for s, n in sources.most_common(5))})")
    licenses = related.get("licenses", {})
    print(f"licences: {sorted({l.get('shortName') or l.get('name', '?') for l in licenses.values()}) or 'none returned'}")
    print(f"wrote {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

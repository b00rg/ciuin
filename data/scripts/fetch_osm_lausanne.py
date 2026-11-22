"""Download named amenity/shop/leisure/tourism POIs for the Lausanne demo bbox (todo.md section A).

Raw Overpass JSON -> data/raw/osm_lausanne.json (gitignored; rerun this script to rebuild).
Ways and relations come with a `center` point so every element has one coordinate.

Stdlib only. Run from the repo root:
  python data/scripts/fetch_osm_lausanne.py
"""

from __future__ import annotations

import json
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

USER_AGENT = "ciuin-hackathon/0.1 (sensory map POI download)"
OVERPASS = "https://overpass-api.de/api/interpreter"
OUT = Path(__file__).resolve().parents[1] / "raw" / "osm_lausanne.json"

# Same bbox as BBox.LAUSANNE in shared/ and count_tags.py: south, west, north, east
LAUSANNE_BBOX = (46.50, 6.58, 46.54, 6.67)
CATEGORY_KEYS = ("amenity", "shop", "leisure", "tourism")


def build_query(bbox: tuple[float, float, float, float]) -> str:
    s, w, n, e = bbox
    keys = "|".join(CATEGORY_KEYS)
    return (
        "[out:json][timeout:180];\n"
        f'nwr[~"^({keys})$"~"."]["name"]({s},{w},{n},{e});\n'
        "out center tags;"
    )


def fetch(query: str, retries: int = 3) -> dict:
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - Overpass is often busy; retry with backoff
            if attempt == retries - 1:
                raise
            wait = 15 * (attempt + 1)
            print(f"  retry in {wait}s after: {e}", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def category(tags: dict) -> str:
    return next((f"{k}={tags[k]}" for k in CATEGORY_KEYS if k in tags), "?")


def main() -> int:
    query = build_query(LAUSANNE_BBOX)
    print("querying Overpass ...", file=sys.stderr)
    res = fetch(query)
    elements = res.get("elements", [])
    if res.get("remark"):  # Overpass reports timeouts / memory limits here, with partial results
        print(f"Overpass remark (results may be partial): {res['remark']}", file=sys.stderr)
        return 1

    missing_coords = [el for el in elements if "lat" not in el and "center" not in el]
    res["ciuin"] = {"bbox_south_west_north_east": list(LAUSANNE_BBOX), "query": query}

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(res, ensure_ascii=False), encoding="utf-8")

    types = Counter(el["type"] for el in elements)
    cats = Counter(category(el.get("tags", {})) for el in elements)
    with_wheelchair = sum(1 for el in elements if "wheelchair" in el.get("tags", {}))
    print(f"{len(elements):,} named POIs  ({dict(types)})")
    print(f"OSM data as of {res.get('osm3s', {}).get('timestamp_osm_base')}")
    print(f"with wheelchair tag: {with_wheelchair:,} ({100 * with_wheelchair / max(len(elements), 1):.1f}%)")
    print(f"without coordinates: {len(missing_coords)}")
    print("top categories: " + ", ".join(f"{c} {n}" for c, n in cats.most_common(12)))
    print(f"wrote {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

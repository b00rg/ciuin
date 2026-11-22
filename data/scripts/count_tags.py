"""Count accessibility / sensory OSM tags per data tier (todo.md section A, due 14:00).

Tiers:
  world        taginfo.openstreetmap.org          (whole planet)
  europe       taginfo.geofabrik.de/europe        (Geofabrik Europe extract)
  switzerland  taginfo.geofabrik.de/europe:switzerland
  lausanne     Overpass API, demo bbox            (also counts named POIs, to get a coverage %)

Stdlib only. Run from the repo root:
  python data/scripts/count_tags.py
Writes data/processed/tag_counts.json and prints a summary to paste in chat.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

USER_AGENT = "ciuin-hackathon/0.1 (sensory map tag counts)"
OUT = Path(__file__).resolve().parents[1] / "processed" / "tag_counts.json"

TAGINFO = {
    "world": "https://taginfo.openstreetmap.org",
    "europe": "https://taginfo.geofabrik.de/europe",
    "switzerland": "https://taginfo.geofabrik.de/europe:switzerland",
}

OVERPASS = "https://overpass-api.de/api/interpreter"
# Same bbox as BBox.LAUSANNE in shared/: south, west, north, east
LAUSANNE_BBOX = (46.50, 6.55, 46.54, 6.67)

WHEELCHAIR_VALUES = ("yes", "limited", "no", "designated")


def get_json(url: str, data: bytes | None = None, retries: int = 3) -> dict:
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - report and retry any network error
            if attempt == retries - 1:
                raise
            wait = 5 * (attempt + 1)
            print(f"  retry in {wait}s after: {e}", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def taginfo(base: str, path: str, **params) -> dict:
    return get_json(f"{base}/api/4/{path}?{urllib.parse.urlencode(params)}")


def total_count(stats: dict) -> int:
    return next((d["count"] for d in stats["data"] if d["type"] == "all"), 0)


def count_taginfo_tier(base: str) -> dict:
    wheelchair_total = total_count(taginfo(base, "key/stats", key="wheelchair"))
    values = taginfo(base, "key/values", key="wheelchair", sortname="count", sortorder="desc", rp=50)
    by_value = {v["value"]: v["count"] for v in values["data"]}

    # Headline = objects with the main sensory_friendly key. Subkeys (sensory_friendly:*) are listed
    # separately and NOT summed: one object often carries several of them, so a sum double-counts.
    sensory_keys = taginfo(base, "keys/all", query="sensory_friendly", sortname="count_all", sortorder="desc", rp=50)
    sensory = {k["key"]: k["count_all"] for k in sensory_keys["data"] if k["key"].startswith("sensory_friendly")}
    sensory_values = taginfo(base, "key/values", key="sensory_friendly", sortname="count", sortorder="desc", rp=20)

    quiet_room = total_count(taginfo(base, "tag/stats", key="amenity", value="quiet_room"))
    data_until = values.get("data_until")

    return {
        "source": base,
        "data_until": data_until,
        "wheelchair": {
            "total": wheelchair_total,
            **{v: by_value.get(v, 0) for v in WHEELCHAIR_VALUES},
        },
        "sensory_friendly": {
            "total": sensory.get("sensory_friendly", 0),
            "by_value": {v["value"]: v["count"] for v in sensory_values["data"]},
            "subkeys_not_summed": {k: c for k, c in sensory.items() if k != "sensory_friendly"},
        },
        "amenity=quiet_room": quiet_room,
    }


def count_lausanne() -> dict:
    s, w, n, e = LAUSANNE_BBOX
    bbox = f"{s},{w},{n},{e}"
    poi = f'nwr[~"^(amenity|shop|leisure|tourism)$"~"."]["name"]({bbox})'
    statements = {
        "named_pois": f"{poi};",
        "named_pois_with_wheelchair": f'{poi.replace("({bbox})", "")}["wheelchair"]({bbox});',
        "wheelchair": f'nwr["wheelchair"]({bbox});',
        **{f"wheelchair={v}": f'nwr["wheelchair"="{v}"]({bbox});' for v in WHEELCHAIR_VALUES},
        "sensory_friendly": f'nwr["sensory_friendly"]({bbox});',
        "amenity=quiet_room": f'nwr["amenity"="quiet_room"]({bbox});',
    }
    query = "[out:json][timeout:120];\n" + "\n".join(f"{q}\nout count;" for q in statements.values())
    res = get_json(OVERPASS, data=urllib.parse.urlencode({"data": query}).encode())
    counts = [int(el["tags"]["total"]) for el in res["elements"] if el.get("type") == "count"]
    if len(counts) != len(statements):
        raise RuntimeError(f"Overpass returned {len(counts)} counts, expected {len(statements)}")
    c = dict(zip(statements, counts))

    return {
        "source": OVERPASS,
        "bbox_south_west_north_east": list(LAUSANNE_BBOX),
        "data_until": res.get("osm3s", {}).get("timestamp_osm_base"),
        "named_pois": c["named_pois"],
        "named_pois_with_wheelchair": c["named_pois_with_wheelchair"],
        "named_pois_wheelchair_pct": round(100 * c["named_pois_with_wheelchair"] / c["named_pois"], 1)
        if c["named_pois"]
        else None,
        "wheelchair": {"total": c["wheelchair"], **{v: c[f"wheelchair={v}"] for v in WHEELCHAIR_VALUES}},
        "sensory_friendly": {"total": c["sensory_friendly"]},
        "amenity=quiet_room": c["amenity=quiet_room"],
    }


def summary(result: dict) -> str:
    rows = [("Tier", "wheelchair", "yes/limited/no", "sensory_friendly", "quiet_room")]
    for tier in ("world", "europe", "switzerland", "lausanne"):
        t = result["tiers"].get(tier)
        if not t:
            continue
        w = t["wheelchair"]
        rows.append(
            (
                tier,
                f'{w["total"]:,}',
                f'{w["yes"]:,}/{w["limited"]:,}/{w["no"]:,}',
                f'{t["sensory_friendly"]["total"]:,}',
                f'{t["amenity=quiet_room"]:,}',
            )
        )
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    lines = ["  ".join(c.ljust(widths[i]) for i, c in enumerate(r)) for r in rows]
    lz = result["tiers"].get("lausanne")
    if lz:
        lines.append(
            f'\nLausanne: {lz["named_pois"]:,} named POIs, {lz["named_pois_with_wheelchair"]:,} '
            f'({lz["named_pois_wheelchair_pct"]}%) have a wheelchair tag'
        )
    return "\n".join(lines)


def main() -> int:
    result = {"generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "tiers": {}, "errors": {}}
    for tier, base in TAGINFO.items():
        print(f"counting {tier} ...", file=sys.stderr)
        try:
            result["tiers"][tier] = count_taginfo_tier(base)
        except Exception as e:  # noqa: BLE001 - keep going, report at the end
            result["errors"][tier] = str(e)
    print("counting lausanne (Overpass) ...", file=sys.stderr)
    try:
        result["tiers"]["lausanne"] = count_lausanne()
    except Exception as e:  # noqa: BLE001
        result["errors"]["lausanne"] = str(e)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(summary(result))
    if result["errors"]:
        print(f"\nERRORS: {result['errors']}", file=sys.stderr)
    print(f"\nwrote {OUT}", file=sys.stderr)
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())

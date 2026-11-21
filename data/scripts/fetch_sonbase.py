"""Download sonBASE daytime road + railway noise for all of Switzerland (todo.md section A).

Source: FOEN/BAFU via data.geo.admin.ch STAC. Each layer is one cloud-optimised GeoTIFF in
LV95 (EPSG:2056), values = Lr day in dB(A). ~553 MB (road) + ~174 MB (rail).
Downloads resume if interrupted (HTTP Range). Files go to data/raw/sonbase/ (gitignored).

Stdlib only. Run from the repo root:
  python data/scripts/fetch_sonbase.py
Then check the files with: python data/scripts/check_sonbase.py
"""

from __future__ import annotations

import sys
import time
import urllib.request
from pathlib import Path

USER_AGENT = "ciuin-hackathon/0.1 (sensory map noise download)"
OUT_DIR = Path(__file__).resolve().parents[1] / "raw" / "sonbase"

LAYERS = {
    "road_day": "https://data.geo.admin.ch/ch.bafu.laerm-strassenlaerm_tag/laerm-strassenlaerm_tag/laerm-strassenlaerm_tag_2056.tif",
    "rail_day": "https://data.geo.admin.ch/ch.bafu.laerm-bahnlaerm_tag/laerm-bahnlaerm_tag/laerm-bahnlaerm_tag_2056.tif",
}
CHUNK = 1 << 20  # 1 MiB


def remote_size(url: str) -> int:
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers["Content-Length"])


def download(url: str, dest: Path) -> None:
    total = remote_size(url)
    part = dest.with_suffix(dest.suffix + ".part")
    if dest.exists() and dest.stat().st_size == total:
        print(f"{dest.name}: already complete ({total / 1e6:.0f} MB)")
        return

    for attempt in range(5):
        have = part.stat().st_size if part.exists() else 0
        headers = {"User-Agent": USER_AGENT}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=120) as r, part.open("ab" if have else "wb") as f:
                if have and r.status != 206:  # server ignored Range: start over
                    f.truncate(0)
                    have = 0
                last = time.monotonic()
                while chunk := r.read(CHUNK):
                    f.write(chunk)
                    have += len(chunk)
                    if time.monotonic() - last > 5:
                        print(f"  {dest.name}: {have / 1e6:.0f}/{total / 1e6:.0f} MB", file=sys.stderr)
                        last = time.monotonic()
            if have == total:
                part.replace(dest)
                print(f"{dest.name}: done ({total / 1e6:.0f} MB)")
                return
            print(f"  {dest.name}: short read ({have}/{total}), resuming", file=sys.stderr)
        except Exception as e:  # noqa: BLE001 - network error: resume from what we have
            print(f"  {dest.name}: {e}; resuming in {5 * (attempt + 1)}s", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    raise SystemExit(f"{dest.name}: failed after 5 attempts")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, url in LAYERS.items():
        download(url, OUT_DIR / f"{name}_2056.tif")
    return 0


if __name__ == "__main__":
    sys.exit(main())

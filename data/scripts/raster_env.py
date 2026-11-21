"""Import this BEFORE rasterio in any script that reads rasters:  import raster_env  # noqa: F401

Windows + non-ASCII repo path (e.g. "Ciúin") breaks rasterio's remote reads: rasterio points
GDAL's curl at certifi's CA bundle inside the venv, curl can't open that non-ASCII path, and
rasterio then crashes decoding GDAL's error message. Fix: point GDAL at an ASCII copy of the
bundle. Must run before rasterio is imported, because rasterio sets the option on import and
can't even read the bad value back. No-op when the path is already ASCII (macOS/Linux, most repos).
Local files are unaffected either way.
"""

from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path


def _fix_ca_bundle() -> None:
    try:
        import certifi
    except ImportError:
        return
    ca = certifi.where()
    if ca.isascii():
        return
    tmp = Path(tempfile.gettempdir()) / "ciuin-cacert.pem"
    if not str(tmp).isascii():
        return  # no ASCII location available; remote reads may fail, local files still work
    if not tmp.exists() or tmp.stat().st_size != os.path.getsize(ca):
        shutil.copyfile(ca, tmp)
    os.environ["GDAL_CURL_CA_BUNDLE"] = str(tmp)
    os.environ["CURL_CA_BUNDLE"] = str(tmp)


_fix_ca_bundle()

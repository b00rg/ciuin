"""Download the B1 audio datasets into data/raw/audio/ (gitignored).

    python3 ml/download_datasets.py                 # all three
    python3 ml/download_datasets.py esc50 us8k      # a subset
    python3 ml/download_datasets.py --keep-archives # keep .tar.gz/.zip after extracting

Stdlib only. Safe to re-run: finished datasets are skipped, and the
UrbanSound8K download resumes where it stopped.

Disk: ~18 GB once extracted. Archives are deleted after extraction and
MUSAN (11 GB) is extracted while it streams, so peak use stays near the
final size instead of ~36 GB.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

RAW = Path(__file__).resolve().parent.parent / "data" / "raw" / "audio"
CHUNK = 1 << 20

DATASETS = {
    "esc50": {
        "url": "https://github.com/karolpiczak/ESC-50/archive/refs/heads/master.zip",
        "archive": "ESC-50-master.zip",
        "md5": None,
        "dest": "ESC-50",
        "expect_wavs": 2000,
    },
    "us8k": {
        "url": "https://zenodo.org/api/records/1203745/files/UrbanSound8K.tar.gz/content",
        "archive": "UrbanSound8K.tar.gz",
        "size": 6_023_741_708,
        "md5": "9aa69802bbf37fb986f71ec1483a196e",
        "dest": "UrbanSound8K",
        "expect_wavs": 8732,
    },
    "musan": {
        "url": "https://www.openslr.org/resources/17/musan.tar.gz",
        "archive": None,  # streamed straight into tar, never written to disk
        "size": 11_086_114_085,
        "md5": None,
        "dest": "musan",
        "expect_wavs": 2016,  # music 660 + noise 930 + speech 426
    },
}


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


class Progress:
    def __init__(self, name: str, total: int | None, start: int = 0):
        self.name, self.total, self.done = name, total, start
        self.t0, self.start, self.last = time.time(), start, 0.0

    def update(self, n: int) -> None:
        self.done += n
        now = time.time()
        if now - self.last < 2 and (self.total is None or self.done < self.total):
            return
        self.last = now
        rate = (self.done - self.start) / max(now - self.t0, 1e-6)
        pct = f" {100 * self.done / self.total:5.1f}%" if self.total else ""
        print(f"  {self.name}:{pct} {human(self.done)} @ {human(rate)}/s", flush=True)


def open_url(url: str, offset: int = 0):
    req = urllib.request.Request(url, headers={"User-Agent": "ciuin-dataset-downloader"})
    if offset:
        req.add_header("Range", f"bytes={offset}-")
    return urllib.request.urlopen(req, timeout=60)


def download_file(name: str, url: str, path: Path, size: int | None, retries: int = 20) -> None:
    """Download with resume (HTTP Range) and retries."""
    for attempt in range(1, retries + 1):
        have = path.stat().st_size if path.exists() else 0
        if size and have == size:
            return
        try:
            with open_url(url, have) as r:
                if have and r.status != 206:  # server ignored Range: start over
                    have = 0
                total = size or (int(r.headers["Content-Length"]) + have if r.headers.get("Content-Length") else None)
                prog = Progress(name, total, have)
                with open(path, "ab" if have else "wb") as f:
                    while chunk := r.read(CHUNK):
                        f.write(chunk)
                        prog.update(len(chunk))
            if size is None or path.stat().st_size == size:
                return
        except OSError as e:
            print(f"  {name}: attempt {attempt} failed ({e}); retrying", flush=True)
            time.sleep(min(30, 2 * attempt))
    raise RuntimeError(f"{name}: download did not complete after {retries} attempts")


def md5sum(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        while chunk := f.read(CHUNK * 8):
            h.update(chunk)
    return h.hexdigest()


class CountingReader:
    """File-like wrapper that reports progress while tarfile streams from HTTP."""

    def __init__(self, raw, prog: Progress):
        self.raw, self.prog = raw, prog

    def read(self, n: int = -1) -> bytes:
        b = self.raw.read(n)
        self.prog.update(len(b))
        return b


def count_wavs(d: Path) -> int:
    return sum(1 for _ in d.rglob("*.wav"))


def fetch(key: str, keep_archives: bool) -> None:
    cfg = DATASETS[key]
    dest = RAW / cfg["dest"]
    marker = dest / ".complete"
    if marker.exists():
        print(f"[{key}] already done ({count_wavs(dest)} wavs), skipping")
        return

    print(f"[{key}] downloading {cfg['url']}")
    if cfg["archive"] is None:
        # Stream-extract: tar reads the gzip stream as it arrives. Not resumable,
        # so retry from scratch on failure.
        tmp = RAW / f".{cfg['dest']}.partial"
        for attempt in range(1, 4):
            shutil.rmtree(tmp, ignore_errors=True)
            tmp.mkdir(parents=True)
            try:
                with open_url(cfg["url"]) as r:
                    reader = CountingReader(r, Progress(key, cfg.get("size")))
                    with tarfile.open(fileobj=reader, mode="r|gz") as tf:
                        tf.extractall(tmp, filter="data")
                break
            except (OSError, tarfile.TarError, EOFError) as e:
                print(f"[{key}] attempt {attempt} failed ({e})", flush=True)
                if attempt == 3:
                    raise
        inner = tmp / cfg["dest"]
        shutil.rmtree(dest, ignore_errors=True)
        (inner if inner.is_dir() else tmp).rename(dest)
        shutil.rmtree(tmp, ignore_errors=True)
    else:
        archive = RAW / cfg["archive"]
        download_file(key, cfg["url"], archive, cfg.get("size"))
        if cfg["md5"]:
            print(f"[{key}] verifying md5")
            got = md5sum(archive)
            if got != cfg["md5"]:
                archive.unlink()
                raise RuntimeError(f"{key}: md5 mismatch (got {got}); archive deleted, re-run to retry")
        print(f"[{key}] extracting")
        if archive.suffix == ".zip":
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(RAW)
            extracted = RAW / archive.stem  # ESC-50-master
            shutil.rmtree(dest, ignore_errors=True)
            extracted.rename(dest)
        else:
            with tarfile.open(archive, "r:gz") as tf:
                tf.extractall(RAW, filter="data")
        if not keep_archives:
            archive.unlink()

    n = count_wavs(dest)
    status = "OK" if n == cfg["expect_wavs"] else f"WARNING: expected {cfg['expect_wavs']}"
    print(f"[{key}] {n} wavs in {dest} ({status})")
    marker.write_text(f"{n}\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("datasets", nargs="*", choices=list(DATASETS), default=["esc50", "us8k", "musan"])
    ap.add_argument("--keep-archives", action="store_true")
    args = ap.parse_args()

    RAW.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(RAW).free
    print(f"free disk: {human(free)}")
    if free < 20 * 1024**3:
        print("warning: under 20 GB free; all three datasets need ~18 GB", file=sys.stderr)

    for key in args.datasets:
        fetch(key, args.keep_archives)
    return 0


if __name__ == "__main__":
    sys.exit(main())

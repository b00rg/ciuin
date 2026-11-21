"""Download the B1 audio datasets into data/raw/audio/ (gitignored).

    python3 ml/download_datasets.py                 # everything
    python3 ml/download_datasets.py esc50 us8k      # a subset
    (esc50, us8k, musan, fsd50k, demand)
    python3 ml/download_datasets.py --keep-archives # keep .tar.gz/.zip after extracting

Stdlib only. Safe to re-run: finished datasets are skipped, and the
UrbanSound8K download resumes where it stopped.

Disk: ~21 GB once extracted. Archives are deleted after extraction and
MUSAN (11 GB) is extracted while it streams, so peak use stays near the
final size. FSD50K is ~30 GB in full, but only the ~2,900 alarm / siren /
crowd clips (~2 GB) are pulled out of it with HTTP range requests.

  ESC-50        2,000 clips   base training data
  UrbanSound8K  8,732 clips   base training data
  MUSAN         speech, music, noise
  FSD50K        alarm, siren, crowd/chatter subset (alarm_beep, speech_crowd)
  DEMAND        cafeteria, restaurant, station, square recordings (speech_crowd)
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import http.client
import json
import shutil
import struct
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
import zlib
from concurrent.futures import ThreadPoolExecutor, as_completed
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

# Extra data for the two weakest classes (alarm_beep, speech_crowd).
EXTRA = ("fsd50k", "demand")

# FSD50K (Zenodo 4060432): ~30 GB as split zips, but we only need the clips
# carrying these labels (~2,900 clips, ~2 GB). They're pulled straight out of
# the split zip with HTTP range requests. Labels are propagated up the AudioSet
# ontology, so "Alarm" already covers telephone, doorbell, ringtone, buzzer,
# smoke alarm and siren clips. labels.yaml decides which class each goes to.
FSD50K_RECORD = "https://zenodo.org/api/records/4060432"
FSD50K_WANTED = {"Alarm", "Siren", "Crowd", "Chatter", "Conversation"}
FSD50K_PARTS = {
    "dev": [*(f"FSD50K.dev_audio.z0{i}" for i in range(1, 6)), "FSD50K.dev_audio.zip"],
    "eval": ["FSD50K.eval_audio.z01", "FSD50K.eval_audio.zip"],
}

# DEMAND (Zenodo 1227121): 5-minute, 16-channel real recordings of busy
# public places. 16 kHz versions, ~110 MB each.
DEMAND_RECORD = "https://zenodo.org/api/records/1227121"
DEMAND_ENVS = ("PCAFETER", "PRESTO", "PSTATION", "SPSQUARE")  # cafeteria, restaurant, station hall, town square


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


def zenodo_files(record: str) -> dict[str, int]:
    with open_url(record) as r:
        return {f["key"]: f["size"] for f in json.load(r)["files"]}


def http_range(url: str, start: int, end: int, retries: int = 10) -> bytes:
    """Bytes [start, end] inclusive, backing off on rate limits."""
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ciuin-dataset-downloader",
                                                       "Range": f"bytes={start}-{end}"})
            with urllib.request.urlopen(req, timeout=120) as r:
                if r.status != 206:
                    raise RuntimeError(f"server ignored Range request ({r.status})")
                data = r.read()
            if len(data) == end - start + 1:
                return data
            raise OSError(f"short read {len(data)}/{end - start + 1}")
        except urllib.error.HTTPError as e:
            wait = int(e.headers.get("Retry-After") or 30) if e.code == 429 else min(60, 3 * attempt)
            if attempt == retries:
                raise
            time.sleep(wait)
        except (OSError, http.client.HTTPException):
            if attempt == retries:
                raise
            time.sleep(min(60, 3 * attempt))
    raise AssertionError("unreachable")


class SplitZip:
    """Random access to one file inside a remote multi-part (zip64) archive."""

    def __init__(self, record: str, parts: list[str], sizes: dict[str, int]):
        self.urls = [f"{record}/files/{p}/content" for p in parts]
        self.sizes = [sizes[p] for p in parts]
        self.starts = [sum(self.sizes[:i]) for i in range(len(parts))]

    def read(self, disk: int, offset: int, n: int) -> bytes:
        out, pos, remaining = b"", self.starts[disk] + offset, n
        while remaining > 0:
            d = max(i for i, s in enumerate(self.starts) if s <= pos)
            local = pos - self.starts[d]
            take = min(remaining, self.sizes[d] - local)
            out += http_range(self.urls[d], local, local + take - 1)
            pos, remaining = pos + take, remaining - take
        return out

    def entries(self) -> dict[str, tuple]:
        """name -> (disk, local_header_offset, compressed_size, size, method, crc, name_len)."""
        last = len(self.urls) - 1
        tail_len = min(65536, self.sizes[last])
        tail = self.read(last, self.sizes[last] - tail_len, tail_len)
        k = tail.rfind(b"PK\x06\x06")  # zip64 end of central directory
        if k < 0:
            raise RuntimeError("no zip64 end-of-central-directory record found")
        *_, cd_disk, _, _, cd_size, cd_off = struct.unpack("<IQHHIIQQQQ", tail[k:k + 56])
        cd = self.read(cd_disk, cd_off, cd_size)

        out, i = {}, 0
        while i < len(cd) and cd[i:i + 4] == b"PK\x01\x02":
            (_, _, _, _, method, _, _, crc, csize, usize, nlen, elen, clen,
             disk, _, _, lhoff) = struct.unpack("<IHHHHHHIIIHHHHHII", cd[i:i + 46])
            name = cd[i + 46:i + 46 + nlen].decode()
            extra = cd[i + 46 + nlen:i + 46 + nlen + elen]
            j = 0
            while j + 4 <= len(extra):  # zip64 extended info holds the real values
                hid, hlen = struct.unpack("<HH", extra[j:j + 4])
                if hid == 1:
                    vals, p = extra[j + 4:j + 4 + hlen], 0
                    if usize == 0xFFFFFFFF:
                        usize, p = struct.unpack("<Q", vals[p:p + 8])[0], p + 8
                    if csize == 0xFFFFFFFF:
                        csize, p = struct.unpack("<Q", vals[p:p + 8])[0], p + 8
                    if lhoff == 0xFFFFFFFF:
                        lhoff, p = struct.unpack("<Q", vals[p:p + 8])[0], p + 8
                    if disk == 0xFFFF:
                        disk = struct.unpack("<I", vals[p:p + 4])[0]
                j += 4 + hlen
            out[name] = (disk, lhoff, csize, usize, method, crc, nlen)
            i += 46 + nlen + elen + clen
        return out

    def extract(self, entry: tuple, name_len: int) -> bytes:
        """One range request per file: local header + data (+ slack for extra fields)."""
        disk, lhoff, csize, usize, method, crc = entry
        want = 30 + name_len + 128 + csize
        buf = self.read(disk, lhoff, min(want, self.starts[-1] + self.sizes[-1] - self.starts[disk] - lhoff))
        if buf[:4] != b"PK\x03\x04":
            raise RuntimeError("bad local header")
        nlen, elen = struct.unpack("<HH", buf[26:30])
        start = 30 + nlen + elen
        data = buf[start:start + csize]
        if len(data) < csize:  # extra field longer than the slack
            data += self.read(disk, lhoff + start + len(data), csize - len(data))
        if method == 8:
            data = zlib.decompress(data, -15)
        elif method != 0:
            raise RuntimeError(f"unsupported zip compression method {method}")
        if len(data) != usize or zlib.crc32(data) != crc:
            raise RuntimeError("CRC/size mismatch")
        return data


def fetch_fsd50k(workers: int = 8) -> None:
    dest = RAW / "FSD50K"
    marker = dest / ".complete"
    if marker.exists():
        print(f"[fsd50k] already done ({count_wavs(dest)} wavs), skipping")
        return
    dest.mkdir(parents=True, exist_ok=True)
    sizes = zenodo_files(FSD50K_RECORD)

    # Ground truth (labels + dev train/val split) and metadata (per-clip licences).
    for small in ("FSD50K.ground_truth.zip", "FSD50K.metadata.zip"):
        zpath = dest / small
        download_file(f"fsd50k {small}", f"{FSD50K_RECORD}/files/{small}/content", zpath, sizes[small])
        with zipfile.ZipFile(zpath) as zf:
            zf.extractall(dest)
        zpath.unlink()

    total = 0
    for split, parts in FSD50K_PARTS.items():
        with open(dest / "FSD50K.ground_truth" / f"{split}.csv", newline="") as f:
            wanted = {row["fname"] for row in csv.DictReader(f)
                      if FSD50K_WANTED & set(row["labels"].split(","))}
        out_dir = dest / split
        out_dir.mkdir(exist_ok=True)
        todo = sorted(w for w in wanted if not (out_dir / f"{w}.wav").exists())
        print(f"[fsd50k] {split}: {len(wanted)} matching clips, {len(todo)} to fetch")
        if not todo:
            total += len(wanted)
            continue

        archive = SplitZip(FSD50K_RECORD, parts, sizes)
        entries = {Path(name).stem: e for name, e in archive.entries().items() if name.endswith(".wav")}

        def one(fname: str) -> int:
            *entry, nlen = entries[fname]
            data = archive.extract(tuple(entry), nlen)
            tmp = out_dir / f".{fname}.wav.part"
            tmp.write_bytes(data)
            tmp.rename(out_dir / f"{fname}.wav")
            return len(data)

        prog = Progress(f"fsd50k {split}", None)
        failed = []
        with ThreadPoolExecutor(workers) as pool:
            futures = {pool.submit(one, f): f for f in todo}
            for n_done, fut in enumerate(as_completed(futures), 1):
                try:
                    prog.update(fut.result())
                except Exception as e:  # keep going; re-run picks up the rest
                    failed.append(futures[fut])
                    print(f"  fsd50k: {futures[fut]} failed ({e})", flush=True)
                if n_done % 200 == 0:
                    print(f"  fsd50k {split}: {n_done}/{len(todo)} clips", flush=True)
        if failed:
            raise RuntimeError(f"fsd50k: {len(failed)} clips failed; re-run to retry them")
        total += len(wanted)

    n = count_wavs(dest)
    status = "OK" if n == total else f"WARNING: expected {total}"
    print(f"[fsd50k] {n} wavs in {dest} ({status})")
    marker.write_text(f"{n}\n")


def fetch_demand() -> None:
    dest = RAW / "DEMAND"
    marker = dest / ".complete"
    if marker.exists():
        print(f"[demand] already done ({count_wavs(dest)} wavs), skipping")
        return
    dest.mkdir(parents=True, exist_ok=True)
    sizes = zenodo_files(DEMAND_RECORD)
    for env in DEMAND_ENVS:
        if (dest / env).is_dir() and count_wavs(dest / env) == 16:
            continue
        name = f"{env}_16k.zip"
        zpath = dest / name
        print(f"[demand] downloading {name}")
        download_file(f"demand {env}", f"{DEMAND_RECORD}/files/{name}/content", zpath, sizes[name])
        with zipfile.ZipFile(zpath) as zf:
            zf.extractall(dest)
        zpath.unlink()
    n = count_wavs(dest)
    expect = 16 * len(DEMAND_ENVS)
    print(f"[demand] {n} wavs in {dest} ({'OK' if n == expect else f'WARNING: expected {expect}'})")
    marker.write_text(f"{n}\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("datasets", nargs="*", choices=[*DATASETS, *EXTRA], default=[*DATASETS, *EXTRA])
    ap.add_argument("--keep-archives", action="store_true")
    args = ap.parse_args()

    RAW.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(RAW).free
    print(f"free disk: {human(free)}")
    if free < 20 * 1024**3:
        print("warning: under 20 GB free; everything needs ~21 GB", file=sys.stderr)

    for key in args.datasets:
        if key == "fsd50k":
            fetch_fsd50k()
        elif key == "demand":
            fetch_demand()
        else:
            fetch(key, args.keep_archives)
    return 0


if __name__ == "__main__":
    sys.exit(main())

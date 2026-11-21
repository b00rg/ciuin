"""Build the audio clip index: one SQLite row per clip, with its class and split.

    python3 ml/build_index.py          # -> data/processed/audio_index.sqlite
    python3 ml/build_index.py --test-only  # -> data/processed/audio_test_index.sqlite

Every training, evaluation and weighting script reads clips from this index,
so all of them use the same train / val / test split. Audio stays as files
in data/raw/audio/; the index stores paths relative to that folder. Re-run
it after more datasets finish downloading: it is rebuilt from scratch, and
the split never changes for a given clip.
Use --test-only with the original metadata when only held-out audio is present.

Splits (never split within a dataset fold):
  ESC-50        folds 1-3 train, fold 4 val, fold 5 test
  UrbanSound8K  folds 1-7 train, fold 8 val, folds 9-10 test
  FSD50K        dev split=train / dev split=val / eval
  MUSAN         by file, 80 / 10 / 10 from a hash of the file name (stable)
  DEMAND        by time within each recording: first 80% / next 10% / last 10%;
                channel 1 only (the 16 channels are near-duplicates)

`use` is 'classify' for clips with a class, 'noise' for MUSAN noise
(background augmentation only, no class).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path

import soundfile as sf
import yaml

ML = Path(__file__).resolve().parent
REPO = ML.parent
AUDIO_RAW = REPO / "data" / "raw" / "audio"
INDEX = REPO / "data" / "processed" / "audio_index.sqlite"
LABELS = ML / "export" / "labels.yaml"

ESC_SPLIT = {1: "train", 2: "train", 3: "train", 4: "val", 5: "test"}
US8K_SPLIT = {**{f: "train" for f in range(1, 8)}, 8: "val", 9: "test", 10: "test"}
FSD_SPLIT = {"train": "train", "val": "val"}  # dev.csv `split`; eval.csv -> test
DEMAND_CUTS = (0.8, 0.9)  # train | val | test boundaries as fractions of the recording
MUSAN_PCT = (80, 90)      # hash bucket < 80 train, < 90 val, else test

LICENCES = {
    "esc50": "CC BY-NC 3.0",
    "us8k": "CC BY-NC 4.0",
    "musan": "CC BY 4.0",
    "demand": "CC BY 4.0",
    "fsd50k": "see clip",  # per-clip licence from FSD50K metadata when available
}

SCHEMA = """
CREATE TABLE clips (
    id            INTEGER PRIMARY KEY,
    dataset       TEXT NOT NULL,     -- esc50 | us8k | musan | fsd50k | demand
    path          TEXT NOT NULL,     -- relative to data/raw/audio/
    start_s       REAL NOT NULL,     -- segment within the file (0 = start)
    end_s         REAL NOT NULL,     -- segment end; = duration for whole files
    source_label  TEXT NOT NULL,     -- the dataset's own label(s), comma-separated
    class         TEXT,              -- one of our 9 classes; NULL for noise-only clips
    class_index   INTEGER,           -- model output index (SoundClass order)
    split         TEXT NOT NULL CHECK (split IN ('train', 'val', 'test')),
    fold          TEXT,              -- dataset fold, where the dataset has folds
    use           TEXT NOT NULL CHECK (use IN ('classify', 'noise')),
    sample_rate   INTEGER NOT NULL,
    channels      INTEGER NOT NULL,
    licence       TEXT NOT NULL,
    UNIQUE (path, start_s)
);
CREATE INDEX clips_split_class ON clips (split, class);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE VIEW class_counts AS
    SELECT split, class, COUNT(*) AS clips, ROUND(SUM(end_s - start_s) / 60, 1) AS minutes
    FROM clips WHERE use = 'classify' GROUP BY split, class ORDER BY split, class_index;
"""


def info(path: Path) -> tuple[float, int, int]:
    i = sf.info(str(path))
    return i.frames / i.samplerate, i.samplerate, i.channels


class Builder:
    def __init__(self, labels: dict, test_only: bool = False):
        self.classes = labels["classes"]
        self.mapping = labels["mapping"]
        self.test_only = test_only
        self.rows: list[tuple] = []
        self.missing: list[str] = []

    def add(self, dataset, path: Path, source_label, cls, split, fold=None,
            start=0.0, end=None, licence=None):
        if self.test_only and split != "test":
            return
        dur, sr, ch = info(path)
        self.rows.append((
            dataset, path.relative_to(AUDIO_RAW).as_posix(), start, dur if end is None else end,
            source_label, cls, None if cls is None else self.classes.index(cls), split,
            None if fold is None else str(fold), "classify" if cls else "noise", sr, ch,
            licence or LICENCES[dataset],
        ))

    def esc50(self):
        root = AUDIO_RAW / "ESC-50"
        meta = root / "meta" / "esc50.csv"
        if not meta.exists():
            return self.missing.append("ESC-50")
        with open(meta, newline="") as f:
            for r in csv.DictReader(f):
                fold = int(r["fold"])
                self.add("esc50", root / "audio" / r["filename"], r["category"],
                         self.mapping["esc50"][r["category"]], ESC_SPLIT[fold], fold)

    def us8k(self):
        root = AUDIO_RAW / "UrbanSound8K"
        meta = root / "metadata" / "UrbanSound8K.csv"
        if not meta.exists():
            return self.missing.append("UrbanSound8K")
        with open(meta, newline="") as f:
            for r in csv.DictReader(f):
                fold = int(r["fold"])
                self.add("us8k", root / "audio" / f"fold{fold}" / r["slice_file_name"], r["class"],
                         self.mapping["us8k"][r["class"]], US8K_SPLIT[fold], fold)

    def musan(self):
        root = AUDIO_RAW / "musan"
        if not root.is_dir():
            return self.missing.append("MUSAN")
        for folder, cls in self.mapping["musan"].items():
            for wav in sorted((root / folder).rglob("*.wav")):
                bucket = int(hashlib.sha1(wav.name.encode()).hexdigest(), 16) % 100
                split = "train" if bucket < MUSAN_PCT[0] else "val" if bucket < MUSAN_PCT[1] else "test"
                self.add("musan", wav, folder, cls, split, fold=wav.parent.name)

    def fsd50k(self):
        root = AUDIO_RAW / "FSD50K"
        gt = root / "FSD50K.ground_truth"
        if not (root / ".complete").exists():
            return self.missing.append("FSD50K")
        licences = {}
        for split_name in ("dev", "eval"):
            info_json = root / "FSD50K.metadata" / f"{split_name}_clips_info_FSD50K.json"
            if info_json.exists():
                for fname, d in json.loads(info_json.read_text()).items():
                    licences[(split_name, fname)] = d.get("license", "unknown")
        priority = list(self.mapping["fsd50k"].items())  # yaml order = priority
        for split_name in ("dev", "eval"):
            with open(gt / f"{split_name}.csv", newline="") as f:
                for r in csv.DictReader(f):
                    labels = r["labels"].split(",")
                    cls = next((dst for src, dst in priority if src in labels), None)
                    wav = root / split_name / f"{r['fname']}.wav"
                    if cls is None or not wav.exists():
                        continue
                    split = FSD_SPLIT[r["split"]] if split_name == "dev" else "test"
                    self.add("fsd50k", wav, r["labels"], cls, split, fold=split_name,
                             licence=licences.get((split_name, r["fname"]), "unknown"))

    def demand(self):
        root = AUDIO_RAW / "DEMAND"
        for env, cls in self.mapping["demand"].items():
            wav = root / env / "ch01.wav"
            if not wav.exists():
                self.missing.append(f"DEMAND/{env}")
                continue
            dur = info(wav)[0]
            cuts = [0.0, round(dur * DEMAND_CUTS[0], 3), round(dur * DEMAND_CUTS[1], 3), dur]
            for split, start, end in zip(("train", "val", "test"), cuts, cuts[1:]):
                self.add("demand", wav, env, cls, split, fold=env, start=start, end=end)


def check(db: sqlite3.Connection) -> list[str]:
    errors = []
    # A dataset fold must sit in exactly one split.
    for dataset, fold, n in db.execute(
            "SELECT dataset, fold, COUNT(DISTINCT split) FROM clips "
            "WHERE dataset IN ('esc50', 'us8k') GROUP BY dataset, fold HAVING COUNT(DISTINCT split) > 1"):
        errors.append(f"{dataset} fold {fold} spans {n} splits")
    # A file may only span several splits if it is time-split (DEMAND).
    for path, n in db.execute(
            "SELECT path, COUNT(DISTINCT split) FROM clips WHERE dataset != 'demand' "
            "GROUP BY path HAVING COUNT(DISTINCT split) > 1"):
        errors.append(f"{path} appears in {n} splits")
    # Time segments of one recording must not overlap.
    for path, a, b in db.execute(
            "SELECT x.path, x.split, y.split FROM clips x JOIN clips y ON x.path = y.path AND x.id < y.id "
            "WHERE x.start_s < y.end_s AND y.start_s < x.end_s"):
        errors.append(f"{path}: {a} and {b} segments overlap")
    return errors


def main() -> int:
    global AUDIO_RAW, INDEX
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--audio-root", type=Path, default=AUDIO_RAW, help="folder holding the datasets")
    ap.add_argument("--out", type=Path, help="override the default index path")
    ap.add_argument("--test-only", action="store_true", help="index only test rows and skip other audio files")
    args = ap.parse_args()
    default_index = INDEX.with_name("audio_test_index.sqlite") if args.test_only else INDEX
    AUDIO_RAW, INDEX = args.audio_root.resolve(), (args.out or default_index).resolve()

    t0 = time.time()
    b = Builder(yaml.safe_load(LABELS.read_text()), test_only=args.test_only)
    for step in (b.esc50, b.us8k, b.musan, b.fsd50k, b.demand):
        step()

    INDEX.parent.mkdir(parents=True, exist_ok=True)
    tmp = INDEX.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    db.executescript(SCHEMA)
    db.executemany("INSERT INTO clips (dataset, path, start_s, end_s, source_label, class, class_index, "
                   "split, fold, use, sample_rate, channels, licence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", b.rows)
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("built_at", time.strftime("%Y-%m-%dT%H:%M:%S%z")),
        ("labels_sha1", hashlib.sha1(LABELS.read_bytes()).hexdigest()),
        ("missing", ",".join(b.missing)),
    ])
    db.commit()
    errors = check(db)
    if errors:
        for e in errors:
            print(f"error: {e}")
        db.close()
        tmp.unlink()
        return 1

    print(f"{len(b.rows)} clips indexed in {time.time() - t0:.1f}s -> {INDEX}")
    print(f"{'dataset':<8}{'train':>8}{'val':>8}{'test':>8}")
    for ds, tr, va, te in db.execute(
            "SELECT dataset, SUM(split='train'), SUM(split='val'), SUM(split='test') FROM clips GROUP BY dataset"):
        print(f"{ds:<8}{tr:>8}{va:>8}{te:>8}")
    print(f"\n{'class':<15}{'train':>8}{'val':>8}{'test':>8}")
    for cls, tr, va, te in db.execute(
            "SELECT class, SUM(split='train'), SUM(split='val'), SUM(split='test') FROM clips "
            "WHERE use='classify' GROUP BY class ORDER BY class_index"):
        print(f"{cls:<15}{tr:>8}{va:>8}{te:>8}")
    if b.missing:
        print(f"\nnot downloaded yet (re-run once they are): {', '.join(b.missing)}")
    db.close()
    tmp.replace(INDEX)
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""SQLite storage (stdlib sqlite3, no ORM). Tables are created and migrated on startup.
Never commit *.db.

Also a CLI:
  python db.py --reseed   refresh places from the GeoJSON after rebuilding it; measurements are kept
  python db.py --reset    wipe everything and re-seed (measurements are lost)
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEFAULT_DB = Path(__file__).resolve().parent / "ciuin.db"
SEED_FILES = [REPO / "data" / "processed" / "places_lausanne.geojson"]

SCHEMA = """
CREATE TABLE IF NOT EXISTS places (
    id                INTEGER PRIMARY KEY,      -- rowid; also the R-Tree id
    place_id          TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    lat               REAL NOT NULL,
    lon               REAL NOT NULL,
    category          TEXT NOT NULL,
    wheelchair        TEXT,
    sensory_friendly  TEXT,
    quiet_room        INTEGER NOT NULL DEFAULT 0,
    baseline_noise_db REAL,
    baseline_noise_night_db REAL,
    baseline_source   TEXT,
    osm_tags          TEXT,                          -- JSON object of selected OSM tags
    prior             TEXT,                          -- JSON FeatureVector, ESTIMATED (priors.py)
    n                 INTEGER NOT NULL DEFAULT 0,   -- number of measurements
    mean_features     TEXT,                          -- JSON FeatureVector, null until measured
    score             TEXT                           -- JSON PlaceScore, null until measured
);
CREATE INDEX IF NOT EXISTS places_lat ON places(lat);

CREATE TABLE IF NOT EXISTS measurements (
    id            INTEGER PRIMARY KEY,
    place_id      TEXT NOT NULL,
    lat           REAL NOT NULL,
    lon           REAL NOT NULL,
    timestamp     TEXT NOT NULL,
    duration_s    INTEGER NOT NULL,
    features      TEXT NOT NULL,   -- JSON FeatureVector
    flicker_hz    REAL,
    model_variant TEXT NOT NULL,
    device        TEXT NOT NULL,
    measurement_id TEXT,            -- optional request ID for safe upload retries
    received_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
CREATE INDEX IF NOT EXISTS measurements_place ON measurements(place_id);
"""

RTREE = "CREATE VIRTUAL TABLE IF NOT EXISTS places_rtree USING rtree(id, min_lon, max_lon, min_lat, max_lat)"


def db_path() -> Path:
    return Path(os.environ.get("CIUIN_DB", DEFAULT_DB))


def connect(*, check_same_thread: bool = True) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path(), timeout=10, check_same_thread=check_same_thread)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")  # readers don't block the writer
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def has_rtree(conn: sqlite3.Connection) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE name='places_rtree'").fetchone() is not None


# Columns added after the first release: (table, column, type). init() adds any that are missing,
# so an existing ciuin.db upgrades in place without losing measurements. Append, never edit.
MIGRATIONS = [
    ("places", "baseline_noise_night_db", "REAL"),
    ("places", "osm_tags", "TEXT"),
    ("places", "prior", "TEXT"),
    ("measurements", "measurement_id", "TEXT"),
]


def init(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    for table, column, sqltype in MIGRATIONS:
        cols = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
        if column not in cols:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {sqltype}")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS measurements_request ON measurements(measurement_id)")
    try:
        conn.execute(RTREE)
    except sqlite3.OperationalError:
        pass  # SQLite built without R-Tree: queries fall back to the lat index
    conn.commit()


def seed(conn: sqlite3.Connection, files: list[Path] = SEED_FILES) -> int:
    """Insert places from GeoJSON. Existing places keep their measurements/aggregates."""
    rtree = has_rtree(conn)
    count = 0
    for f in files:
        if not f.is_file():
            print(f"seed: {f} not found, skipping", file=sys.stderr)
            continue
        for feat in json.loads(f.read_text(encoding="utf-8"))["features"]:
            p = feat["properties"]
            cur = conn.execute(
                """INSERT INTO places (place_id, name, lat, lon, category, wheelchair, sensory_friendly,
                                       quiet_room, baseline_noise_db, baseline_noise_night_db,
                                       baseline_source, osm_tags, prior)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(place_id) DO UPDATE SET
                     name=excluded.name, lat=excluded.lat, lon=excluded.lon, category=excluded.category,
                     wheelchair=excluded.wheelchair, sensory_friendly=excluded.sensory_friendly,
                     quiet_room=excluded.quiet_room, baseline_noise_db=excluded.baseline_noise_db,
                     baseline_noise_night_db=excluded.baseline_noise_night_db,
                     baseline_source=excluded.baseline_source, osm_tags=excluded.osm_tags,
                     prior=excluded.prior
                   RETURNING id""",
                (
                    p["place_id"], p["name"], p["lat"], p["lon"], p["category"], p.get("wheelchair"),
                    p.get("sensory_friendly"), int(bool(p.get("quiet_room"))), p.get("baseline_noise_db"),
                    p.get("baseline_noise_night_db"), p.get("baseline_source"),
                    json.dumps(p.get("osm_tags") or {}, ensure_ascii=False),
                    json.dumps(p["prior"]) if p.get("prior") else None,
                ),
            )
            rowid = cur.fetchone()[0]
            if rtree:
                conn.execute(
                    "INSERT OR REPLACE INTO places_rtree VALUES (?, ?, ?, ?, ?)",
                    (rowid, p["lon"], p["lon"], p["lat"], p["lat"]),
                )
            count += 1
    conn.commit()
    return count


def ensure_ready() -> None:
    """Create tables and seed on first start (empty places table)."""
    with closing(connect()) as conn:
        init(conn)
        if conn.execute("SELECT COUNT(*) FROM places").fetchone()[0] == 0:
            n = seed(conn)
            print(f"seeded {n} places into {db_path()}", file=sys.stderr)


def main() -> int:
    if "--reseed" in sys.argv:  # refresh places from the GeoJSON, keep measurements
        with closing(connect()) as conn:
            init(conn)
            print(f"re-seeded {seed(conn)} places (measurements kept)")
    if "--reset" in sys.argv:
        for suffix in ("", "-wal", "-shm"):
            Path(str(db_path()) + suffix).unlink(missing_ok=True)
        print(f"deleted {db_path()}")
    ensure_ready()
    with closing(connect()) as conn:
        print(f"{conn.execute('SELECT COUNT(*) FROM places').fetchone()[0]} places, "
              f"{conn.execute('SELECT COUNT(*) FROM measurements').fetchone()[0]} measurements, "
              f"R-Tree {'on' if has_rtree(conn) else 'off'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

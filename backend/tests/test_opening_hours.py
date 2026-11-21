from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from opening_hours import is_open, parse, zurich_local  # noqa: E402


def utc(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)


# 2026-11-21 is a Saturday; Zurich is UTC+1 in November.
SAT_1000_LOCAL = utc("2026-11-21T09:00:00")
SAT_1830_LOCAL = utc("2026-11-21T17:30:00")
SUN_0100_LOCAL = utc("2026-11-22T00:00:00")
WED_1230_LOCAL = utc("2026-11-25T11:30:00")


def test_zurich_time_handles_summer_and_winter():
    assert zurich_local(utc("2026-11-21T09:00:00")).hour == 10  # CET
    assert zurich_local(utc("2026-07-01T09:00:00")).hour == 11  # CEST
    assert zurich_local(utc("2026-03-29T00:59:00")).hour == 1   # just before the switch (last Sunday of March)
    assert zurich_local(utc("2026-03-29T01:00:00")).hour == 3   # just after


@pytest.mark.parametrize("value, at, expected", [
    ("24/7", SUN_0100_LOCAL, True),
    ("Mo-Fr 09:00-19:00; Sa 09:00-18:00", SAT_1000_LOCAL, True),
    ("Mo-Fr 09:00-19:00; Sa 09:00-18:00", SAT_1830_LOCAL, False),
    ("Mo-Fr 09:00-19:00", SAT_1000_LOCAL, False),
    ("Mo-Su 08:00-12:00,13:30-18:00", WED_1230_LOCAL, False),  # lunch break
    ("Mo-Su 08:00-12:00,13:30-18:00", SAT_1000_LOCAL, True),
    ("Mo-Sa 10:00-20:00; Sa off", SAT_1000_LOCAL, False),        # later rule wins
    ("Fr-Sa 18:00-02:00", SUN_0100_LOCAL, True),                 # Saturday night runs past midnight
    ("Mo-Su,PH 10:00-22:00", SAT_1000_LOCAL, True),              # PH ignored
    ("Mo-Fr 06:00-24:00; Sa,Su 07:00-24:00", SAT_1000_LOCAL, True),
    ("Fr-Mo 10:00-18:00", SAT_1000_LOCAL, True),                 # wrapping day range
    ("Mo-Fr 07:30-19:00, Sa 07:30-18:00", SAT_1000_LOCAL, True),  # comma as rule separator
    ("Mo-Fr 07:30-19:00, Sa 07:30-18:00", SAT_1830_LOCAL, False),
    ("Mo, Tu 08:00-18:00; We-Su 08:00-22:00", SAT_1830_LOCAL, True),  # space in day list
    ("Tu,We 11:00-24:00, Th 11:00-01:00, Fr,Sa 11:00-02:00", SUN_0100_LOCAL, True),  # Sat night past midnight
])
def test_is_open(value, at, expected):
    assert is_open(value, at) is expected


@pytest.mark.parametrize("value", [
    "Tu-Su 10:00-18:00; Jun 20-Sep 21: Mo-Su 09:00-20:00",
    "Tu 17:00-19:00; Sa[1] 09:30-11:30",
    '"si le temps est sec"',
    "Mo-Su,PH 10:00-24:00+",
    'Mo-Fr 08:30-11:30 "phone" || "by appointment"',
    "Tu,Th 09:00-12:00; SH off",
    "sunrise-sunset",
    "",
    None,
])
def test_unsupported_is_unknown(value):
    assert is_open(value, SAT_1000_LOCAL) is None


def test_parses_most_lausanne_values():
    places = json.loads((Path(__file__).resolve().parents[2] / "data" / "processed" / "places_lausanne.geojson")
                        .read_text(encoding="utf-8"))["features"]
    values = [f["properties"]["osm_tags"]["opening_hours"] for f in places
              if "opening_hours" in f["properties"].get("osm_tags", {})]
    parsed = sum(parse(v) is not None for v in values)
    assert len(values) > 1000
    assert parsed / len(values) > 0.9, f"only {parsed}/{len(values)} parsed"

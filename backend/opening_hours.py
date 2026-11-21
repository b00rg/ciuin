"""Minimal OSM `opening_hours` evaluator: is a place open at a given moment?

Handles the common forms (~97% of Lausanne's values):
  "24/7" | "Mo-Fr 09:00-19:00; Sa 09:00-18:00" | "Mo,We 08:00-12:00,13:30-18:00" | "Su off"
  overnight spans ("Fr 18:00-02:00"), "24:00" ends, "PH ..." rules (ignored: public holidays unknown).
Anything else (month/date ranges, week numbers, "Sa[1]", sunrise, "SH", quoted comments, "||", open-ended
"+") returns None = unknown. Returning unknown is better than guessing for an alert-sensitive app.

Local time is Europe/Zurich, computed without a tz database (CET/CEST, EU rules) so it works on every
machine without extra packages.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

DAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
_DAY_ALT = "Mo|Tu|We|Th|Fr|Sa|Su|PH"
_TIME = r"(\d{1,2}):(\d{2})"
_SPAN = re.compile(rf"^{_TIME}-{_TIME}$")
_UNSUPPORTED = re.compile(
    r'"|\|\||\+|\[|sunrise|sunset|dawn|dusk|week|easter|SH\b|'
    r"\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\b|\b\d{4}\b"
)


def _last_sunday(year: int, month: int) -> datetime:
    d = datetime(year, month + 1, 1, tzinfo=timezone.utc) - timedelta(days=1) if month < 12 else datetime(year, 12, 31, tzinfo=timezone.utc)
    return d - timedelta(days=(d.weekday() + 1) % 7)


def zurich_local(at_utc: datetime) -> datetime:
    """UTC -> Europe/Zurich wall time (CET +1, CEST +2 from last Sunday of March to last Sunday of October, 01:00 UTC)."""
    at_utc = at_utc.astimezone(timezone.utc)
    start = _last_sunday(at_utc.year, 3).replace(hour=1)
    end = _last_sunday(at_utc.year, 10).replace(hour=1)
    offset = 2 if start <= at_utc < end else 1
    return (at_utc + timedelta(hours=offset)).replace(tzinfo=None)


def _days(selector: str) -> set[int] | None:
    """'Mo-Fr,Su' -> {0,1,2,3,4,6}. None if unparseable."""
    out: set[int] = set()
    for part in selector.split(","):
        part = part.strip()
        if "-" in part:
            a, b = part.split("-", 1)
            if a not in DAYS or b not in DAYS:
                return None
            i, j = DAYS.index(a), DAYS.index(b)
            out.update(range(i, j + 1) if i <= j else [*range(i, 7), *range(0, j + 1)])
        elif part in DAYS:
            out.add(DAYS.index(part))
        else:
            return None
    return out


def _spans(times: str) -> list[tuple[int, int]] | None:
    """'08:00-12:00,13:30-18:00' -> [(480, 720), (810, 1080)] minutes; end may exceed 1440 (overnight)."""
    out = []
    for t in times.split(","):
        m = _SPAN.match(t.strip())
        if not m:
            return None
        h1, m1, h2, m2 = map(int, m.groups())
        start, end = h1 * 60 + m1, h2 * 60 + m2
        if start > 24 * 60 or end > 24 * 60:
            return None
        if end <= start:
            end += 24 * 60  # runs past midnight
        out.append((start, end))
    return out


def parse(value: str) -> dict[int, list[tuple[int, int]]] | None:
    """Weekday -> open spans (minutes from that day's 00:00). None if the value is unsupported."""
    v = value.strip()
    if v == "24/7":
        return {d: [(0, 24 * 60)] for d in range(7)}
    if not v or _UNSUPPORTED.search(v):
        return None
    # "Mo, Tu 08:00-18:00" -> "Mo,Tu 08:00-18:00" (spaces inside day lists)
    v = re.sub(rf"\b({_DAY_ALT})\s*,\s*(?=({_DAY_ALT})\b)", r"\1,", v)
    # "Mo-Fr 07:30-19:00, Sa 07:30-18:00": a comma before a new day selector separates rules
    rules = [r for chunk in v.split(";") for r in re.split(rf",\s+(?=({_DAY_ALT})\b)", chunk)
             if r and r not in DAYS + ["PH"]]
    week: dict[int, list[tuple[int, int]]] = {d: [] for d in range(7)}
    seen_any = False
    for rule in (r.strip() for r in rules):
        if not rule:
            continue
        tokens = rule.split(None, 1)
        selector, rest = (tokens[0], tokens[1] if len(tokens) > 1 else "")
        if re.match(r"^\d", selector):  # no day selector: times apply every day
            selector, rest = "Mo-Su", rule
        # drop PH from selectors ("Mo-Su,PH 10:00-22:00"); a PH-only rule is skipped
        parts = [p for p in selector.split(",") if p != "PH"]
        if not parts:
            continue
        days = _days(",".join(parts))
        if days is None:
            return None
        rest = rest.strip()
        if rest.lower() in ("off", "closed"):
            spans: list[tuple[int, int]] = []
        else:
            spans = _spans(rest)
            if spans is None:
                return None
        for d in days:  # a later rule replaces earlier ones for the days it names (OSM semantics)
            week[d] = list(spans)
        seen_any = True
    return week if seen_any else None


def is_open(value: str | None, at_utc: datetime) -> bool | None:
    """True/False if we can tell, None if unknown (no tag or unsupported syntax)."""
    if not value:
        return None
    week = parse(value)
    if week is None:
        return None
    local = zurich_local(at_utc)
    day, minute = local.weekday(), local.hour * 60 + local.minute
    if any(s <= minute < e for s, e in week[day]):
        return True
    prev = (day - 1) % 7  # yesterday's spans that ran past midnight
    return any(s <= minute + 24 * 60 < e for s, e in week[prev] if e > 24 * 60)

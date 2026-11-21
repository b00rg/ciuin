"""Prior (estimated) feature vector per place, for places nobody has measured yet (todo.md §0 `prior`).

Same shape as a measured FeatureVector, so the personal model can score every place. It is an ESTIMATE:
the app must never present it as measured data. Real measurements go in `measured`, never here.

Inputs (all already in the place record):
  - category group  -> typical indoor level, light, flicker, crowd, sound mix (table below)
  - baseline_noise_db (outdoor road+rail, day) -> leaks in through the façade (~20 dB attenuation);
    for outdoor places it IS the level, and it raises the `traffic` share
  - osm_tags.capacity -> crowd estimate when tagged

The numbers are first guesses from typical values (e.g. restaurants ~60-70 dB(A), retail lighting
~500-1000 lx, LED/fluorescent flicker higher in big retail). Emma owns the personal-model side: tune
them there. Change a group here, rebuild places, `python backend/db.py --reseed`.
"""

from __future__ import annotations

import math

SOUND_CLASSES = ("speech_crowd", "music", "traffic", "siren", "construction", "alarm_beep", "dog", "machinery_hum", "other")
FACADE_ATTENUATION_DB = 20.0

# group: (indoor dB(A), lux, flicker_index, people, sound mix (renormalised; missing classes = 0), outdoor?)
GROUPS: dict[str, tuple[float, float, float, int, dict[str, float], bool]] = {
    "restaurant": (66, 250, 0.03, 15, {"speech_crowd": .55, "music": .15, "machinery_hum": .10, "other": .15, "traffic": .05}, False),
    "cafe": (60, 300, 0.03, 10, {"speech_crowd": .50, "music": .20, "machinery_hum": .10, "other": .15, "traffic": .05}, False),
    "bar": (70, 100, 0.05, 20, {"music": .40, "speech_crowd": .45, "other": .15}, False),
    "nightclub": (92, 30, 0.20, 80, {"music": .80, "speech_crowd": .15, "other": .05}, False),
    "quiet_culture": (40, 300, 0.02, 5, {"other": .60, "speech_crowd": .30, "music": .10}, False),
    "education": (62, 400, 0.05, 30, {"speech_crowd": .70, "other": .30}, False),
    "big_retail": (62, 750, 0.08, 20, {"machinery_hum": .25, "speech_crowd": .35, "music": .20, "alarm_beep": .05, "other": .15}, False),
    "shop": (55, 500, 0.05, 4, {"speech_crowd": .30, "music": .25, "machinery_hum": .10, "other": .30, "traffic": .05}, False),
    "health": (50, 600, 0.05, 6, {"speech_crowd": .40, "other": .40, "machinery_hum": .20}, False),
    "personal_care": (58, 500, 0.04, 4, {"speech_crowd": .40, "music": .30, "machinery_hum": .20, "other": .10}, False),
    "sports": (72, 500, 0.06, 25, {"speech_crowd": .40, "music": .30, "other": .30}, False),
    "screen_stage": (70, 5, 0.00, 50, {"music": .50, "speech_crowd": .40, "other": .10}, False),
    "lodging": (45, 250, 0.02, 5, {"speech_crowd": .30, "other": .60, "machinery_hum": .10}, False),
    "services": (52, 450, 0.05, 6, {"speech_crowd": .45, "other": .40, "machinery_hum": .15}, False),
    "workshop": (68, 500, 0.06, 4, {"machinery_hum": .40, "construction": .15, "speech_crowd": .20, "other": .25}, False),
    "outdoor": (0, 1000, 0.00, 10, {"other": .50, "speech_crowd": .25, "dog": .05}, True),
    "playground": (0, 1000, 0.00, 15, {"speech_crowd": .60, "other": .30, "dog": .05}, True),
}
DEFAULT_GROUP = "shop"

CATEGORY_GROUP = {
    **dict.fromkeys(["restaurant", "fast_food", "food_court", "canteen"], "restaurant"),
    **dict.fromkeys(["cafe", "ice_cream", "bakery", "pastry", "tea", "coffee", "chocolate", "confectionery"], "cafe"),
    **dict.fromkeys(["bar", "pub", "biergarten"], "bar"),
    **dict.fromkeys(["nightclub", "casino"], "nightclub"),
    **dict.fromkeys(["library", "place_of_worship", "museum", "gallery", "archive", "books", "monastery"], "quiet_culture"),
    **dict.fromkeys(["school", "kindergarten", "university", "college", "childcare", "language_school",
                     "music_school", "driving_school", "training", "prep_school"], "education"),
    **dict.fromkeys(["supermarket", "department_store", "mall", "convenience", "hardware", "doityourself",
                     "electronics", "variety_store", "marketplace"], "big_retail"),
    **dict.fromkeys(["pharmacy", "doctors", "dentist", "clinic", "hospital", "veterinary", "optician",
                     "hearing_aids", "medical_supply", "physiotherapist"], "health"),
    **dict.fromkeys(["hairdresser", "beauty", "massage", "tattoo", "cosmetics", "nail_salon"], "personal_care"),
    **dict.fromkeys(["sports_centre", "fitness_centre", "swimming_pool", "stadium", "sports_hall", "dance",
                     "ice_rink", "bowling_alley"], "sports"),
    **dict.fromkeys(["cinema", "theatre", "arts_centre", "events_venue", "concert_hall"], "screen_stage"),
    **dict.fromkeys(["hotel", "hostel", "guest_house", "apartment", "motel"], "lodging"),
    **dict.fromkeys(["bank", "post_office", "townhall", "police", "community_centre", "social_facility",
                     "courthouse", "embassy", "public_building", "coworking_space", "conference_centre",
                     "social_centre", "travel_agency", "copyshop", "dry_cleaning", "laundry"], "services"),
    **dict.fromkeys(["car_repair", "bicycle", "motorcycle", "car", "tyres"], "workshop"),
    **dict.fromkeys(["toy_library"], "quiet_culture"),
    **dict.fromkeys(["park", "garden", "viewpoint", "pitch", "nature_reserve", "picnic_site", "dog_park",
                     "attraction", "camp_site", "zoo", "beach_resort", "track", "common", "golf_course",
                     "marina", "ferry_terminal"], "outdoor"),
    **dict.fromkeys(["playground", "water_park", "theme_park"], "playground"),
}


def group_of(category: str) -> str:
    return CATEGORY_GROUP.get(category, DEFAULT_GROUP)


def _energetic_sum(*levels: float) -> float:
    return 10 * math.log10(sum(10 ** (l / 10) for l in levels))


def prior_for(place: dict) -> dict:
    """FeatureVector-shaped dict (JSON names) for one place record."""
    group = group_of(place["category"])
    indoor_db, lux, flicker, people, mix, outdoor = GROUPS[group]
    baseline = place.get("baseline_noise_db")

    if outdoor:
        db = baseline if baseline is not None else 50.0
        # louder street noise -> more of what you hear is traffic
        traffic = max(0.0, min(0.7, (db - 45) / 30))
        mix = {**{k: v * (1 - traffic) for k, v in mix.items()}, "traffic": traffic}
    else:
        db = _energetic_sum(indoor_db, baseline - FACADE_ATTENUATION_DB) if baseline is not None else indoor_db

    capacity = str(place.get("osm_tags", {}).get("capacity", "")).strip()
    if capacity.isdigit() and int(capacity) > 0:
        people = max(1, round(int(capacity) * 0.3))  # ~30% occupancy on average

    total = sum(mix.values())
    sound = {c: round(mix.get(c, 0.0) / total, 4) for c in SOUND_CLASSES}
    return {
        "db_mean": round(db, 1),
        "db_peak": round(db + 12, 1),
        "sound_classes": sound,
        "lux_mean": float(lux),
        "flicker_index": flicker,
        "people_count": int(people),
    }

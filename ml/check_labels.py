"""Check ml/export/labels.yaml and regenerate ml/export/labels.txt from it.

    pip install -r ml/requirements.txt
    python3 ml/check_labels.py          # validate + write labels.txt
    python3 ml/check_labels.py --check  # validate only; fail if labels.txt is stale

Checks:
- exactly 9 classes, no "quiet", same order as the Kotlin SoundClass enum
- every source class (ESC-50, UrbanSound8K, MUSAN) mapped exactly once
- every class has training data and an importance weight
If the datasets are downloaded (data/raw/audio/), the mapping is also
checked against their metadata files.
"""

from __future__ import annotations

import csv
import re
import sys
from collections import Counter
from pathlib import Path

import yaml

ML = Path(__file__).resolve().parent
REPO = ML.parent
LABELS = ML / "export" / "labels.yaml"
LABELS_TXT = ML / "export" / "labels.txt"
AUDIO_RAW = REPO / "data" / "raw" / "audio"
SOUND_CLASS_KT = REPO / "shared/src/commonMain/kotlin/ch/ciuin/shared/model/SoundClass.kt"
N_CLASSES = 9

# Known class lists, used when the datasets aren't downloaded yet.
ESC50 = {
    "dog", "rooster", "pig", "cow", "frog", "cat", "hen", "insects", "sheep", "crow",
    "rain", "sea_waves", "crackling_fire", "crickets", "chirping_birds", "water_drops",
    "wind", "pouring_water", "toilet_flush", "thunderstorm",
    "crying_baby", "sneezing", "clapping", "breathing", "coughing", "footsteps",
    "laughing", "brushing_teeth", "snoring", "drinking_sipping",
    "door_wood_knock", "mouse_click", "keyboard_typing", "door_wood_creaks", "can_opening",
    "washing_machine", "vacuum_cleaner", "clock_alarm", "clock_tick", "glass_breaking",
    "helicopter", "chainsaw", "siren", "car_horn", "engine", "train", "church_bells",
    "airplane", "fireworks", "hand_saw",
}
US8K = {
    "air_conditioner", "car_horn", "children_playing", "dog_bark", "drilling",
    "engine_idling", "gun_shot", "jackhammer", "siren", "street_music",
}
MUSAN = {"speech", "music", "noise"}


def load() -> dict:
    return yaml.safe_load(LABELS.read_text())


def csv_classes(path: Path, column: str) -> set[str] | None:
    if not path.exists():
        return None
    with open(path, newline="") as f:
        return {row[column] for row in csv.DictReader(f)}


def expected_sources() -> dict[str, set[str]]:
    esc = csv_classes(AUDIO_RAW / "ESC-50" / "meta" / "esc50.csv", "category")
    us8k = csv_classes(AUDIO_RAW / "UrbanSound8K" / "metadata" / "UrbanSound8K.csv", "class")
    musan_dir = AUDIO_RAW / "musan"
    musan = {p.name for p in musan_dir.iterdir() if p.is_dir()} if musan_dir.is_dir() else None
    for name, got, known in (("esc50", esc, ESC50), ("us8k", us8k, US8K), ("musan", musan, MUSAN)):
        if got is not None and got != known:
            print(f"note: {name} metadata differs from the built-in list: {sorted(got ^ known)}")
    return {"esc50": esc or ESC50, "us8k": us8k or US8K, "musan": musan or MUSAN}


def kotlin_labels() -> list[str] | None:
    if not SOUND_CLASS_KT.exists():
        return None
    return re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', SOUND_CLASS_KT.read_text(), re.M)


def validate(cfg: dict) -> list[str]:
    errors = []
    classes = cfg.get("classes") or []
    if len(classes) != N_CLASSES:
        errors.append(f"expected {N_CLASSES} classes, got {len(classes)}")
    if len(set(classes)) != len(classes):
        errors.append(f"duplicate classes: {[c for c, n in Counter(classes).items() if n > 1]}")
    if "quiet" in classes:
        errors.append("'quiet' must not be a class (stage 0 handles quiet)")

    kt = kotlin_labels()
    if kt is not None and kt != classes:
        errors.append(f"order differs from SoundClass.kt: yaml={classes} kotlin={kt}")

    importance = cfg.get("importance") or {}
    if set(importance) != set(classes):
        errors.append(f"importance must list exactly the classes; differs on {sorted(set(importance) ^ set(classes))}")
    bad = {c: w for c, w in importance.items() if not isinstance(w, (int, float)) or w <= 0}
    if bad:
        errors.append(f"importance weights must be positive numbers: {bad}")

    mapping = cfg.get("mapping") or {}
    used = set()
    for source, expected in expected_sources().items():
        m = mapping.get(source)
        if m is None:
            errors.append(f"mapping.{source} missing")
            continue
        missing, extra = expected - m.keys(), m.keys() - expected
        if missing:
            errors.append(f"mapping.{source}: unmapped source classes {sorted(missing)}")
        if extra:
            errors.append(f"mapping.{source}: unknown source classes {sorted(extra)}")
        for src, dst in m.items():
            if dst is not None and dst not in classes:
                errors.append(f"mapping.{source}.{src} -> '{dst}' is not one of our classes")
            used.add(dst)
    unused = set(classes) - used
    if unused:
        errors.append(f"classes with no training data: {sorted(unused)}")
    return errors


def main() -> int:
    cfg = load()
    errors = validate(cfg)
    for e in errors:
        print(f"error: {e}")
    if errors:
        return 1

    txt = "".join(f"{c}\n" for c in cfg["classes"])
    if "--check" in sys.argv:
        if not LABELS_TXT.exists() or LABELS_TXT.read_text() != txt:
            print("error: ml/export/labels.txt is out of date; run without --check")
            return 1
    else:
        LABELS_TXT.write_text(txt)

    counts = Counter(d for m in cfg["mapping"].values() for d in m.values() if d)
    print("ok: " + ", ".join(f"{c}={counts[c]}" for c in cfg["classes"]) + " source classes")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Personal trigger model: Python prototype, hyperparameter pick, Kotlin test vectors (#8).

    python3 ml/personal_model.py            # tune on synthetic users, write spec data
    python3 ml/personal_model.py --quick    # skip the grid search, use the chosen numbers

Logistic regression over the shared feature vector, started from a preset and
pulled back toward it (L2 toward the preset, not toward zero), so it works
with zero taps and never forgets the preset entirely with few taps.

Everything here is deterministic (full-batch gradient descent, no RNG at train
time) so the Kotlin port in commonMain can match it exactly. The spec is
ml/export/personal_model.md; the test vectors are
ml/export/personal_model_test.json.

Outputs (ml/export/):
  personal_presets.json        preset weights (the app bundles this)
  personal_model_test.json     synthetic users: example stream + expected weights / scores
  personal_model_results.json  chosen hyperparameters, grid, synthetic-user scores
"""

from __future__ import annotations

import argparse
import json
import math
from itertools import product
from pathlib import Path

import numpy as np

ML = Path(__file__).resolve().parent
EXPORT = ML / "export"

SOUND_CLASSES = ["speech_crowd", "music", "traffic", "siren", "construction",
                 "alarm_beep", "dog", "machinery_hum", "other"]

# Order of the model's inputs. Kotlin must build exactly this vector.
FEATURES = ["noise", "peak"] + [f"sound_{c}" for c in SOUND_CLASSES] + ["light", "flicker", "crowd"]

# Chosen by tune() below (grid in export/personal_model_results.json).
DEFAULT_HP = {"lr": 1.0, "l2": 0.01, "steps": 60, "cap": 200, "max_pos_weight": 3.0}


# ---------------------------------------------------------------- features

def clamp01(v: float) -> float:
    return min(1.0, max(0.0, v))


def featurize(fv: dict) -> np.ndarray:
    """FeatureVector (contract JSON) -> model input x, every entry in [0, 1].

    noise    (db_mean - 35) / 50          35 dB -> 0, 85 dB -> 1
    peak     (db_peak - db_mean) / 30     how impulsive: sudden bangs, alarms
    sound_*  the 9 softmax probabilities as they are
    light    log10(lux_mean + 1) / 4      ~10 000 lux (daylight) -> 1
    flicker  flicker_index                already in [0, 1]
    crowd    ln(1 + people) / ln(31)      30 people in view -> 1
    """
    sc = fv["sound_classes"]
    x = [
        clamp01((fv["db_mean"] - 35.0) / 50.0),
        clamp01((fv["db_peak"] - fv["db_mean"]) / 30.0),
        *[clamp01(float(sc.get(c, 0.0))) for c in SOUND_CLASSES],
        clamp01(math.log10(max(fv["lux_mean"], 0.0) + 1.0) / 4.0),
        clamp01(fv["flicker_index"]),
        clamp01(math.log(1.0 + max(fv["people_count"], 0)) / math.log(31.0)),
    ]
    return np.array(x, dtype=np.float64)


# ---------------------------------------------------------------- presets

def _preset(bias: float, **w: float) -> dict:
    weights = {f: 0.0 for f in FEATURES}
    for k, v in w.items():
        assert k in weights, k
        weights[k] = v
    return {"bias": bias, "weights": weights}


# Hand-set from the sensory-processing literature on autism (loud / sudden /
# high-pitched sound, flicker, bright light and crowding are the commonest
# reported triggers), then sanity-checked on the synthetic scenes below:
# (autism_friendly) quiet room ~0.01, library ~0.1, busy café ~0.5, siren / drilling / station hall ~0.9.
PRESETS = {
    "autism_friendly": _preset(
        -6.0, noise=4.0, peak=2.0,
        sound_speech_crowd=1.0, sound_music=0.5, sound_traffic=0.5, sound_siren=3.0,
        sound_construction=2.5, sound_alarm_beep=3.0, sound_dog=1.5, sound_machinery_hum=1.0,
        light=1.0, flicker=3.0, crowd=2.0),
    "sound_sensitive": _preset(
        -6.0, noise=5.0, peak=2.5,
        sound_speech_crowd=1.0, sound_music=0.8, sound_traffic=0.8, sound_siren=3.0,
        sound_construction=3.0, sound_alarm_beep=3.0, sound_dog=2.0, sound_machinery_hum=1.5,
        light=0.3, flicker=1.0, crowd=1.0),
    "light_sensitive": _preset(
        -6.0, noise=2.0, peak=1.0, sound_siren=1.5, sound_alarm_beep=1.5, sound_construction=1.0,
        light=3.0, flicker=10.0, crowd=1.0),
    "crowd_sensitive": _preset(
        -6.5, noise=2.5, peak=1.0, sound_speech_crowd=2.5, sound_music=1.0, sound_siren=1.5,
        sound_alarm_beep=1.5, light=0.5, flicker=1.0, crowd=4.0),
}


def preset_vector(name: str) -> np.ndarray:
    p = PRESETS[name]
    return np.array([p["bias"]] + [p["weights"][f] for f in FEATURES])


# ---------------------------------------------------------------- model

def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-z))


class PersonalModel:
    """theta = [bias, w_1..w_14]. Mirrors the Kotlin class line for line."""

    def __init__(self, preset: str, hp: dict = DEFAULT_HP):
        self.hp = hp
        self.prior = preset_vector(preset)
        self.theta = self.prior.copy()
        self.X: list[np.ndarray] = []
        self.y: list[int] = []

    def score(self, x: np.ndarray) -> float:
        return float(sigmoid(self.theta[0] + self.theta[1:] @ x))

    def update(self, x: np.ndarray, label: int) -> None:
        self.X.append(x)
        self.y.append(label)
        if len(self.X) > self.hp["cap"]:          # FIFO: drop the oldest
            self.X.pop(0)
            self.y.pop(0)
        self.fit()

    def fit(self) -> None:
        """Retrain from the preset on the whole buffer: `steps` full-batch GD steps on

            L(theta) = sum_i v_i * logloss_i / sum_i v_i  +  l2/2 * ||theta - prior||^2

        v_i = pos_weight for label 1, else 1;  pos_weight = clamp(n_neg / n_pos, 1, max_pos_weight).
        Starting from the preset every time (not from the last theta) makes the result
        depend only on the buffer, so Kotlin and Python agree regardless of update history.
        """
        X = np.array(self.X)
        y = np.array(self.y, dtype=np.float64)
        n_pos = y.sum()
        n_neg = len(y) - n_pos
        pos_w = 1.0 if n_pos == 0 else min(self.hp["max_pos_weight"], max(1.0, n_neg / n_pos))
        v = np.where(y == 1, pos_w, 1.0)
        Xb = np.hstack([np.ones((len(X), 1)), X])
        theta = self.prior.copy()
        for _ in range(self.hp["steps"]):
            p = sigmoid(Xb @ theta)
            grad = Xb.T @ (v * (p - y)) / v.sum() + self.hp["l2"] * (theta - self.prior)
            theta = theta - self.hp["lr"] * grad
        self.theta = theta

    def reset(self) -> None:
        self.theta = self.prior.copy()
        self.X, self.y = [], []


# ---------------------------------------------------------------- synthetic world

SCENES = {
    # name: (db_mean, db_peak, dominant sound classes, lux, flicker, people)
    "quiet_room":        (38, 45, {"other": 0.7, "machinery_hum": 0.3}, 150, 0.01, 0),
    "library":           (45, 55, {"speech_crowd": 0.5, "other": 0.5}, 400, 0.02, 4),
    "office_fluorescent": (52, 60, {"speech_crowd": 0.5, "machinery_hum": 0.4, "other": 0.1}, 600, 0.25, 6),
    "cafe":              (63, 75, {"speech_crowd": 0.7, "music": 0.2, "other": 0.1}, 350, 0.04, 12),
    "bar_music":         (75, 88, {"music": 0.7, "speech_crowd": 0.3}, 60, 0.10, 25),
    "street":            (68, 82, {"traffic": 0.8, "other": 0.2}, 8000, 0.0, 8),
    "siren_passing":     (78, 95, {"siren": 0.8, "traffic": 0.2}, 8000, 0.0, 5),
    "construction":      (80, 98, {"construction": 0.8, "traffic": 0.1, "other": 0.1}, 9000, 0.0, 3),
    "station_hall":      (70, 90, {"speech_crowd": 0.5, "alarm_beep": 0.2, "traffic": 0.3}, 900, 0.15, 30),
    "supermarket":       (60, 72, {"speech_crowd": 0.4, "music": 0.3, "alarm_beep": 0.2, "other": 0.1}, 1200, 0.30, 15),
    "park_dogs":         (55, 78, {"dog": 0.6, "speech_crowd": 0.2, "other": 0.2}, 10000, 0.0, 6),
    "hacking_hall":      (66, 80, {"speech_crowd": 0.8, "machinery_hum": 0.1, "other": 0.1}, 500, 0.08, 40),
}


def scene_sample(name: str, rng: np.random.Generator) -> dict:
    db, peak, sounds, lux, flicker, people = SCENES[name]
    probs = np.full(len(SOUND_CLASSES), 0.01)
    for c, p in sounds.items():
        probs[SOUND_CLASSES.index(c)] += p
    probs = rng.dirichlet(probs * 30)
    dbm = db + rng.normal(0, 3)
    return {
        "db_mean": round(float(dbm), 2),
        "db_peak": round(float(max(dbm, peak + rng.normal(0, 4))), 2),
        "sound_classes": {c: round(float(p), 5) for c, p in zip(SOUND_CLASSES, probs)},
        "lux_mean": round(float(max(0.0, lux * rng.lognormal(0, 0.3))), 1),
        "flicker_index": round(float(np.clip(flicker + rng.normal(0, 0.03), 0, 1)), 4),
        "people_count": int(max(0, people + rng.normal(0, people * 0.3 + 1))),
    }


def user_truth(name: str) -> tuple[str, np.ndarray]:
    """(preset the user picks at onboarding, their true weights)."""
    t = preset_vector("autism_friendly").copy()
    idx = {f: i + 1 for i, f in enumerate(FEATURES)}
    if name == "hates_sirens_ok_with_speech":
        t[idx["sound_siren"]] = 7.0
        t[idx["sound_alarm_beep"]] = 6.0
        t[idx["sound_speech_crowd"]] = -1.5
        t[idx["crowd"]] = 0.0
        return "autism_friendly", t
    if name == "flicker_only":
        t[idx["noise"]] = 1.5
        t[idx["flicker"]] = 9.0
        t[idx["light"]] = 2.5
        return "autism_friendly", t
    if name == "dogs_and_crowds":
        t[idx["sound_dog"]] = 6.0
        t[idx["crowd"]] = 5.0
        t[idx["sound_siren"]] = 1.0
        return "sound_sensitive", t
    if name == "matches_preset":
        return "autism_friendly", t
    raise KeyError(name)


USERS = ["hates_sirens_ok_with_speech", "flicker_only", "dogs_and_crowds", "matches_preset"]


def stream(user: str, n: int, rng: np.random.Generator):
    """A day's worth of examples as the app would log them: automatic background
    samples are 0 unless the user would tap; taps happen with p_true, label 1."""
    _, truth = user_truth(user)
    names = list(SCENES)
    out = []
    for _ in range(n):
        fv = scene_sample(names[int(rng.integers(len(names)))], rng)
        p = float(sigmoid(truth[0] + truth[1:] @ featurize(fv)))
        out.append((fv, int(rng.random() < p)))
    return out


def evaluate(model: PersonalModel, user: str, probes: list[dict]) -> dict:
    """Mean |p_model - p_true| and ranking agreement on held-out scenes."""
    _, truth = user_truth(user)
    xs = [featurize(f) for f in probes]
    p_true = np.array([sigmoid(truth[0] + truth[1:] @ x) for x in xs])
    p_hat = np.array([model.score(x) for x in xs])
    # Pairwise ranking agreement (AUC-like): which of two places is worse for this user.
    i, j = np.triu_indices(len(xs), 1)
    keep = np.abs(p_true[i] - p_true[j]) > 0.05
    agree = np.mean(np.sign(p_hat[i] - p_hat[j])[keep] == np.sign(p_true[i] - p_true[j])[keep])
    return {"mae": float(np.abs(p_hat - p_true).mean()), "rank_agreement": float(agree)}


def run_user(user: str, hp: dict, n: int, seed: int) -> dict:
    rng = np.random.default_rng(seed)
    preset, _ = user_truth(user)
    probes = [scene_sample(s, rng) for s in SCENES for _ in range(5)]
    model = PersonalModel(preset, hp)
    out = {"n0": evaluate(model, user, probes)}
    for k, (fv, y) in enumerate(stream(user, n, rng), 1):
        model.update(featurize(fv), y)
        if k in (10, 30, 100, n):
            out[f"n{k}"] = evaluate(model, user, probes)
    return out


def tune(n: int = 100, seeds=(1, 2, 3)) -> tuple[dict, list]:
    grid = []
    for lr, l2, steps in product((0.2, 0.5, 1.0), (0.01, 0.05, 0.2), (10, 30, 60)):
        hp = {**DEFAULT_HP, "lr": lr, "l2": l2, "steps": steps}
        r = [run_user(u, hp, n, s) for u in USERS for s in seeds]
        # Score: error after 30 and 100 examples, plus a penalty if the
        # preset-matching user gets worse (the model shouldn't drift off a good prior).
        mae30 = np.mean([x["n30"]["mae"] for x in r])
        mae100 = np.mean([x[f"n{n}"]["mae"] for x in r])
        drift = np.mean([x[f"n{n}"]["mae"] for x, (u, _) in zip(r, product(USERS, seeds)) if u == "matches_preset"])
        grid.append({"lr": lr, "l2": l2, "steps": steps, "mae30": round(float(mae30), 4),
                     "mae100": round(float(mae100), 4), "preset_user_mae100": round(float(drift), 4),
                     "objective": round(float(mae30 + mae100 + drift), 4)})
        print(f"  lr {lr:<4} l2 {l2:<5} steps {steps:<3}  mae@30 {mae30:.4f}  mae@100 {mae100:.4f}  "
              f"preset-user {drift:.4f}", flush=True)
    best = min(grid, key=lambda g: g["objective"])
    return {**DEFAULT_HP, "lr": best["lr"], "l2": best["l2"], "steps": best["steps"]}, grid


# ---------------------------------------------------------------- Kotlin test vectors

def test_vectors(hp: dict) -> dict:
    """Per synthetic user: the example stream in contract JSON, and after each of a few
    checkpoints the expected theta and p_overwhelm on fixed probe vectors."""
    users = []
    for k, user in enumerate(USERS):
        rng = np.random.default_rng(100 + k)
        preset, _ = user_truth(user)
        probes = [scene_sample(s, rng) for s in SCENES]
        examples = stream(user, 40, rng)
        model = PersonalModel(preset, hp)
        checkpoints = [{"after": 0, "theta": model.theta.round(10).tolist(),
                        "probe_scores": [round(model.score(featurize(p)), 10) for p in probes]}]
        for i, (fv, y) in enumerate(examples, 1):
            model.update(featurize(fv), y)
            if i in (1, 5, 20, 40):
                checkpoints.append({"after": i, "theta": model.theta.round(10).tolist(),
                                    "probe_scores": [round(model.score(featurize(p)), 10) for p in probes]})
        users.append({
            "user": user, "preset": preset,
            "examples": [{"features": fv, "label": y, "timestamp": f"2026-11-21T{10 + i // 60:02d}:{i % 60:02d}:00Z"}
                         for i, (fv, y) in enumerate(examples)],
            "probes": [{"scene": s, "features": p, "x": featurize(p).round(10).tolist()}
                       for s, p in zip(SCENES, probes)],
            "checkpoints": checkpoints,
        })
    # Small cap check: feed 6 examples with cap 4 -> only the last 4 count.
    hp_cap = {**hp, "cap": 4}
    rng = np.random.default_rng(999)
    ex = stream("hates_sirens_ok_with_speech", 6, rng)
    m = PersonalModel("autism_friendly", hp_cap)
    for fv, y in ex:
        m.update(featurize(fv), y)
    return {
        "hyperparameters": hp, "features": FEATURES, "presets": PRESETS,
        "tolerance": {"theta": 1e-6, "score": 1e-6},
        "users": users,
        "cap_test": {"hyperparameters": hp_cap, "preset": "autism_friendly",
                     "examples": [{"features": fv, "label": y} for fv, y in ex],
                     "expected_theta": m.theta.round(10).tolist()},
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="skip the grid search")
    args = ap.parse_args()

    if args.quick:
        hp, grid = DEFAULT_HP, []
    else:
        print("grid search over lr / l2 / steps on synthetic users:")
        hp, grid = tune()
    print("chosen:", hp)

    summary = {u: run_user(u, hp, 200, seed=7) for u in USERS}
    for u, r in summary.items():
        print(f"  {u:30s} " + "  ".join(f"{k}: mae {v['mae']:.3f} rank {v['rank_agreement']:.2f}"
                                         for k, v in r.items()))

    EXPORT.mkdir(exist_ok=True)
    (EXPORT / "personal_presets.json").write_text(json.dumps(
        {"features": FEATURES, "presets": PRESETS}, indent=2) + "\n")
    (EXPORT / "personal_model_test.json").write_text(json.dumps(test_vectors(hp), indent=1) + "\n")
    (EXPORT / "personal_model_results.json").write_text(json.dumps({
        "model": "logistic regression, 14 features + bias, L2 toward the preset",
        "hyperparameters": hp,
        "synthetic_users": summary,
        "grid": grid,
    }, indent=2) + "\n")
    print("wrote personal_presets.json, personal_model_test.json, personal_model_results.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# Personal trigger model: spec for the Kotlin port (#8)

Implements `PersonalModel` in `shared/src/commonMain/kotlin/ch/ciuin/shared/personal/`. Pure Kotlin, no ML library. Stays on the phone, never synced.

Prototype and source of truth: [`ml/personal_model.py`](../personal_model.py). If this page and the code disagree, the code wins.

## Inputs: 14 features, each clamped to [0, 1]

Build `x` from a `FeatureVector` in exactly this order (`clamp01(v) = min(1, max(0, v))`):

| i | Feature | Formula |
|---:|---|---|
| 0 | `noise` | `clamp01((db_mean - 35) / 50)` (35 dB → 0, 85 dB → 1) |
| 1 | `peak` | `clamp01((db_peak - db_mean) / 30)` (how impulsive) |
| 2–10 | `sound_<class>` | the 9 `sound_classes` probabilities in `SoundClass` order: speech_crowd, music, traffic, siren, construction, alarm_beep, dog, machinery_hum, other. Missing key → 0 |
| 11 | `light` | `clamp01(log10(max(lux_mean, 0) + 1) / 4)` (~10 000 lux → 1) |
| 12 | `flicker` | `clamp01(flicker_index)` |
| 13 | `crowd` | `clamp01(ln(1 + max(people_count, 0)) / ln(31))` (30 people → 1) |

Parameters: `theta = [bias, w0 … w13]` (15 doubles).

## Score

```
p_overwhelm = sigmoid(theta[0] + sum_i theta[i+1] * x[i])
```

The same function scores live audio windows and a place's mean `measured.features`.

## Presets

`personal_presets.json` holds 4 presets as `{bias, weights{feature: w}}`: `autism_friendly` (the default), `sound_sensitive`, `light_sensitive` and `crowd_sensitive`. Bundle the file. Onboarding sets `prior = theta = preset`.

With `autism_friendly`, synthetic scenes score about: quiet room 0.01, library 0.09, office with fluorescent flicker 0.34, café 0.49, street 0.53, station hall 0.92, siren 0.95.

## Update (each `TrainingExample`)

Hyperparameters: `lr = 1.0`, `l2 = 0.01`, `steps = 60`, `cap = 200`, `max_pos_weight = 3.0`.

1. Append `(x, label)` to the buffer. If it now holds more than `cap` examples, drop the **oldest**.
2. Retrain **from the preset** on the whole buffer. Don't continue from the previous theta: this way theta depends only on the buffer, so Kotlin and Python agree whatever the update history.

```
n_pos = count(label == 1);  n_neg = size - n_pos
pos_w = if (n_pos == 0) 1.0 else clamp(n_neg / n_pos, 1.0, max_pos_weight)
v_i   = if (label_i == 1) pos_w else 1.0
theta = prior
repeat(steps) {
    p_i  = sigmoid(theta · [1, x_i])
    grad = sum_i v_i * (p_i - y_i) * [1, x_i] / sum_i v_i  +  l2 * (theta - prior)
    theta -= lr * grad          // all 15 entries updated simultaneously
}
```

- The L2 pulls toward the **preset**, not zero, so a few taps can't wipe out the prior. The bias is regularised too.
- Cost: 200 × 15 × 60 ≈ 180k multiply-adds per tap. That's microseconds, so run it on every update.
- `reset()`: empty the buffer, set `theta = prior`.
- Persist the buffer (not theta). Theta can be recomputed from it.

## Why these numbers

Tuned on 4 synthetic users (`hates_sirens_ok_with_speech`, `flicker_only`, `dogs_and_crowds`, `matches_preset`) over a grid of `lr` × `l2` × `steps`. The grid and per-user scores are in `personal_model_results.json`.

- Users whose triggers are sounds (`hates_sirens…`, `dogs_and_crowds`): error against their true `p_overwhelm` falls from 0.28–0.30 (preset only) to 0.05–0.06 within 10 examples.
- `flicker_only` adapts slowly: 0.10 → 0.08 only by about 100 examples. Few synthetic scenes are flickery, so few examples carry the signal. In the app, the `light_sensitive` preset is the fix for these users.
- A user who matches the preset drifts a little and then recovers: 0.07 at 10 examples, 0.02 at 200.
- Ranking places correctly (which of two is worse for this user) goes from 0.87–0.88 to 0.89–0.97.
- `max_pos_weight = 3` costs a little on the synthetic users (mean error 0.073 vs 0.055 at 200 examples with no reweighting). Synthetic users always tap when uncomfortable, and real ones won't: automatic "0" samples include moments they just didn't tap. Capping at 3 offsets some of that without inflating scores as much as fully balanced weights would.

## Unit test: `personal_model_test.json`

- `hyperparameters`, `features`, `presets`, and `tolerance` (1e-6 on theta and scores).
- `users[]`: per synthetic user, its `preset`, 40 `examples` (contract `TrainingExample` JSON), 12 `probes` (one per scene, with the `features` JSON and the expected `x`), and `checkpoints` after 0, 1, 5, 20 and 40 updates with the expected `theta` and `probe_scores`.
- `cap_test`: `cap = 4`, 6 examples. Only the last 4 may count, and `expected_theta` checks that.

Suggested tests:
1. `featurize(probe.features) == probe.x`
2. Replay `examples` one by one. At each checkpoint, check `theta` and `score(probe)` for every probe.
3. The cap test.

Regenerate with `python3 ml/personal_model.py` (or `--quick` to skip the grid search).

# Audio ML (B1)

Training side of the audio classifier: datasets, label mapping, class weights, teacher and student models. The on-device side (export, quantisation, Kotlin inference) is B2. Plan: `Claude/todo.md`, section B1.

## Setup

```sh
pip install -r ml/requirements.txt
python3 ml/download_datasets.py   # ~21 GB into data/raw/audio/ (gitignored)
python3 ml/check_labels.py        # validate labels.yaml, regenerate labels.txt
python3 ml/class_weights.py       # recompute loss weights + the table below
```

## Classes

9 classes. The order is the model's output index and must match the `SoundClass` enum in `shared/src/commonMain/kotlin/ch/ciuin/shared/model/SoundClass.kt`; `check_labels.py` fails if they drift. There is no `quiet` class: quiet means the cascade's stage 0 stayed asleep.

The source of truth is [`export/labels.yaml`](export/labels.yaml). It holds the class list, the importance weights and the mapping from every dataset class onto ours.

| # | Class | Main sources |
|---|---|---|
| 0 | `speech_crowd` | US8K children_playing, MUSAN speech, FSD50K Crowd / Chatter / Conversation, DEMAND cafeteria, restaurant, station, square |
| 1 | `music` | US8K street_music, MUSAN music |
| 2 | `traffic` | US8K car_horn, engine_idling; ESC-50 car_horn, engine, train, helicopter, airplane |
| 3 | `siren` | US8K + ESC-50 siren, FSD50K Siren |
| 4 | `construction` | US8K drilling, jackhammer; ESC-50 chainsaw, hand_saw |
| 5 | `alarm_beep` | ESC-50 clock_alarm, FSD50K Alarm (phones, doorbells, buzzers, smoke alarms) |
| 6 | `dog` | US8K dog_bark, ESC-50 dog |
| 7 | `machinery_hum` | US8K air_conditioner, ESC-50 washing_machine, vacuum_cleaner |
| 8 | `other` | everything else (38 ESC-50 classes, US8K gun_shot) |

## Class importance

How much a mistake on each class matters for someone at risk of sensory overload, regardless of how much data the class has. Set in `labels.yaml` under `importance`.

| Importance | Classes | Why |
|---:|---|---|
| **3.0** | `siren`, `alarm_beep` | Alert-critical: sudden and piercing. The cascade must not miss these; missing one is the worst failure |
| **2.0** | `construction`, `speech_crowd` | Common overload triggers: drilling is loud and impulsive, crowd babble is one of the most reported triggers |
| **1.5** | `dog`, `traffic` | Frequent background triggers: barking startles, traffic is constant and loud near roads |
| **1.0** | `music`, `machinery_hum` | Usually tolerable: music is often chosen by the listener, hum is steady and low intensity |
| **0.5** | `other` | Catch-all. It exists so the model isn't forced into a real class; confusing two "other" sounds costs nothing |

## Loss weights

The weight used in training multiplies importance by a data-balancing term, so rare classes get a boost but don't dominate:

```
w_c ∝ importance_c × min( (1 / (K · freq_c)) ^ 0.5 , 5 )
```

`freq_c` is the class's share of training examples and K = 9. Weights are then scaled so the average weight per training example is 1, which keeps the loss scale unchanged.

- Apply them to the **cross-entropy (hard-label) term only**. Don't weight the distillation KL term: the student should copy the teacher's whole distribution.
- MUSAN and DEMAND recordings are minutes long, so they count as 5 s windows (MUSAN capped at 4 per file). The training sampler must draw the same way, or the weights won't match.
- Weighting shifts the model's probabilities toward the boosted classes. That is intended for `siren` and `alarm_beep` (recall over precision), but re-check the cascade thresholds after training.

<!-- class-weights:start -->
**Estimated** from published dataset sizes; `ml/class_weights.py` replaces this table with exact counts once all datasets are downloaded.

| Class | Train examples | Share | Importance | Loss weight |
|---|---:|---:|---:|---:|
| `speech_crowd` | 2,667 | 20.1% | 2.0 | **0.91** |
| `music` | 2,902 | 21.9% | 1.0 | **0.43** |
| `traffic` | 1,250 | 9.4% | 1.5 | **0.99** |
| `siren` | 824 | 6.2% | 3.0 | **2.44** |
| `construction` | 1,628 | 12.3% | 2.0 | **1.16** |
| `alarm_beep` | 1,136 | 8.6% | 3.0 | **2.08** |
| `dog` | 814 | 6.1% | 1.5 | **1.23** |
| `machinery_hum` | 838 | 6.3% | 1.0 | **0.81** |
| `other` | 1,207 | 9.1% | 0.5 | **0.34** |
<!-- class-weights:end -->

## Splits

Never split within a dataset fold.

| | Train | Validation | Test |
|---|---|---|---|
| ESC-50 | folds 1–3 | fold 4 | fold 5 |
| UrbanSound8K | folds 1–7 | fold 8 | folds 9–10 |
| FSD50K | dev, `split=train` | dev, `split=val` | eval |
| MUSAN | ~80% of files (fixed seed) | ~10% | ~10% |
| DEMAND | first ~80% of each recording | next ~10% | last ~10% |

DEMAND has 16 near-identical mic channels per recording, so it is split by time, not by channel; otherwise the same moment leaks into train and test.

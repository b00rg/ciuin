# Sensory Map: dev todo

**What we're building:** a Kotlin Multiplatform app (Android + Desktop) that maps how loud, bright, flickery and crowded places are. Measurements run on-device with small distilled models; no audio or images leave the phone. Each user's map is personalised by a model that learns their own triggers, and an always-on low-power listener warns them before they're overwhelmed and points them to the nearest quiet place.

**Hard requirements:** KMP (JetBrains challenge). Everything reused (pretrained models, datasets, libraries, prior code) must be listed in `REUSED.md`.

## Team

| Who | Role | Owns | Then |
|---|---|---|---|
| **Siddhi** | A: Data and backend | Place data, noise maps, API, aggregation, quiet-place lookup | Joins Christine on the app once the backend is up (~19:00) |
| **Emma** | B1: Audio ML training | Audio datasets, teacher model, distilled student models, cascade thresholds, personal model design | Hands models to Paramveer at fixed points (see handoffs) |
| **Paramveer** | B2: On-device ML | Export, quantisation, fitting variants to different devices, on-device inference, camera module, personal model in Kotlin | Integrates into Christine's app |
| **Christine** | C: KMP app | Compose Multiplatform app, map, background listener, alerts, sync | Siddhi joins from ~19:00 |

**Phase 2 (from Sun 04:00): everyone tests.** See the testing section at the bottom.

## Dev checkpoints (Lausanne time)

| When | Checkpoint | Owner |
|---|---|---|
| Sat 13:00 | Contracts in section 0 agreed | All |
| Sat 13:30 | KMP project scaffolded (Android + Desktop), builds on everyone's machine | Christine |
| Sat 15:00 | Data go/no-go: OSM counts known, datasets downloaded, model export format agreed | Siddhi, Emma, Paramveer |
| Sat 17:00 | **Handoff 1:** Emma → Paramveer, fine-tuned teacher + first student | Emma |
| Sat 19:00 | **MVP:** phone measures a place, the measurement appears on the map. Backend done; Siddhi moves to the app | All |
| Sat 23:00 | **Handoff 2:** Emma → Paramveer, all student sizes + `ml/results.json` (accuracy side) | Emma |
| Sun 00:00 | Cascade listener running on the phone (stage 0 + stage 1) | Paramveer, Christine |
| Sun 02:00 | Variants running on 2+ devices with measured latency, heatmap working | Paramveer, Christine, Siddhi |
| Sun 04:00 | Personal model learning from taps, map re-colours per user, alert + quiet-place route working. **Phase 2 starts** | All |
| Sun 08:00 | **Feature freeze.** Bug fixes only | All |
| Sun 10:00 | `REUSED.md` complete | Siddhi |
| Sun 11:00 | Final build tagged and submitted (deadline 12:00) | Christine |

---

## 0. Shared contracts (agree by 13:00, then don't change without telling everyone)

### Feature vector (shared by measurements, the personal model and scoring)
Defined once in `commonMain` as a Kotlin data class:
```json
{
  "db_mean": 62.4, "db_peak": 78.1,
  "sound_classes": {"speech": 0.71, "music": 0.12, "traffic": 0.08, "siren": 0.0, "construction": 0.0, "alarm": 0.0, "dog": 0.0, "quiet": 0.09},
  "lux_mean": 340, "flicker_index": 0.04,
  "people_count": 7
}
```

### Measurement (phone → backend)
Only aggregates. **Never** raw audio, images, embeddings, trigger taps or user identity.
```json
{
  "place_id": "osm:node/123456",
  "lat": 46.5191, "lon": 6.5668,
  "timestamp": "2026-11-21T14:05:00Z",
  "duration_s": 30,
  "features": { "...": "feature vector above" },
  "flicker_hz": 100,
  "model_variant": "audio-student-s",
  "device": "pixel-7"
}
```

### Place (backend → app)
`measured` carries the **mean feature vector** so the personal model can re-score every place on the phone.
```json
{
  "place_id": "osm:node/123456", "name": "Café X", "lat": 46.5191, "lon": 6.5668,
  "category": "cafe",
  "wheelchair": "yes", "sensory_friendly": null, "quiet_room": false,
  "baseline_noise_db": 58,
  "measured": {"n": 4, "features": { "...": "mean feature vector" }},
  "score": {"noise": 0.4, "light": 0.8, "flicker": 0.9, "crowd": 0.6, "access": 1.0}
}
```

### Model handoff (Emma → Paramveer)
```
ml/export/
  audio-full.{tflite,onnx}
  audio-teacher.onnx   # reference only, too big for phones
  audio-student-l.{tflite,onnx}
  audio-student-m.{tflite,onnx}
  audio-student-s.{tflite,onnx}
  labels.yaml          # index → our 8 classes
  preprocess.md        # sample rate, window length, mel params, normalisation (must match exactly on-device)
  results.json         # per model (teacher + each student): params, size_mb, accuracy, macro_f1  (Paramveer adds latency per device)
  test_clips/          # 10 clips + expected top class, to check the on-device port gives the same answers
```

### Personal trigger model (stays on the phone, never synced)
- Training example: `{"features": {...}, "label": 1 | 0, "timestamp": "..."}`
  - `1` = user tapped "Too much"
  - `0` = sampled automatically every few minutes when the user didn't tap, plus an explicit "This is fine" button
- Model: logistic regression over the feature vector, pure Kotlin in `commonMain`, initialised from the chosen preset's weights so it works with zero taps
- Output: `p_overwhelm` in [0, 1] for any feature vector (live audio or a place's mean measurement)

### Cascade listener states
| Stage | Runs | Cost | Moves up when |
|---|---|---|---|
| 0 | Every 1 s: dB level + spectral flux, no model | ~free | dB or flux jumps past a threshold |
| 1 | Smallest variant that fits the device, on a 1 s window | low | `p_overwhelm` > 0.5, or classifier is uncertain |
| 2 | Larger variant + camera features if the app is open | higher | `p_overwhelm` > threshold → **alert** |
Log time spent in each stage; this is the battery/frugality evidence.

### API (Siddhi owns)
- `GET /places?bbox=minLon,minLat,maxLon,maxLat` → list of Place
- `POST /measurements` → stores one Measurement, recomputes that place's aggregates
- `GET /heatmap?bbox=...&dim=noise` → GeoJSON grid for the heatmap layer
- `GET /places/nearby?lat=..&lon=..&radius=300` → places sorted by distance, with mean features
- `GET /stats` → places mapped, measurements taken, `sensory_friendly` tags found

---

## A. Siddhi: data and backend (until ~19:00)

### Gathering
- [ ] Overpass query for the Lausanne bbox: `amenity`, `shop`, `leisure`, `tourism` POIs with `name` → `data/raw/osm_lausanne.json`
- [ ] Count tags: `wheelchair=*`, `sensory_friendly=*`, `amenity=quiet_room`; post counts in chat by 14:00
- [ ] Register for an accessibility.cloud app token; pull places for the same bbox → `data/raw/a11ycloud_lausanne.json`
- [ ] Download sonBASE daytime road noise (layer `ch.bafu.laerm-strassenlaerm_tag`) and railway noise for Lausanne → `data/raw/sonbase/`
- [ ] Download opentransportdata.swiss accessibility data (stops, toilets, platforms) → `data/raw/prm/`
- [ ] Optional: EEA END noise grid for Dublin

### Transformation
- [ ] Normalise OSM + accessibility.cloud into the Place schema; dedupe on name + distance < 25 m
- [ ] Reproject sonBASE to WGS84; sample noise at each place → `baseline_noise_db`
- [ ] Build a 50 m grid over Lausanne with baseline noise for the heatmap layer
- [ ] Seed unmeasured places with a prior feature vector from category + `baseline_noise_db` (so the personal model can score them)
- [ ] Write `data/processed/places_lausanne.geojson` and commit it (app must work offline)

### Backend
- [ ] FastAPI + SQLite (or plain JSON files): the endpoints above
- [ ] Aggregation: mean feature vector per place; generic per-dimension score in [0, 1] for users with no personal model
- [ ] Seed script that loads `places_lausanne.geojson`
- [ ] Run on a laptop reachable over a phone hotspot (eduroam may block device-to-device traffic)

### After ~19:00: help Christine
- [ ] Take the app screens Christine hands over (likely Place detail, Privacy, Model screen UI)
- [ ] Ktor client against your own API, plus the offline fallback using the bundled GeoJSON
- [ ] Own `REUSED.md`: collect entries from everyone as they go

---

## B1. Emma: audio ML training

### Data
- [ ] **Start the UrbanSound8K download now** (6 GB, Zenodo record 1203745)
- [ ] Download ESC-50 (~600 MB) and SONYC-UST (evaluation only)
- [ ] Map ESC-50 and UrbanSound8K labels onto our 8 classes (speech/crowd, music, traffic, siren, construction, alarm/beeping, dog, quiet) → `ml/labels.yaml`
- [ ] Respect predefined folds (ESC-50 5 folds, US8K 10 folds) to avoid train/test leakage

### Classifier (→ Handoff 1 at 17:00)
- [ ] **Teacher:** fine-tune a strong pretrained audio model (e.g. PANNs CNN14 or YAMNet with a bigger head) on our 8 classes. Size doesn't matter; accuracy does
- [ ] Record baseline accuracy and macro-F1
- [ ] Write `preprocess.md` so Paramveer can reproduce the input pipeline exactly
- [ ] Train a first small student quickly (see below) and export it + 10 test clips with expected outputs, so Paramveer can start porting

### Distillation (→ Handoff 2 at 23:00)
- [ ] Precompute the teacher's soft labels (logits) on all training clips once and cache them, so student training is fast
- [ ] Students: 3 small CNNs on log-mel input, same architecture at different widths (S / M / L, e.g. ~50k / ~200k / ~1M params). Use ops that export cleanly to TFLite (conv, depthwise conv, pooling, dense)
- [ ] Loss: KL divergence to the teacher's softened outputs (temperature T ≈ 2–4) + cross-entropy on true labels; tune the mix (α) on a validation fold
- [ ] Baseline: train the S student on hard labels only, to show what distillation adds
- [ ] Augment with mixup / time shifts / background noise from the quiet clips so students see more than the teacher's training set
- [ ] Per model: params, size, accuracy, macro-F1 → `results.json` (teacher, each student, and the no-distillation baseline)
- [ ] Evaluate on SONYC-UST and record the drop

### Cascade and personal model (design side)
- [ ] Stage 0 thresholds (dB + spectral flux): tune on venue recordings so it rarely wakes in calm places
- [ ] Stage 1 → 2 rule: confidence margin or entropy cut-off
- [ ] Personal model spec for Paramveer: learning rate, L2 strength, positive-class weight, preset prior weights, example cap
- [ ] Prototype the personal model in Python first on synthetic users (hates sirens, tolerates speech) to pick those numbers

---

## B2. Paramveer: on-device ML and device fitting

### Export and port (from Handoff 1)
- [ ] Agree format with Christine by 15:00: TFLite/LiteRT on Android, ONNX on Desktop
- [ ] Port preprocessing to Kotlin (mel spectrogram etc.) following `preprocess.md`
- [ ] Check parity: run the 10 test clips on-device; top class must match Emma's expected output
- [ ] A-weighted dB and spectral flux in Kotlin in `commonMain` (shared with stage 0)

### Fitting variants to devices (from Handoff 2)
- [ ] int8 quantisation of the students; re-check parity
- [ ] Benchmark every variant on every phone/laptop the team has: latency, memory, model load time → add per-device rows to `results.json`
- [ ] Device profile rule: pick the stage-1 and stage-2 variants per device from RAM / CPU cores / benchmark result (and drop a size when battery is low)
- [ ] Hardware acceleration where available (NNAPI / GPU delegate on Android), with CPU fallback
- [ ] Expose a single `AudioClassifier` interface in `commonMain` with platform implementations, so Christine just calls `classify(window)`

### Camera module (a function taking frames, returning numbers)
- [ ] Brightness: mean luminance per frame → approximate lux
- [ ] Flicker: luminance time series at max frame rate → FFT → dominant frequency + flicker index
- [ ] Crowd: smallest pretrained COCO person detector that works (class 0), counts only, frames discarded immediately; benchmark it like the audio variants

### Runtime
- [ ] Cascade state machine (stages 0–2) in `commonMain`, using Emma's thresholds; logs per-stage time and inference count
- [ ] Personal model in pure Kotlin in `commonMain` from Emma's spec, exposing `score(features): Double`
- [ ] Unit test the Kotlin personal model against Emma's Python prototype on the same synthetic data

---

## C. Christine: KMP app (Siddhi joins ~19:00)

### Project
- [ ] **By 13:30:** Compose Multiplatform project with **Android + Desktop (JVM)** targets
- [ ] `commonMain`: UI, DTOs, Ktor API client, feature vector, presets, plus Paramveer's classifier interface, cascade and personal model
- [ ] `androidMain`: mic + camera capture, foreground service, notifications, vibration
- [ ] `desktopMain`: map for the projector; optional ONNX inference on a recorded clip

### Screens
- [ ] Onboarding (first launch): short explanation + choose a preset
- [ ] Map: OSM tiles, markers coloured by **this user's** `p_overwhelm`, heatmap toggle per dimension, gap badge from `/stats`
- [ ] Place detail: features, accessibility tags, measurement count, "Measure here"
- [ ] Measure flow: 30 s capture → dB + classifier + camera module → show → confirm → POST aggregates
- [ ] "Too much" and "This is fine" buttons, reachable from the map and from the alert notification
- [ ] Model screen: variants and per-device latency from `results.json`, which variant this device picked, switch live, cascade stage stats, personal model example count + reset
- [ ] Privacy screen: what's measured, what's sent, what never leaves the phone (trigger taps never do)

### Background listener and alerts
- [ ] Android foreground service running Paramveer's cascade (mic permission + persistent notification)
- [ ] On alert: vibrate + notification "Above your limit — [place] is quiet, 40 m"
- [ ] Quiet-place pick: call `/places/nearby`, score each with the personal model, choose the lowest `p_overwhelm` within 300 m (bundled data offline)
- [ ] Route: straight line + distance and bearing on the map; walking directions only if time
- [ ] Debounce alerts (max one per 2 minutes) and add a snooze

### Sync and fallbacks
- [ ] Desktop map polls `/places` every 2 s so a phone measurement appears live
- [ ] Bundle `places_lausanne.geojson` for offline use
- [ ] Debug option: replay a recorded measurement / audio clip through the full pipeline

---

## Phase 2: everyone tests (from Sun 04:00)

Split by area; whoever finishes first picks up the next unchecked item.

| Area | Lead | Tests |
|---|---|---|
| Field measurements | Siddhi | Measure 5+ real places (quiet room, hacking hall, cafeteria, corridor, outside by a road); note a human rating for each; check the ranking makes sense |
| Alerts and cascade | Emma | Play siren/alarm/drilling clips from the dataset test folds: stage 0 wakes → alert fires within N s; log N. Check it stays asleep in the quiet room for 10 min |
| Devices | Paramveer | Full flow on every phone we have; confirm each picks the right variant; note latency and battery drain over 30 min |
| App and sync | Christine | Fresh install + onboarding; phone measurement appears on the desktop map; offline mode with wifi off; hotspot setup on the demo laptop |
| Personal model | Everyone | Each person uses a different preset, taps for 10 min, checks their map differs from the others' |

- [ ] Log every bug in one shared list with owner + severity; only demo-breaking bugs get fixed after 08:00
- [ ] Record venue clips (10–15 s each) early in Phase 2 so the replay fallback has real audio

---

## Technical risks and fallbacks

| Risk | Fallback |
|---|---|
| On-device inference in KMP takes too long | Run inference on recorded clips in the Desktop target; keep the phone for dB + camera features |
| Preprocessing mismatch between Python and Kotlin | Parity test with the 10 test clips catches it early; fix before Handoff 2 |
| Foreground service / background mic restrictions on a test phone | Run the cascade only while the app is open |
| Personal model unstable with few taps | Stronger preset prior; fewer gradient steps; clamp weights |
| eduroam blocks phone ↔ laptop | Phone hotspot; bundled offline data; scoring on-device |
| Small student accuracy too low | Ship M as the default; S only for stage 0→1 on weak devices; the size/accuracy curve is still the story |
| Few OSM places tagged | Category + baseline-noise priors fill the map |
| Flicker FFT limited by camera frame rate | Report flicker index only |

## Data sources

- OpenStreetMap via Overpass API: https://overpass-api.de/api/interpreter · tag usage: https://taginfo.openstreetmap.org
- `sensory_friendly` tag: https://wiki.openstreetmap.org/wiki/Proposal:Sensory
- accessibility.cloud: https://www.accessibility.cloud
- sonBASE noise (FOEN): https://www.bafu.admin.ch/en/noise-geodata · map: https://map.geo.admin.ch/?layers=ch.bafu.laerm-strassenlaerm_tag
- Swiss public transport accessibility: https://opentransportdata.swiss/en/cookbook/accessibility/
- EEA noise data: https://www.eea.europa.eu/data-and-maps/data/quiet-areas-in-europe-2
- ESC-50: https://github.com/karolpiczak/ESC-50
- UrbanSound8K: https://zenodo.org/record/1203745
- SONYC-UST: https://wp.nyu.edu/sonyc/

Licences: UrbanSound8K is CC BY-NC 4.0 and sonBASE allows non-commercial use; fine for the hackathon.
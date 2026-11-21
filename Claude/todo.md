# Sensory Map: dev todo

**What we're building:** a Kotlin Multiplatform app (Android + Desktop) that maps how loud, bright, flickery and crowded places are. Measurements run on-device with small pruned models; no audio or images leave the phone. Each user's map is personalised by a model that learns their own triggers, and an always-on low-power listener warns them before they're overwhelmed and points them to the nearest quiet place.

**Hard requirements:** KMP (JetBrains challenge). Everything reused (pretrained models, datasets, libraries, prior code) must be listed in `REUSED.md`.

## Dev checkpoints (Lausanne time)

| When | Checkpoint |
|---|---|
| Sat 13:00 | Contracts in section 0 agreed |
| Sat 13:30 | KMP project scaffolded (Android + Desktop), builds on everyone's machine |
| Sat 15:00 | Data go/no-go: OSM counts known, datasets downloaded, model export format agreed |
| Sat 19:00 | **MVP:** phone measures a place, the measurement appears on the map |
| Sun 00:00 | Cascade listener running on the phone (stage 0 + stage 1) |
| Sun 02:00 | Pruned variants on the phone, heatmap working, 3+ real places measured |
| Sun 04:00 | Personal trigger model learning from taps, map re-colours per user, alert + quiet-place route working |
| Sun 06:00 | Full flow works end to end on the demo setup (phone + laptop on hotspot) |
| Sun 08:00 | **Feature freeze.** Bug fixes only |
| Sun 10:00 | `REUSED.md` complete |
| Sun 11:00 | Final build tagged and submitted (deadline 12:00) |

---

## Roles

| Person | Role | Owns |
|---|---|---|
| **A** (Paramveer?) | Data and backend | Place data, noise maps, API, scoring, quiet-place lookup |
| **B** | Audio ML | Sound classifier, pruning sweep, cascade logic, personal trigger model |
| **C** | KMP app | Compose Multiplatform app, map, background listener, alerts, inference integration |
| **D** | Vision sensing and testing | Brightness/flicker/crowd module, test clips, field measurements, `REUSED.md` |

Fill in names, then delete the `?`.

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
  "model_variant": "audio-pruned-50",
  "device": "android"
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

### Personal trigger model (stays on the phone, never synced)
- Training example: `{"features": {...}, "label": 1 | 0, "timestamp": "..."}`
  - `1` = user tapped "Too much"
  - `0` = sampled automatically every few minutes when the user didn't tap, plus an explicit "This is fine" button
- Model: logistic regression over the feature vector, written in pure Kotlin in `commonMain`, initialised from the chosen preset's weights so it works with zero taps
- Output: `p_overwhelm` in [0, 1] for any feature vector (live audio or a place's mean measurement)

### Cascade listener states
| Stage | Runs | Cost | Moves up when |
|---|---|---|---|
| 0 | Every 1 s: dB level + spectral flux, no model | ~free | dB or flux jumps past a threshold |
| 1 | Smallest pruned classifier on a 1 s window | low | `p_overwhelm` > 0.5, or classifier is uncertain |
| 2 | Larger variant + camera features if the app is open | higher | `p_overwhelm` > threshold → **alert** |
Log time spent in each stage; this is the battery/frugality evidence.

### API (A owns)
- `GET /places?bbox=minLon,minLat,maxLon,maxLat` → list of Place
- `POST /measurements` → stores one Measurement, recomputes that place's aggregates
- `GET /heatmap?bbox=...&dim=noise` → GeoJSON grid for the heatmap layer
- `GET /places/nearby?lat=..&lon=..&radius=300` → places sorted by distance, with mean features (the phone picks the quietest for *this* user)
- `GET /stats` → places mapped, measurements taken, `sensory_friendly` tags found

---

## A. Data and backend

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
- [ ] Seed places with no measurements using a prior feature vector derived from category + `baseline_noise_db` (so the personal model can score them too)
- [ ] Write `data/processed/places_lausanne.geojson` and commit it (app must work offline)

### Backend
- [ ] FastAPI + SQLite (or plain JSON files): the endpoints above
- [ ] Aggregation: mean feature vector per place; generic per-dimension score in [0, 1] for users with no personal model
- [ ] Seed script that loads `places_lausanne.geojson`
- [ ] Run on a laptop reachable over a phone hotspot (eduroam may block device-to-device traffic)

---

## B. Audio ML

### Data
- [ ] **Start the UrbanSound8K download now** (6 GB, Zenodo record 1203745)
- [ ] Download ESC-50 (~600 MB) and SONYC-UST (evaluation only)
- [ ] Map ESC-50 and UrbanSound8K labels onto our 8 classes (speech/crowd, music, traffic, siren, construction, alarm/beeping, dog, quiet) → `ml/labels.yaml`
- [ ] Respect predefined folds (ESC-50 5 folds, US8K 10 folds) to avoid train/test leakage

### Classifier
- [ ] Start from a small pretrained audio backbone (YAMNet or a PANNs MobileNet variant); fine-tune the head on our 8 classes
- [ ] Record baseline accuracy and macro-F1
- [ ] A-weighted dB from raw samples, written in Kotlin in `commonMain` (shared with C)
- [ ] Spectral flux for stage 0, also Kotlin in `commonMain`

### Pruning and variants
- [ ] Structured pruning sweep at 0 / 30 / 50 / 70 / 90%, fine-tune after each; int8 quantisation on the best
- [ ] Per variant: params, file size, accuracy, macro-F1, latency on a real phone → `ml/results.json` (read by the app's Model screen)
- [ ] Ship 2–3 variants in the app: smallest for stage 1, larger for stage 2
- [ ] Export: TFLite/LiteRT for Android, ONNX for Desktop if needed; agree with C by 15:00
- [ ] Evaluate the shipped variants on SONYC-UST and record the drop

### Cascade
- [ ] Stage 0 thresholds: tune on recordings of the venue (quiet room, hall, cafeteria) so it wakes up rarely in calm places
- [ ] Stage 1 → 2 rule: confidence margin or entropy of the class probabilities
- [ ] Log per-stage time and inference count; compute "% of time with no model running" for the Model screen

### Personal trigger model (pure Kotlin, `commonMain`)
- [ ] Logistic regression with L2 regularisation, trained by a few gradient steps on-device after each new example
- [ ] Preset priors: autism-friendly, wheelchair, low-light → initial weights
- [ ] Class imbalance: weight positive taps higher; cap stored examples (e.g. last 200)
- [ ] Unit test: simulate a user who hates sirens but tolerates speech; after ~10 taps `p_overwhelm` should rank siren-heavy places above chatty ones
- [ ] Expose `score(features): Double` used by both the live alert and the map

---

## C. KMP app

Keep as much as possible in `commonMain`.

### Project
- [ ] **By 13:30:** Compose Multiplatform project with **Android + Desktop (JVM)** targets
- [ ] `commonMain`: UI, DTOs, Ktor API client, feature vector, dB/flux, personal model, scoring, presets
- [ ] `androidMain`: mic + camera capture, TFLite/LiteRT inference, foreground service, notifications, vibration
- [ ] `desktopMain`: map for the projector; optional ONNX inference on a recorded clip

### Screens
- [ ] Onboarding (first launch): short explanation + choose a preset
- [ ] Map: OSM tiles, markers coloured by **this user's** `p_overwhelm`, heatmap toggle per dimension, gap badge from `/stats`
- [ ] Place detail: features, accessibility tags, measurement count, "Measure here"
- [ ] Measure flow: 30 s capture → dB + classifier + D's camera module → show → confirm → POST aggregates
- [ ] "Too much" and "This is fine" buttons, reachable from the map and from the alert notification
- [ ] Model screen: variants from `ml/results.json`, switch live, measured latency, cascade stage stats, personal model example count + reset button
- [ ] Privacy screen: what's measured, what's sent, what never leaves the phone (trigger taps never do)

### Background listener and alerts
- [ ] Android foreground service running the cascade (needs mic permission + persistent notification)
- [ ] On alert: vibrate + notification "Above your limit — [place] is quiet, 40 m"
- [ ] Quiet-place pick: call `/places/nearby`, score each with the personal model, choose the lowest `p_overwhelm` within 300 m (fall back to bundled data offline)
- [ ] Route: straight line + distance and bearing on the map is enough; walking directions only if time
- [ ] Debounce alerts (no more than one per 2 minutes) and add a snooze

### Sync and fallbacks
- [ ] Desktop map polls `/places` every 2 s so a phone measurement appears live
- [ ] Bundle `places_lausanne.geojson` for offline use
- [ ] Debug option: replay a recorded measurement / audio clip through the full pipeline

---

## D. Vision sensing and testing

### Camera module (a function taking frames, returning numbers; hand to C)
- [ ] Brightness: mean luminance per frame → approximate lux (rough calibration against a light meter app)
- [ ] Flicker: luminance time series at max frame rate → FFT → dominant frequency + flicker index; test under LED and fluorescent lights in the building
- [ ] Crowd: smallest pretrained COCO person detector that works (class 0), counts only, frames discarded immediately; record size + latency
- [ ] Unit-test each on short clips recorded at the venue

### Test assets
- [ ] Record 10–15 s clips at the venue: quiet room, hacking hall, cafeteria, corridor, outside by a road
- [ ] Build trigger test clips (siren, alarm, drilling, crowd) from ESC-50/UrbanSound8K test folds to drive the cascade and alerts
- [ ] Scripted test: play siren clip → stage 0 wakes → stage 1/2 → alert fires within N seconds; log N

### Field measurements
- [ ] Measure 5+ real places with the app; note a human rating (quiet/OK/overwhelming) for each
- [ ] Check the ranking makes sense; report problems to A and B fast

### `REUSED.md` (required)
- [ ] Track as we go: pretrained models (audio backbone, COCO detector), datasets, map tiles, libraries (with versions), any prior code or techniques
- [ ] Second section: what we built this weekend

---

## Technical risks and fallbacks

| Risk | Fallback |
|---|---|
| On-device inference in KMP takes too long | Run inference on recorded clips in the Desktop target; keep the phone for dB + camera features |
| Foreground service / background mic restrictions on the test phone | Run the cascade only while the app is open |
| Personal model unstable with few taps | Stronger prior from the preset; fewer gradient steps; clamp weights |
| eduroam blocks phone ↔ laptop | Phone hotspot; bundled offline data; scoring on-device |
| Pruned accuracy collapses | Ship the 30% or 50% variant |
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
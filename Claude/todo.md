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
  "sound_classes": {"speech_crowd": 0.71, "music": 0.12, "traffic": 0.08, "siren": 0.0, "construction": 0.0, "alarm_beep": 0.0, "dog": 0.0, "machinery_hum": 0.03, "other": 0.06},
  "lux_mean": 340, "flicker_index": 0.04,
  "people_count": 7
}
```
`null` means a reading was unavailable. A measured zero is a value. When classification is available, `sound_classes` includes all **9 classes** from B1 in model output order (`SoundClass` in `commonMain`). The foreground flow averages class scores across captured windows and uses scores of zero for windows the audio pipeline identified as quiet.

### Measurement (phone → backend)
Only aggregates. **Never** raw audio, images, embeddings, trigger taps or user identity.
```json
{
  "place_id": "osm:node/123456",
  "lat": 46.5191, "lon": 6.5668,
  "timestamp": "2026-11-21T14:05:00Z",
  "duration_s": 30,
  "features": { "...": "feature vector above" },
  "flicker_hz": 12.0,
  "model_variant": "audio-student-s",
  "device": "pixel-7",
  "measurement_id": "bd05fd21-2825-4c37-a940-9fabcab5fe0a"
}
```

### Place (backend → app)
`measured` carries the **mean feature vector** so the personal model can re-score every place on the phone. Each mean uses only readings available for that field. Existing numeric readings are preserved.
`baseline_noise_db` (day, 06–22) / `baseline_noise_night_db` (night, 22–06) / `baseline_source` are null outside regions with noise data. They are **outdoor** road+rail noise around the place, not the room inside. `baseline_source` is `"sonbase"` (Switzerland), `"eea"` (EU END maps) or null.
`osm_tags` (added 21 Nov, optional, default `{}`): selected raw OSM tags. Lausanne coverage: `opening_hours` 53%, `website` 39%, `cuisine` 14%, `outdoor_seating` 11%, `indoor_seating` 10%, `brand` 10%, `level` 6%, `smoking` 2%, `toilets:wheelchair` 1%, `capacity`, `air_conditioning`, `live_music` (rare). Absent key = unknown.
`open_now` (added 21 Nov, optional): computed by the backend from `osm_tags.opening_hours` at request time (Europe/Zurich), `true`/`false`, or `null` = unknown (no tag, or e.g. "by appointment"). ~97% of Lausanne's opening hours are understood. Not in the bundled offline file.
`prior` (added 21 Nov, optional): an **estimated** FeatureVector for every place (category group + outdoor noise through the façade + `capacity`; `data/scripts/priors.py`), so the personal model can score places nobody has measured. Never show it as measured data: use `measured` when present, otherwise `prior` (or blend, trusting `measured` more as `n` grows). Values are first guesses: **Emma to tune** in `priors.py`.
`measured` and `score` are null until the place has at least one measurement. `score` is the generic per-dimension score for users with no personal model: **1 = calmest / most accessible, 0 = worst** (heuristics and thresholds in `backend/aggregate.py`; tune in Phase 2).
```json
{
  "place_id": "osm:node/123456", "name": "Café X", "lat": 46.5191, "lon": 6.5668,
  "category": "cafe",
  "wheelchair": "yes", "sensory_friendly": null, "quiet_room": false,
  "baseline_noise_db": 58, "baseline_noise_night_db": 47.5, "baseline_source": "sonbase",
  "osm_tags": {"opening_hours": "Mo-Sa 07:00-19:00", "outdoor_seating": "yes", "cuisine": "coffee_shop"},
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
  labels.yaml          # index → our 9 classes (order must match SoundClass in commonMain)
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
- `GET /places?bbox=minLon,minLat,maxLon,maxLat[&at=ISO-8601]` → list of Place. `open_now` is evaluated at `at` (default now)
- `POST /measurements` → stores one Measurement, recomputes that place's aggregates. **201** `{"stored": true, "place": Place | null}` (`place` is the updated place; null if `place_id` isn't a known place: the measurement is still stored and counted). **422** on any field not in the contract (privacy guard) or if every reading is missing or a present `sound_classes` map lacks the exact nine labels. Retry a capture with the same optional `measurement_id` and payload. Reusing that ID with different data returns **409**.
- `GET /heatmap?bbox=...&dim=noise&period=day` → GeoJSON FeatureCollection of 50 m cells: Point features at cell centres (draw 50 m cells), top-level `"res_m": 50` (also `cell_m`). Every feature has `properties.value` and `properties.source` (`"sonbase"` | `"measured"`, plus `n` for measured).
  - `dim=noise`: sonBASE outdoor noise in dB(A), `period=day` (06–22) or `night` (22–06), Lausanne only (~10.8k cells). Road and rail kept separate so the app can show the road-vs-rail breakdown (Christine): `{"value": 62.9, "road_db": 62.1, "rail_db": 55.0, "source": "sonbase"}`, `value` = energetic sum, `null` where a source has no data.
  - `dim=light | flicker | crowd`: mean of the **measurements** in each cell (lux, flicker index, people count). Empty until phones measure; works anywhere
- `GET /places/nearby?lat=..&lon=..&radius=300[&at=…][&open_only=true]` → places sorted by distance, with mean features (radius in m, max 5000). `open_only` drops places known to be closed (unknown hours stay)
- `GET /stats` → `{"places_mapped": n, "measurements_taken": n, "sensory_friendly_tags": n}`
- `GET /health` → `{"status": "ok"}`. Interactive API docs at `/docs`
- `GET /meta` → attribution the app **must show** (`attribution[].required`: "© OpenStreetMap contributors", sonBASE © FOEN), data sources, counts and freshness
- `GET /coverage?res_deg=2` → coarse grid for the 3D globe (below). `res_deg=2` global, `res_deg=1` Europe. Never per-place

### Scope and coverage (3D globe): three data tiers + demo city
| Tier | Granularity | What's in it | Served by |
|---|---|---|---|
| **Global** | 2° grid cells | `wheelchair=*` / `sensory_friendly=*` counts, measurement counts | `/coverage?res_deg=2` |
| **Europe** | 1° grid cells (source data is 1°, finer would be fake detail) | same counts (EEA noise coverage not added yet) | `/coverage?res_deg=1` |
| **Switzerland** (minimum per-place scope) | Every named POI (est. 150–300k) | Place schema, OSM tags, `baseline_noise_db` from sonBASE (covers all of CH) | `/places`, `/places/nearby` |
| **Lausanne** (demo city) | Per place + 50 m grid | Everything above + 50 m noise heatmap + live measurements | `/heatmap` |

Phone measurements and the personal model work anywhere: a measurement outside Switzerland is still stored and shows up in the coverage counts.
`sensory_friendly` is a proposal tag: global but sparse; shown where present, never promised.
**Build order:** Lausanne end to end first, then widen the same pipeline to all of Switzerland, then the Europe/global grids. A slip still leaves a working demo.
Storage: SQLite (stdlib `sqlite3`, R-Tree index for bbox / nearby) for Swiss places + measurements; precomputed grids served as JSON files. No 50 m grid for all of CH (~16M cells).

Pitch line: *measurements work anywhere, map data covers the world → Europe → every place in Switzerland, Lausanne in full detail.*

`GET /coverage?res_deg=2` response (`lat`/`lon` = cell centre; empty cells omitted):
```json
{
  "res_deg": 2.0,
  "cells": [
    {"lat": 47.0, "lon": 7.0, "wheelchair": 18342, "sensory_friendly": 3, "noise_source": "sonbase", "measurements": 12}
  ]
}
```
3D globe: drawn on a Compose Canvas in `commonMain` (dotted land from Natural Earth, data as radial spikes, drag to spin, fly-to → hands off to the flat map). No native 3D engine, no WebView.

---

## A. Siddhi: data and backend (until ~19:00)

### Gathering
- [x] Overpass query for the Lausanne bbox: `amenity`, `shop`, `leisure`, `tourism` POIs with `name` → `data/raw/osm_lausanne.json`
- [x] Count tags: `wheelchair=*`, `sensory_friendly=*`, `amenity=quiet_room` for Lausanne / Switzerland / Europe / world (`data/scripts/count_tags.py` → `data/processed/tag_counts.json`); post counts in chat by 14:00
- [ ] **Stretch:** Switzerland POIs: Geofabrik `switzerland-latest.osm.pbf` filtered locally (more reliable than one huge Overpass query) → `data/raw/osm_switzerland/`
- [ ] **Stretch, blocked:** accessibility.cloud app token. Sign-up app (v2.accessibility.cloud/sign-up) never finished loading on 21 Nov (laptop + phone on mobile data, normal + private). Retry later; once there's a token, `data/scripts/fetch_a11ycloud_lausanne.py` is ready → `data/raw/a11ycloud_lausanne.json`. Not on the critical path: OSM `wheelchair=*` covers the demo. NB: accessibilitycloud.com (no dot) is an unrelated web-testing product
- [x] Download sonBASE daytime road noise (layer `ch.bafu.laerm-strassenlaerm_tag`) and railway noise for all of Switzerland → `data/raw/sonbase/`
- [ ] Download opentransportdata.swiss accessibility data (stops, toilets, platforms) → `data/raw/prm/`
- [ ] Optional: EEA END noise grid for Dublin

### Transformation
- [x] Normalise OSM into the Place schema, drop non-venues, dedupe on name + category < 25 m (`build_places_lausanne.py`). accessibility.cloud merge pending a token
- [x] Sample sonBASE at each place → `baseline_noise_db`. Rasters are 10 m, LV95 (EPSG:2056), float64, nodata -9999: transform place coords to LV95 (don't reproject the raster). Use the **median within ~25–50 m**, not the single cell (a POI point on the street-side façade reads 20 dB louder than its own courtyard). Combine road + rail energetically: 10·log10(10^(road/10) + 10^(rail/10)). Scripts: `fetch_sonbase.py`, `check_sonbase.py`; import `raster_env` before rasterio
- [x] Build a 50 m grid over Lausanne with baseline noise for the heatmap layer: day + night, 10,812 cells (`build_heatmap_lausanne.py` → `data/processed/heatmap_lausanne_noise.geojson`). Night layers of sonBASE added; places carry `baseline_noise_night_db` + `osm_tags`
- [x] Seed unmeasured places with a prior feature vector: `prior` field (team decision 21 Nov: separate field, not `measured` with n=0), `priors.py`
- [x] Write `data/processed/places_lausanne.geojson` and commit it (app must work offline). 2,873 places, all with noise; Kotlin test `BundledPlacesTest` checks every feature decodes as Place
- [ ] **Stretch (team decision 21 Nov: MVP first):** Widen to Switzerland: same normalise + sonBASE sampling for all Swiss POIs → `data/processed/places_switzerland.geojson` (check size; gitignore if > 50 MB and document how to rebuild)
- [x] Coverage grids: `build_coverage.py` → `coverage_2deg.json` (global, 2,919 cells) + `coverage_1deg.json` (Europe, 1,503 cells; 1° because the source maps are 1°). `wheelchair` per cell = each country's real total (Geofabrik taginfo) spread over its tagged 1° cells (estimate; country totals exact, 98.8% of the planet total placed). `sensory_friendly` exact from Overpass (143 worldwide)

### Backend
- [x] FastAPI + SQLite (stdlib `sqlite3`, no ORM, WAL mode, R-Tree index on place coordinates): the endpoints above. `backend/`, 12 pytest tests. Tables created on startup; schema change = delete the `.db` and re-seed. Never commit `*.db`
- [x] `GET /coverage?res_deg=`: serves `data/processed/coverage_<res>deg.json` when built, otherwise counts places in the DB (Lausanne only for now); live `measurements` counts added per cell
- [x] Aggregation: mean feature vector per place; generic per-dimension score in [0, 1] for users with no personal model (`backend/aggregate.py`; thresholds are first guesses)
- [x] Seed: DB is created and seeded from `places_lausanne.geojson` on first start; `python db.py --reset` to wipe and re-seed. Add `places_switzerland.geojson` to `SEED_FILES` in `db.py` once built
- [x] Reachability: Cloudflare quick tunnel (`cloudflared tunnel --protocol http2 --url http://127.0.0.1:8000`; QUIC is blocked here). Tested Android emulator → tunnel → laptop. URL changes on restart → rebuild the app; see backend/README.md so phones reach the laptop on any network; phone hotspot as fallback. Everyone tests against this one shared backend

### After ~19:00: help Christine
- [ ] Take the app screens Christine hands over (likely Place detail, Privacy, Model screen UI)
- [x] Ktor client against your own API: `KtorPlacesApi` in `shared/commonMain` (mock-engine tests + a live round-trip test). App picks the URL per platform (`composeApp/.../ApiConfig.kt`); `postMeasurement` now returns `MeasurementResult`
- [ ] Offline fallback using the bundled GeoJSON (when the backend is unreachable). Android quiet-place lookup uses it. The map still falls back to sample places. [PR #38](https://github.com/b00rg/ciuin/pull/38)
- [ ] Own `REUSED.md`: collect entries from everyone as they go (data sources, backend + app libraries added 21 Nov; keep adding)

---

## B1. Emma: audio ML training

### Data
- [x] **Start the UrbanSound8K download now** (~6 GB, Zenodo record 1203745)
- [x] Download ESC-50 (~600 MB) and **MUSAN** (speech, music and noise; check size first). Needed because ESC-50 and US8K have almost no speech and little music. [PR #41](https://github.com/b00rg/ciuin/pull/41)
- [x] Map labels onto our **9 classes** → `ml/export/labels.yaml`: speech_crowd, music, traffic, siren, construction, alarm_beep, dog, machinery_hum, other
  - No "quiet" class: the cascade reports quiet when stage 0 stays asleep
  - `machinery_hum`: US8K `air_conditioner`, ESC-50 `vacuum_cleaner` / `washing_machine`
  - `other`: every unmapped ESC-50 class + `gun_shot`, so the model isn't forced into one of the 8
- [x] Folds: test = ESC-50 fold 5 + US8K folds 9–10; validation = ESC-50 fold 4 + US8K fold 8; train = the rest. MUSAN split by file with a fixed seed. Never split within a fold

### Contract (→ Handoff 0 at 15:00, with Paramveer and Christine)
- [x] Agree model input/output: input `float32 [1, 15600]` raw 16 kHz mono waveform in [-1, 1]; output `float32 [1, 9]` softmax; `labels.txt`
- [x] **Spectrogram inside the model graph** (STFT as Conv1d with fixed weights + mel matrix multiply), so the app never computes spectrograms. Prove it exports to TFLite and runs on a phone. Verified with Student S on the Android emulator.
- [x] Hand over a dummy `.tflite` with the exact input/output signature

### Classifier (→ Handoff 1 at 17:00)
- [x] **Teacher:** frozen YAMNet embeddings + trained MLP head (embeddings precomputed once, fast). Upgrade to PANNs CNN14 only if time allows. Teacher stays offline; size doesn't matter, accuracy does
- [x] Record teacher accuracy and macro-F1 → `results.json`
- [x] Write `preprocess.md`: resampling, mono downmix, normalisation, 0.975 s windowing, clip-level averaging
- [x] Train a first S student quickly (ESC-50 only if US8K is still downloading; undertrained is fine). Export fp32 `.tflite` + 10 test clips with expected outputs. [PR #28](https://github.com/b00rg/ciuin/pull/28)
- [x] Save ~200 preprocessed training clips as a calibration `.npy` for Paramveer's quantisation

### Distillation (→ Handoff 2 at 23:00)
- [x] Precompute the teacher's soft labels (logits) on all training clips once and cache them
- [ ] Students: 3 small CNNs, same architecture at different widths (S / M / L, ~50k / ~200k / ~1M params). Only ops that export cleanly to TFLite (conv, depthwise conv, pooling, dense)
- [ ] Loss: KL divergence to the teacher's softened outputs (T ≈ 2–4) + cross-entropy on true labels; tune α on the validation folds. Both loss terms are implemented. Validation tuning of α is pending.
- [x] Baseline: train the S student on hard labels only, to show what distillation adds
- [ ] Augment with mixup / time shifts / background noise from MUSAN noise + venue room-tone recordings. Handoff 2 uses mixup, shifts and DEMAND background noise.
- [ ] Export each student as fp32 `.tflite`; hand over with the test split for Paramveer's int8 check
- [ ] Per model (fp32): params, size, MACs, accuracy, macro-F1 → `results.json` (teacher, each student, hard-label baseline)

Handoff 2 has S and M fp32 exports and updated metrics. L and the full metrics table are pending. [PR #43](https://github.com/b00rg/ciuin/pull/43)

### Cascade and personal model (design side)
- [ ] Record venue audio: quiet room, hall, cafeteria
- [ ] Stage 0 thresholds (dB + spectral flux): run Paramveer's Kotlin code on the recordings via the JVM target, tune so it rarely wakes in calm places. Provisional thresholds use DEMAND recordings. [PR #39](https://github.com/b00rg/ciuin/pull/39)
- [x] Stage 1 → 2 rule: compare top-2 margin vs entropy on validation; pick the better one at ~10–20% escalation rate → `cascade.json`
- [x] Re-check the escalation threshold on the int8 models (quantisation can shift confidence values). Re-checked on the Handoff 1 S int8 model. Repeat for the refreshed models when quantised. [PR #33](https://github.com/b00rg/ciuin/pull/33)
- [ ] Replay venue recordings offline → % time with no model running, escalation rate, inferences per hour → `results.json`. DEMAND replay metrics are in `ml/export/cascade_replay.json`. [PR #40](https://github.com/b00rg/ciuin/pull/40)
- [x] Personal model spec for Paramveer: learning rate, **L2 toward the preset weights** (not toward zero), positive-class weight, autism-friendly preset weights, 200-example cap
- [x] Prototype the personal model in Python on synthetic users (hates sirens, tolerates speech) to pick those numbers; export the synthetic data + expected scores for Paramveer's unit test. [PR #26](https://github.com/b00rg/ciuin/pull/26)

### Add-ons (only after Handoff 2)
- [ ] Slimmable student: one set of weights replacing S / M / L
- [ ] Conformal escalation rule
- [ ] SONYC-UST evaluation (per-coarse-tag AUPRC; siren + alarm_beep summed into alert-signal)
- [ ] Babble synthesis for speech_crowd

---

## B2. Paramveer: on-device ML, quantisation and device fitting

### Before Handoff 1
- [x] Agree format with Christine and Emma by 15:00: TFLite/LiteRT on Android, ONNX on Desktop only if needed
- [ ] **Fallback demo:** stock YAMNet running in the app via MediaPipe's audio classifier, outputs grouped into our classes
- [x] A-weighted dB and spectral flux in Kotlin in `commonMain`: radix-2 FFT, A-weighting coefficients for the mic's actual sample rate, report dBFS(A) (mic is uncalibrated). Unit tests against numpy values. Runnable on WAV files from the JVM target so Emma can tune thresholds. [PR #37](https://github.com/b00rg/ciuin/pull/37)
- [ ] Expose a single `AudioClassifier` interface in `commonMain` with platform implementations, so Christine just calls `classify(window)`. The shared interface and test adapters exist. The Android listener still calls LiteRT directly.

### Export and port (from Handoff 1)
- [x] Load Emma's fp32 student; only `preprocess.md` steps go in Kotlin (resample, mono, normalise, window). [PR #35](https://github.com/b00rg/ciuin/pull/35), [PR #38](https://github.com/b00rg/ciuin/pull/38)
- [x] Check parity: run the 10 test clips on-device; top class must match Emma's expected output, probabilities within a small tolerance. Verified for the Handoff 1 S on the Android emulator.
- [ ] Fallback only if the in-graph spectrogram fails to export: port log-mel to Kotlin with a parity test on the same 10 clips

### Quantisation (first student from Handoff 1, all students from Handoff 2)
- [ ] int8 post-training quantisation of each student using Emma's calibration `.npy`
- [x] Keep the spectrogram part (STFT, mel, log) in float; quantise only conv/dense layers. Verified for Handoff 1 S. [PR #36](https://github.com/b00rg/ciuin/pull/36)
- [ ] Run fp32 and int8 on Emma's test split; flag any student losing more than ~1–2 points of macro-F1
- [ ] Re-run the 10-clip parity test on-device for every int8 model
- [ ] Add int8 rows (size, accuracy, macro-F1) to `results.json`
- [ ] If int8 breaks a model, ship fp32 for that one and note it

Handoff 1 Student S has an int8 export, held-out accuracy and macro-F1 results, and 10-clip Android emulator parity. PR #36 merged into the preprocessing branch. The int8 files are still outside `main`, and the Handoff 2 exports need a fresh int8 pass. [PR #36](https://github.com/b00rg/ciuin/pull/36)

### Fitting variants to devices (from Handoff 2)
- [ ] Benchmark every variant (fp32 and int8) on every phone/laptop the team has: latency, peak memory, load time → per-device rows in `results.json`. Handoff 1 S fp32 and int8 were benchmarked on the Android emulator. [PR #36](https://github.com/b00rg/ciuin/pull/36)
- [ ] Device profile rule: pick stage-1 and stage-2 variants per device from a launch benchmark (+ RAM / CPU cores), drop a size when battery is low
- [ ] Acceleration: CPU (XNNPACK) by default; try the GPU delegate only if benchmarks show a win. Skip NNAPI (deprecated)

### Camera module (a function taking frames or sensor values, returning numbers)

- [x] Brightness uses Android `TYPE_LIGHT` when present. iOS shows lux as unavailable because [SensorKit's ambient light readings require Apple's approved-research entitlement](https://developer.apple.com/documentation/sensorkit/configuring-your-project-for-sensor-reading).
- [x] Flicker uses timestamped frame luminance and locks exposure when supported. The app requests up to 60 fps and shows the FFT peak, flicker index and range supported by the measured frame rate. A 60 fps sample stream reports a practical upper limit of 27 Hz. Higher frequencies can alias into that range.
- [x] Crowd uses the median of per-frame person counts. Android uses quantized COCO SSD MobileNet V1 (class 0, up to ten detections per frame). iOS uses the system Vision human detector. Frame data stays in memory through processing and only counts enter the measurement. See [REUSED.md](../REUSED.md).
- [ ] Benchmark the camera, light sensor and crowd detector on physical phones.
- [x] Checked local history, remote branches and repository PRs for D's vision work. No teammate detector was found in the project repository.

### Runtime
- [ ] Cascade state machine (stages 0–2) in `commonMain`, using `cascade.json`; logs per-stage time and inference count for the Model screen. The Android listener scores audio with a fixed S model.
- [x] Personal model in pure Kotlin in `commonMain` from Emma's spec, exposing `score(features): Double`
- [x] Unit test the Kotlin personal model against Emma's Python prototype on the same synthetic data. [PR #38](https://github.com/b00rg/ciuin/pull/38)

---

## C. Christine: KMP app (Siddhi joins ~19:00)

### Project
- [x] **By 13:30:** Compose Multiplatform project with **Android + Desktop (JVM)** targets
- [ ] `commonMain`: UI, DTOs, Ktor API client, feature vector, presets, plus Paramveer's classifier interface, cascade and personal model
- [x] `androidMain` has microphone, camera and ambient light capture, foreground service, notifications and vibration.
- [x] `desktopMain`: map for the projector; optional ONNX inference on a recorded clip

T3 Code also produced a local iOS map port on `kmp-app-ios` (`50d7f9e`), starting from the earlier Android map branch.

### Screens
- [ ] Onboarding (first launch): short explanation + choose a preset. Preset selection is available from the map settings.
- [ ] Map: OSM tiles, markers coloured by **this user's** `p_overwhelm`, heatmap toggle per dimension, gap badge from `/stats`. The map, personal markers, category heatmaps and badge are in place. A dedicated flicker layer is pending.
- [x] Place detail: features, accessibility tags, measurement count, "Measure here". The button opens the capture flow below on mobile.
- [ ] 3D globe (opening screen, after MVP): static rotating dotted globe early; then spikes from `/coverage`, live measurement pulses, fly-to Lausanne → flat map. Bundle Natural Earth land points + `coverage.json` for offline
- [x] Android and iOS share a 30-second foreground capture and review flow from **Measure here**. Cancel discards the capture. Confirm submits aggregates with an ID for safe retries. [PR #44](https://github.com/b00rg/ciuin/pull/44)
  - Android shares the microphone stream with live listening and reuses Stage 0 and Student S. iOS uses Stage 0 for local dBFS(A) review. It has no calibrated SPL or sound classifier in this build.
  - Deploy the updated backend and app clients together. Existing databases migrate on startup. Legacy app binaries expect numeric fields in place responses.
  - **Android 15 arm64 emulator.** Camera and mic denial yielded a lux-only review. A full capture reported the injected 321 lux, 29.1 fps and a 2.0–13.1 Hz detectable range. The person detector ran 29 times at 81 ms mean and 406 ms peak, including startup. Confirm stored one 30-second aggregate row with all nine quiet sound scores and an observed zero people count. Cancel returned to the place, released the camera and added no row.
  - **iPhone 17 Pro simulator (iOS 27).** Verified microphone denial, explicit unavailable readings with no upload action, and cancellation. With microphone access enabled, native review showed −50 dBFS(A) average and −44 dBFS(A) peak for local review. Camera capture was unavailable. A separate synthetic camera fixture exercised review and confirm at 60.0 fps and uploaded exactly one aggregate row. It reported 8.0 Hz, index 0.120, a 2.0–27.0 Hz range and 3 people. The backend kept unavailable sound and lux fields null.
  - **Physical devices.** No Android phone was attached. The paired iPhone 14 Pro reported unavailable during testing. The iPhone device-target build succeeded unsigned. Physical sensor capture, detector latency and accuracy remain unverified.
  - **Automated tests.** 89 Kotlin/JVM tests passed with one opt-in live test skipped. All 50 backend tests passed. Test coverage includes jitter and gaps in camera timestamps, steady light, missing readings, 30-second aggregation, cancellation, legacy DB migration and concurrent retries.
- [x] "Too much" and "This is fine" buttons, reachable from the map and from the alert notification
- [ ] Model screen: variants and per-device latency from `results.json`, which variant this device picked, switch live, cascade stage stats, personal model example count + reset. Map settings show the personal model example count and reset control.
- [ ] Privacy screen: what's measured, what's sent, what never leaves the phone (trigger taps never do)

### Background listener and alerts
- [ ] Android foreground service running Paramveer's cascade (mic permission + persistent notification). The service and persistent notification are in place. Full cascade integration is pending.
- [x] On alert: vibrate + notification "Above your limit — [place] is quiet, 40 m"
- [x] Quiet-place pick: call `/places/nearby`, score each with the personal model, choose the lowest `p_overwhelm` within 300 m (bundled data offline)
- [ ] Route: straight line + distance and bearing on the map; walking directions only if time. Straight-line routing, distance and walking directions are in place. Bearing is pending.
- [x] Debounce alerts (max one per 2 minutes) and add a snooze. [PR #38](https://github.com/b00rg/ciuin/pull/38)

### Sync and fallbacks
- [x] Desktop map polls `/places` every 2 s so a phone measurement appears live
- [x] Bundle `places_lausanne.geojson` for offline use. The Android app bundles it for quiet-place lookup.
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
- Natural Earth (globe land outline, public domain): https://www.naturalearthdata.com
- ESC-50: https://github.com/karolpiczak/ESC-50
- UrbanSound8K: https://zenodo.org/record/1203745
- SONYC-UST: https://wp.nyu.edu/sonyc/

Licences: UrbanSound8K is CC BY-NC 4.0 and sonBASE allows non-commercial use; fine for the hackathon.

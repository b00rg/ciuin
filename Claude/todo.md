# Sensory Map: LauzHack 2026 todo

**One-liner:** a map of how loud, bright, flickery and crowded places are, measured on-device by a small pruned model so no audio or images ever leave the phone. Built for people with sensory sensitivities and mobility needs.

**Challenges entered:** AXA (frugal multimodal AI) · IMC (tech for good: inclusion) · JetBrains (Kotlin Multiplatform). *Confirm the JetBrains KMP requirement at their booth.*

**Hard deadlines (Lausanne time)**

| When | Checkpoint |
|---|---|
| Sat 12:30 | Idea locked, roles confirmed, repo set up |
| Sat 15:00 | Data go/no-go: OSM counts known, datasets downloaded, contracts below agreed |
| Sat 19:00 | **MVP:** phone measures a place, the measurement appears on the map |
| Sat 21:00 | **Pitch storyline drafted** (D): problem, stats, demo moment, 6 slide titles. Team reads it in 10 min |
| Sun 02:00 | Pruned models on the phone, heatmap working, 3+ real places measured |
| Sun 06:00 | Slides v1 with real numbers (B's frugality chart, D's field measurements) |
| Sun 08:00 | **Feature freeze.** Bug fixes and polish only |
| Sun 08:30 | Record a backup demo video (screen + phone) in case live demo fails |
| Sun 09:00 | **Full pitch dry run #1**, whole team, timed. Fix slides after |
| Sun 10:00 | Write-up drafted. Sponsor 1-minute angles ready (AXA, IMC, JetBrains) |
| Sun 11:00 | **Submit** (official deadline 12:00) |
| Sun 11:00–11:45 | Dry runs #2 and #3, Q&A drill (one person fires judge questions) |

**Pitch time is protected.** From 09:00 Sunday, A, B and C stop new work and join D on the pitch. Only fix bugs that would break the demo.

---

## Roles

| Person | Role | Owns |
|---|---|---|
| **A** (Paramveer?) | Data and backend | Place data, noise maps, measurement API, scoring |
| **B** | Audio ML and pruning (the AXA core) | Sound classifier, pruning sweep, on-device export |
| **C** | KMP app | Kotlin Multiplatform app, map UI, on-device inference integration |
| **D** | Vision sensing, validation and pitch | Brightness/flicker/crowd measurement, field tests, slides, write-up |

Fill in names, then delete the `?`.

---

## 0. Shared contracts (agree by 13:00, then don't change without telling everyone)

### Measurement (phone → backend)
Only aggregates. **Never** raw audio, images or exact user identity.
```json
{
  "place_id": "osm:node/123456",
  "lat": 46.5191, "lon": 6.5668,
  "timestamp": "2026-11-21T14:05:00Z",
  "duration_s": 30,
  "db_mean": 62.4, "db_peak": 78.1,
  "sound_classes": {"speech": 0.71, "music": 0.12, "traffic": 0.08, "siren": 0.0, "construction": 0.0},
  "lux_mean": 340, "flicker_hz": 100, "flicker_index": 0.04,
  "people_count": 7,
  "model_variant": "audio-pruned-50",
  "device": "android"
}
```

### Place (backend → app)
```json
{
  "place_id": "osm:node/123456", "name": "Café X", "lat": 46.5191, "lon": 6.5668,
  "category": "cafe",
  "wheelchair": "yes", "sensory_friendly": null, "quiet_room": false,
  "baseline_noise_db": 58,
  "measured": {"n": 4, "db_mean": 61.0, "lux_mean": 320, "flicker_index": 0.03, "people_mean": 6},
  "score": {"noise": 0.4, "light": 0.8, "flicker": 0.9, "crowd": 0.6, "access": 1.0}
}
```

### Sensory profile (stays on the phone)
Weights per dimension, e.g. autism-friendly preset `{"noise": 0.4, "flicker": 0.3, "light": 0.15, "crowd": 0.15}`; wheelchair preset filters on `access`.

### API (A owns)
- `GET /places?bbox=minLon,minLat,maxLon,maxLat` → list of Place
- `POST /measurements` → stores one Measurement, recomputes that place's score
- `GET /heatmap?bbox=...&dim=noise` → GeoJSON grid for the heatmap layer

---

## A. Data and backend

### Gathering
- [ ] Run Overpass query for the Lausanne bbox: `amenity`, `shop`, `leisure`, `tourism` POIs with `name`. Save to `data/raw/osm_lausanne.json`
- [ ] Count tags: `wheelchair=*`, `sensory_friendly=*`, `amenity=quiet_room`. **Post counts in chat by 14:00** (tells D what to claim in the pitch)
- [ ] Register for an accessibility.cloud app token; pull places for the same bbox → `data/raw/a11ycloud_lausanne.json`
- [ ] Download sonBASE daytime road noise (layer `ch.bafu.laerm-strassenlaerm_tag`) and railway noise for the Lausanne area → `data/raw/sonbase/`
- [ ] Download the opentransportdata.swiss accessibility data (stops, toilets, platforms) → `data/raw/prm/`
- [ ] Optional: EEA END noise grid for Dublin, if we want a second city in the demo

### Transformation
- [ ] Normalise OSM + accessibility.cloud into the Place schema; dedupe on name + distance < 25 m
- [ ] Reproject sonBASE to WGS84; sample noise at each place → `baseline_noise_db`
- [ ] Build a 50 m grid over Lausanne with baseline noise for the heatmap layer
- [ ] Write `data/processed/places_lausanne.geojson` and commit it (the demo must work offline)

### Backend
- [ ] FastAPI + SQLite (or plain JSON files): the three endpoints above
- [ ] Scoring: per-dimension score in [0, 1], blending baseline data with measurements (measurements win once n ≥ 2)
- [ ] Seed script that loads `places_lausanne.geojson`
- [ ] Deploy somewhere reachable from phones on venue wifi (or run on a laptop + hotspot as fallback)

---

## B. Audio ML and pruning (the AXA story)

### Data
- [ ] **Start the UrbanSound8K download now** (6 GB, Zenodo record 1203745)
- [ ] Download ESC-50 (~600 MB) and SONYC-UST (for evaluation only)
- [ ] Define our **sensory label set** (~8 classes): speech/crowd, music, traffic, siren, construction (drilling + jackhammer), alarm/beeping, dog, quiet/background
- [ ] Map ESC-50 and UrbanSound8K labels onto it; write the mapping to `ml/labels.yaml`
- [ ] Respect predefined folds (ESC-50 5 folds, US8K 10 folds) so there's no train/test leakage

### Model
- [ ] Start from a small pretrained audio backbone (YAMNet or a PANNs MobileNet variant). No training from scratch
- [ ] Fine-tune the head on our label set; record baseline accuracy and macro-F1
- [ ] dB meter: compute A-weighted level from raw samples on-device (no model needed; give C the formula)

### Frugality (the AXA judging criteria: technological challenge and innovative idea)
- [ ] Pruning sweep at 0 / 30 / 50 / 70 / 90% sparsity (structured pruning so it actually speeds up), fine-tune after each
- [ ] Also try int8 quantisation on the best pruned model
- [ ] For each variant record: params, file size (MB), accuracy, macro-F1, latency on a real phone, energy if measurable
- [ ] **Deliverable:** `ml/results.csv` + one chart of accuracy vs. size/latency. This chart goes on a slide
- [ ] Adaptive sizing: pick model variant from device capability or battery level (the "small model for small compute" idea). Even a simple rule counts
- [ ] Export to the on-device format C needs (TFLite/LiteRT for Android; agree with C by 15:00)
- [ ] Evaluate the shipped variant on SONYC-UST (real city recordings) and report the drop honestly

---

## C. KMP app

- [ ] **By 13:30:** confirm with JetBrains whether KMP is required; scaffold a Compose Multiplatform project (Android target is enough for the demo; iOS if time)
- [ ] Map screen: OSM tiles, place markers coloured by the user's weighted score, heatmap toggle per dimension
- [ ] Profile screen: presets (autism-friendly, wheelchair, low-light) + custom sliders
- [ ] Place detail: scores, accessibility tags, number of measurements, "Measure here" button
- [ ] Measure flow: 30 s capture → mic dB + audio classifier + D's light/flicker/crowd module → show results → confirm → POST aggregates only
- [ ] Integrate B's model (Android: TFLite/LiteRT in `androidMain`; shared scoring logic in `commonMain`)
- [ ] Show which model variant ran and its latency (judges will ask; it proves the frugality claim)
- [ ] Offline fallback: bundle `places_lausanne.geojson` so the map works without the backend
- [ ] Privacy screen: one sentence on what's measured, what's sent, what never leaves the phone

---

## D. Vision sensing, validation and pitch

### Sensing module (hand to C as a function taking camera frames, returning numbers)
- [ ] Brightness: mean luminance per frame → approximate lux (calibrate roughly against a phone light meter app)
- [ ] Flicker: luminance time series at max frame rate → FFT → dominant frequency + flicker index. Test under LED and fluorescent lights in the building
- [ ] Crowd: pretrained COCO person detector (class 0), count only, frames discarded immediately. Use the smallest model that's accurate enough; record its size and latency for the frugality slide
- [ ] Unit-test each on short recorded clips from the venue

### Validation
- [ ] Measure 5+ real places in and around the venue (quiet study room, cafeteria at lunch, corridor, hacking hall at night, outside by a road)
- [ ] For each, note a human judgement (quiet/OK/overwhelming) so we can show model output vs. human rating
- [ ] Check that the scores rank the places sensibly; if not, tell A and B fast

### Pitch and submission
- [ ] Problem stats: start from the **Pitch stats** section at the bottom (already sourced). Open each link once to confirm before it goes on a slide
- [ ] Slides (max 6): problem → demo → how it works (on-device, pruned) → frugality chart from B → privacy → what's next
- [ ] Demo script, 2 min: pick the autism-friendly preset → map shows the cafeteria as red → walk to the quiet room → measure live → it turns green
- [ ] Write-up sections (inspiration, what it does, how we built it, challenges, accomplishments, what's next)
- [ ] Judge Q&A prep: accuracy on real recordings, how pruning was done, privacy, why not just use Google Maps, what's actually missing from OSM
- [ ] Separate 1-minute angles for each sponsor jury: AXA (frugality chart), IMC (inclusion and community-contributed data), JetBrains (KMP, shared code)

---

## Risks and fallbacks

| Risk | Fallback |
|---|---|
| On-device model integration in KMP takes too long | Run the pruned model on a laptop next to the phone for the demo; still show the size/latency chart from a real phone run |
| Venue wifi blocks the backend | Laptop hotspot; bundled offline place data |
| Pruned model accuracy collapses | Ship the 30% or 50% variant; the trade-off curve is still the story |
| Few OSM places tagged | That's the problem statement: "nobody has mapped this, so we measure it" |
| Flicker FFT limited by camera frame rate | Report flicker index only, be honest about the frame-rate limit |

## Pitch stats (sourced)

Pick 2–3 for the slides; keep the rest for Q&A. Say the year and source out loud.

**Who it's for**
| Stat | Source | Notes |
|---|---|---|
| About 1 in 127 people worldwide were autistic in 2021, nearly 62 million people | WHO autism fact sheet (Sep 2025) and WHO statement (Sep 2025): https://www.who.int/news-room/fact-sheets/detail/autism-spectrum-disorders | WHO's newer figure. Don't use the old "1 in 100 children" |
| 94.4% of autistic adults reported extreme levels of sensory processing in at least one area | Crane et al., *Autism* 2009: https://pubmed.ncbi.nlm.nih.gov/19369385/ | Small sample; estimates across studies range 45–96% depending on method |
| An estimated 1.3 billion people, 1 in 6, experience significant disability | WHO disability fact sheet (Mar 2023): https://www.who.int/news-room/fact-sheets/detail/disability-and-health | Use for the wheelchair/accessibility side |
| About 1.8 million people with disabilities in Switzerland | Federal Statistical Office: https://www.bfs.admin.ch/bfs/en/home/statistics/economic-social-situation-population/equality-people-disabilities/disabilities.html | Local hook for EPFL judges |

**The problem in public places**
| Stat | Source | Notes |
|---|---|---|
| 64% of autistic people avoid the shops; 28% have been asked to leave a public place for reasons linked to their autism | National Autistic Society surveys, via Retail Week (2019): https://www.retail-week.com/industry-insight/how-to-make-shopping-experiences-more-accessible/7032635.article | Strongest emotional stat. UK data |
| Three-quarters of autistic shoppers and families found busy stores and high noise problematic; 49% named PA announcements | Autistica survey of 120 people, via The Grocer: https://www.thegrocer.co.uk/people/diversity-and-inclusion/most-autistic-people-avoid-supermarkets-report-finds/563136.article | Directly maps to what we measure: noise + crowd |
| Four in five people with disabilities in Switzerland feel severely excluded in at least one area of life | Pro Infirmis 2026 inclusion study (2,207 people): https://www.bluewin.ch/en/news/switzerland/little-progress-in-the-inclusion-of-people-with-disabilities-li.3276201 | Mobility is named as one of the biggest gaps |
| One in ten people in Switzerland is exposed to loud road traffic noise where they live | FOEN: https://www.bafu.admin.ch/bafu/en/home/topics/noise.html | Shows noise isn't only an autism issue |
| Noise sensitivity / hyperacusis in the general population: 0.2–17.2% across studies | Ren et al. 2021 scoping review: https://www.frontiersin.org/articles/10.3389/fneur.2021.706555/full | Wide range; only use as "beyond autism, many people are sound-sensitive" |

**Our own numbers (the strongest slide)**
- B's chart: accuracy vs. model size and phone latency across pruning levels
- D's field test: 5+ real places, model score vs. human rating
- How many Lausanne places have `sensory_friendly` tags in OSM (A's count). If it's near zero, that's the gap we fill

**Don't claim:** that the app diagnoses anything, that it works for every disability, or that pruned accuracy matches the full model unless B's numbers show it.

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

Licences: UrbanSound8K is CC BY-NC 4.0 and sonBASE allows non-commercial use. Fine for the hackathon; mention it if asked about commercialising.
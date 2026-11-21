# Reused and built

Everything not written during the event (pretrained models, datasets, libraries, prior code) must be listed under **Reused**.

## Reused

| What | Kind | Source / licence | Used for |
|---|---|---|---|
| TensorFlow, Keras, LiteRT (`ai-edge-litert`) | Library | Google, Apache 2.0 | Audio model training, TFLite export and verification |
| YAMNet (pretrained, frozen) | Model | Google, Apache 2.0; https://www.kaggle.com/models/google/yamnet, trained on AudioSet | Teacher: audio embeddings, with our own classifier head trained on top |
| YAMNet log-mel frontend parameters | Design (no code) | Google, Apache 2.0 | Window/hop/mel settings of the in-graph spectrogram, so student inputs match the teacher's |
| Kotlin Multiplatform, Compose Multiplatform | Library | JetBrains, Apache 2.0 | App UI and shared code |
| Ktor client, kotlinx.serialization, kotlinx.coroutines | Library | JetBrains, Apache 2.0 | Networking, JSON, concurrency |
| FastAPI, Uvicorn | Library | MIT / BSD-3 | Backend |
| OpenStreetMap raster tiles (tile.openstreetmap.org) | Data / service | © OpenStreetMap contributors, ODbL; OSMF tile usage policy | Map background |
| Pydantic | Library | MIT | Backend request/response validation |
| SQLite (Python `sqlite3`, R-Tree module) | Library | Public domain | Backend storage and spatial index |
| pytest, httpx | Library | MIT / BSD-3 | Backend tests |
| rasterio, GDAL, NumPy | Library | BSD-3 / MIT / BSD-3 | Reading and sampling the noise rasters |
| AndroidX Activity Compose, Android Gradle Plugin | Library | Google, Apache 2.0 | Android app shell and build |
| Roboto (weights 400 and 500), `iosApp/iosApp/Fonts/Roboto-*.ttf` | Font | Google, Apache 2.0; static instances cut from Android 15's variable `Roboto-Regular.ttf` (v3.005) with fontTools | iOS text in the same typeface Android uses by default |
| Noto Sans Symbols (one glyph, U+2302), `iosApp/iosApp/Fonts/NotoSansSymbols-House.ttf` | Font | Google, SIL OFL 1.1; subset of Android 15's `NotoSansSymbols-Regular-Subsetted.ttf` (v1.09) | The "⌂" button on iOS, matching Android's fallback glyph |
| cloudflared (Cloudflare quick tunnel) | Tool / service | Cloudflare, Apache 2.0 (client) | Making the laptop backend reachable from phones |
| **OpenStreetMap data** | Dataset | © OpenStreetMap contributors, **ODbL 1.0** | Places, categories, `wheelchair` and other tags. Attribution required; `data/processed/places_lausanne.geojson` is a derived database, so it is ODbL too |
| Overpass API (overpass-api.de) | Service | Free public instance | Downloading Lausanne OSM places and counting tags |
| taginfo (taginfo.openstreetmap.org, taginfo.geofabrik.de) | Service | Free public instances | Tag counts for world / Europe / Switzerland; per-country `wheelchair` totals and planet distribution maps for the globe coverage grids |
| Geofabrik region index (`download.geofabrik.de/index-v1.json`) | Dataset | Geofabrik; boundaries derived from OSM (ODbL) | Country polygons to place per-country counts on the globe grid |
| **sonBASE noise maps** (road + rail, day + night, 2021) | Dataset | © FOEN / BAFU, via data.geo.admin.ch (Swiss federal geodata; source attribution required) | `baseline_noise_db`, `baseline_noise_night_db`, the 50 m noise heatmap |
| ESC-50 | Dataset | https://github.com/karolpiczak/ESC-50, CC BY-NC 3.0 | Audio classifier training/eval (fold 4 val, fold 5 test) |
| UrbanSound8K | Dataset | https://zenodo.org/record/1203745, CC BY-NC 4.0 | Audio classifier training/eval (fold 8 val, folds 9–10 test) |
| MUSAN | Dataset | https://www.openslr.org/17/, CC BY 4.0 | Downloaded for speech/music data and augmentation noise; not yet in the trained models (it arrived after the Handoff 2 training started) |
| FSD50K (alarm, siren, crowd subset) | Dataset | https://zenodo.org/record/4060432, CC BY 4.0 (clips CC0 / CC BY / CC BY-NC / CC Sampling+, per clip in its metadata) | Extra `alarm_beep`, `siren` and `speech_crowd` training/eval data (teacher and the Handoff 2 students) |
| DEMAND (PCAFETER, PRESTO, PSTATION, SPSQUARE) | Dataset | https://zenodo.org/record/1227121, CC BY 4.0 | Real crowd ambience for `speech_crowd`; augmentation noise; ambience for tuning and replaying the cascade |

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Canvas slippy map (tiles, markers, gestures) and map screen | Christine | `composeApp/src/commonMain/kotlin/ch/ciuin/app/` |
| Data pipeline: tag counts, OSM + sonBASE downloads, place builder (filter, dedupe, day/night noise sampling), 50 m heatmap grid | Siddhi (with Claude Code) | `data/scripts/`, `data/processed/` |
| Backend API: all section 0 endpoints, privacy guard, aggregation and scores, measured heatmap layers, Swagger docs, tests | Siddhi (with Claude Code) | `backend/` |
| Ktor client (`KtorPlacesApi`), per-platform API config, live placeholder screen | Siddhi (with Claude Code) | `shared/`, `composeApp/` |
| Audio dataset downloader | Emma | `ml/download_datasets.py` |

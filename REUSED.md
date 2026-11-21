# Reused and built

Everything not written during the event (pretrained models, datasets, libraries, prior code) must be listed under **Reused**.

## Reused

| What | Kind | Source / licence | Used for |
|---|---|---|---|
| TensorFlow, Keras, LiteRT (`ai-edge-litert`) | Library | Google, Apache 2.0 | Audio model training, TFLite export and verification |
| YAMNet log-mel frontend parameters | Design (no code) | Google, Apache 2.0 | Window/hop/mel settings of the in-graph spectrogram, so student inputs match the teacher's |
| Kotlin Multiplatform, Compose Multiplatform | Library | JetBrains, Apache 2.0 | App UI and shared code |
| Ktor client, kotlinx.serialization, kotlinx.coroutines | Library | JetBrains, Apache 2.0 | Networking, JSON, concurrency |
| FastAPI, Uvicorn | Library | MIT / BSD-3 | Backend |
| Pydantic | Library | MIT | Backend request/response validation |
| SQLite (Python `sqlite3`, R-Tree module) | Library | Public domain | Backend storage and spatial index |
| pytest, httpx | Library | MIT / BSD-3 | Backend tests |
| rasterio, GDAL, NumPy | Library | BSD-3 / MIT / BSD-3 | Reading and sampling the noise rasters |
| AndroidX Activity Compose, Android Gradle Plugin | Library | Google, Apache 2.0 | Android app shell and build |
| cloudflared (Cloudflare quick tunnel) | Tool / service | Cloudflare, Apache 2.0 (client) | Making the laptop backend reachable from phones |
| **OpenStreetMap data** | Dataset | © OpenStreetMap contributors, **ODbL 1.0** | Places, categories, `wheelchair` and other tags. Attribution required; `data/processed/places_lausanne.geojson` is a derived database, so it is ODbL too |
| Overpass API (overpass-api.de) | Service | Free public instance | Downloading Lausanne OSM places and counting tags |
| taginfo (taginfo.openstreetmap.org, taginfo.geofabrik.de) | Service | Free public instances | Tag counts for world / Europe / Switzerland |
| **sonBASE noise maps** (road + rail, day + night, 2021) | Dataset | © FOEN / BAFU, via data.geo.admin.ch (Swiss federal geodata; source attribution required) | `baseline_noise_db`, `baseline_noise_night_db`, the 50 m noise heatmap |

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Data pipeline: tag counts, OSM + sonBASE downloads, place builder (filter, dedupe, day/night noise sampling), 50 m heatmap grid | Siddhi (with Claude Code) | `data/scripts/`, `data/processed/` |
| Backend API: all section 0 endpoints, privacy guard, aggregation and scores, measured heatmap layers, Swagger docs, tests | Siddhi (with Claude Code) | `backend/` |
| Ktor client (`KtorPlacesApi`), per-platform API config, live placeholder screen | Siddhi (with Claude Code) | `shared/`, `composeApp/` |

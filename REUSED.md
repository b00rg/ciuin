# Reused and built

Everything not written during the event (pretrained models, datasets, libraries, prior code) must be listed under **Reused**.

## Reused

| What | Kind | Source / licence | Used for |
|---|---|---|---|
| Kotlin Multiplatform, Compose Multiplatform | Library | JetBrains, Apache 2.0 | App UI and shared code |
| Ktor client, kotlinx.serialization, kotlinx.coroutines | Library | JetBrains, Apache 2.0 | Networking, JSON, concurrency |
| FastAPI, Uvicorn | Library | MIT / BSD-3 | Backend |
| OpenStreetMap raster tiles (tile.openstreetmap.org) | Data / service | © OpenStreetMap contributors, ODbL; OSMF tile usage policy | Map background |

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Backend skeleton with `/health` | | `backend/` |
| Canvas slippy map (tiles, markers, gestures) and map screen | Christine | `composeApp/src/commonMain/kotlin/ch/ciuin/app/` |

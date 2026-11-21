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

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Backend skeleton with `/health` | | `backend/` |

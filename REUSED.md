# Reused and built

Everything not written during the event (pretrained models, datasets, libraries, prior code) must be listed under **Reused**.

## Reused

| What | Kind | Source / licence | Used for |
|---|---|---|---|
| Kotlin Multiplatform, Compose Multiplatform | Library | JetBrains, Apache 2.0 | App UI and shared code |
| Ktor client, kotlinx.serialization, kotlinx.coroutines | Library | JetBrains, Apache 2.0 | Networking, JSON, concurrency |
| FastAPI, Uvicorn | Library | MIT / BSD-3 | Backend |
| ESC-50 | Dataset | https://github.com/karolpiczak/ESC-50, CC BY-NC 3.0 | Audio classifier training/eval (fold 4 val, fold 5 test) |
| UrbanSound8K | Dataset | https://zenodo.org/record/1203745, CC BY-NC 4.0 | Audio classifier training/eval (fold 8 val, folds 9–10 test) |
| MUSAN | Dataset | https://www.openslr.org/17/, CC BY 4.0 | Speech and music classes; background noise for augmentation |

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Backend skeleton with `/health` | | `backend/` |
| Audio dataset downloader | Emma | `ml/download_datasets.py` |

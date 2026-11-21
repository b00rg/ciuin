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
| ESC-50 | Dataset | https://github.com/karolpiczak/ESC-50, CC BY-NC 3.0 | Audio classifier training/eval (fold 4 val, fold 5 test) |
| UrbanSound8K | Dataset | https://zenodo.org/record/1203745, CC BY-NC 4.0 | Audio classifier training/eval (fold 8 val, folds 9–10 test) |
| MUSAN | Dataset | https://www.openslr.org/17/, CC BY 4.0 | Downloaded for speech/music data and augmentation noise; not yet in the trained models (it arrived after the Handoff 2 training started) |
| FSD50K (alarm, siren, crowd subset) | Dataset | https://zenodo.org/record/4060432, CC BY 4.0 (clips CC0 / CC BY / CC BY-NC / CC Sampling+, per clip in its metadata) | Extra `alarm_beep`, `siren` and `speech_crowd` training/eval data (teacher and the Handoff 2 students) |
| DEMAND (PCAFETER, PRESTO, PSTATION, SPSQUARE) | Dataset | https://zenodo.org/record/1227121, CC BY 4.0 | Real crowd ambience for `speech_crowd`; augmentation noise; ambience for tuning and replaying the cascade |

## Built during the event

| What | Who | Where |
|---|---|---|
| KMP project skeleton, shared contracts (FeatureVector, Measurement, Place, TrainingExample) and interfaces | Christine | `shared/`, `composeApp/` |
| Backend skeleton with `/health` | | `backend/` |
| Audio dataset downloader | Emma | `ml/download_datasets.py` |

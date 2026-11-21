# Ciúin: Sensory Map

Kotlin Multiplatform app (Android + Desktop + iOS) that maps how loud, bright, flickery and crowded places are. Plan and contracts: [`Claude/todo.md`](Claude/todo.md). Third-party material: [`REUSED.md`](REUSED.md).

| Folder | What |
|---|---|
| `composeApp/` | Compose Multiplatform UI (Android + Desktop + iOS) |
| `shared/` | KMP library: contracts (`commonMain`), platform code, tests |
| `backend/` | FastAPI server |
| `data/` | Data pipeline scripts; `raw/` is local only, `processed/` is committed |
| `ml/` | Audio model training |

## Setup

The team uses macOS (Emma, Paramveer) and Windows (Siddhi, Christine). Commands differ only where shown.

**Needs:** JDK 17+ (21 is fine), the Android SDK, Python 3.12.

| | macOS | Windows |
|---|---|---|
| JDK | `brew install --cask temurin@21`, or Android Studio's bundled JDK | Temurin 21 installer, or Android Studio's bundled JDK |
| Android SDK | Install Android Studio; open the project once (it writes `local.properties`), or `export ANDROID_HOME=~/Library/Android/sdk` | Android Studio, or set `ANDROID_HOME` to `%LOCALAPPDATA%\Android\Sdk` |
| Python | `python3` (macOS has no `python` by default) | `python` |
| venv activate | `source .venv/bin/activate` | `.venv\Scripts\activate` |
| venv's python | `.venv/bin/python` | `.venv\Scripts\python` |

### App
```sh
./gradlew :composeApp:run              # desktop window (Windows: gradlew.bat or .\gradlew)
./gradlew :composeApp:installDebug     # Android, with an emulator or phone connected
open iosApp/iosApp.xcodeproj           # iOS on macOS: select a simulator and run iosApp
./gradlew :shared:desktopTest          # contract tests
```
The first build downloads Gradle and dependencies (a few minutes).

**Which backend the app talks to:**
| Target | Default | Override |
|---|---|---|
| Desktop | `http://localhost:8000` | `CIUIN_API_URL=https://….trycloudflare.com ./gradlew :composeApp:run` |
| Android | `http://10.0.2.2:8000` (the laptop, seen from the emulator) | `./gradlew :composeApp:installDebug -Pciuin.apiUrl=https://….trycloudflare.com` (real phones need the tunnel URL) |
| iOS Simulator | `http://localhost:8000` | Set `CIUIN_API_URL` in the Xcode scheme’s Run environment or override the build setting |

Use `fake` as the URL to run on the 5 hard-coded places without a backend. If the backend is down, the app shows the error and a Retry button.

Live end-to-end test (writes a measurement, so use a throwaway DB):
```sh
cd backend && CIUIN_DB=/tmp/e2e.db uvicorn main:app --port 8000      # terminal 1
CIUIN_LIVE_API=http://localhost:8000 ./gradlew :shared:desktopTest --tests '*LiveTest'   # terminal 2
```

### Backend
```sh
cd backend
python3 -m venv .venv && source .venv/bin/activate     # Windows: python -m venv .venv; .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### Data pipeline
Scripts in `data/scripts/`. The `fetch_*` / `count_*` scripts need only the standard library; raster work needs `data/requirements.txt`.
```sh
python3 data/scripts/count_tags.py            # tag counts per tier → data/processed/tag_counts.json
python3 data/scripts/fetch_osm_lausanne.py    # → data/raw/osm_lausanne.json
python3 data/scripts/fetch_sonbase.py         # ~730 MB → data/raw/sonbase/
python3 -m venv data/.venv && data/.venv/bin/pip install -r data/requirements.txt
data/.venv/bin/python data/scripts/check_sonbase.py
```
On Windows use `python` and `data\.venv\Scripts\...` instead.

## Windows notes

These only matter on Windows when the repo path contains non-ASCII characters (e.g. `Ciúin`). They're already handled; don't remove them:
- `gradle.properties`: `android.overridePathCheck=true` and `-Dfile.encoding=COMPAT`.
- `data/scripts/raster_env.py`: import before `rasterio`.

## Committing
- `gradlew` and `*.sh` must keep LF line endings (enforced by `.gitattributes`).
- Never commit `data/raw/`, `*.db`, `.env` (tokens) or model files over 50 MB; run `sh scripts/ignore-large-models.sh` before committing exports.

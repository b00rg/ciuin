# backend

FastAPI + SQLite (stdlib `sqlite3`). Implements the API in `Claude/todo.md` section 0.

| File | What |
|---|---|
| `main.py` | Endpoints |
| `models.py` | Contracts (mirror of `shared/commonMain`); measurements reject unknown fields |
| `db.py` | Schema, R-Tree index, WAL, seeding; `python db.py --reset` wipes and re-seeds |
| `aggregate.py` | Mean feature vector + generic [0, 1] scores (1 = calmest); thresholds at the top |
| `tests/` | `python -m pytest -q` |

## Run

macOS / Linux:
```sh
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```
Windows:
```powershell
python -m venv .venv; .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

On first start the database (`ciuin.db`, gitignored) is created and seeded from `data/processed/places_lausanne.geojson`.

- Check: `curl http://localhost:8000/health` → `{"status":"ok"}`
- API docs: http://localhost:8000/docs
- Different DB file: set `CIUIN_DB=/path/to/file.db`

Existing databases receive new columns on startup and retain prior measurements.
In `POST /measurements`, missing readings are null and each per-place mean uses the
measurements that have that reading. Send all nine sound labels when classification is
available. Reuse the optional `measurement_id` with the same payload to retry an upload
without counting it twice.

## Reaching it from phones

Phones and the laptop may not see each other on eduroam. Use a Cloudflare quick tunnel (no account).
Install: `winget install --id Cloudflare.cloudflared -e` (Windows) or `brew install cloudflared` (macOS). Then, with the backend running:
```sh
cloudflared tunnel --no-autoupdate --protocol http2 --url http://127.0.0.1:8000
```
- It prints a public `https://<random-words>.trycloudflare.com` URL. Point the app at it:
  `./gradlew :composeApp:installDebug -Pciuin.apiUrl=https://<…>.trycloudflare.com`
- `--protocol http2`: the default (QUIC over UDP) fails on the venue network; HTTP/2 over TCP works. Tested 21 Nov: Android emulator → tunnel → laptop, all Lausanne places in ~0.3 s.
- **The URL changes every time the tunnel restarts**, and the Android app has it baked in at build time. Keep one tunnel running for the whole event and post the URL in the team chat; if it restarts, rebuild the app with the new URL.
- The URL is public: anyone who has it can read places and post measurements. Share it only in the team chat.
- Fallback: phone hotspot, with the laptop's hotspot IP as the URL (`http://192.168.x.x:8000`, run uvicorn with `--host 0.0.0.0`).

Everyone should test against this one shared backend so all measurements land in one database.

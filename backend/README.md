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
- Schema changed? `python db.py --reset` (measurements are lost)

## Reaching it from phones

Phones and the laptop may not see each other on eduroam. Use a Cloudflare quick tunnel (no account):
```sh
cloudflared tunnel --url http://localhost:8000
```
It prints a public `https://….trycloudflare.com` URL; point the app at that. Fallback: phone hotspot.
Everyone should test against this one shared backend so all measurements land in one database.

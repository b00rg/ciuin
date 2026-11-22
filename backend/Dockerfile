# Backend image for Railway (or any Docker host). Build from the REPO ROOT so the data files are included:
#   docker build -f backend/Dockerfile .
# Only backend/ and data/processed/ (~5 MB) go in; see .dockerignore (data/raw is ~1.5 GB and never shipped).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    # SQLite lives on the persistent volume mounted at /data, so measurements survive redeploys
    CIUIN_DB=/data/ciuin.db \
    # each deploy ships fresh places/heatmap files: refresh places on start (measurements are kept)
    CIUIN_RESEED_ON_START=1

WORKDIR /app
COPY backend/requirements.txt backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt

COPY backend/ backend/
COPY data/processed/ data/processed/
RUN mkdir -p /data

WORKDIR /app/backend
# Railway sets $PORT; default 8000 elsewhere
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]

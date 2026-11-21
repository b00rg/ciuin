"""Sensory Map backend. Endpoints from Claude/todo.md section 0 go here."""

from fastapi import FastAPI

app = FastAPI(title="Sensory Map API")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

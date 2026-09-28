from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pymongo.database import Database

from app.auth.dependencies import get_db
from app.database.mongo import ping

SERVICE_NAME = "geo-tree-api"
API_VERSION = "0.1.0"

router = APIRouter(prefix="/api/v1", tags=["health"])


@router.get("/health")
def health(db: Database = Depends(get_db)) -> JSONResponse:
    """Clients use ``service`` to confirm they reached a GEO Tree backend."""
    database_ok = ping(db)
    return JSONResponse(
        status_code=200 if database_ok else 503,
        content={
            "status": "ok" if database_ok else "degraded",
            "service": SERVICE_NAME,
            "version": API_VERSION,
            "database": "ok" if database_ok else "unavailable",
        },
    )

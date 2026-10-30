"""Mobile Settings for the web administration: the Android release served by /download and its
history, the stable download link/QR code, and the AI model and dataset profiles.

Reading is for Admins and Super Admins. Registering a release (which changes what the history
calls "current") is Super Admin only. Dataset archives are uploaded by administrators for
validation/staging; nothing here activates a dataset or starts training.
"""

from __future__ import annotations

import base64
import io
from typing import Any

from fastapi import APIRouter, Depends, File, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from pymongo.database import Database
from starlette.concurrency import run_in_threadpool

from app.auth.dependencies import get_db, get_settings, require_admin, require_super_admin
from app.config import Settings
from app.errors import ApiError
from app.routes.pagination import PageParams, page_params
from app.services import dataset_archive, mobile_profile, mobile_release

router = APIRouter(prefix="/api/v1/admin/mobile", tags=["admin: mobile settings"])


class ReleaseRegister(BaseModel):
    notes: str = Field(default="", max_length=2000)
    dataset_archive_id: str | None = Field(default=None, alias="datasetArchiveId", max_length=64)
    baseline: bool = False


def qr_png_data_uri(url: str) -> str:
    """A QR code (PNG data URI) that encodes exactly ``url``; segno is pure Python, imported on use."""
    import segno

    buffer = io.BytesIO()
    segno.make(url, error="m").save(buffer, kind="png", scale=8, border=4)
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


@router.get("/overview")
async def overview(db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                   _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    # Hashing a newly replaced APK takes seconds: keep it off the event loop.
    result = await run_in_threadpool(mobile_release.overview, db, settings)
    url = settings.download_public_url
    result["qr"] = {"url": url, "pngDataUri": qr_png_data_uri(url)} if url else None
    return result


@router.get("/releases")
def releases(paging: PageParams = Depends(page_params), db: Database = Depends(get_db),
             _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    items, total = mobile_release.history(db, paging.skip, paging.page_size)
    return paging.response(items, total)


@router.post("/releases", status_code=201)
async def register_release(body: ReleaseRegister, db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                           user: dict[str, Any] = Depends(require_super_admin)) -> dict[str, Any]:
    return await run_in_threadpool(mobile_release.register, db, settings, user, body.notes, body.dataset_archive_id, body.baseline)


@router.get("/model")
async def model(request: Request, db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    ai_runtime = getattr(request.app.state, "ai", None) is not None
    return await run_in_threadpool(mobile_profile.model_profile, db, settings, ai_runtime)


@router.get("/dataset")
def dataset(db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
            _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return mobile_profile.dataset_profile(db, settings)


@router.get("/dataset/archives")
def archives(paging: PageParams = Depends(page_params), db: Database = Depends(get_db),
             _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    items, total = dataset_archive.page(db, paging.skip, paging.page_size)
    return paging.response(items, total)


@router.post("/dataset/archives", status_code=202)
async def upload_archive(request: Request, file: UploadFile = File(...), db: Database = Depends(get_db),
                         settings: Settings = Depends(get_settings), user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    dataset_archive.archive_root(settings.dataset_archive_dir)  # 503 before reading anything when disabled
    declared = int(request.headers.get("content-length") or 0)
    if declared > settings.dataset_archive_max_bytes + 1024 * 1024:
        raise ApiError(413, "ARCHIVE_TOO_LARGE", f"The archive exceeds {settings.dataset_archive_max_bytes // (1024 * 1024)} MB.")
    record = await run_in_threadpool(dataset_archive.store_upload, db, settings.dataset_archive_dir, file.file,
                                     file.filename or "archive.zip", settings.dataset_archive_max_bytes, user)
    return dataset_archive.view(record)


@router.get("/dataset/archives/{archive_id}")
def archive(archive_id: str, db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return dataset_archive.view(dataset_archive.get(db, archive_id))


@router.get("/dataset/archives/{archive_id}/manifest.csv")
def archive_manifest(archive_id: str, db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                     _: dict[str, Any] = Depends(require_admin)) -> FileResponse:
    dataset_archive.get(db, archive_id)
    return FileResponse(dataset_archive.manifest_path(settings.dataset_archive_dir, archive_id), media_type="text/csv",
                        filename=f"dataset-archive-{archive_id[:8]}-manifest.csv")


@router.delete("/dataset/archives/{archive_id}")
def discard_archive(archive_id: str, db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                    user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return dataset_archive.view(dataset_archive.discard(db, settings.dataset_archive_dir, archive_id, user))

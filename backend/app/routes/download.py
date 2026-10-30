"""Stable APK download page: https://<host>/download (the QR code target) and the current APK.

The page and APK live in DISTRIBUTION_DIR (a read-only folder outside the app image), so a new
release only replaces those two files: the URL and the printed QR code never change. Public on
purpose (people install the app before they have an account); nothing else is served from there.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, RedirectResponse

from app.auth.dependencies import get_settings
from app.config import Settings
from app.errors import ApiError
from app.services.mobile_release import APK_FILE_NAME

router = APIRouter(tags=["download"])

PAGE = "index.html"
APK = APK_FILE_NAME
_NO_CACHE = {"Cache-Control": "no-cache"}


def _file(settings: Settings, name: str):
    if settings.distribution_dir is None:
        raise ApiError(404, "NOT_FOUND", "Downloads are not published on this server.")
    path = settings.distribution_dir / name
    if not path.is_file():
        raise ApiError(404, "NOT_FOUND", "No app release is published yet.")
    return path


@router.get("/download", include_in_schema=False)
def download_page(settings: Settings = Depends(get_settings)) -> FileResponse:
    return FileResponse(_file(settings, PAGE), media_type="text/html; charset=utf-8", headers=_NO_CACHE)


@router.get("/download/", include_in_schema=False)
def download_page_slash() -> RedirectResponse:
    return RedirectResponse("/download", status_code=308)


@router.get(f"/download/{APK}", include_in_schema=False)
def download_apk(settings: Settings = Depends(get_settings)) -> FileResponse:
    return FileResponse(
        _file(settings, APK),
        media_type="application/vnd.android.package-archive",
        filename=APK,
        headers=_NO_CACHE,
    )

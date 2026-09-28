from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse

from app.auth.dependencies import get_current_user, get_settings
from app.config import Settings
from app.errors import ApiError
from app.services.image_storage import resolve_image

router = APIRouter(prefix="/uploads", tags=["uploads"])

_MEDIA_TYPES = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


@router.get("/tree_images/{filename}")
def tree_image(
    filename: str,
    settings: Settings = Depends(get_settings),
    _: dict[str, Any] = Depends(get_current_user),
) -> FileResponse:
    path = resolve_image(settings.upload_dir, filename)
    if path is None:
        raise ApiError(404, "IMAGE_NOT_FOUND", "Image not found.")
    return FileResponse(path, media_type=_MEDIA_TYPES[path.suffix.lstrip(".")])

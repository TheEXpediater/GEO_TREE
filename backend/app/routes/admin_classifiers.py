"""Admin-only management of classifier classes, their dataset images, and model training."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, File, UploadFile
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from app.auth.dependencies import get_ai_service, require_admin
from app.schemas.ai import ClassifierCreate, ClassifierUpdate

router = APIRouter(prefix="/api/v1/admin", tags=["admin: classifiers"], dependencies=[Depends(require_admin)])


@router.get("/classifiers")
def list_classifiers(ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_classes()


@router.post("/classifiers", status_code=201)
def create_classifier(body: ClassifierCreate, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.create_class(body.name)


@router.patch("/classifiers/{class_id}")
def rename_classifier(class_id: str, body: ClassifierUpdate, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.rename_class(class_id, body.name)


@router.delete("/classifiers/{class_id}")
def delete_classifier(class_id: str, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.delete_class(class_id)


@router.get("/classifiers/{class_id}/images")
def list_images(class_id: str, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_images(class_id)


@router.post("/classifiers/{class_id}/images")
async def upload_images(class_id: str, files: list[UploadFile] = File(...), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    payload: list[tuple[str | None, bytes]] = []
    for upload in files[: ai.settings.max_files_per_upload + 1]:
        # Read one byte past the limit so oversize files are detected without reading them fully.
        payload.append((upload.filename, await upload.read(ai.max_image_bytes + 1)))
    # Validation, storage and DINOv2 feature extraction are blocking work: keep them off the event loop.
    return await run_in_threadpool(ai.add_images, class_id, payload)


@router.delete("/classifiers/{class_id}/images/{image_id}")
def delete_image(class_id: str, image_id: str, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.delete_image(class_id, image_id)


@router.get("/classifiers/{class_id}/images/{image_id}/thumbnail")
def image_thumbnail(class_id: str, image_id: str, ai: Any = Depends(get_ai_service)) -> FileResponse:
    path, media_type = ai.image_file(class_id, image_id, thumbnail=True)
    return FileResponse(path, media_type=media_type)


@router.get("/classifiers/{class_id}/images/{image_id}/file")
def image_file(class_id: str, image_id: str, ai: Any = Depends(get_ai_service)) -> FileResponse:
    path, media_type = ai.image_file(class_id, image_id, thumbnail=False)
    return FileResponse(path, media_type=media_type)


@router.get("/ai/model")
def model_status(ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.model_status()


@router.post("/ai/retrain", status_code=202)
def retrain(ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.retrain()

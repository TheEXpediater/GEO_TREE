"""Admin-only management of classifier classes, their dataset images, and model training.

Used by both the Android admin screens and the web administration; every change is audited.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, File, Query, Request, UploadFile
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from app.auth.dependencies import get_ai_service, require_admin
from app.schemas.ai import ClassifierCreate, ClassifierUpdate
from app.services import audit

router = APIRouter(prefix="/api/v1/admin", tags=["admin: classifiers"], dependencies=[Depends(require_admin)])


def _db(request: Request) -> Any:
    return request.app.state.db


@router.get("/classifiers")
def list_classifiers(ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_classes()


@router.post("/classifiers", status_code=201)
def create_classifier(body: ClassifierCreate, request: Request, ai: Any = Depends(get_ai_service),
                      user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    created = ai.create_class(body.name)
    audit.record(_db(request), user, "classifier.create", f"Added classifier class {created.get('name')}", "classifier_class", created.get("id"))
    return created


@router.patch("/classifiers/{class_id}")
def rename_classifier(class_id: str, body: ClassifierUpdate, request: Request, ai: Any = Depends(get_ai_service),
                      user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    renamed = ai.rename_class(class_id, body.name)
    audit.record(_db(request), user, "classifier.rename", f"Renamed classifier class to {renamed.get('name')}", "classifier_class", class_id)
    return renamed


@router.delete("/classifiers/{class_id}")
def delete_classifier(class_id: str, request: Request, ai: Any = Depends(get_ai_service),
                      user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    result = ai.delete_class(class_id)
    audit.record(_db(request), user, "classifier.delete", "Deleted a classifier class and its training images", "classifier_class", class_id,
                 {key: result[key] for key in ("deletedImages", "modelInvalidated") if key in result})
    return result


@router.get("/classifiers/{class_id}/images")
def list_images(class_id: str, ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_images(class_id)


@router.post("/classifiers/{class_id}/images")
async def upload_images(class_id: str, request: Request, files: list[UploadFile] = File(...), ai: Any = Depends(get_ai_service),
                        user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    payload: list[tuple[str | None, bytes]] = []
    for upload in files[: ai.settings.max_files_per_upload + 1]:
        # Read one byte past the limit so oversize files are detected without reading them fully.
        payload.append((upload.filename, await upload.read(ai.max_image_bytes + 1)))
    # Validation, storage and DINOv2 feature extraction are blocking work: keep them off the event loop.
    result = await run_in_threadpool(ai.add_images, class_id, payload)
    uploaded, rejected = len(result.get("uploaded", [])), len(result.get("rejected", []))
    audit.record(_db(request), user, "classifier.images_upload", f"Uploaded {uploaded} training image(s)", "classifier_class", class_id,
                 {"uploaded": uploaded, "rejected": rejected})
    return result


@router.delete("/classifiers/{class_id}/images/{image_id}")
def delete_image(class_id: str, image_id: str, request: Request, ai: Any = Depends(get_ai_service),
                 user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    result = ai.delete_image(class_id, image_id)
    audit.record(_db(request), user, "classifier.image_delete", "Deleted a training image", "classifier_image", image_id, {"classId": class_id})
    return result


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


@router.get("/ai/models")
def model_versions(limit: int = Query(default=20, ge=1, le=100), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_models(limit)


@router.get("/ai/jobs")
def training_jobs(limit: int = Query(default=20, ge=1, le=100), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.list_jobs(limit)


@router.post("/ai/retrain", status_code=202)
def retrain(request: Request, ai: Any = Depends(get_ai_service), user: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    job = ai.retrain()
    audit.record(_db(request), user, "model.retrain_requested", "Requested a classifier rebuild", "ai_job", job.get("jobId") if job else None)
    return job

"""Leaf assessment history: sync from devices, per-tree history, detail and change feed.

These are plain data routes (no DINOv2 needed), so they also work where AI_ENABLED=false.
Inference itself stays in ``routes/ai.py``; the device records each result and syncs it here.
"""

from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Query, Response, UploadFile
from pymongo.database import Database

from app.auth.dependencies import get_current_user, get_db, get_settings
from app.config import Settings
from app.errors import ApiError
from app.repositories import leaf_assessment_repository, tree_repository
from app.schemas.leaf_assessment import (
    LeafAssessmentChangesResponse,
    LeafAssessmentListResponse,
    LeafAssessmentOut,
    LeafAssessmentSyncRequest,
    LeafAssessmentSyncResponse,
)
from app.services import image_storage, leaf_assessment_sync

router = APIRouter(prefix="/api/v1", tags=["leaf-assessments"])


@router.post("/leaf-assessments/sync", response_model=LeafAssessmentSyncResponse)
def sync(
    body: LeafAssessmentSyncRequest,
    db: Database = Depends(get_db),
    user: dict[str, Any] = Depends(get_current_user),
) -> LeafAssessmentSyncResponse:
    result, document = leaf_assessment_sync.sync_assessment(db, body, user["email"])
    return LeafAssessmentSyncResponse(result=result, assessment=LeafAssessmentOut.from_document(document))


@router.get("/leaf-assessments/changes", response_model=LeafAssessmentChangesResponse)
def changes(
    after_version: int = Query(default=0, ge=0),
    limit: int = Query(default=200, ge=1, le=500),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(get_current_user),
) -> LeafAssessmentChangesResponse:
    documents = leaf_assessment_repository.changes_after(db, after_version, limit + 1)
    has_more = len(documents) > limit
    items = [LeafAssessmentOut.from_document(doc) for doc in documents[:limit]]
    latest = items[-1].server_version if items else after_version
    return LeafAssessmentChangesResponse(items=items, latest_version=latest, has_more=has_more)


@router.get("/leaf-assessments/{assessment_id}", response_model=LeafAssessmentOut)
def get_assessment(
    assessment_id: UUID, db: Database = Depends(get_db), _: dict[str, Any] = Depends(get_current_user)
) -> LeafAssessmentOut:
    document = leaf_assessment_repository.find_by_id(db, str(assessment_id))
    if document is None:
        raise ApiError(404, "ASSESSMENT_NOT_FOUND", "Leaf assessment not found.")
    return LeafAssessmentOut.from_document(document)


@router.post("/leaf-assessments/{assessment_id}/image", response_model=LeafAssessmentOut)
async def upload_image(
    assessment_id: UUID,
    response: Response,
    file: UploadFile = File(...),
    db: Database = Depends(get_db),
    settings: Settings = Depends(get_settings),
    _: dict[str, Any] = Depends(get_current_user),
) -> LeafAssessmentOut:
    """Stores the scan's photo once under ``uploads/leaf_assessments/``. Never training data."""
    existing = leaf_assessment_repository.find_by_id(db, str(assessment_id))
    if existing is None:
        raise ApiError(404, "ASSESSMENT_NOT_FOUND", "Sync the assessment before uploading its photo.")
    if existing.get("image_path"):
        return LeafAssessmentOut.from_document(existing)  # idempotent: the first photo stays
    if not settings.image_storage_enabled:
        # No durable photo storage on this host: the scan is synced, its photo stays on the phone.
        response.headers[image_storage.IMAGE_STORAGE_HEADER] = "disabled"
        return LeafAssessmentOut.from_document(existing)
    data = await file.read(settings.max_image_bytes + 1)
    image_path = image_storage.save_assessment_image(settings.upload_dir, str(assessment_id), data, settings.max_image_bytes)
    updated = leaf_assessment_sync.attach_image(db, str(assessment_id), image_path)
    return LeafAssessmentOut.from_document(updated or leaf_assessment_repository.find_by_id(db, str(assessment_id)))


@router.get("/trees/{tree_id}/leaf-assessments", response_model=LeafAssessmentListResponse)
def tree_history(
    tree_id: UUID,
    limit: int = Query(default=200, ge=1, le=500),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(get_current_user),
) -> LeafAssessmentListResponse:
    if tree_repository.find_by_id(db, str(tree_id)) is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Tree not found.")
    documents = leaf_assessment_repository.for_tree(db, str(tree_id), limit)
    return LeafAssessmentListResponse(items=[LeafAssessmentOut.from_document(doc) for doc in documents])

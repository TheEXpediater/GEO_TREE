from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Query, UploadFile
from pymongo.database import Database

from app.auth.dependencies import get_current_user, get_db, get_settings
from app.config import Settings
from app.errors import ApiError
from app.repositories import tree_repository
from app.schemas.tree import TreeChangesResponse, TreeOut, TreeSyncRequest, TreeSyncResponse
from app.services import image_storage, tree_sync

router = APIRouter(prefix="/api/v1/trees", tags=["trees"])


@router.post("/sync", response_model=TreeSyncResponse)
def sync(
    body: TreeSyncRequest,
    db: Database = Depends(get_db),
    user: dict[str, Any] = Depends(get_current_user),
) -> TreeSyncResponse:
    result, document = tree_sync.sync_tree(db, body, user["email"])
    return TreeSyncResponse(result=result, tree=TreeOut.from_document(document))


@router.get("/changes", response_model=TreeChangesResponse)
def changes(
    after_version: int = Query(default=0, ge=0),
    limit: int = Query(default=200, ge=1, le=500),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(get_current_user),
) -> TreeChangesResponse:
    # Fetch one extra row to know whether another page exists.
    documents = tree_repository.changes_after(db, after_version, limit + 1)
    has_more = len(documents) > limit
    items = [TreeOut.from_document(doc) for doc in documents[:limit]]
    latest = items[-1].server_version if items else after_version
    return TreeChangesResponse(items=items, latest_version=latest, has_more=has_more)


@router.get("/{tree_id}", response_model=TreeOut)
def get_tree(tree_id: UUID, db: Database = Depends(get_db), _: dict[str, Any] = Depends(get_current_user)) -> TreeOut:
    document = tree_repository.find_by_id(db, str(tree_id))
    if document is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Tree not found.")
    return TreeOut.from_document(document)


@router.post("/{tree_id}/image", response_model=TreeOut)
async def upload_image(
    tree_id: UUID,
    file: UploadFile = File(...),
    db: Database = Depends(get_db),
    settings: Settings = Depends(get_settings),
    _: dict[str, Any] = Depends(get_current_user),
) -> TreeOut:
    existing = tree_repository.find_by_id(db, str(tree_id))
    if existing is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Sync the tree metadata before uploading its image.")
    data = await file.read(settings.max_image_bytes + 1)
    image_path = image_storage.save_tree_image(settings.upload_dir, str(tree_id), data, settings.max_image_bytes)
    updated = tree_sync.attach_image(db, str(tree_id), image_path)
    if existing.get("image_path") and existing["image_path"] != image_path:
        image_storage.delete_image(settings.upload_dir, existing["image_path"])
    return TreeOut.from_document(updated)

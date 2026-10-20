"""Tree records and general tags for the web administration (Admin and Super Admin).

Editing rules that keep the Android field app consistent:

* GPS coordinates, accuracy and capture time are field evidence: never edited here.
* A metadata edit is a normal synced change (new ``updated_at`` and ``server_version``), so phones
  pull it; the prototype conflict policy (newer ``updated_at`` wins) applies as for phone edits.
* Archiving is a soft delete: ``archived`` + a new ``server_version`` only (``updated_at`` is kept,
  so a pending phone edit is not discarded). Phones hide archived trees after their next pull.
  Leaf assessments and photos of an archived tree are kept; restoring brings the tree back.
* Tags are web-only metadata; phones never send ``tag_ids``, so syncing never removes them.
* Assessment history is never modified from these routes.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Query, UploadFile
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.auth.dependencies import get_db, get_settings, require_admin
from app.config import Settings
from app.database.mongo import LEAF_ASSESSMENTS
from app.errors import ApiError
from app.repositories import tag_repository, tree_repository
from app.routes.pagination import PageParams, page_params
from app.schemas.admin import TagCreate, TagUpdate, TreeUpdate
from app.services import audit, field_quality, image_storage, tree_sync

router = APIRouter(prefix="/api/v1/admin", tags=["admin: trees and tags"])

_SYNCED_EDIT_FIELDS = ("tree_code", "age", "taste_category", "yearly_yield", "fruit_quality", "notes")
_CAMEL = {"tree_code": "treeCode", "taste_category": "tasteCategory", "yearly_yield": "yearlyYield", "fruit_quality": "fruitQuality"}


def image_url(image_path: str | None) -> str | None:
    """The authenticated download route for a stored image (never a file system path)."""
    return f"/uploads/{image_path}" if image_path else None


def _tag_view(tag: dict[str, Any], tree_count: int | None = None) -> dict[str, Any]:
    view = {"id": tag["id"], "name": tag["name"], "color": tag.get("color", "moss"), "description": tag.get("description"),
            "createdAt": tag.get("created_at"), "updatedAt": tag.get("updated_at")}
    if tree_count is not None:
        view["treeCount"] = tree_count
    return view


def _assessment_summaries(db: Database, tree_ids: list[str]) -> dict[str, dict[str, Any]]:
    summaries: dict[str, dict[str, Any]] = {}
    if not tree_ids:
        return summaries
    projection = {"_id": False, "assessment_id": 1, "tree_id": 1, "status": 1, "class_name": 1, "confidence": 1, "created_at": 1}
    for doc in db[LEAF_ASSESSMENTS].find({"tree_id": {"$in": tree_ids}}, projection).sort("created_at", -1):
        entry = summaries.setdefault(doc["tree_id"], {"count": 0, "latest": None})
        entry["count"] += 1
        if entry["latest"] is None:
            entry["latest"] = {"id": doc["assessment_id"], "status": doc.get("status"), "className": doc.get("class_name"),
                               "confidence": doc.get("confidence"), "createdAt": doc.get("created_at")}
    return summaries


def tree_view(tree: dict[str, Any], tags: dict[str, dict[str, Any]], summary: dict[str, Any] | None) -> dict[str, Any]:
    tag_ids = [tag_id for tag_id in tree.get("tag_ids") or [] if tag_id in tags]
    return {
        "id": tree["id"],
        "treeCode": tree["tree_code"],
        "latitude": tree["latitude"],
        "longitude": tree["longitude"],
        "accuracyMeters": tree["accuracy_meters"],
        "accuracyLevel": field_quality.accuracy_level(tree.get("accuracy_meters")),
        "altitudeMeters": tree.get("altitude_meters"),
        "locationCapturedAt": tree.get("location_captured_at"),
        "age": tree.get("age"),
        "tasteCategory": tree.get("taste_category"),
        "yearlyYield": tree.get("yearly_yield"),
        "fruitQuality": tree.get("fruit_quality"),
        "notes": tree.get("notes"),
        "imageUrl": image_url(tree.get("image_path")),
        "createdAt": tree.get("created_at"),
        "updatedAt": tree.get("updated_at"),
        "serverUpdatedAt": tree.get("server_updated_at"),
        "serverVersion": tree.get("server_version"),
        "createdBy": tree.get("created_by"),
        "updatedBy": tree.get("updated_by"),
        "archived": bool(tree.get("archived", False)),
        "archivedAt": tree.get("archived_at"),
        "archivedBy": tree.get("archived_by"),
        "tags": [_tag_view(tags[tag_id]) for tag_id in tag_ids],
        "completeness": field_quality.completeness(tree),
        "assessmentCount": (summary or {}).get("count", 0),
        "latestAssessment": (summary or {}).get("latest"),
    }


def _single_view(db: Database, tree: dict[str, Any]) -> dict[str, Any]:
    tags = tag_repository.by_ids(db, tree.get("tag_ids") or [])
    return tree_view(tree, tags, _assessment_summaries(db, [tree["id"]]).get(tree["id"]))


def _require_tree(db: Database, tree_id: UUID) -> dict[str, Any]:
    tree = tree_repository.find_by_id(db, str(tree_id))
    if tree is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Tree not found.")
    return tree


# Trees ------------------------------------------------------------------------------------------
@router.get("/trees")
def list_trees(
    q: str | None = Query(default=None, max_length=40),
    status: str = Query(default="active", pattern="^(active|archived|all)$"),
    has_image: bool | None = Query(default=None, alias="hasImage"),
    tag_id: str | None = Query(default=None, alias="tagId", max_length=64),
    sort: str = Query(default="-createdAt", pattern="^-?(treeCode|createdAt|updatedAt)$"),
    paging: PageParams = Depends(page_params),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    selector = tree_repository.admin_filter(q, status, has_image, tag_id)
    trees, total = tree_repository.admin_page(db, selector, sort, paging.skip, paging.page_size)
    tags = tag_repository.by_ids(db, [tag_id for tree in trees for tag_id in tree.get("tag_ids") or []])
    summaries = _assessment_summaries(db, [tree["id"] for tree in trees])
    return paging.response([tree_view(tree, tags, summaries.get(tree["id"])) for tree in trees], total)


@router.get("/trees/{tree_id}")
def get_tree(tree_id: UUID, db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return _single_view(db, _require_tree(db, tree_id))


@router.patch("/trees/{tree_id}")
def update_tree(tree_id: UUID, body: TreeUpdate, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    tree = _require_tree(db, tree_id)
    provided = body.model_fields_set
    now = datetime.now(timezone.utc)
    fields: dict[str, Any] = {}
    changed: list[str] = []

    for name in _SYNCED_EDIT_FIELDS:
        if name in provided and getattr(body, name) != tree.get(name):
            if name == "tree_code" and body.tree_code is None:
                raise ApiError(422, "VALIDATION_ERROR", "Tree Code cannot be empty.")
            fields[name] = getattr(body, name)
            changed.append(_CAMEL.get(name, name))
    if "tree_code" in fields:
        holder = tree_repository.find_by_code(db, fields["tree_code"])
        if holder is not None and holder["id"] != tree["id"]:
            raise ApiError(409, "TREE_CODE_CONFLICT", f"Tree Code {fields['tree_code']} is already registered to another tree.",
                           {"tree_code": fields["tree_code"], "existing_id": holder["id"]})
        fields["tree_code_normalized"] = fields["tree_code"]
    if fields:
        fields.update(updated_at=now, updated_by=actor["email"], server_updated_at=now,
                      server_version=tree_repository.next_server_version(db))

    if "tag_ids" in provided:
        requested = list(dict.fromkeys(body.tag_ids or []))
        known = tag_repository.by_ids(db, requested)
        unknown = [tag_id for tag_id in requested if tag_id not in known]
        if unknown:
            raise ApiError(422, "TAG_NOT_FOUND", "One or more tags do not exist.", {"tagIds": unknown})
        if requested != (tree.get("tag_ids") or []):
            fields["tag_ids"] = requested
            changed.append("tags")

    if not fields:
        return _single_view(db, tree)
    try:
        updated = tree_repository.update(db, tree["id"], fields)
    except DuplicateKeyError as exc:
        raise ApiError(409, "TREE_CODE_CONFLICT", "That Tree Code was just taken by another tree.") from exc
    audit.record(db, actor, "tree.update", f"Edited tree {updated['tree_code']}", "tree", tree["id"], {"fields": changed})  # type: ignore[index]
    return _single_view(db, updated)  # type: ignore[arg-type]


def _set_archived(db: Database, tree: dict[str, Any], archived: bool, actor: dict[str, Any]) -> dict[str, Any]:
    if bool(tree.get("archived", False)) == archived:
        return tree
    now = datetime.now(timezone.utc)
    updated = tree_repository.update(db, tree["id"], {
        "archived": archived,
        "archived_at": now if archived else None,
        "archived_by": actor["email"] if archived else None,
        "server_version": tree_repository.next_server_version(db),
        "server_updated_at": now,
    })
    verb = "Archived" if archived else "Restored"
    audit.record(db, actor, "tree.archive" if archived else "tree.restore", f"{verb} tree {tree['tree_code']}", "tree", tree["id"])
    return updated  # type: ignore[return-value]


@router.post("/trees/{tree_id}/archive")
def archive_tree(tree_id: UUID, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return _single_view(db, _set_archived(db, _require_tree(db, tree_id), True, actor))


@router.post("/trees/{tree_id}/restore")
def restore_tree(tree_id: UUID, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    return _single_view(db, _set_archived(db, _require_tree(db, tree_id), False, actor))


@router.post("/trees/{tree_id}/image")
async def replace_tree_image(
    tree_id: UUID,
    file: UploadFile = File(...),
    db: Database = Depends(get_db),
    settings: Settings = Depends(get_settings),
    actor: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    """Uploads or replaces the registration photo (validated by content, stored under a generated name)."""
    tree = _require_tree(db, tree_id)
    if not settings.image_storage_enabled:
        raise ApiError(503, "IMAGE_STORAGE_DISABLED", "This server does not store photos; registration photos stay on the field phones.")
    data = await file.read(settings.max_image_bytes + 1)
    image_path = image_storage.save_tree_image(settings.upload_dir, tree["id"], data, settings.max_image_bytes)
    updated = tree_sync.attach_image(db, tree["id"], image_path)
    if tree.get("image_path") and tree["image_path"] != image_path:
        image_storage.delete_image(settings.upload_dir, tree["image_path"])
    original = Path(file.filename or "").name[:120] or None
    audit.record(db, actor, "tree.image_replace" if tree.get("image_path") else "tree.image_upload",
                 f"New registration photo for tree {tree['tree_code']}", "tree", tree["id"], {"originalFilename": original})
    return _single_view(db, updated)


@router.delete("/trees/{tree_id}/image")
def delete_tree_image(tree_id: UUID, db: Database = Depends(get_db), settings: Settings = Depends(get_settings),
                      actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    tree = _require_tree(db, tree_id)
    if not tree.get("image_path"):
        return _single_view(db, tree)
    now = datetime.now(timezone.utc)
    updated = tree_repository.update(db, tree["id"], {"image_path": None, "server_version": tree_repository.next_server_version(db),
                                                      "server_updated_at": now})
    image_storage.delete_image(settings.upload_dir, tree["image_path"])
    audit.record(db, actor, "tree.image_delete", f"Deleted the registration photo of tree {tree['tree_code']}", "tree", tree["id"])
    return _single_view(db, updated)  # type: ignore[arg-type]


# Tags -------------------------------------------------------------------------------------------
@router.get("/tags")
def list_tags(db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    counts = tag_repository.tree_counts(db)
    return {"items": [_tag_view(tag, counts.get(tag["id"], 0)) for tag in tag_repository.list_all(db)]}


def _name_conflict(name: str) -> ApiError:
    return ApiError(409, "TAG_NAME_CONFLICT", f"A tag named {name} already exists.", {"name": name})


@router.post("/tags", status_code=201)
def create_tag(body: TagCreate, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    tag = {"id": str(uuid.uuid4()), "name": body.name, "slug": tag_repository.slugify(body.name), "color": body.color,
           "description": body.description, "created_at": now, "updated_at": now, "created_by": actor["email"]}
    if not tag_repository.insert(db, tag):
        raise _name_conflict(body.name)
    audit.record(db, actor, "tag.create", f"Created tag {body.name}", "tag", tag["id"])
    return _tag_view(tag, 0)


@router.patch("/tags/{tag_id}")
def update_tag(tag_id: str, body: TagUpdate, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    tag = tag_repository.find(db, tag_id)
    if tag is None:
        raise ApiError(404, "TAG_NOT_FOUND", "Tag not found.")
    fields: dict[str, Any] = {}
    if body.name is not None and body.name != tag["name"]:
        fields.update(name=body.name, slug=tag_repository.slugify(body.name))
    if body.color is not None and body.color != tag.get("color"):
        fields["color"] = body.color
    if "description" in body.model_fields_set and body.description != tag.get("description"):
        fields["description"] = body.description
    if fields:
        fields["updated_at"] = datetime.now(timezone.utc)
        try:
            tag = tag_repository.update(db, tag_id, fields) or tag
        except DuplicateKeyError as exc:
            raise _name_conflict(body.name or tag["name"]) from exc
        audit.record(db, actor, "tag.update", f"Edited tag {tag['name']}", "tag", tag_id, {"fields": sorted(fields)})
    return _tag_view(tag, tag_repository.tree_counts(db).get(tag_id, 0))


@router.delete("/tags/{tag_id}")
def delete_tag(tag_id: str, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    tag = tag_repository.find(db, tag_id)
    if tag is None:
        raise ApiError(404, "TAG_NOT_FOUND", "Tag not found.")
    detached = tag_repository.delete(db, tag_id)
    audit.record(db, actor, "tag.delete", f"Deleted tag {tag['name']} (removed from {detached} trees)", "tag", tag_id)
    return {"deleted": True, "id": tag_id, "treesDetached": detached}

"""Tree synchronization rules.

Prototype conflict policy (documented in README):

* Identity is the device-generated UUID. Re-sending the same UUID updates, never duplicates.
* A Tree Code is unique across all trees (case-insensitive). A different UUID using an
  existing Tree Code is rejected with 409 TREE_CODE_CONFLICT; nothing is overwritten.
* For the same UUID, the write with the newer ``updated_at`` wins. A write whose
  ``updated_at`` is not newer than the stored one is a no-op ("unchanged"), which makes
  repeated sync idempotent and ignores stale retries.
* Every accepted change receives a new server-side ``server_version``.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.errors import ApiError
from app.repositories import tree_repository
from app.schemas.tree import TreeSyncRequest

_SYNC_FIELDS = (
    "tree_code",
    "latitude",
    "longitude",
    "accuracy_meters",
    "altitude_meters",
    "location_captured_at",
    "age",
    "taste_category",
    "yearly_yield",
    "fruit_quality",
    "notes",
    "updated_at",
)


def _as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def _conflict(tree_code: str, existing_id: str) -> ApiError:
    return ApiError(
        409,
        "TREE_CODE_CONFLICT",
        f"Tree Code {tree_code} is already registered to another tree.",
        {"tree_code": tree_code, "existing_id": existing_id},
    )


def sync_tree(db: Database, request: TreeSyncRequest, user_email: str) -> tuple[str, dict[str, Any]]:
    tree_id = str(request.id)
    code = request.tree_code  # already normalized by the schema

    holder = tree_repository.find_by_code(db, code)
    if holder is not None and holder["id"] != tree_id:
        raise _conflict(code, holder["id"])

    existing = tree_repository.find_by_id(db, tree_id)
    if existing is not None and _as_utc(existing["updated_at"]) >= request.updated_at:
        return "unchanged", existing

    fields = {name: getattr(request, name) for name in _SYNC_FIELDS}
    fields["tree_code_normalized"] = code
    fields["server_version"] = tree_repository.next_server_version(db)
    fields["updated_by"] = user_email
    fields["server_updated_at"] = datetime.now(timezone.utc)

    try:
        if existing is None:
            document = {
                "id": tree_id,
                **fields,
                "image_path": None,
                "created_at": request.created_at,
                "created_by": user_email,
            }
            tree_repository.insert(db, document)
            return "created", tree_repository.find_by_id(db, tree_id)
        return "updated", tree_repository.update(db, tree_id, fields)
    except DuplicateKeyError as exc:
        # Lost a race with a concurrent request; report precisely what collided.
        holder = tree_repository.find_by_code(db, code)
        if holder is not None and holder["id"] != tree_id:
            raise _conflict(code, holder["id"]) from exc
        raise ApiError(409, "SYNC_CONFLICT", "Concurrent sync for this tree; retry.") from exc


def attach_image(db: Database, tree_id: str, image_path: str) -> dict[str, Any]:
    updated = tree_repository.update(
        db,
        tree_id,
        {
            "image_path": image_path,
            "server_version": tree_repository.next_server_version(db),
            "server_updated_at": datetime.now(timezone.utc),
        },
    )
    if updated is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Tree not found.")
    return updated

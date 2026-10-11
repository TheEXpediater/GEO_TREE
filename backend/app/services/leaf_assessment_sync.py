"""Leaf assessment history sync rules.

* Identity is the device-generated assessment UUID. One scan = one document; a tree has many.
* A finished assessment is an immutable historical event. Re-sending the same assessment with
  the same result is a no-op ("unchanged"), which makes sync idempotent. Re-sending it with a
  *different* result is rejected with 409 ASSESSMENT_CONFLICT: history is never overwritten.
* The photo is attached once and stored under ``uploads/leaf_assessments/``. It is never added
  to the classifier training dataset.
* Every accepted change (creation, photo attachment) receives a new ``server_version``.
"""

from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any

from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.errors import ApiError
from app.repositories import leaf_assessment_repository, tree_repository
from app.schemas.leaf_assessment import LeafAssessmentSyncRequest

# The fields that make up the recorded result. They are compared, never updated.
_RESULT_FIELDS = (
    "tree_id",
    "status",
    "class_id",
    "class_name",
    "confidence",
    "model_name",
    "model_version",
    "created_at",
)


def _as_utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def _millis(value: datetime | None) -> int | None:
    # MongoDB keeps millisecond precision; compare at that precision.
    return None if value is None else int(_as_utc(value).timestamp() * 1000)


def _same(name: str, stored: Any, incoming: Any) -> bool:
    if name == "created_at":
        return _millis(stored) == _millis(incoming)
    if name == "confidence" and stored is not None and incoming is not None:
        return math.isclose(stored, incoming, rel_tol=0, abs_tol=1e-6)
    return stored == incoming


def _fields(request: LeafAssessmentSyncRequest) -> dict[str, Any]:
    return {
        "tree_id": str(request.tree_id),
        "status": request.status,
        "class_id": request.class_id,
        "class_name": request.class_name,
        "confidence": request.confidence,
        "model_name": request.model_name,
        "model_display_name": request.model_display_name,
        "model_version": request.model_version,
        "error_code": request.error_code,
        "error_message": request.error_message,
        "created_at": request.created_at,
        "completed_at": request.completed_at,
        "updated_at": request.updated_at,
    }


def sync_assessment(db: Database, request: LeafAssessmentSyncRequest, user_email: str) -> tuple[str, dict[str, Any]]:
    assessment_id = str(request.assessment_id)
    incoming = _fields(request)

    existing = leaf_assessment_repository.find_by_id(db, assessment_id)
    if existing is not None:
        return _unchanged_or_conflict(existing, incoming)

    if tree_repository.find_by_id(db, incoming["tree_id"]) is None:
        raise ApiError(404, "TREE_NOT_FOUND", "Sync the tree before its leaf assessments.")

    document = {
        "assessment_id": assessment_id,
        **incoming,
        "image_path": None,
        "server_version": leaf_assessment_repository.next_server_version(db),
        "created_by": user_email,
        "server_updated_at": datetime.now(timezone.utc),
    }
    try:
        leaf_assessment_repository.insert(db, document)
    except DuplicateKeyError:
        # Lost a race with the same assessment sent twice at once: the stored one decides.
        return _unchanged_or_conflict(leaf_assessment_repository.find_by_id(db, assessment_id), incoming)
    return "created", leaf_assessment_repository.find_by_id(db, assessment_id)


def _unchanged_or_conflict(existing: dict[str, Any], incoming: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    differing = [name for name in _RESULT_FIELDS if not _same(name, existing.get(name), incoming[name])]
    if differing:
        raise ApiError(
            409,
            "ASSESSMENT_CONFLICT",
            "This assessment was already recorded with a different result. Recorded assessments are never overwritten.",
            {"assessment_id": existing["assessment_id"], "fields": differing},
        )
    return "unchanged", existing


def attach_image(db: Database, assessment_id: str, image_path: str) -> dict[str, Any] | None:
    return leaf_assessment_repository.set_image(
        db,
        assessment_id,
        image_path,
        leaf_assessment_repository.next_server_version(db),
        datetime.now(timezone.utc),
    )

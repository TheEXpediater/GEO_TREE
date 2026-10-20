"""Read-only review of leaf assessment history for the web administration.

Every scan is a historical record: these routes never edit or delete one, and a later model
version never changes a recorded prediction.
"""

from __future__ import annotations

import re
from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from pymongo import DESCENDING
from pymongo.database import Database

from app.auth.dependencies import get_db, require_admin
from app.database.mongo import LEAF_ASSESSMENTS, TREES
from app.errors import ApiError
from app.routes.admin_trees import image_url
from app.routes.pagination import PageParams, page_params

router = APIRouter(prefix="/api/v1/admin/assessments", tags=["admin: assessments"])

STATUSES = ("PENDING", "PROCESSING", "COMPLETED", "UNCERTAIN", "FAILED")


def _view(doc: dict[str, Any], tree_codes: dict[str, str]) -> dict[str, Any]:
    return {
        "assessmentId": doc["assessment_id"],
        "treeId": doc["tree_id"],
        "treeCode": tree_codes.get(doc["tree_id"]),
        "status": doc.get("status"),
        "classId": doc.get("class_id"),
        "className": doc.get("class_name"),
        "confidence": doc.get("confidence"),
        "modelName": doc.get("model_name"),
        "modelDisplayName": doc.get("model_display_name"),
        "modelVersion": doc.get("model_version"),
        "errorCode": doc.get("error_code"),
        "errorMessage": doc.get("error_message"),
        "createdAt": doc.get("created_at"),
        "completedAt": doc.get("completed_at"),
        "imageUrl": image_url(doc.get("image_path")),
        "createdBy": doc.get("created_by"),
        "serverVersion": doc.get("server_version"),
    }


def _tree_codes(db: Database, tree_ids: list[str]) -> dict[str, str]:
    return {t["id"]: t["tree_code"] for t in db[TREES].find({"id": {"$in": list(set(tree_ids))}}, {"_id": False, "id": 1, "tree_code": 1})}


def _day_start(day: date) -> datetime:
    return datetime.combine(day, time.min, tzinfo=timezone.utc)


@router.get("")
def list_assessments(
    q: str | None = Query(default=None, max_length=40, description="Tree Code contains"),
    tree_id: UUID | None = Query(default=None, alias="treeId"),
    status: str | None = Query(default=None, pattern="^(PENDING|PROCESSING|COMPLETED|UNCERTAIN|FAILED)$"),
    class_name: str | None = Query(default=None, alias="className", max_length=80),
    date_from: date | None = Query(default=None, alias="from"),
    date_to: date | None = Query(default=None, alias="to"),
    has_image: bool | None = Query(default=None, alias="hasImage"),
    paging: PageParams = Depends(page_params),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    selector: dict[str, Any] = {}
    if tree_id is not None:
        selector["tree_id"] = str(tree_id)
    elif q:
        matching = [t["id"] for t in db[TREES].find({"tree_code_normalized": {"$regex": re.escape(q.strip().upper())}}, {"id": 1})]
        selector["tree_id"] = {"$in": matching}
    if status:
        selector["status"] = status
    if class_name:
        selector["class_name"] = class_name
    created: dict[str, Any] = {}
    if date_from:
        created["$gte"] = _day_start(date_from)
    if date_to:
        created["$lt"] = _day_start(date_to) + timedelta(days=1)
    if created:
        selector["created_at"] = created
    if has_image is True:
        selector["image_path"] = {"$nin": [None, ""]}
    elif has_image is False:
        selector["image_path"] = {"$in": [None, ""]}

    total = db[LEAF_ASSESSMENTS].count_documents(selector)
    docs = list(
        db[LEAF_ASSESSMENTS].find(selector, {"_id": False})
        .sort([("created_at", DESCENDING), ("assessment_id", DESCENDING)])
        .skip(paging.skip).limit(paging.page_size)
    )
    codes = _tree_codes(db, [d["tree_id"] for d in docs])
    return paging.response([_view(d, codes) for d in docs], total)


@router.get("/facets")
def facets(db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    """Values for the filter controls, taken from the recorded history itself."""
    names = sorted({n for n in db[LEAF_ASSESSMENTS].distinct("class_name") if n})
    return {"statuses": list(STATUSES), "classNames": names}


@router.get("/{assessment_id}")
def get_assessment(assessment_id: UUID, db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    doc = db[LEAF_ASSESSMENTS].find_one({"assessment_id": str(assessment_id)}, {"_id": False})
    if doc is None:
        raise ApiError(404, "ASSESSMENT_NOT_FOUND", "Leaf assessment not found.")
    return _view(doc, _tree_codes(db, [doc["tree_id"]]))

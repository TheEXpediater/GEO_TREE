"""Lightweight marker data for the web administration's maps (Admin and Super Admin).

* Only the fields a marker and its popup need: no image bytes, no file system paths (photos are
  fetched separately through the authenticated /uploads routes, and only for the selected marker).
* Coordinates come from the tree records exactly as the field app captured them. A record whose
  coordinates are missing or malformed is never placed on the map; it is counted and listed instead.
* Field evidence (registration photos and leaf-assessment scans) has no coordinates of its own: it is
  placed at the GPS position recorded for its tree. Evidence whose tree is missing or has no valid
  position is counted as excluded.
* Fixed number of queries per request (trees, assessments, tags): no per-marker lookups.
"""

from __future__ import annotations

import math
import re
from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from pymongo import DESCENDING
from pymongo.database import Database

from app.auth.dependencies import get_db, require_admin
from app.database.mongo import LEAF_ASSESSMENTS, TREES
from app.repositories import tag_repository
from app.routes.admin_trees import image_url
from app.services import field_quality

router = APIRouter(prefix="/api/v1/admin/map", tags=["admin: map"])

# A safety cap for one response; the field dataset is far smaller. ``truncated`` tells the client.
MAX_MARKERS = 5000
_TREE_FIELDS = {"_id": False, "id": 1, "tree_code": 1, "latitude": 1, "longitude": 1, "accuracy_meters": 1, "archived": 1,
                "image_path": 1, "tag_ids": 1, "created_at": 1, "location_captured_at": 1}


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return None
    return float(value)


def _tree_selector(status: str, tag_id: str | None, query: str | None) -> dict[str, Any]:
    conditions: list[dict[str, Any]] = []
    if status == "active":
        conditions.append({"archived": {"$ne": True}})
    elif status == "archived":
        conditions.append({"archived": True})
    if tag_id:
        conditions.append({"tag_ids": tag_id})
    if query:
        conditions.append({"tree_code_normalized": {"$regex": re.escape(query.strip().upper())}})
    return {"$and": conditions} if conditions else {}


def _excluded_view(tree: dict[str, Any], reason: str) -> dict[str, Any]:
    return {"id": tree.get("id"), "treeCode": tree.get("tree_code"), "reason": reason}


def _missing_reason(tree: dict[str, Any]) -> str:
    lat, lon = tree.get("latitude"), tree.get("longitude")
    if lat is None or lon is None:
        return "MISSING_COORDINATES"
    return "INVALID_COORDINATES"


@router.get("/trees")
def tree_markers(
    status: str = Query(default="active", pattern="^(active|archived|all)$"),
    tag_id: str | None = Query(default=None, alias="tagId", max_length=64),
    q: str | None = Query(default=None, max_length=40),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    trees = list(db[TREES].find(_tree_selector(status, tag_id, q), _TREE_FIELDS).sort("tree_code_normalized", 1))
    placed, excluded = [], []
    for tree in trees:
        if field_quality.valid_coordinates(tree.get("latitude"), tree.get("longitude")):
            placed.append(tree)
        else:
            excluded.append(_excluded_view(tree, _missing_reason(tree)))
    truncated = len(placed) > MAX_MARKERS
    placed = placed[:MAX_MARKERS]

    ids = [t["id"] for t in placed]
    latest: dict[str, dict[str, Any]] = {}
    counts: dict[str, int] = {}
    projection = {"_id": False, "assessment_id": 1, "tree_id": 1, "status": 1, "class_name": 1, "confidence": 1, "created_at": 1}
    for doc in db[LEAF_ASSESSMENTS].find({"tree_id": {"$in": ids}}, projection).sort("created_at", DESCENDING):
        counts[doc["tree_id"]] = counts.get(doc["tree_id"], 0) + 1
        latest.setdefault(doc["tree_id"], {"id": doc["assessment_id"], "status": doc.get("status"), "className": doc.get("class_name"),
                                           "confidence": doc.get("confidence"), "createdAt": doc.get("created_at")})
    tags = tag_repository.by_ids(db, [tag for t in placed for tag in t.get("tag_ids") or []])

    items = []
    for tree in placed:
        accuracy = _number(tree.get("accuracy_meters"))
        items.append({
            "id": tree["id"],
            "treeCode": tree["tree_code"],
            "latitude": float(tree["latitude"]),
            "longitude": float(tree["longitude"]),
            "accuracyMeters": accuracy,
            "accuracyLevel": field_quality.accuracy_level(accuracy),
            "archived": bool(tree.get("archived", False)),
            "hasPhoto": bool(tree.get("image_path")),
            "imageUrl": image_url(tree.get("image_path")),
            "tags": [{"id": tags[i]["id"], "name": tags[i]["name"], "color": tags[i].get("color", "moss")}
                     for i in tree.get("tag_ids") or [] if i in tags],
            "assessmentCount": counts.get(tree["id"], 0),
            "latestAssessment": latest.get(tree["id"]),
            "createdAt": tree.get("created_at"),
            "locationCapturedAt": tree.get("location_captured_at"),
        })
    return {"items": items, "total": len(trees), "placed": len(items), "excludedCount": len(excluded),
            "excluded": excluded[:100], "truncated": truncated}


def _day_start(day: date) -> datetime:
    return datetime.combine(day, time.min, tzinfo=timezone.utc)


def _utc(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


@router.get("/evidence")
def evidence_markers(
    type_: str = Query(default="all", alias="type", pattern="^(all|registration|assessment)$"),
    status: str | None = Query(default=None, pattern="^(PENDING|PROCESSING|COMPLETED|UNCERTAIN|FAILED)$"),
    date_from: date | None = Query(default=None, alias="from"),
    date_to: date | None = Query(default=None, alias="to"),
    tree_id: UUID | None = Query(default=None, alias="treeId"),
    q: str | None = Query(default=None, max_length=40, description="Tree Code contains"),
    include_archived: bool = Query(default=False, alias="includeArchived"),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    """Evidence placed at its tree's recorded position. Registration photos are dated by the tree's GPS
    capture time; leaf assessments by their scan time."""
    tree_selector = _tree_selector("all" if include_archived else "active", None, q)
    if tree_id is not None:
        tree_selector = {"$and": [tree_selector, {"id": str(tree_id)}]} if tree_selector else {"id": str(tree_id)}
    trees = {t["id"]: t for t in db[TREES].find(tree_selector, _TREE_FIELDS)}
    start = _day_start(date_from) if date_from else None
    end = _day_start(date_to) + timedelta(days=1) if date_to else None

    def in_range(at: datetime | None) -> bool:
        if start is None and end is None:
            return True
        if at is None:
            return False
        return (start is None or at >= start) and (end is None or at < end)

    items: list[dict[str, Any]] = []
    excluded = 0

    def place(tree: dict[str, Any] | None, item: dict[str, Any]) -> None:
        nonlocal excluded
        if tree is None or not field_quality.valid_coordinates(tree.get("latitude"), tree.get("longitude")):
            excluded += 1
            return
        items.append({**item, "treeId": tree["id"], "treeCode": tree.get("tree_code"), "latitude": float(tree["latitude"]),
                      "longitude": float(tree["longitude"]), "accuracyMeters": _number(tree.get("accuracy_meters")),
                      "treeArchived": bool(tree.get("archived", False))})

    if type_ in ("all", "registration") and status is None:
        for tree in trees.values():
            if tree.get("image_path"):
                at = _utc(tree.get("location_captured_at")) or _utc(tree.get("created_at"))
                if in_range(at):
                    place(tree, {"id": f"photo-{tree['id']}", "type": "REGISTRATION_PHOTO", "at": at, "imageUrl": image_url(tree["image_path"])})

    if type_ in ("all", "assessment"):
        selector: dict[str, Any] = {}
        if tree_id is not None or q or not include_archived:
            selector["tree_id"] = {"$in": list(trees)}
        if status:
            selector["status"] = status
        created: dict[str, Any] = {}
        if start:
            created["$gte"] = start
        if end:
            created["$lt"] = end
        if created:
            selector["created_at"] = created
        for doc in db[LEAF_ASSESSMENTS].find(selector, {"_id": False}).sort("created_at", DESCENDING):
            tree = trees.get(doc["tree_id"])  # None when the tree record itself is missing on the server
            place(tree, {
                "id": f"assessment-{doc['assessment_id']}",
                "type": "LEAF_ASSESSMENT",
                "at": doc.get("created_at"),
                "imageUrl": image_url(doc.get("image_path")),
                "assessment": {
                    "assessmentId": doc["assessment_id"], "treeId": doc["tree_id"], "treeCode": (tree or {}).get("tree_code"),
                    "status": doc.get("status"), "classId": doc.get("class_id"), "className": doc.get("class_name"),
                    "confidence": doc.get("confidence"), "modelName": doc.get("model_name"),
                    "modelDisplayName": doc.get("model_display_name"), "modelVersion": doc.get("model_version"),
                    "errorCode": doc.get("error_code"), "errorMessage": doc.get("error_message"),
                    "createdAt": doc.get("created_at"), "completedAt": doc.get("completed_at"),
                    "imageUrl": image_url(doc.get("image_path")), "createdBy": doc.get("created_by"),
                    "serverVersion": doc.get("server_version"),
                },
            })

    items.sort(key=lambda i: _utc(i.get("at")) or datetime.min.replace(tzinfo=timezone.utc), reverse=True)
    truncated = len(items) > MAX_MARKERS
    items = items[:MAX_MARKERS]
    return {"items": items, "placed": len(items), "excludedCount": excluded, "truncated": truncated,
            "treeCount": len({i["treeId"] for i in items})}

from __future__ import annotations

import re
from typing import Any

from pymongo import ASCENDING, ReturnDocument
from pymongo.database import Database

from app.database.mongo import COUNTERS, TREES

_PROJECTION = {"_id": False}


def next_server_version(db: Database) -> int:
    """Server-side monotonic counter. Device clocks are never used as the sync cursor."""
    counter = db[COUNTERS].find_one_and_update(
        {"_id": "tree_server_version"},
        {"$inc": {"value": 1}},
        upsert=True,
        return_document=ReturnDocument.AFTER,
    )
    return int(counter["value"])


def find_by_id(db: Database, tree_id: str) -> dict[str, Any] | None:
    return db[TREES].find_one({"id": tree_id}, _PROJECTION)


def find_by_code(db: Database, tree_code_normalized: str) -> dict[str, Any] | None:
    return db[TREES].find_one({"tree_code_normalized": tree_code_normalized}, _PROJECTION)


def insert(db: Database, document: dict[str, Any]) -> None:
    db[TREES].insert_one(dict(document))


def update(db: Database, tree_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
    return db[TREES].find_one_and_update(
        {"id": tree_id},
        {"$set": fields},
        projection=_PROJECTION,
        return_document=ReturnDocument.AFTER,
    )


def changes_after(db: Database, after_version: int, limit: int) -> list[dict[str, Any]]:
    cursor = (
        db[TREES]
        .find({"server_version": {"$gt": after_version}}, _PROJECTION)
        .sort("server_version", ASCENDING)
        .limit(limit)
    )
    return list(cursor)


_SORTS = {
    "treeCode": ("tree_code_normalized", ASCENDING),
    "-treeCode": ("tree_code_normalized", -1),
    "createdAt": ("created_at", ASCENDING),
    "-createdAt": ("created_at", -1),
    "updatedAt": ("updated_at", ASCENDING),
    "-updatedAt": ("updated_at", -1),
}


def admin_filter(query: str | None, status: str, has_image: bool | None, tag_id: str | None) -> dict[str, Any]:
    conditions: list[dict[str, Any]] = []
    if query:
        conditions.append({"tree_code_normalized": {"$regex": re.escape(query.strip().upper())}})
    if status == "active":
        conditions.append({"archived": {"$ne": True}})
    elif status == "archived":
        conditions.append({"archived": True})
    if has_image is True:
        conditions.append({"image_path": {"$nin": [None, ""]}})
    elif has_image is False:
        conditions.append({"image_path": {"$in": [None, ""]}})
    if tag_id:
        conditions.append({"tag_ids": tag_id})
    return {"$and": conditions} if conditions else {}


def admin_page(db: Database, selector: dict[str, Any], sort: str, skip: int, limit: int) -> tuple[list[dict[str, Any]], int]:
    field, direction = _SORTS.get(sort, _SORTS["-createdAt"])
    total = db[TREES].count_documents(selector)
    cursor = db[TREES].find(selector, _PROJECTION).sort([(field, direction), ("id", ASCENDING)]).skip(skip).limit(limit)
    return list(cursor), total


def all_trees(db: Database, projection: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    return list(db[TREES].find({}, projection or _PROJECTION))

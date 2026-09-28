from __future__ import annotations

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

"""MongoDB access for leaf assessment history. One document per scan; many per tree."""

from __future__ import annotations

from typing import Any

from pymongo import ASCENDING, DESCENDING, ReturnDocument
from pymongo.database import Database

from app.database.mongo import COUNTERS, LEAF_ASSESSMENTS

_PROJECTION = {"_id": False}


def next_server_version(db: Database) -> int:
    """Separate monotonic cursor for the assessment change feed (never a device clock)."""
    counter = db[COUNTERS].find_one_and_update(
        {"_id": "leaf_assessment_server_version"},
        {"$inc": {"value": 1}},
        upsert=True,
        return_document=ReturnDocument.AFTER,
    )
    return int(counter["value"])


def find_by_id(db: Database, assessment_id: str) -> dict[str, Any] | None:
    return db[LEAF_ASSESSMENTS].find_one({"assessment_id": assessment_id}, _PROJECTION)


def insert(db: Database, document: dict[str, Any]) -> None:
    db[LEAF_ASSESSMENTS].insert_one(dict(document))


def set_image(db: Database, assessment_id: str, image_path: str, server_version: int, at: Any) -> dict[str, Any] | None:
    """Attaches the photo once; an assessment that already has one is left untouched."""
    return db[LEAF_ASSESSMENTS].find_one_and_update(
        {"assessment_id": assessment_id, "image_path": None},
        {"$set": {"image_path": image_path, "server_version": server_version, "server_updated_at": at}},
        projection=_PROJECTION,
        return_document=ReturnDocument.AFTER,
    )


def for_tree(db: Database, tree_id: str, limit: int) -> list[dict[str, Any]]:
    """Newest scan first."""
    cursor = (
        db[LEAF_ASSESSMENTS]
        .find({"tree_id": tree_id}, _PROJECTION)
        .sort([("created_at", DESCENDING), ("assessment_id", DESCENDING)])
        .limit(limit)
    )
    return list(cursor)


def changes_after(db: Database, after_version: int, limit: int) -> list[dict[str, Any]]:
    cursor = (
        db[LEAF_ASSESSMENTS]
        .find({"server_version": {"$gt": after_version}}, _PROJECTION)
        .sort("server_version", ASCENDING)
        .limit(limit)
    )
    return list(cursor)

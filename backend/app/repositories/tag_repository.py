"""General tags for tree records (web administration). Not the DINOv2 condition classes."""

from __future__ import annotations

import re
from typing import Any

from pymongo import ASCENDING, ReturnDocument
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.database.mongo import TAGS, TREES

_PROJECTION = {"_id": False}


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "tag"


def list_all(db: Database) -> list[dict[str, Any]]:
    return list(db[TAGS].find({}, _PROJECTION).sort("name", ASCENDING))


def find(db: Database, tag_id: str) -> dict[str, Any] | None:
    return db[TAGS].find_one({"id": tag_id}, _PROJECTION)


def by_ids(db: Database, tag_ids: list[str]) -> dict[str, dict[str, Any]]:
    if not tag_ids:
        return {}
    return {tag["id"]: tag for tag in db[TAGS].find({"id": {"$in": list(set(tag_ids))}}, _PROJECTION)}


def insert(db: Database, document: dict[str, Any]) -> bool:
    try:
        db[TAGS].insert_one(dict(document))
    except DuplicateKeyError:
        return False
    return True


def update(db: Database, tag_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
    """Returns None when the tag is missing; raises DuplicateKeyError on a name clash."""
    return db[TAGS].find_one_and_update({"id": tag_id}, {"$set": fields}, projection=_PROJECTION, return_document=ReturnDocument.AFTER)


def delete(db: Database, tag_id: str) -> int:
    """Deletes the tag and removes it from every tree. Returns how many trees carried it."""
    detached = db[TREES].update_many({"tag_ids": tag_id}, {"$pull": {"tag_ids": tag_id}}).modified_count
    db[TAGS].delete_one({"id": tag_id})
    return detached


def tree_counts(db: Database) -> dict[str, int]:
    counts: dict[str, int] = {}
    for tree in db[TREES].find({"tag_ids": {"$exists": True, "$ne": []}}, {"tag_ids": 1}):
        for tag_id in tree.get("tag_ids") or []:
            counts[tag_id] = counts.get(tag_id, 0) + 1
    return counts

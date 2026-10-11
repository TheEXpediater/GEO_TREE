"""MongoDB access for classifier classes (labels) and their dataset images (metadata only)."""

from __future__ import annotations

from typing import Any

from pymongo import ASCENDING, DESCENDING, ReturnDocument
from pymongo.database import Database

from app.database.mongo import CLASSIFIER_CLASSES, CLASSIFIER_IMAGES

_PROJECTION = {"_id": False}


def list_classes(db: Database) -> list[dict[str, Any]]:
    return list(db[CLASSIFIER_CLASSES].find({"active": True}, _PROJECTION).sort("created_at", ASCENDING))


def count_classes(db: Database) -> int:
    return db[CLASSIFIER_CLASSES].count_documents({})


def find_class(db: Database, class_id: str) -> dict[str, Any] | None:
    return db[CLASSIFIER_CLASSES].find_one({"id": class_id, "active": True}, _PROJECTION)


def find_class_by_slug(db: Database, slug: str) -> dict[str, Any] | None:
    return db[CLASSIFIER_CLASSES].find_one({"slug": slug}, _PROJECTION)


def insert_class(db: Database, document: dict[str, Any]) -> None:
    db[CLASSIFIER_CLASSES].insert_one(dict(document))


def update_class(db: Database, class_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
    return db[CLASSIFIER_CLASSES].find_one_and_update(
        {"id": class_id}, {"$set": fields}, projection=_PROJECTION, return_document=ReturnDocument.AFTER
    )


def delete_class(db: Database, class_id: str) -> None:
    db[CLASSIFIER_CLASSES].delete_one({"id": class_id})


def image_counts(db: Database) -> dict[str, dict[str, int]]:
    """Per class id: active images, and how many of them have a usable cached embedding."""
    counts: dict[str, dict[str, int]] = {}
    for image in db[CLASSIFIER_IMAGES].find({"active": True}, {"class_id": 1, "embedding_status": 1, "_id": 0}):
        entry = counts.setdefault(image["class_id"], {"total": 0, "embedded": 0})
        entry["total"] += 1
        if image.get("embedding_status") == "READY":
            entry["embedded"] += 1
    return counts


def list_images(db: Database, class_id: str) -> list[dict[str, Any]]:
    return list(db[CLASSIFIER_IMAGES].find({"class_id": class_id, "active": True}, _PROJECTION).sort("created_at", DESCENDING))


def all_active_images(db: Database) -> list[dict[str, Any]]:
    return list(db[CLASSIFIER_IMAGES].find({"active": True}, _PROJECTION).sort("created_at", ASCENDING))


def find_image(db: Database, class_id: str, image_id: str) -> dict[str, Any] | None:
    return db[CLASSIFIER_IMAGES].find_one({"id": image_id, "class_id": class_id, "active": True}, _PROJECTION)


def find_image_by_hash(db: Database, sha256: str) -> dict[str, Any] | None:
    return db[CLASSIFIER_IMAGES].find_one({"sha256": sha256}, _PROJECTION)


def insert_image(db: Database, document: dict[str, Any]) -> None:
    db[CLASSIFIER_IMAGES].insert_one(dict(document))


def update_image(db: Database, image_id: str, fields: dict[str, Any]) -> None:
    db[CLASSIFIER_IMAGES].update_one({"id": image_id}, {"$set": fields})


def delete_image(db: Database, image_id: str) -> None:
    db[CLASSIFIER_IMAGES].delete_one({"id": image_id})


def delete_images_of_class(db: Database, class_id: str) -> list[dict[str, Any]]:
    images = list(db[CLASSIFIER_IMAGES].find({"class_id": class_id}, _PROJECTION))
    db[CLASSIFIER_IMAGES].delete_many({"class_id": class_id})
    return images

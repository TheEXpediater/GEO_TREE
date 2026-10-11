"""MongoDB connection and index setup. FastAPI is the only gateway to MongoDB."""

from __future__ import annotations

from pymongo import ASCENDING, DESCENDING, MongoClient
from pymongo.database import Database

USERS = "users"
TREES = "trees"
COUNTERS = "counters"
LEAF_ASSESSMENTS = "leaf_assessments"
CLASSIFIER_CLASSES = "classifier_classes"
CLASSIFIER_IMAGES = "classifier_images"
AI_MODELS = "ai_models"
AI_JOBS = "ai_jobs"


def create_client(uri: str) -> MongoClient:
    return MongoClient(uri, serverSelectionTimeoutMS=3000, tz_aware=True)


def ensure_indexes(db: Database) -> None:
    db[USERS].create_index([("email", ASCENDING)], unique=True, name="uniq_email")
    # The device-generated UUID is the shared identity; Mongo's _id stays internal.
    db[TREES].create_index([("id", ASCENDING)], unique=True, name="uniq_tree_id")
    db[TREES].create_index([("tree_code_normalized", ASCENDING)], unique=True, name="uniq_tree_code")
    db[TREES].create_index([("server_version", ASCENDING)], name="server_version")
    # Leaf assessment history: one document per scan. tree_id is deliberately NOT unique.
    db[LEAF_ASSESSMENTS].create_index([("assessment_id", ASCENDING)], unique=True, name="uniq_assessment_id")
    db[LEAF_ASSESSMENTS].create_index([("tree_id", ASCENDING), ("created_at", DESCENDING)], name="tree_history")
    db[LEAF_ASSESSMENTS].create_index([("server_version", ASCENDING)], name="assessment_server_version")


def ensure_ai_indexes(db: Database) -> None:
    db[CLASSIFIER_CLASSES].create_index([("id", ASCENDING)], unique=True, name="uniq_class_id")
    # Class names are unique case-insensitively through their slug.
    db[CLASSIFIER_CLASSES].create_index([("slug", ASCENDING)], unique=True, name="uniq_class_slug")
    db[CLASSIFIER_IMAGES].create_index([("id", ASCENDING)], unique=True, name="uniq_image_id")
    db[CLASSIFIER_IMAGES].create_index([("class_id", ASCENDING), ("created_at", ASCENDING)], name="class_images")
    # Exact duplicate content is rejected across the whole dataset.
    db[CLASSIFIER_IMAGES].create_index([("sha256", ASCENDING)], unique=True, name="uniq_image_sha256")
    db[AI_MODELS].create_index([("id", ASCENDING)], unique=True, name="uniq_model_id")
    db[AI_MODELS].create_index([("model_version", ASCENDING)], unique=True, name="uniq_model_version")
    db[AI_JOBS].create_index([("id", ASCENDING)], unique=True, name="uniq_job_id")
    db[AI_JOBS].create_index([("status", ASCENDING), ("run_after", ASCENDING)], name="job_queue")


def ping(db: Database) -> bool:
    try:
        db.client.admin.command("ping")
        return True
    except Exception:  # noqa: BLE001 - health must never raise
        return False

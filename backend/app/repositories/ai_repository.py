"""MongoDB access for trained model versions, training jobs and AI counters."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pymongo import ASCENDING, DESCENDING, ReturnDocument
from pymongo.database import Database

from app.database.mongo import AI_JOBS, AI_MODELS, COUNTERS

_PROJECTION = {"_id": False}
DATASET_VERSION = "ai_dataset_version"
MODEL_VERSION = "ai_model_version"


def _increment(db: Database, name: str) -> int:
    counter = db[COUNTERS].find_one_and_update(
        {"_id": name}, {"$inc": {"value": 1}}, upsert=True, return_document=ReturnDocument.AFTER
    )
    return int(counter["value"])


def bump_dataset_version(db: Database) -> int:
    return _increment(db, DATASET_VERSION)


def dataset_version(db: Database) -> int:
    counter = db[COUNTERS].find_one({"_id": DATASET_VERSION})
    return int(counter["value"]) if counter else 0


def next_model_version(db: Database) -> int:
    return _increment(db, MODEL_VERSION)


# Models -------------------------------------------------------------------------------------
def insert_model(db: Database, document: dict[str, Any]) -> None:
    db[AI_MODELS].insert_one(dict(document))


def active_model(db: Database) -> dict[str, Any] | None:
    return db[AI_MODELS].find_one({"active": True}, _PROJECTION, sort=[("model_version", DESCENDING)])


def activate_model(db: Database, model_id: str, activated_at: datetime) -> None:
    """Activate the new version first, then retire the others: there is never a gap without a model."""
    db[AI_MODELS].update_one({"id": model_id}, {"$set": {"active": True, "activated_at": activated_at}})
    db[AI_MODELS].update_many({"id": {"$ne": model_id}, "active": True}, {"$set": {"active": False}})


def deactivate_models_with_class(db: Database, class_id: str, reason: str, at: datetime) -> int:
    result = db[AI_MODELS].update_many(
        {"active": True, "class_mapping.class_id": class_id},
        {"$set": {"active": False, "status": "INVALIDATED", "invalidated_reason": reason, "invalidated_at": at}},
    )
    return result.modified_count


# Jobs ---------------------------------------------------------------------------------------
def insert_job(db: Database, document: dict[str, Any]) -> None:
    db[AI_JOBS].insert_one(dict(document))


def find_job(db: Database, job_id: str) -> dict[str, Any] | None:
    return db[AI_JOBS].find_one({"id": job_id}, _PROJECTION)


def find_job_with_status(db: Database, status: str) -> dict[str, Any] | None:
    return db[AI_JOBS].find_one({"status": status}, _PROJECTION, sort=[("created_at", ASCENDING)])


def latest_finished_job(db: Database) -> dict[str, Any] | None:
    return db[AI_JOBS].find_one({"status": {"$in": ["COMPLETE", "ERROR"]}}, _PROJECTION, sort=[("finished_at", DESCENDING)])


def update_job(db: Database, job_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
    return db[AI_JOBS].find_one_and_update(
        {"id": job_id}, {"$set": fields}, projection=_PROJECTION, return_document=ReturnDocument.AFTER
    )


def claim_due_job(db: Database, now: datetime) -> dict[str, Any] | None:
    """Moves the oldest due QUEUED job to TRAINING. The conditional update on ``status`` means a
    job can be claimed only once, even if two callers pick the same candidate."""
    candidate = db[AI_JOBS].find_one({"status": "QUEUED", "run_after": {"$lte": now}}, {"id": 1}, sort=[("created_at", ASCENDING)])
    if candidate is None:
        return None
    claimed = db[AI_JOBS].update_one(
        {"id": candidate["id"], "status": "QUEUED"}, {"$set": {"status": "TRAINING", "started_at": now}}
    )
    return find_job(db, candidate["id"]) if claimed.modified_count == 1 else None


def fail_interrupted_jobs(db: Database, now: datetime) -> int:
    result = db[AI_JOBS].update_many(
        {"status": "TRAINING"},
        {"$set": {"status": "ERROR", "finished_at": now, "error_message": "Interrupted by a server restart. Rebuild the model to retry."}},
    )
    return result.modified_count


def list_models(db: Database, limit: int) -> list[dict[str, Any]]:
    return list(db[AI_MODELS].find({}, _PROJECTION).sort("model_version", DESCENDING).limit(limit))


def list_jobs(db: Database, limit: int, status: str | None = None) -> list[dict[str, Any]]:
    selector = {"status": status} if status else {}
    return list(db[AI_JOBS].find(selector, _PROJECTION).sort("created_at", DESCENDING).limit(limit))

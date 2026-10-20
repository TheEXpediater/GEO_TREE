"""Dashboard figures and the activity log for the web administration.

Every number comes from MongoDB at request time; nothing is estimated or sampled. Labels are
specific on purpose: tree-registration photos, classifier training images and leaf-assessment
records are three different things and are counted separately.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from pymongo.database import Database

from app.auth.dependencies import get_db, require_admin
from app.database.mongo import AI_JOBS, AI_MODELS, CLASSIFIER_CLASSES, LEAF_ASSESSMENTS, TREES
from app.repositories import classifier_repository, user_repository
from app.routes.pagination import PageParams, page_params
from app.services import audit, field_quality

router = APIRouter(prefix="/api/v1/admin", tags=["admin: overview"])

WEEKS = 12


def _utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def _week_start(now: datetime) -> datetime:
    start = now - timedelta(days=now.weekday())
    return start.replace(hour=0, minute=0, second=0, microsecond=0)


def _weekly(dates: list[datetime], now: datetime) -> list[dict[str, Any]]:
    first = _week_start(now) - timedelta(weeks=WEEKS - 1)
    buckets = [{"weekStart": (first + timedelta(weeks=i)).date().isoformat(), "count": 0} for i in range(WEEKS)]
    for value in dates:
        index = (value - first).days // 7
        if 0 <= index < WEEKS:
            buckets[index]["count"] += 1
    return buckets


def _trees_section(db: Database, now: datetime) -> dict[str, Any]:
    trees = list(db[TREES].find({}, {"_id": False, "archived": 1, "image_path": 1, "accuracy_meters": 1, "created_at": 1,
                                     **{name: 1 for name in field_quality.DESCRIPTIVE_FIELDS}}))
    active = [t for t in trees if not t.get("archived")]
    accuracy = {"GOOD": 0, "ACCEPTABLE": 0, "LOW": 0}
    criteria = {"photo": 0, "accuracy": 0, "descriptive": 0}
    complete = 0
    for tree in active:
        accuracy[field_quality.accuracy_level(tree.get("accuracy_meters"))] += 1
        checks = field_quality.completeness(tree)
        for name in criteria:
            criteria[name] += int(checks[name])
        complete += int(checks["complete"])
    week_ago = now - timedelta(days=7)
    created = [_utc(t["created_at"]) for t in active if t.get("created_at")]
    return {
        "total": len(active),
        "archived": len(trees) - len(active),
        "registeredLast7Days": sum(1 for c in created if c and c >= week_ago),
        "withRegistrationPhoto": criteria["photo"],
        "accuracy": {"good": accuracy["GOOD"], "acceptable": accuracy["ACCEPTABLE"], "low": accuracy["LOW"]},
        "completeness": {
            "complete": complete,
            "total": len(active),
            "percent": round(100 * complete / len(active), 1) if active else None,
            "criteria": criteria,
            "definition": field_quality.COMPLETENESS_DEFINITION,
        },
        "weekly": _weekly([c for c in created if c], now),
    }


def _assessments_section(db: Database, now: datetime) -> dict[str, Any]:
    docs = list(db[LEAF_ASSESSMENTS].find({}, {"_id": False, "status": 1, "class_name": 1, "created_at": 1, "image_path": 1}))
    by_status: dict[str, int] = {}
    by_class: dict[str, int] = {}
    for doc in docs:
        by_status[doc.get("status") or "UNKNOWN"] = by_status.get(doc.get("status") or "UNKNOWN", 0) + 1
        if doc.get("status") in ("COMPLETED", "UNCERTAIN") and doc.get("class_name"):
            by_class[doc["class_name"]] = by_class.get(doc["class_name"], 0) + 1
    created = [_utc(d["created_at"]) for d in docs if d.get("created_at")]
    week_ago = now - timedelta(days=7)
    return {
        "total": len(docs),
        "withEvidencePhoto": sum(1 for d in docs if d.get("image_path")),
        "last7Days": sum(1 for c in created if c and c >= week_ago),
        "byStatus": by_status,
        "byPredictedClass": [{"className": k, "count": v} for k, v in sorted(by_class.items(), key=lambda kv: -kv[1])],
        "weekly": _weekly([c for c in created if c], now),
    }


def _training_images(db: Database) -> dict[str, Any]:
    counts = classifier_repository.image_counts(db)
    classes = list(db[CLASSIFIER_CLASSES].find({}, {"_id": False, "id": 1, "name": 1}).sort("name", 1))
    by_class = [{"classId": c["id"], "name": c["name"], "count": counts.get(c["id"], {}).get("total", 0),
                 "embedded": counts.get(c["id"], {}).get("embedded", 0)} for c in classes]
    return {"total": sum(entry["count"] for entry in by_class), "byClass": by_class}


def _job_brief(job: dict[str, Any]) -> dict[str, Any]:
    return {"jobId": job["id"], "status": job["status"], "trigger": job.get("trigger"), "createdAt": job.get("created_at"),
            "finishedAt": job.get("finished_at"), "modelVersion": job.get("model_version"), "errorMessage": job.get("error_message")}


def _model_section(db: Database, ai: Any) -> dict[str, Any]:
    recent_failures = [_job_brief(j) for j in db[AI_JOBS].find({"status": "ERROR"}, {"_id": False}).sort("finished_at", -1).limit(5)]
    if ai is not None:
        status = ai.model_status()
        active = status.get("activeModel")
        return {
            "aiEnabled": True,
            "status": status["status"],
            "stale": status.get("stale", False),
            "activeVersion": active["modelVersion"] if active else None,
            "backbone": status["backbone"]["displayName"],
            "trainedAt": active["trainedAt"] if active else None,
            "validationAccuracy": active["metrics"]["accuracy"] if active else None,
            "validationMacroF1": active["metrics"]["macroF1"] if active else None,
            "currentJob": status.get("currentJob"),
            "lastJob": status.get("lastJob"),
            "recentFailures": recent_failures,
        }
    # AI disabled on this server: report the stored model registry only.
    active = db[AI_MODELS].find_one({"active": True}, {"_id": False}, sort=[("model_version", -1)])
    last = db[AI_JOBS].find_one({"status": {"$in": ["COMPLETE", "ERROR"]}}, {"_id": False}, sort=[("finished_at", -1)])
    return {
        "aiEnabled": False,
        "status": "DISABLED",
        "stale": False,
        "activeVersion": active["model_version"] if active else None,
        "backbone": active.get("backbone_display_name") if active else None,
        "trainedAt": active.get("trained_at") if active else None,
        "validationAccuracy": active["metrics"]["accuracy"] if active else None,
        "validationMacroF1": active["metrics"]["macroF1"] if active else None,
        "currentJob": None,
        "lastJob": _job_brief(last) if last else None,
        "recentFailures": recent_failures,
    }


@router.get("/dashboard")
def dashboard(request: Request, db: Database = Depends(get_db), _: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    now = datetime.now(timezone.utc)
    trees = _trees_section(db, now)
    assessments = _assessments_section(db, now)
    recent, _total = audit.page(db, None, None, 0, 8)
    return {
        "generatedAt": now,
        "trees": trees,
        "images": {
            "treeRegistration": trees["withRegistrationPhoto"],
            "training": _training_images(db),
            "assessmentEvidence": assessments["withEvidencePhoto"],
        },
        "assessments": assessments,
        "users": user_repository.summary(db),
        "model": _model_section(db, getattr(request.app.state, "ai", None)),
        "recentActivity": [audit.view(e) for e in recent],
    }


@router.get("/activity")
def activity(
    action: str | None = Query(default=None, max_length=40, pattern=r"^[a-z_.]+$"),
    actor: str | None = Query(default=None, max_length=100),
    paging: PageParams = Depends(page_params),
    db: Database = Depends(get_db),
    _: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    entries, total = audit.page(db, action, actor, paging.skip, paging.page_size)
    return paging.response([audit.view(e) for e in entries], total)

"""AI model and dataset profiles for Mobile Settings. Every figure is read at request time from
MongoDB, the served APK or recorded evaluation files; nothing is estimated.

Two models are described separately because they can differ: the *server* model (the active
``ai_models`` record, trained from the current dataset) and the *bundled* model inside the APK
that /download serves (what phones run offline). A newly trained server model does not change
APKs already installed; it reaches phones through export, parity checks and a new release.

Metrics always carry their evaluation type and sample count. The server model's own metrics are
an internal validation split; locked-test and cross-source results are separate records written by
``scripts/record_model_evaluation.py`` from evaluation report files.
"""

from __future__ import annotations

import csv
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pymongo import DESCENDING
from pymongo.database import Database

from app.config import Settings
from app.database.mongo import AI_JOBS, AI_MODELS, CLASSIFIER_CLASSES, CLASSIFIER_IMAGES, DATASET_ARCHIVES, LEAF_ASSESSMENTS, MODEL_EVALUATIONS
from app.services import dataset_archive, mobile_release

PIPELINE = ("One frozen DINOv2 feature extractor and one lightweight classifier head with {n} classes "
            "(not one model per class).")


def _class_names(db: Database) -> dict[str, str]:
    return {c["id"]: c["name"] for c in db[CLASSIFIER_CLASSES].find({}, {"_id": False, "id": 1, "name": 1})}


def _server_model(db: Database) -> dict[str, Any] | None:
    model = db[AI_MODELS].find_one({"active": True}, {"_id": False}, sort=[("model_version", DESCENDING)])
    if model is None:
        return None
    names = _class_names(db)
    mapping = sorted(model.get("class_mapping", []), key=lambda e: e.get("index", 0))
    metrics = model.get("metrics") or {}

    def name(class_id: str) -> str:
        return names.get(class_id) or next((e["name"] for e in mapping if e["class_id"] == class_id), class_id)

    matrix = metrics.get("confusionMatrix") or {}
    return {
        "modelVersion": model.get("model_version"),
        "backbone": model.get("backbone"),
        "backboneDisplayName": model.get("backbone_display_name"),
        "embeddingModel": model.get("embedding_model"),
        "embeddingDimension": model.get("embedding_dimension"),
        "head": (model.get("head") or {}).get("type"),
        "status": model.get("status"),
        "trainedAt": model.get("trained_at"),
        "activatedAt": model.get("activated_at"),
        "classes": [name(e["class_id"]) for e in mapping],
        "trainCount": model.get("train_count"),
        "validationCount": model.get("validation_count"),
        "perClassCounts": [
            {"name": name(e["class_id"]), "train": e.get("train_count"), "validation": e.get("validation_count")} for e in mapping
        ],
        "validation": {
            "type": "internal_validation_split",
            "label": "Internal validation split (same dataset, grouped split)",
            "samples": metrics.get("samples", model.get("validation_count")),
            "accuracy": metrics.get("accuracy"),
            "macroF1": metrics.get("macroF1"),
            "macroPrecision": metrics.get("macroPrecision"),
            "macroRecall": metrics.get("macroRecall"),
            "perClass": [
                {"name": name(r["label"]), "precision": r.get("precision"), "recall": r.get("recall"), "f1": r.get("f1"), "support": r.get("support")}
                for r in metrics.get("perClass", [])
            ],
            "confusionMatrix": {"labels": [name(x) for x in matrix.get("labels", [])], "matrix": matrix.get("matrix")} if matrix else None,
        } if metrics else None,
    }


def evaluation_view(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": record["id"],
        "modelVersion": record.get("model_version"),
        "headSha256": record.get("head_sha256"),
        "type": record.get("evaluation_type"),
        "label": record.get("label"),
        "samples": record.get("samples"),
        "accuracy": record.get("accuracy"),
        "balancedAccuracy": record.get("balanced_accuracy"),
        "macroF1": record.get("macro_f1"),
        "perClass": record.get("per_class") or [],
        "confusionMatrix": record.get("confusion_matrix"),
        "details": record.get("details") or {},
        "limitations": record.get("limitations") or "",
        "sourceFile": record.get("source_file"),
        "sourceSha256": record.get("source_sha256"),
        "evaluatedAt": record.get("evaluated_at"),
        "recordedAt": record.get("recorded_at"),
    }


def model_profile(db: Database, settings: Settings, ai_runtime: bool) -> dict[str, Any]:
    server = _server_model(db)
    apk, apk_error = mobile_release.inspect_served(settings)
    bundled = apk.get("bundledModel") if apk else None
    versions = {v for v in ((server or {}).get("modelVersion"), (bundled or {}).get("modelVersion")) if v is not None}
    evaluations = [
        evaluation_view(r)
        for r in db[MODEL_EVALUATIONS].find({"model_version": {"$in": sorted(versions)}}, {"_id": False}).sort("recorded_at", DESCENDING)
    ]
    job = db[AI_JOBS].find_one({}, {"_id": False}, sort=[("created_at", DESCENDING)])
    comparison = None
    if server and bundled:
        comparison = {
            "sameVersion": server["modelVersion"] == bundled.get("modelVersion"),
            "sameClassOrder": server["classes"] == bundled.get("classes"),
            "sameFeatureExtractor": server["embeddingModel"] == bundled.get("embeddingModel"),
        }
    class_count = len((bundled or server or {}).get("classes") or [])
    return {
        "pipeline": PIPELINE.format(n=class_count) if class_count else None,
        "server": server,
        "serverAiRuntime": ai_runtime,
        "bundled": {**bundled, "apkVersionName": apk.get("versionName"), "apkVersionCode": apk.get("versionCode"),
                    "inference": "on-device (offline, ONNX Runtime)"} if bundled else None,
        "bundledError": apk_error,
        "comparison": comparison,
        "evaluations": evaluations,
        "latestJob": {
            "id": job.get("id"), "status": job.get("status"), "trigger": job.get("trigger"), "createdAt": job.get("created_at"),
            "finishedAt": job.get("finished_at"), "modelVersion": job.get("model_version"), "errorMessage": job.get("error_message"),
        } if job else None,
        "updateNote": "Training a new server model does not change the model inside installed APKs. A new model reaches "
                      "phones only after evaluation, ONNX export/parity checks and a new APK release.",
    }


def _archive_hashes(settings: Settings, archive_id: str) -> set[str] | None:
    if settings.dataset_archive_dir is None:
        return None
    path = settings.dataset_archive_dir / archive_id / "manifest.csv"
    if not path.is_file():
        return None
    with path.open(encoding="utf-8") as fh:
        return {r["sha256"] for r in csv.DictReader(fh) if r.get("status") == dataset_archive.ACCEPTED}


def dataset_profile(db: Database, settings: Settings) -> dict[str, Any]:
    names = _class_names(db)
    images = list(db[CLASSIFIER_IMAGES].find({}, {"_id": False, "class_id": 1, "sha256": 1, "source_part": 1, "active": 1}))
    active_images = [i for i in images if i.get("active", True)]
    by_class = Counter(names.get(i["class_id"], i["class_id"]) for i in active_images)
    by_source: dict[str, Counter] = defaultdict(Counter)
    for image in active_images:
        by_source[image.get("source_part") or "(no provenance)"][names.get(image["class_id"], image["class_id"])] += 1

    active_archive = db[DATASET_ARCHIVES].find_one({"status": dataset_archive.ACTIVE}, {"_id": False})
    consistency = None
    if active_archive:
        accepted = _archive_hashes(settings, active_archive["id"])
        if accepted is not None:
            in_archive = sum(1 for i in active_images if i.get("sha256") in accepted)
            consistency = {"activeImages": len(active_images), "inActiveArchive": in_archive,
                           "notInActiveArchive": len(active_images) - in_archive}

    model = db[AI_MODELS].find_one({"active": True}, {"_id": False, "model_version": 1, "train_count": 1, "validation_count": 1},
                                   sort=[("model_version", DESCENDING)])
    archives, total_archives = dataset_archive.page(db, 0, 20)
    return {
        "activeTrainingImages": {
            "total": len(active_images),
            "byClass": dict(sorted(by_class.items())),
            "bySource": {part: dict(c) for part, c in sorted(by_source.items())},
            "note": "Images the server trains from (training + validation). Locked test images are never imported.",
        },
        "activeArchive": dataset_archive.view(active_archive) if active_archive else None,
        "consistency": consistency,
        "activeModel": {"modelVersion": model.get("model_version"), "trainCount": model.get("train_count"),
                        "validationCount": model.get("validation_count")} if model else None,
        "leafAssessmentPhotos": db[LEAF_ASSESSMENTS].count_documents({"image_path": {"$nin": [None, ""]}}),
        "leafAssessmentNote": "Field assessment photos are separate from training data and never enter it automatically.",
        "archives": archives,
        "archivesTotal": total_archives,
        "uploadsEnabled": settings.dataset_archive_dir is not None,
        "maxUploadBytes": settings.dataset_archive_max_bytes,
        "decodeChecked": dataset_archive.DECODE_AVAILABLE,
    }


# Evaluation records (written by scripts/record_model_evaluation.py) -------------------------

def _metrics_fields(metrics: dict[str, Any]) -> dict[str, Any]:
    matrix = metrics.get("confusionMatrix") or {}
    return {
        "samples": metrics.get("samples"),
        "accuracy": metrics.get("accuracy"),
        "balanced_accuracy": metrics.get("balancedAccuracy"),
        "macro_f1": metrics.get("macroF1"),
        "per_class": [
            {"name": r.get("label"), "precision": r.get("precision"), "recall": r.get("recall"), "f1": r.get("f1"), "support": r.get("support")}
            for r in metrics.get("perClass", [])
        ],
        "confusion_matrix": {"labels": matrix.get("labels"), "matrix": matrix.get("rowsExpected_colsPredicted") or matrix.get("matrix")}
        if matrix else None,
    }


def evaluation_records(report: dict[str, Any], source_file: str, source_sha256: str, head_sha256: str | None,
                       evaluated_at: datetime | None, limitations: str) -> list[dict[str, Any]]:
    """Evaluation records from an ``evaluate_classifier_holdout.py`` report (locked test and, when
    present, leave-one-source-out)."""
    version = report["model"]["version"]
    now = datetime.now(timezone.utc)
    common = {"model_version": version, "head_sha256": head_sha256, "source_file": source_file, "source_sha256": source_sha256,
              "evaluated_at": evaluated_at, "recorded_at": now}
    test_set = report.get("testSet") or {}
    records = [{
        "id": uuid.uuid4().hex, **common, "evaluation_type": "locked_test",
        "label": "Locked held-out test (never trained on)",
        **_metrics_fields(report["test"]),
        "details": {"correct": report["test"].get("correct"), "groups": test_set.get("groups"),
                    "perClassSupport": test_set.get("perClass"), "bySetup": {
                        k: {"samples": v.get("samples"), "accuracy": v.get("accuracy")} for k, v in (report.get("testBySetup") or {}).items()}},
        "limitations": limitations,
    }]
    loso = report.get("leaveOneSourceOut") or {}
    if loso:
        accuracies = [v["accuracy"] for v in loso.values()]
        records.append({
            "id": uuid.uuid4().hex, **common, "evaluation_type": "leave_one_source_out",
            "label": "Cross-source check (train on other source folders, test on the held-out one)",
            "samples": sum(v["samples"] for v in loso.values()),
            "accuracy": None, "balanced_accuracy": None, "macro_f1": None, "per_class": [], "confusion_matrix": None,
            "details": {"accuracyMin": min(accuracies), "accuracyMax": max(accuracies),
                        "bySource": {k: {"samples": v["samples"], "accuracy": v["accuracy"], "macroF1": v.get("macroF1")} for k, v in loso.items()}},
            "limitations": "Heads refitted per held-out source; indicates transfer to a new capture session, not the deployed head's accuracy.",
        })
    return records


def archive_split_from_manifest(path: Path) -> dict[str, Any]:
    """Per-class split counts from a provenance manifest (split column: train_val | test | excluded)."""
    with path.open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    counts: dict[str, Counter] = defaultdict(Counter)
    for row in rows:
        counts[row.get("canonical_label") or "(none)"][row.get("split") or "(none)"] += 1
    totals = Counter(row.get("split") for row in rows)
    return {"perClass": {k: dict(v) for k, v in sorted(counts.items())}, "totals": dict(totals), "manifestFile": path.name}

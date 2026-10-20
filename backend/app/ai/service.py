"""AI orchestration behind the admin and assessment routes.

Pipeline (see CLAUDE.md, "Training strategy"):

    upload → validate → persist image + thumbnail → DINOv2 feature computed once → cached .npy
           → dataset version + 1 → coalesced (debounced) training job
    job    → compute only missing/incompatible features → deterministic stratified split
           → fit the logistic-regression head → validate → write versioned artifact → activate

The DINOv2 backbone is never trained. A failed job never touches the active model.

Concurrency model (local prototype): one backend process, one in-process worker thread that
runs training jobs one at a time. Jobs are persisted in MongoDB, so queued jobs survive a
restart; a job interrupted by a restart is marked ERROR (never silently retried in a loop).
This is not a durable production queue.
"""

from __future__ import annotations

import hashlib
import logging
import re
import threading
import time
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any

import numpy as np
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.ai.config import AiSettings
from app.ai.dataset import ClassCount, SplitItem, readiness, split_train_validation
from app.ai.features import BackboneUnavailable, FeatureExtractor
from app.ai.head import C_GRID, MAX_ITERATIONS, LinearHead, fit_head
from app.ai.images import ValidatedImage, make_thumbnail, validate_image
from app.ai.metrics import classification_metrics
from app.ai.mobile import head_document
from app.ai.resources import MemoryGuard
from app.ai.storage import AiStorage
from app.database.mongo import ensure_ai_indexes
from app.errors import ApiError
from app.services import audit
from app.repositories import ai_repository, classifier_repository

logger = logging.getLogger(__name__)

DEFAULT_CLASSES = ("Healthy", "Spotting", "Powdery Mildew")
MAX_CLASS_NAME = 40
WORKER_POLL_SECONDS = 1.0


class TrainingError(Exception):
    """An expected training failure with a message meant for the administrator."""


def _new_id() -> str:
    return uuid.uuid4().hex


def slugify(name: str) -> str:
    return "-".join(re.findall(r"[^\W_]+", name.lower()))


def normalize_class_name(raw: str) -> str:
    name = " ".join((raw or "").split())
    allowed = all(ch.isalnum() or ch in " -_()" for ch in name)
    if not 1 <= len(name) <= MAX_CLASS_NAME or not allowed or not slugify(name):
        raise ApiError(422, "INVALID_CLASS_NAME", f"Use 1-{MAX_CLASS_NAME} letters, numbers, spaces, hyphens or underscores.")
    return name


def display_filename(raw: str | None) -> str:
    """For display only; stored files always get server-generated names."""
    name = (raw or "").replace("\\", "/").rsplit("/", 1)[-1]
    name = "".join(ch for ch in name if ch.isprintable()).strip()[:120]
    return name or "image"


class AiService:
    def __init__(
        self,
        db: Database,
        settings: AiSettings,
        extractor: FeatureExtractor,
        max_image_bytes: int,
        clock: Callable[[], datetime] | None = None,
        memory_guard: MemoryGuard | None = None,
    ):
        self.db = db
        self.settings = settings
        self.extractor = extractor
        self.max_image_bytes = max_image_bytes
        self.storage = AiStorage(settings.data_dir)
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._queue_lock = threading.Lock()
        self._head_lock = threading.Lock()
        self._head_cache: tuple[int, LinearHead] | None = None
        self._stop = threading.Event()
        self._worker: threading.Thread | None = None
        self.guard = memory_guard or MemoryGuard(
            settings.training_min_free_mb,
            settings.inference_min_free_mb,
            settings.container_min_headroom_mb,
            container_inference_mb=settings.container_inference_headroom_mb,
        )
        # Why queued work is waiting (None when it is not), for the admin status screen.
        self._deferred: dict[str, Any] | None = None
        self._last_deferral_log = 0.0

    # Lifecycle ------------------------------------------------------------------------------
    def start(self, seed_default_classes: bool) -> None:
        self.storage.prepare()
        ensure_ai_indexes(self.db)
        if seed_default_classes:
            self.seed_default_classes()
        interrupted = ai_repository.fail_interrupted_jobs(self.db, self._clock())
        if interrupted:
            logger.warning("Marked %d interrupted training job(s) as ERROR.", interrupted)
        if self.settings.worker_enabled:
            self._worker = threading.Thread(target=self._worker_loop, name="ai-training-worker", daemon=True)
            self._worker.start()
        if self.settings.preload_backbone and hasattr(self.extractor, "load"):
            threading.Thread(target=self._preload, name="ai-backbone-preload", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()
        if self._worker is not None:
            self._worker.join(timeout=5)

    def _preload(self) -> None:
        try:
            self.extractor.load()  # type: ignore[attr-defined]
        except BackboneUnavailable as exc:
            logger.error("DINOv2 backbone not available yet: %s", exc)

    def _worker_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.run_due_jobs()
            except Exception:  # noqa: BLE001 - the worker must survive and keep serving the queue
                logger.exception("AI training worker iteration failed")
            self._stop.wait(WORKER_POLL_SECONDS)

    def seed_default_classes(self) -> int:
        if classifier_repository.count_classes(self.db):
            return 0
        for name in DEFAULT_CLASSES:
            self._insert_class(name)
        logger.info("Seeded default classifier classes: %s", ", ".join(DEFAULT_CLASSES))
        return len(DEFAULT_CLASSES)

    # Classes --------------------------------------------------------------------------------
    def _insert_class(self, name: str) -> dict[str, Any]:
        now = self._clock()
        document = {"id": _new_id(), "name": name, "slug": slugify(name), "active": True, "created_at": now, "updated_at": now}
        try:
            classifier_repository.insert_class(self.db, document)
        except DuplicateKeyError as exc:
            raise self._name_conflict(name) from exc
        return document

    @staticmethod
    def _name_conflict(name: str) -> ApiError:
        return ApiError(409, "CLASS_NAME_CONFLICT", f"A class named {name} already exists.")

    def _require_class(self, class_id: str) -> dict[str, Any]:
        document = classifier_repository.find_class(self.db, class_id)
        if document is None:
            raise ApiError(404, "CLASS_NOT_FOUND", "Classifier class not found.")
        return document

    def list_classes(self) -> dict[str, Any]:
        classes = classifier_repository.list_classes(self.db)
        counts = classifier_repository.image_counts(self.db)
        active = ai_repository.active_model(self.db)
        in_model = {entry["class_id"] for entry in (active or {}).get("class_mapping", [])}
        return {
            "items": [self._class_view(c, counts.get(c["id"], {}), c["id"] in in_model) for c in classes],
            "minImagesPerClass": self.settings.min_images_per_class,
        }

    def _class_view(self, document: dict[str, Any], counts: dict[str, int], in_active_model: bool) -> dict[str, Any]:
        total = counts.get("total", 0)
        embedded = counts.get("embedded", 0)
        if total < self.settings.min_images_per_class:
            status = "INSUFFICIENT"
        elif embedded < total:
            status = "PENDING_FEATURES"
        else:
            status = "READY"
        return {
            "id": document["id"],
            "name": document["name"],
            "slug": document["slug"],
            "imageCount": total,
            "embeddedCount": embedded,
            "status": status,
            "inActiveModel": in_active_model,
            "createdAt": document["created_at"],
            "updatedAt": document["updated_at"],
        }

    def create_class(self, raw_name: str) -> dict[str, Any]:
        name = normalize_class_name(raw_name)
        if classifier_repository.find_class_by_slug(self.db, slugify(name)):
            raise self._name_conflict(name)
        document = self._insert_class(name)
        # The class set changed: the active model cannot predict this label until rebuilt.
        ai_repository.bump_dataset_version(self.db)
        return self._class_view(document, {}, False)

    def rename_class(self, class_id: str, raw_name: str) -> dict[str, Any]:
        current = self._require_class(class_id)
        name = normalize_class_name(raw_name)
        holder = classifier_repository.find_class_by_slug(self.db, slugify(name))
        if holder is not None and holder["id"] != class_id:
            raise self._name_conflict(name)
        try:
            updated = classifier_repository.update_class(
                self.db, class_id, {"name": name, "slug": slugify(name), "updated_at": self._clock()}
            )
        except DuplicateKeyError as exc:
            raise self._name_conflict(name) from exc
        # Models map class ids, not names, so a rename never invalidates the active model.
        counts = classifier_repository.image_counts(self.db).get(class_id, {})
        active = ai_repository.active_model(self.db)
        in_model = any(e["class_id"] == class_id for e in (active or {}).get("class_mapping", []))
        logger.info("Renamed class %s from %s to %s", class_id, current["name"], name)
        return self._class_view(updated, counts, in_model)

    def delete_class(self, class_id: str) -> dict[str, Any]:
        self._require_class(class_id)
        images = classifier_repository.delete_images_of_class(self.db, class_id)
        classifier_repository.delete_class(self.db, class_id)
        for image in images:
            self._delete_image_files(image)
        self.storage.delete_class_directory(class_id)
        invalidated = ai_repository.deactivate_models_with_class(
            self.db, class_id, "A class used by this model was deleted.", self._clock()
        )
        self._head_cache = None
        dataset_version = ai_repository.bump_dataset_version(self.db)
        job = self._auto_train()
        return {
            "deletedImages": len(images),
            "modelInvalidated": invalidated > 0,
            "datasetVersion": dataset_version,
            "job": self._job_view(job),
        }

    # Images ---------------------------------------------------------------------------------
    def list_images(self, class_id: str) -> dict[str, Any]:
        document = self._require_class(class_id)
        images = classifier_repository.list_images(self.db, class_id)
        return {"classId": class_id, "className": document["name"], "items": [self._image_view(i) for i in images]}

    @staticmethod
    def _image_view(image: dict[str, Any]) -> dict[str, Any]:
        return {
            "id": image["id"],
            "classId": image["class_id"],
            "originalFilename": image["original_filename"],
            "mimeType": image["mime_type"],
            "fileSize": image["file_size"],
            "width": image.get("width"),
            "height": image.get("height"),
            "embeddingStatus": image.get("embedding_status", "PENDING"),
            "createdAt": image["created_at"],
        }

    def add_images(self, class_id: str, files: list[tuple[str | None, bytes]]) -> dict[str, Any]:
        document = self._require_class(class_id)
        if not files:
            raise ApiError(400, "NO_FILES", "Attach at least one image.")
        if len(files) > self.settings.max_files_per_upload:
            raise ApiError(413, "TOO_MANY_FILES", f"Upload at most {self.settings.max_files_per_upload} images at a time.")

        accepted: list[tuple[dict[str, Any], bytes]] = []
        rejected: list[dict[str, Any]] = []
        seen: set[str] = set()
        for raw_name, data in files:
            filename = display_filename(raw_name)
            try:
                image = validate_image(data, self.max_image_bytes)
                if image.sha256 in seen:
                    raise ApiError(409, "DUPLICATE_IMAGE", "This image appears twice in the upload.")
                holder = classifier_repository.find_image_by_hash(self.db, image.sha256)
                if holder is not None:
                    raise self._duplicate(holder)
                accepted.append((self._store_image(document, filename, image), image.data))
                seen.add(image.sha256)
            except ApiError as exc:
                rejected.append({"filename": filename, "code": exc.code, "message": exc.message})

        if not accepted:
            first = rejected[0]
            status = 409 if first["code"] == "DUPLICATE_IMAGE" else 415 if first["code"] == "UNSUPPORTED_IMAGE" else 422
            raise ApiError(status, first["code"], first["message"], {"rejected": rejected})

        embedded = self._embed_new_images(accepted)
        dataset_version = ai_repository.bump_dataset_version(self.db)
        job = self._auto_train()
        uploaded = [classifier_repository.find_image(self.db, class_id, doc["id"]) or doc for doc, _ in accepted]
        logger.info("Class %s: %d image(s) added, %d feature(s) computed, %d rejected", document["name"], len(accepted), embedded, len(rejected))
        return {
            "uploaded": [self._image_view(image) for image in uploaded],
            "rejected": rejected,
            "embeddingsComputed": embedded,
            "datasetVersion": dataset_version,
            "job": self._job_view(job),
        }

    def _duplicate(self, holder: dict[str, Any]) -> ApiError:
        owner = classifier_repository.find_class(self.db, holder["class_id"])
        where = f" in {owner['name']}" if owner else ""
        return ApiError(409, "DUPLICATE_IMAGE", f"An identical image is already in the dataset{where}.", {"classId": holder["class_id"]})

    def _store_image(self, class_document: dict[str, Any], filename: str, image: ValidatedImage) -> dict[str, Any]:
        image_id = _new_id()
        relative = self.storage.image_relative_path(class_document["id"], image_id, image.extension)
        self.storage.write_bytes(relative, image.data)
        self.storage.thumbnail_path(image_id).write_bytes(make_thumbnail(image.data))
        record = {
            "id": image_id,
            "class_id": class_document["id"],
            "original_filename": filename,
            "stored_path": relative,
            "mime_type": image.mime_type,
            "file_size": len(image.data),
            "width": image.width,
            "height": image.height,
            "sha256": image.sha256,
            "embedding_path": None,
            "embedding_model": None,
            "embedding_dimension": None,
            "embedding_status": "PENDING",
            "active": True,
            "created_at": self._clock(),
        }
        try:
            classifier_repository.insert_image(self.db, record)
        except DuplicateKeyError as exc:  # lost a race with an identical concurrent upload
            self._delete_image_files(record)
            raise ApiError(409, "DUPLICATE_IMAGE", "An identical image is already in the dataset.") from exc
        return record

    def _embed_new_images(self, accepted: list[tuple[dict[str, Any], bytes]]) -> int:
        """Runs DINOv2 once for the new images only. Failures keep the images (retried by training)."""
        decision = self.guard.training()
        if not decision.allowed:
            # Stored now, embedded once later by the training job when memory allows.
            logger.warning("Feature extraction for %d new image(s) deferred: %s", len(accepted), decision.message)
            for record, _ in accepted:
                classifier_repository.update_image(self.db, record["id"], {"embedding_status": "PENDING", "embedding_error": "Deferred: server low on memory."})
            return 0
        try:
            vectors = self.extractor.extract([data for _, data in accepted])
        except Exception as exc:  # noqa: BLE001 - images stay stored; the next training run retries
            logger.warning("Feature extraction failed for %d new image(s): %s", len(accepted), exc)
            for record, _ in accepted:
                classifier_repository.update_image(self.db, record["id"], {"embedding_status": "ERROR", "embedding_error": str(exc)[:300]})
            return 0
        for (record, _), vector in zip(accepted, vectors):
            self._save_embedding(record["id"], vector)
        return len(accepted)

    def _save_embedding(self, image_id: str, vector: np.ndarray) -> None:
        relative = self.storage.write_embedding(image_id, vector)
        classifier_repository.update_image(
            self.db,
            image_id,
            {
                "embedding_path": relative,
                "embedding_model": self.extractor.cache_key,
                "embedding_dimension": int(vector.shape[-1]),
                "embedding_status": "READY",
                "embedding_error": None,
                "embedded_at": self._clock(),
            },
        )

    def _embedding_usable(self, image: dict[str, Any]) -> bool:
        if image.get("embedding_status") != "READY" or image.get("embedding_model") != self.extractor.cache_key:
            return False
        path = image.get("embedding_path")
        return bool(path) and self.storage.resolve(path).is_file()

    def delete_image(self, class_id: str, image_id: str) -> dict[str, Any]:
        self._require_class(class_id)
        image = classifier_repository.find_image(self.db, class_id, image_id)
        if image is None:
            raise ApiError(404, "IMAGE_NOT_FOUND", "Image not found.")
        classifier_repository.delete_image(self.db, image_id)
        self._delete_image_files(image)  # includes the cached embedding
        dataset_version = ai_repository.bump_dataset_version(self.db)
        job = self._auto_train()
        return {"deleted": image_id, "datasetVersion": dataset_version, "job": self._job_view(job)}

    def _delete_image_files(self, image: dict[str, Any]) -> None:
        self.storage.delete(image.get("stored_path"))
        self.storage.delete(image.get("embedding_path"))
        self.storage.thumbnail_path(image["id"]).unlink(missing_ok=True)

    def image_file(self, class_id: str, image_id: str, thumbnail: bool) -> tuple[Any, str]:
        image = classifier_repository.find_image(self.db, class_id, image_id)
        if image is None:
            raise ApiError(404, "IMAGE_NOT_FOUND", "Image not found.")
        path = self.storage.thumbnail_path(image_id) if thumbnail else self.storage.resolve(image["stored_path"])
        if not path.is_file():
            raise ApiError(404, "IMAGE_NOT_FOUND", "Image file is missing on the server.")
        return path, "image/jpeg" if thumbnail else image["mime_type"]

    # Training queue -------------------------------------------------------------------------
    def _readiness(self):
        classes = classifier_repository.list_classes(self.db)
        counts = classifier_repository.image_counts(self.db)
        items = [ClassCount(c["id"], c["name"], counts.get(c["id"], {}).get("total", 0)) for c in classes]
        return readiness(items, self.settings.min_images_per_class), classes

    def _auto_train(self) -> dict[str, Any] | None:
        """Called after every dataset change. Not ready → no job; the status explains why."""
        ready, _ = self._readiness()
        return self.request_training("AUTO") if ready.can_train else None

    def retrain(self) -> dict[str, Any]:
        ready, _ = self._readiness()
        if not ready.can_train:
            raise ApiError(409, "AI_DATASET_INSUFFICIENT", "The dataset is not ready for training.", {"problems": ready.problems})
        return self._job_view(self.request_training("MANUAL"))

    def request_training(self, trigger: str) -> dict[str, Any]:
        """Coalesces: while a job is QUEUED, further changes reuse it and push back its start."""
        now = self._clock()
        run_after = now if trigger == "MANUAL" else now + timedelta(seconds=self.settings.retrain_debounce_seconds)
        with self._queue_lock:
            queued = ai_repository.find_job_with_status(self.db, "QUEUED")
            if queued is not None:
                fields: dict[str, Any] = {"coalesced_changes": int(queued.get("coalesced_changes", 1)) + 1}
                if trigger == "MANUAL":
                    fields.update(trigger="MANUAL", run_after=now)
                elif queued.get("trigger") != "MANUAL":  # never delay an explicit request
                    fields["run_after"] = run_after
                return ai_repository.update_job(self.db, queued["id"], fields) or queued
            job = {
                "id": _new_id(),
                "status": "QUEUED",
                "trigger": trigger,
                "created_at": now,
                "run_after": run_after,
                "coalesced_changes": 1,
                "dataset_version": ai_repository.dataset_version(self.db),
            }
            ai_repository.insert_job(self.db, job)
            return job

    def run_due_jobs(self, now: datetime | None = None) -> int:
        ran = 0
        while True:
            decision = self.guard.training()
            if not decision.allowed:
                # One job at a time, and only with enough free memory: the job stays QUEUED and the
                # previous model stays active until memory is available again.
                self._note_deferral(decision)
                return ran
            job = ai_repository.claim_due_job(self.db, now or self._clock())
            if job is None:
                self._deferred = None
                return ran
            self._deferred = None
            self._run_job(job)
            ran += 1

    def _note_deferral(self, decision: Any) -> None:
        if ai_repository.find_job_with_status(self.db, "QUEUED") is None:
            self._deferred = None
            return
        self._deferred = {"message": decision.message, "availableMb": decision.available_mb, "requiredMb": decision.required_mb}
        now = time.monotonic()
        if now - self._last_deferral_log > 60:
            self._last_deferral_log = now
            logger.warning("Training deferred: %s", decision.message)

    def _run_job(self, job: dict[str, Any]) -> None:
        logger.info("Training job %s started (%s)", job["id"], job.get("trigger"))
        try:
            summary = self._train(job)
        except Exception as exc:  # noqa: BLE001 - every failure is recorded; the active model is untouched
            message = str(exc) if isinstance(exc, (TrainingError, BackboneUnavailable)) else f"Training failed: {exc}"
            if not isinstance(exc, (TrainingError, BackboneUnavailable)):
                logger.exception("Training job %s failed", job["id"])
            else:
                logger.warning("Training job %s failed: %s", job["id"], message)
            ai_repository.update_job(self.db, job["id"], {"status": "ERROR", "finished_at": self._clock(), "error_message": message[:500]})
            audit.record(self.db, None, "model.training_failed", "Classifier training failed; the previous model stays active",
                         "ai_job", job["id"], {"error": message[:300]})
            return
        ai_repository.update_job(self.db, job["id"], {"status": "COMPLETE", "finished_at": self._clock(), **summary})
        audit.record(self.db, None, "model.activated", f"Model v{summary['model_version']} trained and activated", "ai_model",
                     str(summary["model_version"]), {"validationAccuracy": summary["validation_accuracy"],
                                                     "validationMacroF1": summary["validation_macro_f1"]})
        logger.info("Training job %s complete: model v%s", job["id"], summary["model_version"])

    def _ensure_embeddings(self, images: list[dict[str, Any]]) -> tuple[int, int]:
        """Computes features only for images without a usable cached one. Returns (computed, reused)."""
        missing = [image for image in images if not self._embedding_usable(image)]
        batch = max(1, self.settings.batch_size) * 8
        for start in range(0, len(missing), batch):
            chunk = missing[start : start + batch]
            data = [self.storage.resolve(image["stored_path"]).read_bytes() for image in chunk]
            vectors = self.extractor.extract(data)
            for image, vector in zip(chunk, vectors):
                self._save_embedding(image["id"], vector)
                image.update(embedding_path=self.storage.embedding_relative_path(image["id"]))
        return len(missing), len(images) - len(missing)

    def _train(self, job: dict[str, Any]) -> dict[str, Any]:
        dataset_version = ai_repository.dataset_version(self.db)
        ready, classes = self._readiness()
        if not ready.can_train:
            raise TrainingError("Dataset not ready: " + " ".join(ready.problems))
        class_ids = [c["id"] for c in classes]
        names = {c["id"]: c["name"] for c in classes}
        images = [i for i in classifier_repository.all_active_images(self.db) if i["class_id"] in names]

        computed, reused = self._ensure_embeddings(images)
        features = np.stack([self.storage.read_embedding(image["embedding_path"]) for image in images]).astype(np.float32)
        if features.shape[1] != self.extractor.dimension:
            raise TrainingError(f"Cached features have dimension {features.shape[1]}, expected {self.extractor.dimension}.")

        row = {image["id"]: i for i, image in enumerate(images)}
        items = [SplitItem(image["id"], image["class_id"], image["sha256"], image.get("split_group")) for image in images]
        grouped = any(item.group for item in items)
        train, validation = split_train_validation(items, self.settings.validation_fraction, self.settings.split_seed)
        head = fit_head(
            features[[row[i.image_id] for i in train]],
            [i.class_id for i in train],
            class_ids,
            seed=self.settings.split_seed,
            groups=[i.group_key for i in train] if grouped else None,
        )
        predicted = head.predict(features[[row[i.image_id] for i in validation]])
        metrics = classification_metrics([i.class_id for i in validation], predicted, labels=class_ids)
        if metrics["samples"] == 0 or any(r["support"] == 0 for r in metrics["perClass"]) or not np.isfinite(metrics["macroF1"]):
            raise TrainingError("Validation failed: every class needs at least one validation image.")

        now = self._clock()
        version = ai_repository.next_model_version(self.db)
        artifact = self.storage.model_relative_path(version)
        train_counts = {cid: sum(1 for i in train if i.class_id == cid) for cid in class_ids}
        validation_counts = {cid: sum(1 for i in validation if i.class_id == cid) for cid in class_ids}
        document = {
            "id": _new_id(),
            "model_family": "dinov2",
            "backbone": self.extractor.model_name,
            "backbone_display_name": self.extractor.display_name,
            "embedding_model": self.extractor.cache_key,
            "embedding_dimension": self.extractor.dimension,
            "model_version": version,
            "status": "READY",
            "active": False,
            "class_mapping": [
                {"index": i, "class_id": cid, "name": names[cid], "train_count": train_counts[cid], "validation_count": validation_counts[cid]}
                for i, cid in enumerate(class_ids)
            ],
            "train_count": len(train),
            "validation_count": len(validation),
            "metrics": metrics,
            "head": {
                "type": "logistic_regression",
                "c": head.c,
                "c_grid": list(C_GRID),
                "cv_macro_f1_by_c": head.cv_scores,
                "class_weight": "balanced",
                "max_iter": MAX_ITERATIONS,
                "features": "l2-normalized, centered, scaled by sqrt(dimension)",
            },
            "split": {
                "validation_fraction": self.settings.validation_fraction,
                "seed": self.settings.split_seed,
                "stratified": True,
                "grouped_by": "split_group (else sha256)" if grouped else "sha256",
                "validation_image_ids": sorted(i.image_id for i in validation),
            },
            "artifact_path": artifact,
            "dataset_version": dataset_version,
            "job_id": job["id"],
            "created_at": now,
            "trained_at": now,
            "activated_at": None,
        }
        try:
            head.save(self.storage.resolve(artifact))
            ai_repository.insert_model(self.db, document)
        except Exception:
            self.storage.delete(artifact)
            raise
        self.storage.write_model_metadata(version, {k: v for k, v in document.items() if k != "_id"})
        ai_repository.activate_model(self.db, document["id"], self._clock())
        return {
            "model_version": version,
            "embeddings_computed": computed,
            "embeddings_reused": reused,
            "train_count": len(train),
            "validation_count": len(validation),
            "validation_accuracy": metrics["accuracy"],
            "validation_macro_f1": metrics["macroF1"],
        }

    # Prediction -----------------------------------------------------------------------------
    def _active_head(self) -> tuple[dict[str, Any], LinearHead]:
        model = ai_repository.active_model(self.db)
        if model is None:
            raise ApiError(
                409,
                "AI_MODEL_NOT_READY",
                "No trained tamarind leaf model is active yet. An administrator must add images and train the classifier.",
            )
        if model["embedding_model"] != self.extractor.cache_key:
            raise ApiError(409, "AI_MODEL_NOT_READY", "The active model was trained with a different backbone configuration. Rebuild the model.")
        with self._head_lock:
            if self._head_cache is None or self._head_cache[0] != model["model_version"]:
                path = self.storage.resolve(model["artifact_path"])
                if not path.is_file():
                    raise ApiError(409, "AI_MODEL_NOT_READY", "The active model file is missing on the server. Rebuild the model.")
                self._head_cache = (model["model_version"], LinearHead.load(path))
            return model, self._head_cache[1]

    def assess(self, data: bytes) -> dict[str, Any]:
        image = validate_image(data, self.max_image_bytes)
        model, head = self._active_head()
        decision = self.guard.inference()
        if not decision.allowed:
            raise ApiError(503, "AI_BUSY", decision.message or "The AI server is busy. Try again shortly.",
                           {"availableMb": decision.available_mb, "requiredMb": decision.required_mb})
        try:
            feature = self.extractor.extract([image.data])
        except BackboneUnavailable as exc:
            raise ApiError(503, "AI_BACKBONE_UNAVAILABLE", str(exc)) from exc
        probabilities = head.predict_proba(feature)[0]
        names = self._current_names(model)
        ranked = sorted(
            ({"classId": cid, "className": names[cid], "probability": float(p)} for cid, p in zip(head.class_ids, probabilities)),
            key=lambda entry: entry["probability"],
            reverse=True,
        )
        best = ranked[0]
        threshold = self.settings.confidence_threshold
        return {
            "classId": best["classId"],
            "className": best["className"],
            "confidence": best["probability"],
            "uncertain": best["probability"] < threshold,
            "confidenceThreshold": threshold,
            "model": model["backbone"],
            "modelDisplayName": model["backbone_display_name"],
            "modelVersion": model["model_version"],
            "probabilities": ranked,
            "assessedAt": self._clock(),
        }

    def mobile_head(self) -> dict[str, Any]:
        """The active classifier head for on-device inference. Read-only; computes no features."""
        model, head = self._active_head()
        path = self.storage.resolve(model["artifact_path"])
        return head_document(model, head, self._current_names(model), artifact_sha256=hashlib.sha256(path.read_bytes()).hexdigest())

    def _current_names(self, model: dict[str, Any]) -> dict[str, str]:
        current = {c["id"]: c["name"] for c in classifier_repository.list_classes(self.db)}
        return {entry["class_id"]: current.get(entry["class_id"], entry["name"]) for entry in model["class_mapping"]}

    # Status ---------------------------------------------------------------------------------
    def model_status(self) -> dict[str, Any]:
        active = ai_repository.active_model(self.db)
        training = ai_repository.find_job_with_status(self.db, "TRAINING")
        queued = ai_repository.find_job_with_status(self.db, "QUEUED")
        finished = ai_repository.latest_finished_job(self.db)
        ready, _ = self._readiness()
        dataset_version = ai_repository.dataset_version(self.db)

        failed_after_active = (
            finished is not None
            and finished["status"] == "ERROR"
            and (active is None or active.get("activated_at") is None or finished["finished_at"] > active["activated_at"])
        )
        if training:
            status = "TRAINING"
        elif queued:
            status = "QUEUED"
        elif failed_after_active:
            status = "ERROR"
        elif active:
            status = "READY"
        else:
            status = "UNTRAINED"
        counts = classifier_repository.image_counts(self.db)
        backbone = self.extractor.status()
        return {
            "status": status,
            "stale": active is not None and int(active.get("dataset_version", 0)) < dataset_version,
            "datasetVersion": dataset_version,
            "datasetImageCount": sum(entry["total"] for entry in counts.values()),
            "readiness": {"canTrain": ready.can_train, "minImagesPerClass": ready.min_images_per_class, "problems": ready.problems},
            "activeModel": self._model_view(active) if active else None,
            "currentJob": self._job_view(training or queued),
            "lastJob": self._job_view(finished),
            "lastError": finished["error_message"] if failed_after_active else None,
            "backbone": {
                "name": self.extractor.model_name,
                "displayName": self.extractor.display_name,
                "embeddingDimension": self.extractor.dimension,
                "imageSize": self.settings.image_size,
                "loaded": backbone.get("loaded", False),
                "device": backbone.get("device"),
                "error": backbone.get("error"),
            },
            "confidenceThreshold": self.settings.confidence_threshold,
            "resourceGuard": {
                "enabled": self.guard.enabled,
                "deferred": self._deferred is not None,
                "message": (self._deferred or {}).get("message"),
                "trainingMinFreeMb": self.guard.training_min_mb,
                "inferenceMinFreeMb": self.guard.inference_min_mb,
                "containerMinHeadroomMb": self.guard.container_min_mb,
                "containerInferenceHeadroomMb": self.guard.container_inference_mb,
            },
        }

    def public_status(self) -> dict[str, Any]:
        """What a field user needs before assessing a leaf. No dataset or training internals."""
        active = ai_repository.active_model(self.db)
        compatible = active is not None and active["embedding_model"] == self.extractor.cache_key
        return {
            "ready": compatible,
            "status": "READY" if compatible else "NOT_READY",
            "model": self.extractor.model_name,
            "modelDisplayName": self.extractor.display_name,
            "modelVersion": active["model_version"] if compatible else None,
            "classes": sorted(self._current_names(active).values()) if compatible else [],
            "confidenceThreshold": self.settings.confidence_threshold,
            "trainedAt": active["trained_at"] if compatible else None,
        }

    def _model_view(self, model: dict[str, Any]) -> dict[str, Any]:
        names = self._current_names(model)
        metrics = model["metrics"]
        return {
            "modelVersion": model["model_version"],
            "model": model["backbone"],
            "modelDisplayName": model["backbone_display_name"],
            "trainedAt": model["trained_at"],
            "activatedAt": model.get("activated_at"),
            "datasetVersion": model.get("dataset_version"),
            "trainCount": model["train_count"],
            "validationCount": model["validation_count"],
            "classes": [
                {"classId": e["class_id"], "name": names[e["class_id"]], "trainCount": e["train_count"], "validationCount": e["validation_count"]}
                for e in model["class_mapping"]
            ],
            "metrics": {
                "accuracy": metrics["accuracy"],
                "macroPrecision": metrics["macroPrecision"],
                "macroRecall": metrics["macroRecall"],
                "macroF1": metrics["macroF1"],
                "perClass": [
                    {"classId": r["label"], "className": names.get(r["label"], r["label"]), "precision": r["precision"], "recall": r["recall"], "f1": r["f1"], "support": r["support"]}
                    for r in metrics["perClass"]
                ],
                "confusionMatrix": {
                    "classIds": metrics["confusionMatrix"]["labels"],
                    "labels": [names.get(label, label) for label in metrics["confusionMatrix"]["labels"]],
                    "matrix": metrics["confusionMatrix"]["matrix"],
                },
            },
        }

    def list_models(self, limit: int = 20) -> dict[str, Any]:
        """Model version history, newest first. Old versions stay listed after a newer one activates."""
        items = []
        for model in ai_repository.list_models(self.db, limit):
            view = self._model_view(model)
            view.update(active=bool(model.get("active")), status=model.get("status"),
                        invalidatedReason=model.get("invalidated_reason"))
            items.append(view)
        return {"items": items}

    def list_jobs(self, limit: int = 20) -> dict[str, Any]:
        return {"items": [self._job_view(job) for job in ai_repository.list_jobs(self.db, limit)]}

    def job(self, job_id: str) -> dict[str, Any]:
        job = ai_repository.find_job(self.db, job_id)
        if job is None:
            raise ApiError(404, "JOB_NOT_FOUND", "Training job not found.")
        return self._job_view(job)  # type: ignore[return-value]

    @staticmethod
    def _job_view(job: dict[str, Any] | None) -> dict[str, Any] | None:
        if job is None:
            return None
        return {
            "jobId": job["id"],
            "status": job["status"],
            "trigger": job.get("trigger"),
            "createdAt": job.get("created_at"),
            "runAfter": job.get("run_after"),
            "startedAt": job.get("started_at"),
            "finishedAt": job.get("finished_at"),
            "coalescedChanges": job.get("coalesced_changes", 1),
            "modelVersion": job.get("model_version"),
            "embeddingsComputed": job.get("embeddings_computed"),
            "embeddingsReused": job.get("embeddings_reused"),
            "errorMessage": job.get("error_message"),
        }

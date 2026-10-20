"""Admin classifier management, embedding cache, coalesced training and leaf assessment.

Everything runs against mongomock and FakeExtractor (no PyTorch, no DINOv2 download).
"""

from datetime import datetime, timedelta, timezone

import pytest

from app.auth.security import hash_password
from app.repositories import user_repository
from tests.conftest import CLASS_COLORS, leaf_image

API = "/api/v1/admin"
LATER = timedelta(seconds=60)  # past the 15 s debounce window


def classes_by_name(client, headers) -> dict[str, dict]:
    response = client.get(f"{API}/classifiers", headers=headers)
    assert response.status_code == 200, response.text
    return {item["name"]: item for item in response.json()["items"]}


def upload(client, headers, class_id, images: list[tuple[str, bytes]]):
    files = [("files", (name, data, "image/png")) for name, data in images]
    return client.post(f"{API}/classifiers/{class_id}/images", files=files, headers=headers)


def fill_dataset(client, headers, per_class: int = 4, seed_offset: int = 0) -> dict[str, dict]:
    classes = classes_by_name(client, headers)
    for index, (name, color) in enumerate(CLASS_COLORS.items()):
        images = [(f"{name.lower()}_{i}.png", leaf_image(color, seed=seed_offset + index * 1000 + i)) for i in range(per_class)]
        assert upload(client, headers, classes[name]["id"], images).status_code == 200
    return classes


def run_jobs(client, after: timedelta = LATER) -> int:
    return client.app.state.ai.run_due_jobs(now=datetime.now(timezone.utc) + after)


def train(client, headers) -> dict:
    fill_dataset(client, headers)
    assert run_jobs(client) == 1
    status = client.get(f"{API}/ai/model", headers=headers).json()
    assert status["status"] == "READY", status
    return status


def assess(client, headers, data: bytes, name: str = "leaf.png"):
    return client.post("/api/v1/ai/leaf-assessment", files={"file": (name, data, "image/png")}, headers=headers)


# Authorization ------------------------------------------------------------------------------------
def test_dev_admin_has_admin_role(ai_client, admin_headers):
    me = ai_client.get("/api/v1/auth/me", headers=admin_headers).json()
    assert me["role"] == "ADMIN"


def test_existing_dev_admin_without_role_is_upgraded_once(db, settings):
    from app.services.seed import seed_dev_admin

    db.users.insert_one({"email": "admin@gmail.com", "password_hash": hash_password("admin123"), "display_name": "Old"})
    seed_dev_admin(db, settings)
    assert db.users.find_one({"email": "admin@gmail.com"})["role"] == "ADMIN"
    db.users.update_one({"email": "admin@gmail.com"}, {"$set": {"role": "FIELD"}})
    seed_dev_admin(db, settings)  # an existing role is never overwritten
    assert db.users.find_one({"email": "admin@gmail.com"})["role"] == "FIELD"


def test_admin_routes_require_token_and_admin_role(ai_client, db):
    assert ai_client.get(f"{API}/classifiers").status_code == 401
    user_repository.create_if_missing(db, "field@example.com", hash_password("field-pass"), "Field User")
    token = ai_client.post("/api/v1/auth/login", json={"email": "field@example.com", "password": "field-pass"}).json()["access_token"]
    field = {"Authorization": f"Bearer {token}"}
    for method, path in [
        ("get", f"{API}/classifiers"),
        ("post", f"{API}/ai/retrain"),
        ("get", f"{API}/ai/model"),
        ("delete", f"{API}/classifiers/abc"),
        ("get", "/api/v1/ai/jobs/abc"),
    ]:
        response = getattr(ai_client, method)(path, headers=field)
        assert response.status_code == 403, (path, response.text)
        assert response.json()["error"]["code"] == "ADMIN_REQUIRED"
    # Field users may still assess leaves (the model is simply not ready yet).
    response = assess(ai_client, field, leaf_image((40, 160, 60), 1))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "AI_MODEL_NOT_READY"


def test_ai_routes_report_disabled_when_ai_is_off(client, auth_headers):
    assert client.get("/api/v1/health").json()["ai"] == "disabled"
    response = client.get(f"{API}/classifiers", headers=auth_headers)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "AI_DISABLED"
    assert assess(client, auth_headers, leaf_image((1, 2, 3), 1)).json()["error"]["code"] == "AI_DISABLED"


# Classes ------------------------------------------------------------------------------------------
def test_default_classes_are_seeded_once_on_empty_database(ai_client, admin_headers):
    assert ai_client.get("/api/v1/health").json()["ai"] == "enabled"
    body = ai_client.get(f"{API}/classifiers", headers=admin_headers).json()
    assert [item["name"] for item in body["items"]] == ["Healthy", "Spotting", "Powdery Mildew"]
    assert all(item["imageCount"] == 0 and item["status"] == "INSUFFICIENT" for item in body["items"])
    assert body["minImagesPerClass"] == 3
    assert ai_client.app.state.ai.seed_default_classes() == 0


def test_class_create_rename_conflict_and_validation(ai_client, admin_headers):
    created = ai_client.post(f"{API}/classifiers", json={"name": "  Other   leaf "}, headers=admin_headers)
    assert created.status_code == 201
    assert created.json()["name"] == "Other leaf"
    other_id = created.json()["id"]

    duplicate = ai_client.post(f"{API}/classifiers", json={"name": "HEALTHY"}, headers=admin_headers)
    assert duplicate.status_code == 409
    assert duplicate.json()["error"]["code"] == "CLASS_NAME_CONFLICT"

    invalid = ai_client.post(f"{API}/classifiers", json={"name": "../etc"}, headers=admin_headers)
    assert invalid.status_code == 422
    assert invalid.json()["error"]["code"] == "INVALID_CLASS_NAME"

    renamed = ai_client.patch(f"{API}/classifiers/{other_id}", json={"name": "Unknown"}, headers=admin_headers)
    assert renamed.status_code == 200
    assert renamed.json()["slug"] == "unknown"
    clash = ai_client.patch(f"{API}/classifiers/{other_id}", json={"name": "powdery mildew"}, headers=admin_headers)
    assert clash.status_code == 409

    assert ai_client.delete(f"{API}/classifiers/{other_id}", headers=admin_headers).status_code == 200
    assert "Unknown" not in classes_by_name(ai_client, admin_headers)
    assert ai_client.delete(f"{API}/classifiers/{other_id}", headers=admin_headers).status_code == 404


# Images and embedding cache -----------------------------------------------------------------------
def test_upload_persists_images_and_computes_each_feature_once(ai_client, admin_headers, fake_extractor, ai_settings):
    healthy = classes_by_name(ai_client, admin_headers)["Healthy"]
    images = [(f"h{i}.png", leaf_image(CLASS_COLORS["Healthy"], i)) for i in range(2)]
    response = upload(ai_client, admin_headers, healthy["id"], images)
    assert response.status_code == 200, response.text
    body = response.json()
    assert len(body["uploaded"]) == 2 and body["rejected"] == []
    assert body["embeddingsComputed"] == 2
    assert fake_extractor.calls == [2]  # one batch, only the new images
    first = body["uploaded"][0]
    assert first["embeddingStatus"] == "READY"
    assert first["originalFilename"] == "h0.png"
    assert set(first) == {"id", "classId", "originalFilename", "mimeType", "fileSize", "width", "height", "embeddingStatus", "createdAt"}
    assert len(list((ai_settings.ai.data_dir / "embeddings").glob("*.npy"))) == 2

    upload(ai_client, admin_headers, healthy["id"], [("h2.png", leaf_image(CLASS_COLORS["Healthy"], 2))])
    assert fake_extractor.calls == [2, 1]  # old images were not re-embedded
    listed = ai_client.get(f"{API}/classifiers/{healthy['id']}/images", headers=admin_headers).json()
    assert listed["className"] == "Healthy" and len(listed["items"]) == 3
    assert classes_by_name(ai_client, admin_headers)["Healthy"]["imageCount"] == 3


def test_duplicate_and_invalid_uploads_are_rejected(ai_client, admin_headers):
    classes = classes_by_name(ai_client, admin_headers)
    image = leaf_image(CLASS_COLORS["Powdery Mildew"], 7)
    assert upload(ai_client, admin_headers, classes["Powdery Mildew"]["id"], [("a.png", image)]).status_code == 200

    again = upload(ai_client, admin_headers, classes["Healthy"]["id"], [("copy.png", image)])
    assert again.status_code == 409
    assert again.json()["error"]["code"] == "DUPLICATE_IMAGE"
    assert "Powdery Mildew" in again.json()["error"]["message"]

    mixed = upload(ai_client, admin_headers, classes["Powdery Mildew"]["id"], [("new.png", leaf_image(CLASS_COLORS["Powdery Mildew"], 8)), ("evil.png", b"<?php ?>"), ("copy.png", image)])
    assert mixed.status_code == 200
    assert [r["code"] for r in mixed.json()["rejected"]] == ["UNSUPPORTED_IMAGE", "DUPLICATE_IMAGE"]
    assert len(mixed.json()["uploaded"]) == 1

    bad = upload(ai_client, admin_headers, classes["Powdery Mildew"]["id"], [("x.jpg", b"not an image at all")])
    assert bad.status_code == 415
    tiny = upload(ai_client, admin_headers, classes["Powdery Mildew"]["id"], [("t.png", leaf_image((1, 1, 1), 1, size=8))])
    assert tiny.status_code == 422 and tiny.json()["error"]["code"] == "IMAGE_TOO_SMALL"


def test_heic_upload_is_normalized_to_jpeg(ai_client, admin_headers):
    pillow_heif = pytest.importorskip("pillow_heif")
    import io

    from PIL import Image

    buffer = io.BytesIO()
    try:
        Image.open(io.BytesIO(leaf_image(CLASS_COLORS["Healthy"], 3, size=64))).save(buffer, format="HEIF")
    except Exception as exc:  # encoder not bundled in this pillow-heif build
        pytest.skip(f"HEIF encoding unavailable: {exc} ({pillow_heif.__version__})")
    healthy = classes_by_name(ai_client, admin_headers)["Healthy"]
    response = ai_client.post(f"{API}/classifiers/{healthy['id']}/images", files=[("files", ("IMG_1.HEIC", buffer.getvalue(), "image/heic"))], headers=admin_headers)
    assert response.status_code == 200, response.text
    assert response.json()["uploaded"][0]["mimeType"] == "image/jpeg"


def test_thumbnail_and_file_are_served_without_exposing_paths(ai_client, admin_headers):
    healthy = classes_by_name(ai_client, admin_headers)["Healthy"]
    image_id = upload(ai_client, admin_headers, healthy["id"], [("h.png", leaf_image(CLASS_COLORS["Healthy"], 1))]).json()["uploaded"][0]["id"]
    thumb = ai_client.get(f"{API}/classifiers/{healthy['id']}/images/{image_id}/thumbnail", headers=admin_headers)
    assert thumb.status_code == 200 and thumb.headers["content-type"] == "image/jpeg"
    full = ai_client.get(f"{API}/classifiers/{healthy['id']}/images/{image_id}/file", headers=admin_headers)
    assert full.status_code == 200 and full.content.startswith(b"\x89PNG")
    assert ai_client.get(f"{API}/classifiers/{healthy['id']}/images/{image_id}/file").status_code == 401
    assert ai_client.get(f"{API}/classifiers/{healthy['id']}/images/..%2F..%2Fmain.py/file", headers=admin_headers).status_code == 404
    listing = ai_client.get(f"{API}/classifiers/{healthy['id']}/images", headers=admin_headers).text
    assert "classifier_images/" not in listing and "embeddings/" not in listing and "sha256" not in listing


def test_deleting_an_image_removes_its_embedding_and_queues_a_rebuild(ai_client, admin_headers, ai_settings):
    status = train(ai_client, admin_headers)
    assert status["stale"] is False
    healthy = classes_by_name(ai_client, admin_headers)["Healthy"]
    images = ai_client.get(f"{API}/classifiers/{healthy['id']}/images", headers=admin_headers).json()["items"]
    victim = images[0]["id"]
    embedding = ai_settings.ai.data_dir / "embeddings" / f"{victim}.npy"
    assert embedding.exists()

    response = ai_client.delete(f"{API}/classifiers/{healthy['id']}/images/{victim}", headers=admin_headers)
    assert response.status_code == 200
    assert response.json()["job"]["status"] == "QUEUED"
    assert not embedding.exists()
    assert not (ai_settings.ai.data_dir / "thumbnails" / f"{victim}.jpg").exists()
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "QUEUED" and status["stale"] is True
    assert status["activeModel"]["modelVersion"] == 1  # still serving until the rebuild succeeds
    assert ai_client.delete(f"{API}/classifiers/{healthy['id']}/images/{victim}", headers=admin_headers).status_code == 404


# Training queue -----------------------------------------------------------------------------------
def test_insufficient_dataset_is_explained_and_not_trained(ai_client, admin_headers):
    healthy = classes_by_name(ai_client, admin_headers)["Healthy"]
    response = upload(ai_client, admin_headers, healthy["id"], [("h.png", leaf_image(CLASS_COLORS["Healthy"], 1))])
    assert response.json()["job"] is None  # no auto job while the dataset is not ready
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "UNTRAINED"
    assert status["readiness"]["canTrain"] is False
    assert "Healthy needs 2 more images (1 of 3)." in status["readiness"]["problems"]
    retrain = ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    assert retrain.status_code == 409
    assert retrain.json()["error"]["code"] == "AI_DATASET_INSUFFICIENT"
    assert retrain.json()["error"]["details"]["problems"]


def test_dataset_changes_are_coalesced_into_one_debounced_job(ai_client, admin_headers, fake_extractor):
    classes = fill_dataset(ai_client, admin_headers, per_class=3)
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "QUEUED"
    job = status["currentJob"]
    assert job["trigger"] == "AUTO"
    # Three more uploads arrive while the job is waiting: still exactly one job.
    for i in range(3):
        upload(ai_client, admin_headers, classes["Powdery Mildew"]["id"], [(f"m{i}.png", leaf_image(CLASS_COLORS["Powdery Mildew"], 900 + i))])
    jobs = list(ai_client.app.state.ai.db.ai_jobs.find({}))
    assert len(jobs) == 1
    assert jobs[0]["coalesced_changes"] == 4  # 1 (dataset became ready) + 3 more uploads

    assert ai_client.app.state.ai.run_due_jobs() == 0  # debounce window still open
    embedded_before = fake_extractor.images_embedded
    assert run_jobs(ai_client) == 1
    assert fake_extractor.images_embedded == embedded_before  # training reused every cached feature

    done = ai_client.get(f"/api/v1/ai/jobs/{job['jobId']}", headers=admin_headers).json()
    assert done["status"] == "COMPLETE"
    assert done["modelVersion"] == 1
    assert done["embeddingsComputed"] == 0 and done["embeddingsReused"] == 12
    assert ai_client.get("/api/v1/ai/jobs/nope", headers=admin_headers).status_code == 404


def test_manual_retrain_runs_immediately_and_publishes_a_new_version(ai_client, admin_headers):
    train(ai_client, admin_headers)
    response = ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    assert response.status_code == 202
    assert response.json()["trigger"] == "MANUAL"
    assert ai_client.app.state.ai.run_due_jobs() == 1  # no debounce for a manual request
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["activeModel"]["modelVersion"] == 2
    assert ai_client.app.state.ai.db.ai_models.count_documents({"active": True}) == 1


def test_trained_model_records_metrics_mapping_and_artifact(ai_client, admin_headers, ai_settings):
    status = train(ai_client, admin_headers)
    model = status["activeModel"]
    assert model["model"] == "dinov2_vits14" and model["modelDisplayName"] == "DINOv2 ViT-S/14"
    assert [c["name"] for c in model["classes"]] == ["Healthy", "Spotting", "Powdery Mildew"]
    assert model["trainCount"] + model["validationCount"] == 12
    assert all(c["validationCount"] >= 1 for c in model["classes"])
    metrics = model["metrics"]
    for key in ("accuracy", "macroPrecision", "macroRecall", "macroF1"):
        assert 0.0 <= metrics[key] <= 1.0
    assert metrics["confusionMatrix"]["labels"] == ["Healthy", "Spotting", "Powdery Mildew"]
    assert sum(map(sum, metrics["confusionMatrix"]["matrix"])) == model["validationCount"]
    assert (ai_settings.ai.data_dir / "models" / "model_v1.npz").is_file()
    assert (ai_settings.ai.data_dir / "models" / "model_v1.json").is_file()
    stored = ai_client.app.state.ai.db.ai_models.find_one({"model_version": 1})
    assert stored["status"] == "READY" and stored["active"] is True
    assert [entry["index"] for entry in stored["class_mapping"]] == [0, 1, 2]


def test_failed_training_keeps_the_previous_model_active(ai_client, admin_headers, monkeypatch):
    train(ai_client, admin_headers)

    def broken_fit(*args, **kwargs):
        raise ValueError("solver exploded")

    monkeypatch.setattr("app.ai.service.fit_head", broken_fit)
    ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    assert ai_client.app.state.ai.run_due_jobs() == 1
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "ERROR"
    assert "solver exploded" in status["lastError"]
    assert status["activeModel"]["modelVersion"] == 1
    assert ai_client.app.state.ai.db.ai_models.count_documents({}) == 1
    # Predictions keep using v1.
    result = assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Healthy"], 4242))
    assert result.status_code == 200 and result.json()["modelVersion"] == 1

    monkeypatch.undo()
    ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    ai_client.app.state.ai.run_due_jobs()
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "READY" and status["activeModel"]["modelVersion"] == 2


def test_backbone_failure_during_training_is_an_error_not_a_crash(ai_client, admin_headers, fake_extractor):
    from app.ai.features import BackboneUnavailable

    classes = fill_dataset(ai_client, admin_headers)
    fake_extractor.fail = BackboneUnavailable("weights not downloaded")
    upload(ai_client, admin_headers, classes["Healthy"]["id"], [("late.png", leaf_image(CLASS_COLORS["Healthy"], 5555))])
    image = ai_client.app.state.ai.db.classifier_images.find_one({"original_filename": "late.png"})
    assert image["embedding_status"] == "ERROR"  # stored anyway, retried by training
    assert run_jobs(ai_client) == 1
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["status"] == "ERROR" and "weights not downloaded" in status["lastError"]

    fake_extractor.fail = None
    ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    ai_client.app.state.ai.run_due_jobs()
    job = ai_client.app.state.ai.db.ai_jobs.find_one({"status": "COMPLETE"})
    assert job["embeddings_computed"] == 1  # only the image that had failed


def test_backbone_config_change_invalidates_cached_features_and_model(ai_client, admin_headers, fake_extractor):
    train(ai_client, admin_headers)
    fake_extractor.cache_key = "fake-dinov2|test|v2"  # e.g. a different DINOV2_IMAGE_SIZE
    response = assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Healthy"], 1234))
    assert response.status_code == 409 and response.json()["error"]["code"] == "AI_MODEL_NOT_READY"
    ai_client.post(f"{API}/ai/retrain", headers=admin_headers)
    ai_client.app.state.ai.run_due_jobs()
    job = ai_client.app.state.ai.db.ai_jobs.find_one({"model_version": 2})
    assert job["embeddings_computed"] == 12 and job["embeddings_reused"] == 0
    assert assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Healthy"], 1234)).status_code == 200


def test_interrupted_job_is_marked_error_on_startup(ai_settings, db, fake_extractor):
    from fastapi.testclient import TestClient

    from app.main import create_app

    db.ai_jobs.insert_one({"id": "a" * 32, "status": "TRAINING", "trigger": "AUTO", "created_at": datetime.now(timezone.utc)})
    with TestClient(create_app(settings=ai_settings, database=db, feature_extractor=fake_extractor)):
        job = db.ai_jobs.find_one({"id": "a" * 32})
    assert job["status"] == "ERROR" and "restart" in job["error_message"]


# Prediction ---------------------------------------------------------------------------------------
def test_leaf_assessment_returns_class_confidence_and_model_version(ai_client, admin_headers):
    train(ai_client, admin_headers)
    classes = classes_by_name(ai_client, admin_headers)
    for name, color in CLASS_COLORS.items():
        response = assess(ai_client, admin_headers, leaf_image(color, 77_000))
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["className"] == name
        assert body["classId"] == classes[name]["id"]
        assert body["model"] == "dinov2_vits14"
        assert body["modelVersion"] == 1
        assert 0 < body["confidence"] <= 1
        assert body["uncertain"] is (body["confidence"] < body["confidenceThreshold"])
        assert abs(sum(p["probability"] for p in body["probabilities"]) - 1) < 1e-6

    public = ai_client.get("/api/v1/ai/status", headers=admin_headers).json()
    assert public == {**public, "ready": True, "modelVersion": 1, "model": "dinov2_vits14"}
    assert sorted(public["classes"]) == ["Healthy", "Powdery Mildew", "Spotting"]


def test_assessment_never_modifies_the_dataset(ai_client, admin_headers):
    train(ai_client, admin_headers)
    service = ai_client.app.state.ai
    before = (service.db.classifier_images.count_documents({}), service.db.ai_jobs.count_documents({}))
    assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Powdery Mildew"], 31337))
    assert (service.db.classifier_images.count_documents({}), service.db.ai_jobs.count_documents({})) == before


def test_model_not_ready_is_explicit(ai_client, admin_headers):
    response = assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Powdery Mildew"], 3))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "AI_MODEL_NOT_READY"
    assert ai_client.get("/api/v1/ai/status", headers=admin_headers).json()["ready"] is False
    assert assess(ai_client, admin_headers, b"plain text").status_code == 415


def _phone_probabilities(document: dict, feature) -> list[float]:
    """What the Android ClassifierHead computes from the downloaded document."""
    import numpy as np

    x = np.asarray(feature, dtype=np.float64)
    x = x / max(np.linalg.norm(x), 1e-12)
    x = (x - np.asarray(document["mean"])) / np.asarray(document["scale"])
    logits = np.asarray(document["weights"]) @ x + np.asarray(document["bias"])
    exp = np.exp(logits - logits.max())
    return (exp / exp.sum()).tolist()


def test_model_head_for_on_device_inference(ai_client, admin_headers, db, fake_extractor):
    assert ai_client.get("/api/v1/ai/model/head").status_code == 401
    assert ai_client.get("/api/v1/ai/model/head", headers=admin_headers).json()["error"]["code"] == "AI_MODEL_NOT_READY"

    train(ai_client, admin_headers)
    classes = classes_by_name(ai_client, admin_headers)
    ai_client.patch(f"{API}/classifiers/{classes['Powdery Mildew']['id']}", json={"name": "Sooty Mold"}, headers=admin_headers)
    # Field testers download it too: their phones run the assessment.
    user_repository.create_if_missing(db, "field@example.com", hash_password("field-pass"), "Field User")
    token = ai_client.post("/api/v1/auth/login", json={"email": "field@example.com", "password": "field-pass"}).json()["access_token"]
    response = ai_client.get("/api/v1/ai/model/head", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200, response.text
    document = response.json()
    assert document["formatVersion"] == 1
    assert document["modelVersion"] == 1
    assert document["embeddingModel"] == fake_extractor.cache_key
    assert document["embeddingDimension"] == fake_extractor.dimension
    assert [c["index"] for c in document["classes"]] == list(range(3))
    assert "Sooty Mold" in [c["name"] for c in document["classes"]]  # current names, original class ids
    assert len(document["weights"]) == 3 and all(len(row) == fake_extractor.dimension for row in document["weights"])
    assert len(document["serverArtifactSha256"]) == 64
    assert "artifact_path" not in str(document) and "/" not in document["serverArtifactSha256"]

    # Phone math on the downloaded head == the server's own assessment for the same image.
    image = leaf_image(CLASS_COLORS["Spotting"], 4242)
    server = assess(ai_client, admin_headers, image).json()
    phone = _phone_probabilities(document, fake_extractor.extract([image])[0])
    by_id = {c["classId"]: p for c, p in zip(document["classes"], phone)}
    for entry in server["probabilities"]:
        assert abs(by_id[entry["classId"]] - entry["probability"]) < 1e-12
    assert max(by_id, key=by_id.get) == server["classId"]


def test_rename_keeps_model_and_deleting_a_model_class_invalidates_it(ai_client, admin_headers):
    train(ai_client, admin_headers)
    classes = classes_by_name(ai_client, admin_headers)
    ai_client.patch(f"{API}/classifiers/{classes['Spotting']['id']}", json={"name": "Leaf Spot"}, headers=admin_headers)
    renamed = assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Spotting"], 555))
    assert renamed.json()["className"] == "Leaf Spot"

    deleted = ai_client.delete(f"{API}/classifiers/{classes['Powdery Mildew']['id']}", headers=admin_headers)
    assert deleted.status_code == 200
    assert deleted.json() | {"job": None} == {"deletedImages": 4, "modelInvalidated": True, "datasetVersion": deleted.json()["datasetVersion"], "job": None}
    assert assess(ai_client, admin_headers, leaf_image(CLASS_COLORS["Healthy"], 556)).json()["error"]["code"] == "AI_MODEL_NOT_READY"
    # The two remaining classes are still ready, so a rebuild was queued automatically.
    assert deleted.json()["job"]["status"] == "QUEUED"
    run_jobs(ai_client)
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert [c["name"] for c in status["activeModel"]["classes"]] == ["Healthy", "Leaf Spot"]


def test_new_class_appears_in_the_class_mapping_after_rebuild(ai_client, admin_headers):
    train(ai_client, admin_headers)
    other = ai_client.post(f"{API}/classifiers", json={"name": "Other"}, headers=admin_headers).json()
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert status["stale"] is True and status["readiness"]["canTrain"] is False
    images = [(f"o{i}.png", leaf_image((30, 30, 200), 4000 + i)) for i in range(4)]
    assert upload(ai_client, admin_headers, other["id"], images).json()["job"]["status"] == "QUEUED"
    run_jobs(ai_client)
    status = ai_client.get(f"{API}/ai/model", headers=admin_headers).json()
    assert [c["name"] for c in status["activeModel"]["classes"]] == ["Healthy", "Spotting", "Powdery Mildew", "Other"]
    assert assess(ai_client, admin_headers, leaf_image((30, 30, 200), 4999)).json()["className"] == "Other"

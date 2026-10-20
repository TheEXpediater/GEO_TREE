"""IMAGE_STORAGE=disabled: hosts without a durable disk (Render) never write photos where they would
silently vanish. Photo uploads are acknowledged without storing bytes, so records sync and each
photo stays on the phone that took it."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, load_settings
from app.main import create_app
from app.services.image_storage import IMAGE_STORAGE_HEADER
from tests.conftest import tree_payload
from tests.test_images import JPEG_BYTES
from tests.test_leaf_assessments import assessment, sync


@pytest.fixture
def no_storage_client(tmp_path, db) -> Iterator[TestClient]:
    settings = Settings(environment="development", jwt_secret="geo-tree-test-secret-at-least-32-bytes-long",
                        upload_dir=tmp_path / "uploads", image_storage="disabled")
    with TestClient(create_app(settings=settings, database=db)) as client:
        yield client


@pytest.fixture
def headers(no_storage_client) -> dict[str, str]:
    token = no_storage_client.post("/api/v1/auth/login", json={"email": "admin@gmail.com", "password": "admin123"}).json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


def stored_files(tmp_path) -> list:
    return [p for p in (tmp_path / "uploads").rglob("*") if p.is_file()]


def test_health_reports_the_photo_storage_mode(no_storage_client, client):
    assert no_storage_client.get("/api/v1/health").json()["imageStorage"] == "disabled"
    assert client.get("/api/v1/health").json()["imageStorage"] == "local"


def test_tree_photo_upload_syncs_the_record_without_storing_bytes(no_storage_client, headers, db, tmp_path):
    tree = tree_payload()
    assert no_storage_client.post("/api/v1/trees/sync", json=tree, headers=headers).status_code == 200
    response = no_storage_client.post(f"/api/v1/trees/{tree['id']}/image", files={"file": ("t.jpg", JPEG_BYTES, "image/jpeg")}, headers=headers)
    assert response.status_code == 200, response.text
    assert response.headers[IMAGE_STORAGE_HEADER] == "disabled"
    assert response.json()["image_path"] is None  # the phone keeps its photo and marks the tree synced
    assert db.trees.find_one({"id": tree["id"]})["image_path"] is None
    assert stored_files(tmp_path) == []


def test_assessment_photo_upload_syncs_the_scan_without_storing_bytes(no_storage_client, headers, db, tmp_path):
    tree = tree_payload()
    no_storage_client.post("/api/v1/trees/sync", json=tree, headers=headers)
    record = assessment(tree["id"])
    assert sync(no_storage_client, headers, record).status_code == 200
    response = no_storage_client.post(f"/api/v1/leaf-assessments/{record['assessment_id']}/image",
                                      files={"file": ("a.jpg", JPEG_BYTES, "image/jpeg")}, headers=headers)
    assert response.status_code == 200, response.text
    assert response.headers[IMAGE_STORAGE_HEADER] == "disabled"
    assert response.json()["image_path"] is None
    assert db.leaf_assessments.find_one({"assessment_id": record["assessment_id"]})["class_name"] == record["class_name"]
    assert stored_files(tmp_path) == []


def test_admin_photo_replacement_is_refused_with_a_clear_code(no_storage_client, headers):
    tree = tree_payload()
    no_storage_client.post("/api/v1/trees/sync", json=tree, headers=headers)
    response = no_storage_client.post(f"/api/v1/admin/trees/{tree['id']}/image", files={"file": ("t.jpg", JPEG_BYTES, "image/jpeg")}, headers=headers)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "IMAGE_STORAGE_DISABLED"


def test_render_defaults_to_disabled_unless_overridden(monkeypatch):
    for name in ("IMAGE_STORAGE", "RENDER"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("ENVIRONMENT", "development")
    assert load_settings().image_storage == "local"
    monkeypatch.setenv("RENDER", "true")  # set by Render on every service
    assert load_settings().image_storage == "disabled"
    monkeypatch.setenv("IMAGE_STORAGE", "local")  # an explicit choice (e.g. a persistent disk) wins
    assert load_settings().image_storage == "local"
    monkeypatch.setenv("IMAGE_STORAGE", "s3")
    with pytest.raises(RuntimeError):
        load_settings()

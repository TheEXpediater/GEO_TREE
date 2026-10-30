"""Mobile Settings: served APK facts, release registration/history, QR, model and dataset profiles,
and dataset archive validation/staging."""

from __future__ import annotations

import base64
import csv
import dataclasses
import io
import json
import time
import zipfile
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.auth import roles
from app.main import create_app
from app.services import apk_inspect, dataset_archive
from scripts.record_model_evaluation import RecordError, record as record_evaluation
from scripts.register_dataset_archive import register as register_archive
from tests.apk_fixtures import CERT_SHA256, CLASSES, EMBEDDING, build_apk
from tests.test_admin_roles import add_user, headers_for

BASE = "/api/v1/admin/mobile"
PUBLIC_URL = "https://geotree.example.org/download"


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    folder = tmp_path / "distribution"
    folder.mkdir()
    return folder


@pytest.fixture
def mobile_settings(settings, dist, tmp_path):
    return dataclasses.replace(settings, distribution_dir=dist, download_public_url=PUBLIC_URL,
                               release_signer_sha256=CERT_SHA256, dataset_archive_dir=tmp_path / "archives")


@pytest.fixture
def mclient(mobile_settings, db) -> Iterator[TestClient]:
    with TestClient(create_app(settings=mobile_settings, database=db)) as test_client:
        yield test_client


@pytest.fixture
def admin(mclient) -> dict[str, str]:
    return headers_for(mclient, "admin@gmail.com", "admin123")


@pytest.fixture
def superadmin(mclient, db) -> dict[str, str]:
    add_user(db, "super@example.com", roles.SUPER_ADMIN)
    return headers_for(mclient, "super@example.com")


def serve(dist: Path, *args, **kwargs) -> Path:
    return build_apk(dist / "GEO_Tree.apk", *args, **kwargs)


# APK inspection ---------------------------------------------------------------------------------

def test_inspect_reads_manifest_signer_and_bundled_model(tmp_path):
    facts = apk_inspect.inspect_apk(build_apk(tmp_path / "a.apk", "0.8.2", 13))
    assert (facts["packageName"], facts["versionName"], facts["versionCode"]) == ("com.geotree.app", "0.8.2", 13)
    assert (facts["minSdk"], facts["targetSdk"], facts["debuggable"]) == (26, 35, False)
    assert facts["signing"] == {"schemes": ["v2"], "certificateSha256": [CERT_SHA256], "note": None}
    model = facts["bundledModel"]
    assert model["modelVersion"] == 3 and model["classes"] == CLASSES and model["integrityOk"] is True


def test_inspect_detects_unsigned_debuggable_and_corrupt_bundle(tmp_path):
    facts = apk_inspect.inspect_apk(build_apk(tmp_path / "b.apk", debuggable=True, cert=None, corrupt_model=True))
    assert facts["debuggable"] is True
    assert facts["signing"]["certificateSha256"] == []
    assert facts["bundledModel"]["integrityOk"] is False


def test_inspect_rejects_a_non_apk(tmp_path):
    path = tmp_path / "x.apk"
    path.write_bytes(b"not a zip")
    with pytest.raises(apk_inspect.ApkError):
        apk_inspect.inspect_apk(path)


def test_corrupt_bundled_model_json_is_reported_not_raised(mclient, admin, dist):
    with zipfile.ZipFile(dist / "GEO_Tree.apk", "w") as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00\x08\x00\x00\x00")
        archive.writestr("assets/leaf_model/manifest.json", b"{not json")
    body = mclient.get(f"{BASE}/overview", headers=admin).json()
    assert body["health"] == "UNHEALTHY" and "Not a readable APK" in body["servedApkError"]


REAL_APK = Path(__file__).resolve().parents[2] / "geotree-0.8.1.apk"


@pytest.mark.skipif(not REAL_APK.is_file(), reason="the signed 0.8.1 release APK is not in this checkout (gitignored)")
def test_inspect_matches_aapt2_and_apksigner_on_the_real_release():
    facts = apk_inspect.inspect_apk(REAL_APK)
    assert (facts["packageName"], facts["versionName"], facts["versionCode"]) == ("com.geotree.app", "0.8.1", 12)
    assert facts["signing"]["certificateSha256"] == ["071b9904d58bd907b6bfb5520555d55f31f29b2b7436b3218067d827efe43321"]
    assert facts["debuggable"] is False
    assert facts["bundledModel"]["headSha256"].startswith("4ddbdc65") and facts["bundledModel"]["integrityOk"]


# Release overview, registration and history ------------------------------------------------------

def test_overview_reports_not_published_then_unregistered(mclient, admin, dist):
    body = mclient.get(f"{BASE}/overview", headers=admin).json()
    assert body["health"] == "NOT_PUBLISHED" and body["servedApk"] is None
    assert body["download"] == {"configured": True, "pageUrl": PUBLIC_URL, "apkUrl": PUBLIC_URL + "/GEO_Tree.apk",
                                "https": True, "distributionConfigured": True}
    serve(dist)
    body = mclient.get(f"{BASE}/overview", headers=admin).json()
    assert body["health"] == "UNREGISTERED"
    assert body["servedApk"]["versionCode"] == 12 and all(c["ok"] for c in body["checks"])


def test_baseline_then_new_release_then_rollback(mclient, superadmin, admin, dist, db):
    first = serve(dist)
    baseline = mclient.post(f"{BASE}/releases", json={"notes": "0.8.1 already served", "baseline": True}, headers=superadmin)
    assert baseline.status_code == 201, baseline.text
    body = baseline.json()
    assert body["status"] == "PUBLISHED" and body["baseline"] is True and body["originalPublishedAt"] is None
    assert body["apkSha256"] == apk_inspect.sha256_file(first) and body["model"]["modelVersion"] == 3
    assert mclient.get(f"{BASE}/overview", headers=admin).json()["health"] == "HEALTHY"
    assert mclient.post(f"{BASE}/releases", json={}, headers=superadmin).json()["error"]["code"] == "RELEASE_ALREADY_CURRENT"

    old_bytes = first.read_bytes()
    serve(dist, "0.8.2", 13, extra=b"v13")
    overview = mclient.get(f"{BASE}/overview", headers=admin).json()
    assert overview["health"] == "UNHEALTHY" and overview["currentRelease"]["versionCode"] == 12
    assert mclient.post(f"{BASE}/releases", json={"baseline": True}, headers=superadmin).json()["error"]["code"] == "BASELINE_NOT_FIRST"
    new = mclient.post(f"{BASE}/releases", json={"notes": "Hostinger"}, headers=superadmin)
    assert new.status_code == 201 and new.json()["originalPublishedAt"] is not None
    assert mclient.get(f"{BASE}/overview", headers=admin).json()["health"] == "HEALTHY"

    history = mclient.get(f"{BASE}/releases", headers=admin).json()
    assert [(r["versionCode"], r["status"]) for r in history["items"]] == [(13, "PUBLISHED"), (12, "SUPERSEDED")]

    serve(dist, "0.8.0", 11, extra=b"older")  # an unrecorded lower version cannot become current
    assert mclient.post(f"{BASE}/releases", json={}, headers=superadmin).json()["error"]["code"] == "VERSION_NOT_INCREASED"

    (dist / "GEO_Tree.apk").write_bytes(old_bytes)  # restoring a recorded APK is a rollback
    rollback = mclient.post(f"{BASE}/releases", json={}, headers=superadmin)
    assert rollback.status_code == 201 and rollback.json()["versionCode"] == 12
    statuses = {r["versionCode"]: r["status"] for r in mclient.get(f"{BASE}/releases", headers=admin).json()["items"]}
    assert statuses == {12: "PUBLISHED", 13: "ROLLED_BACK"}
    actions = [e["action"] for e in db.audit_log.find({"action": {"$regex": "^release"}})]
    assert actions.count("release.register") == 2 and actions.count("release.rollback") == 1


@pytest.mark.parametrize("kwargs, code", [
    ({"debuggable": True}, "RELEASE_CHECK_FAILED"),
    ({"cert": b"another-key"}, "RELEASE_CHECK_FAILED"),
    ({"package": "com.example.other"}, "RELEASE_CHECK_FAILED"),
    ({"corrupt_model": True}, "RELEASE_CHECK_FAILED"),
])
def test_registration_refuses_apks_that_fail_release_checks(mclient, superadmin, dist, kwargs, code):
    serve(dist, **kwargs)
    response = mclient.post(f"{BASE}/releases", json={"baseline": True}, headers=superadmin)
    assert response.status_code == 422 and response.json()["error"]["code"] == code


def test_registration_without_a_served_apk_is_refused(mclient, superadmin):
    response = mclient.post(f"{BASE}/releases", json={}, headers=superadmin)
    assert response.status_code == 409 and response.json()["error"]["code"] == "NO_SERVED_APK"


def test_mobile_settings_roles(mclient, db, admin, dist):
    serve(dist)
    add_user(db, "tester@example.com", roles.FIELD_TESTER)
    tester = headers_for(mclient, "tester@example.com")
    for path in ("/overview", "/releases", "/model", "/dataset", "/dataset/archives"):
        assert mclient.get(BASE + path).status_code == 401
        assert mclient.get(BASE + path, headers=tester).status_code == 403
        assert mclient.get(BASE + path, headers=admin).status_code == 200
    # Registering a release is Super Admin only.
    response = mclient.post(f"{BASE}/releases", json={"baseline": True}, headers=admin)
    assert response.status_code == 403 and response.json()["error"]["code"] == "SUPER_ADMIN_REQUIRED"


def test_qr_code_decodes_to_the_configured_download_url(mclient, admin):
    zxingcpp = pytest.importorskip("zxingcpp")
    qr = mclient.get(f"{BASE}/overview", headers=admin).json()["qr"]
    assert qr["url"] == PUBLIC_URL
    png = base64.b64decode(qr["pngDataUri"].split(",", 1)[1])
    decoded = zxingcpp.read_barcodes(Image.open(io.BytesIO(png)).convert("L"))
    assert [d.text for d in decoded] == [PUBLIC_URL]


def test_download_route_serves_the_registered_bytes(mclient, superadmin, dist):
    path = serve(dist)
    (dist / "index.html").write_text("<h1>GEO Tree</h1>", encoding="utf-8")
    mclient.post(f"{BASE}/releases", json={"baseline": True}, headers=superadmin)
    served = mclient.get("/download/GEO_Tree.apk")
    assert served.status_code == 200 and served.content == path.read_bytes()


# Model profile and evaluation records -----------------------------------------------------------

def _active_model(db, version=3):
    db.classifier_classes.insert_many([{"id": f"c{i}", "slug": n.lower(), "name": n} for i, n in enumerate(CLASSES)])
    db.ai_models.insert_one({
        "id": "m3", "model_version": version, "active": True, "status": "READY", "backbone": "dinov2_vits14",
        "backbone_display_name": "DINOv2 ViT-S/14", "embedding_model": EMBEDDING, "embedding_dimension": 384,
        "head": {"type": "logistic_regression"}, "train_count": 115, "validation_count": 29,
        "trained_at": datetime(2026, 10, 20, 8, 16, tzinfo=timezone.utc), "activated_at": None,
        "class_mapping": [{"index": i, "class_id": f"c{i}", "name": n, "train_count": 10, "validation_count": 3} for i, n in enumerate(CLASSES)],
        "metrics": {"samples": 29, "accuracy": 0.931, "macroPrecision": 0.92, "macroRecall": 0.92, "macroF1": 0.922,
                    "perClass": [{"label": "c0", "precision": 1.0, "recall": 0.875, "f1": 0.93, "support": 8}],
                    "confusionMatrix": {"labels": ["c0", "c1", "c2"], "matrix": [[7, 1, 0], [0, 6, 0], [0, 1, 14]]}},
    })


EVALUATION = {
    "model": {"version": 3, "artifact": "model_v3.npz", "embeddingModel": EMBEDDING, "classOrder": CLASSES},
    "testSet": {"images": 37, "perClass": {"Healthy": 7, "Powdery Mildew": 22, "Spotting": 8}, "groups": 31},
    "test": {"samples": 37, "accuracy": 34 / 37, "balancedAccuracy": 0.89, "macroF1": 0.878, "correct": 34,
             "perClass": [{"label": "Healthy", "precision": 0.83, "recall": 0.71, "f1": 0.77, "support": 7}],
             "confusionMatrix": {"labels": CLASSES, "rowsExpected_colsPredicted": [[5, 2, 0], [0, 8, 0], [1, 0, 21]]}},
    "leaveOneSourceOut": {"Annah": {"samples": 65, "accuracy": 0.708, "balancedAccuracy": 0.56, "macroF1": 0.58, "perClass": [], "confusionMatrix": {}},
                          "part 1": {"samples": 39, "accuracy": 0.487, "balancedAccuracy": 0.5, "macroF1": 0.45, "perClass": [], "confusionMatrix": {}}},
}


def test_model_profile_distinguishes_server_and_bundled_models(mclient, admin, db, dist, tmp_path):
    serve(dist)
    _active_model(db)
    report = tmp_path / "evaluation_v3.json"
    report.write_text(json.dumps(EVALUATION), encoding="utf-8")
    records = record_evaluation(db, report, "Same photo sessions as training; may be optimistic.", "4ddbdc65", None)
    assert [r["evaluation_type"] for r in records] == ["locked_test", "leave_one_source_out"]
    with pytest.raises(RecordError):
        record_evaluation(db, report, "again", None, None)

    body = mclient.get(f"{BASE}/model", headers=admin).json()
    assert body["server"]["modelVersion"] == 3 and body["server"]["classes"] == CLASSES
    assert body["server"]["validation"]["type"] == "internal_validation_split" and body["server"]["validation"]["samples"] == 29
    assert body["bundled"]["modelVersion"] == 3 and body["bundled"]["inference"].startswith("on-device")
    assert body["comparison"] == {"sameVersion": True, "sameClassOrder": True, "sameFeatureExtractor": True}
    assert body["serverAiRuntime"] is False
    assert "3 classes" in body["pipeline"] and "not one model per class" in body["pipeline"]
    locked = next(e for e in body["evaluations"] if e["type"] == "locked_test")
    assert locked["samples"] == 37 and locked["details"]["correct"] == 34 and locked["limitations"].startswith("Same photo")
    cross = next(e for e in body["evaluations"] if e["type"] == "leave_one_source_out")
    assert cross["accuracy"] is None and cross["details"]["accuracyMin"] == 0.487


def test_model_profile_without_any_model(mclient, admin):
    body = mclient.get(f"{BASE}/model", headers=admin).json()
    assert body["server"] is None and body["bundled"] is None and body["evaluations"] == []


# Dataset archives -------------------------------------------------------------------------------

def _jpeg(color, size=48) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (size, size), color).save(buffer, format="JPEG")
    return buffer.getvalue()


def make_archive(path: Path) -> Path:
    healthy, spot, mold = _jpeg((40, 160, 60)), _jpeg((140, 90, 40)), _jpeg((205, 205, 190))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("Part A/Healthy/h1.jpg", healthy)
        archive.writestr("Part A/Healthy/h1 copy.jpg", healthy)          # exact duplicate, same label
        archive.writestr("Part A/Spot/s1.JPG", spot)                     # alias -> Spotting
        archive.writestr("Part A/Mold/m1.jpg", mold)                     # remap -> Powdery Mildew
        archive.writestr("Part B/Healthy/x.jpg", _jpeg((10, 10, 200)))  # conflict ...
        archive.writestr("Part B/Spotting/x.jpg", _jpeg((10, 10, 200)))  # ... same bytes, other label
        archive.writestr("Part B/Rust/r1.jpg", _jpeg((200, 10, 10)))     # unknown label
        archive.writestr("Part B/Healthy/notes.txt", b"hello")           # not an image
        archive.writestr("Part B/Healthy/fake.jpg", b"not really a jpeg")
        archive.writestr("Part B/Healthy/tiny.jpg", _jpeg((1, 2, 3), size=8))
        archive.writestr("__MACOSX/Part A/._h1.jpg", b"meta")
        archive.writestr(zipfile.ZipInfo("../escape/Healthy/evil.jpg"), healthy)
        archive.writestr("Part B/Healthy/bomb.jpg", b"\xff\xd8\xff" + b"\x00" * 2_000_000)
    return path


def test_validate_archive_classifies_every_entry(tmp_path):
    summary, rows = dataset_archive.validate_archive(make_archive(tmp_path / "a.zip"))
    status = {r["entry"]: r["status"] for r in rows}
    assert status["Part A/Healthy/h1 copy.jpg"] == "duplicate" and status["Part A/Healthy/h1.jpg"] == "accepted"
    assert status["Part B/Healthy/x.jpg"] == status["Part B/Spotting/x.jpg"] == "conflict"
    assert status["Part B/Rust/r1.jpg"] == "unknown_label"
    assert status["Part B/Healthy/notes.txt"] == "unsupported" and status["Part B/Healthy/fake.jpg"] == "unsupported"
    assert status["Part B/Healthy/tiny.jpg"] == "unreadable"
    assert status["__MACOSX/Part A/._h1.jpg"] == "ignored"
    assert status["../escape/Healthy/evil.jpg"] == "unsafe_path"
    assert status["Part B/Healthy/bomb.jpg"] == "unsafe_compression"
    assert summary["acceptedByClass"] == {"Healthy": 1, "Spotting": 1, "Powdery Mildew": 1}
    assert {(r["from"], r["to"]) for r in summary["remappedLabels"]} == {("Spot", "Spotting"), ("Mold", "Powdery Mildew")}
    assert summary["unknownLabels"] == ["Rust"] and summary["conflictFamilies"] == 1


def test_validate_archive_rejects_non_zip_and_encrypted(tmp_path):
    bad = tmp_path / "bad.zip"
    bad.write_bytes(b"plain text")
    with pytest.raises(dataset_archive.ArchiveRejected):
        dataset_archive.validate_archive(bad)


def _wait_validated(client, headers, archive_id) -> dict:
    for _ in range(200):
        body = client.get(f"{BASE}/dataset/archives/{archive_id}", headers=headers).json()
        if body["status"] != "VALIDATING":
            return body
        time.sleep(0.05)
    raise AssertionError("archive validation did not finish")


def test_archive_upload_validates_stages_and_discards(mclient, admin, db, tmp_path, mobile_settings):
    path = make_archive(tmp_path / "upload.zip")
    response = mclient.post(f"{BASE}/dataset/archives", headers=admin,
                            files={"file": ("../../etc/passwd.zip", path.read_bytes(), "application/zip")})
    assert response.status_code == 202, response.text
    archive_id = response.json()["id"]
    body = _wait_validated(mclient, admin, archive_id)
    assert body["status"] == "READY" and body["summary"]["accepted"] == 3
    assert body["sha256"] == apk_inspect.sha256_file(path)
    # Stored under the generated id, never the client's file name.
    stored = list(mobile_settings.dataset_archive_dir.rglob("*"))
    assert all(p.is_relative_to(mobile_settings.dataset_archive_dir / archive_id) for p in stored if p.is_file())

    manifest = mclient.get(f"{BASE}/dataset/archives/{archive_id}/manifest.csv", headers=admin)
    rows = list(csv.DictReader(io.StringIO(manifest.text)))
    assert manifest.status_code == 200 and len(rows) == 13 and {"entry", "sha256", "status"} <= set(rows[0])

    listing = mclient.get(f"{BASE}/dataset", headers=admin).json()
    assert listing["archives"][0]["id"] == archive_id and listing["uploadsEnabled"] is True
    assert db.classifier_images.count_documents({}) == 0  # validation never touches the training dataset

    discarded = mclient.delete(f"{BASE}/dataset/archives/{archive_id}", headers=admin).json()
    assert discarded["status"] == "DISCARDED" and not (mobile_settings.dataset_archive_dir / archive_id).exists()
    assert db.audit_log.count_documents({"action": {"$in": ["dataset_archive.upload", "dataset_archive.discard"]}}) == 2


def test_archive_upload_rejects_non_zip_and_oversize(mclient, admin, db, mobile_settings):
    response = mclient.post(f"{BASE}/dataset/archives", headers=admin, files={"file": ("a.zip", b"nope", "application/zip")})
    body = _wait_validated(mclient, admin, response.json()["id"])
    assert body["status"] == "REJECTED" and "not a readable ZIP" in body["error"]

    small = dataclasses.replace(mobile_settings, dataset_archive_max_bytes=10)
    with TestClient(create_app(settings=small, database=db)) as client:
        headers = headers_for(client, "admin@gmail.com", "admin123")
        response = client.post(f"{BASE}/dataset/archives", headers=headers, files={"file": ("a.zip", b"x" * 100, "application/zip")})
        assert response.status_code == 413 and response.json()["error"]["code"] == "ARCHIVE_TOO_LARGE"


def test_archive_upload_disabled_without_archive_dir(client, auth_headers):
    response = client.post(f"{BASE}/dataset/archives", headers=auth_headers, files={"file": ("a.zip", b"x", "application/zip")})
    assert response.status_code == 503 and response.json()["error"]["code"] == "DATASET_ARCHIVES_DISABLED"


def test_field_tester_cannot_upload_archives(mclient, db):
    add_user(db, "tester@example.com", roles.FIELD_TESTER)
    response = mclient.post(f"{BASE}/dataset/archives", headers=headers_for(mclient, "tester@example.com"),
                            files={"file": ("a.zip", b"x", "application/zip")})
    assert response.status_code == 403


def test_registered_active_archive_drives_the_dataset_profile(mclient, admin, db, tmp_path, mobile_settings):
    path = make_archive(tmp_path / "data-sets.zip")
    provenance = tmp_path / "dataset_manifest_final.csv"
    provenance.write_text("zip_entry,canonical_label,split\na,Healthy,train_val\nb,Healthy,test\nc,Spotting,excluded\n", encoding="utf-8")
    dry = register_archive(db, mobile_settings.dataset_archive_dir, path, None, provenance, True, None, "v3", confirm=False)
    assert dry["written"] is False and db.dataset_archives.count_documents({}) == 0
    result = register_archive(db, mobile_settings.dataset_archive_dir, path, apk_inspect.sha256_file(path), provenance, True,
                              datetime(2026, 10, 20, 8, 15, tzinfo=timezone.utc), "v3")
    assert result["written"] is True

    _, rows = dataset_archive.validate_archive(path)
    accepted = [r for r in rows if r["status"] == "accepted"]
    db.classifier_classes.insert_many([{"id": f"c{i}", "slug": n.lower(), "name": n} for i, n in enumerate(CLASSES)])
    db.classifier_images.insert_many([{"id": f"i{i}", "class_id": "c0", "sha256": r["sha256"], "source_part": "Part A"} for i, r in enumerate(accepted)])
    db.classifier_images.insert_one({"id": "old", "class_id": "c1", "sha256": "f" * 64})  # not from the archive

    body = mclient.get(f"{BASE}/dataset", headers=admin).json()
    assert body["activeArchive"]["id"] == result["id"] and body["activeArchive"]["status"] == "ACTIVE"
    assert body["activeArchive"]["split"]["perClass"]["Healthy"] == {"train_val": 1, "test": 1}
    assert body["consistency"] == {"activeImages": 4, "inActiveArchive": 3, "notInActiveArchive": 1}
    assert body["activeTrainingImages"]["total"] == 4

    with pytest.raises(Exception, match="expected"):
        register_archive(db, mobile_settings.dataset_archive_dir, path, "0" * 64, None, False, None, "")


def test_archive_storage_is_never_on_renders_ephemeral_disk(monkeypatch, tmp_path):
    from app.config import load_settings

    for name in ("RENDER", "DATASET_ARCHIVE_DIR", "DATASET_ARCHIVE_PERSISTENT", "IMAGE_STORAGE"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("ENVIRONMENT", "development")
    assert load_settings().dataset_archive_dir is None
    monkeypatch.setenv("DATASET_ARCHIVE_DIR", str(tmp_path))
    assert load_settings().dataset_archive_dir == tmp_path  # local Docker volume
    monkeypatch.setenv("RENDER", "true")
    assert load_settings().dataset_archive_dir is None  # ephemeral: uploads stay disabled
    monkeypatch.setenv("DATASET_ARCHIVE_PERSISTENT", "true")  # an attached persistent disk
    assert load_settings().dataset_archive_dir == tmp_path

"""Leaf assessment history: one document per scan, never overwritten, idempotent sync."""

import uuid

from tests.conftest import iso, tree_payload

JPEG_BYTES = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01" + b"\x00" * 64 + b"\xff\xd9"
OTHER_JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x02" + b"\x01" * 64 + b"\xff\xd9"


def create_tree(client, headers, **overrides):
    payload = tree_payload(**overrides)
    assert client.post("/api/v1/trees/sync", json=payload, headers=headers).status_code == 200
    return payload["id"]


def assessment(tree_id, **overrides):
    payload = {
        "assessment_id": str(uuid.uuid4()),
        "tree_id": tree_id,
        "status": "COMPLETED",
        "class_id": "class-spotting",
        "class_name": "Spotting",
        "confidence": 0.94,
        "model_name": "dinov2_vits14",
        "model_display_name": "DINOv2 ViT-S/14",
        "model_version": 2,
        "error_code": None,
        "error_message": None,
        "created_at": iso(minute=10),
        "completed_at": iso(minute=10, second=4),
        "updated_at": iso(minute=10, second=4),
    }
    payload.update(overrides)
    return payload


def sync(client, headers, payload):
    return client.post("/api/v1/leaf-assessments/sync", json=payload, headers=headers)


def history(client, headers, tree_id):
    response = client.get(f"/api/v1/trees/{tree_id}/leaf-assessments", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()["items"]


def test_routes_require_auth(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    assert sync(client, {}, assessment(tree_id)).status_code == 401
    assert client.get(f"/api/v1/trees/{tree_id}/leaf-assessments").status_code == 401
    assert client.get("/api/v1/leaf-assessments/changes").status_code == 401
    assert client.get(f"/api/v1/leaf-assessments/{uuid.uuid4()}").status_code == 401


def test_create_one_assessment_persists_result_model_and_confidence(client, auth_headers, db):
    tree_id = create_tree(client, auth_headers)
    payload = assessment(tree_id)
    response = sync(client, auth_headers, payload)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["result"] == "created"
    stored = body["assessment"]
    assert stored["assessment_id"] == payload["assessment_id"]
    assert stored["tree_id"] == tree_id
    assert (stored["class_name"], stored["confidence"]) == ("Spotting", 0.94)
    assert (stored["model_name"], stored["model_version"]) == ("dinov2_vits14", 2)
    assert stored["status"] == "COMPLETED"
    assert stored["server_version"] == 1
    assert stored["image_path"] is None
    assert db.leaf_assessments.count_documents({}) == 1


def test_two_assessments_for_one_tree_are_both_kept_and_the_first_is_unchanged(client, auth_headers, db):
    tree_id = create_tree(client, auth_headers)
    first = assessment(tree_id)
    created_first = sync(client, auth_headers, first).json()["assessment"]
    second = assessment(tree_id, class_id="class-healthy", class_name="Healthy", confidence=0.91,
                        created_at=iso(minute=20), completed_at=iso(minute=20, second=3), updated_at=iso(minute=20, second=3))
    assert sync(client, auth_headers, second).json()["result"] == "created"

    assert first["assessment_id"] != second["assessment_id"]
    assert db.leaf_assessments.count_documents({"tree_id": tree_id}) == 2
    again = client.get(f"/api/v1/leaf-assessments/{first['assessment_id']}", headers=auth_headers).json()
    assert again == created_first  # the earlier scan is untouched by the later one


def test_history_is_newest_first(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    ids = []
    for minute, name in ((1, "Mold"), (30, "Spotting"), (12, "Healthy")):
        payload = assessment(tree_id, class_name=name, created_at=iso(minute=minute),
                             completed_at=iso(minute=minute, second=5), updated_at=iso(minute=minute, second=5))
        sync(client, auth_headers, payload)
        ids.append((minute, payload["assessment_id"]))
    items = history(client, auth_headers, tree_id)
    assert [item["class_name"] for item in items] == ["Spotting", "Healthy", "Mold"]
    assert [item["assessment_id"] for item in items] == [i for _, i in sorted(ids, reverse=True)]


def test_history_is_per_tree(client, auth_headers):
    tree_a = create_tree(client, auth_headers, tree_code="GEO-TAM-001")
    tree_b = create_tree(client, auth_headers, tree_code="GEO-TAM-002")
    sync(client, auth_headers, assessment(tree_a))
    sync(client, auth_headers, assessment(tree_b))
    sync(client, auth_headers, assessment(tree_b, created_at=iso(minute=40)))
    assert len(history(client, auth_headers, tree_a)) == 1
    assert len(history(client, auth_headers, tree_b)) == 2


def test_repeated_sync_of_the_same_assessment_is_idempotent(client, auth_headers, db):
    tree_id = create_tree(client, auth_headers)
    payload = assessment(tree_id)
    first = sync(client, auth_headers, payload).json()
    for _ in range(3):
        again = sync(client, auth_headers, payload)
        assert again.status_code == 200
        assert again.json()["result"] == "unchanged"
        assert again.json()["assessment"]["server_version"] == first["assessment"]["server_version"]
    assert db.leaf_assessments.count_documents({}) == 1


def test_a_different_result_for_a_recorded_assessment_is_rejected_not_overwritten(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    payload = assessment(tree_id)
    sync(client, auth_headers, payload)
    tampered = {**payload, "class_name": "Healthy", "confidence": 0.99, "updated_at": iso(minute=59)}
    response = sync(client, auth_headers, tampered)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "ASSESSMENT_CONFLICT"
    stored = client.get(f"/api/v1/leaf-assessments/{payload['assessment_id']}", headers=auth_headers).json()
    assert (stored["class_name"], stored["confidence"]) == ("Spotting", 0.94)


def test_failed_and_uncertain_assessments_are_kept_as_history(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    failed = assessment(tree_id, status="FAILED", class_id=None, class_name=None, confidence=None, model_name=None,
                        model_display_name=None, model_version=None, completed_at=iso(minute=10, second=30),
                        error_code="AI_UNREACHABLE", error_message="The GEO Tree AI server is not reachable.")
    uncertain = assessment(tree_id, status="UNCERTAIN", confidence=0.48, created_at=iso(minute=11))
    assert sync(client, auth_headers, failed).status_code == 200
    assert sync(client, auth_headers, uncertain).status_code == 200
    items = history(client, auth_headers, tree_id)
    assert [item["status"] for item in items] == ["UNCERTAIN", "FAILED"]
    assert items[1]["error_code"] == "AI_UNREACHABLE"
    assert items[1]["class_name"] is None


def test_completed_assessment_needs_a_full_result(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    for missing in ("class_name", "confidence", "model_name", "model_version", "completed_at"):
        response = sync(client, auth_headers, assessment(tree_id, **{missing: None}))
        assert response.status_code == 422, missing


def test_invalid_values_and_ids_are_rejected(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    assert sync(client, auth_headers, assessment(tree_id, confidence=1.5)).status_code == 422
    assert sync(client, auth_headers, assessment(tree_id, status="SYNCED")).status_code == 422
    assert sync(client, auth_headers, assessment(tree_id, assessment_id="not-a-uuid")).status_code == 422
    assert sync(client, auth_headers, assessment("not-a-uuid")).status_code == 422
    unknown_tree = sync(client, auth_headers, assessment(str(uuid.uuid4())))
    assert unknown_tree.status_code == 404
    assert unknown_tree.json()["error"]["code"] == "TREE_NOT_FOUND"
    missing = client.get(f"/api/v1/leaf-assessments/{uuid.uuid4()}", headers=auth_headers)
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "ASSESSMENT_NOT_FOUND"
    assert client.get("/api/v1/leaf-assessments/not-a-uuid", headers=auth_headers).status_code == 422
    assert client.get(f"/api/v1/trees/{uuid.uuid4()}/leaf-assessments", headers=auth_headers).status_code == 404


def test_photo_is_stored_once_outside_the_training_dataset(client, auth_headers, settings, db):
    tree_id = create_tree(client, auth_headers)
    payload = assessment(tree_id)
    created = sync(client, auth_headers, payload).json()["assessment"]
    url = f"/api/v1/leaf-assessments/{payload['assessment_id']}/image"

    response = client.post(url, files={"file": ("leaf.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["image_path"] == f"leaf_assessments/{payload['assessment_id']}.jpg"
    assert body["server_version"] > created["server_version"]  # other devices pull the photo path
    assert (settings.upload_dir / body["image_path"]).read_bytes() == JPEG_BYTES
    assert not (settings.upload_dir / "classifier_images").exists()
    assert db.classifier_images.count_documents({}) == 0

    # A retry never replaces the recorded photo.
    retry = client.post(url, files={"file": ("other.jpg", OTHER_JPEG, "image/jpeg")}, headers=auth_headers)
    assert retry.json() == body
    assert (settings.upload_dir / body["image_path"]).read_bytes() == JPEG_BYTES

    served = client.get(f"/uploads/{body['image_path']}", headers=auth_headers)
    assert served.status_code == 200 and served.content == JPEG_BYTES
    assert client.get(f"/uploads/{body['image_path']}").status_code == 401
    assert client.get("/uploads/leaf_assessments/..%2F..%2Fmain.py", headers=auth_headers).status_code == 404


def test_photo_upload_validates_content_and_needs_the_record(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    payload = assessment(tree_id)
    sync(client, auth_headers, payload)
    url = f"/api/v1/leaf-assessments/{payload['assessment_id']}/image"
    bad = client.post(url, files={"file": ("x.jpg", b"<?php ?>", "image/jpeg")}, headers=auth_headers)
    assert bad.status_code == 415
    unknown = client.post(f"/api/v1/leaf-assessments/{uuid.uuid4()}/image",
                          files={"file": ("leaf.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)
    assert unknown.status_code == 404


def test_change_feed_returns_new_and_updated_assessments_after_a_version(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    first, second, third = (assessment(tree_id, created_at=iso(minute=m)) for m in (1, 2, 3))
    for payload in (first, second, third):
        sync(client, auth_headers, payload)
    client.post(f"/api/v1/leaf-assessments/{first['assessment_id']}/image",
                files={"file": ("leaf.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)

    page = client.get("/api/v1/leaf-assessments/changes?after_version=0&limit=2", headers=auth_headers).json()
    assert [i["assessment_id"] for i in page["items"]] == [second["assessment_id"], third["assessment_id"]]
    assert page["has_more"] is True and page["latest_version"] == 3
    rest = client.get(f"/api/v1/leaf-assessments/changes?after_version={page['latest_version']}", headers=auth_headers).json()
    assert [i["assessment_id"] for i in rest["items"]] == [first["assessment_id"]]
    assert rest["items"][0]["image_path"] is not None and rest["has_more"] is False
    empty = client.get(f"/api/v1/leaf-assessments/changes?after_version={rest['latest_version']}", headers=auth_headers).json()
    assert empty == {"items": [], "latest_version": rest["latest_version"], "has_more": False}


def test_assessment_versions_do_not_disturb_the_tree_change_feed(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    sync(client, auth_headers, assessment(tree_id))
    trees = client.get("/api/v1/trees/changes?after_version=0", headers=auth_headers).json()
    assert trees["latest_version"] == 1

"""Web administration: tree records, archive, tags, assessment review, dashboard, web serving."""

import uuid

from tests.conftest import iso, tree_payload
from tests.test_leaf_assessments import JPEG_BYTES, assessment, create_tree, sync

ADMIN = "/api/v1/admin"


def changes(client, headers, after=0):
    return client.get(f"/api/v1/trees/changes?after_version={after}", headers=headers).json()["items"]


def stored(db, tree_id):
    return db.trees.find_one({"id": tree_id})


# Trees ------------------------------------------------------------------------------------------
def test_tree_list_searches_filters_sorts_and_paginates(client, auth_headers):
    for index in range(5):
        create_tree(client, auth_headers, tree_code=f"GEO-TAM-{index:03d}", accuracy_meters=3.0 + index * 3)
    page = client.get(f"{ADMIN}/trees?pageSize=2&sort=treeCode", headers=auth_headers).json()
    assert page["total"] == 5 and page["pageSize"] == 2
    assert [t["treeCode"] for t in page["items"]] == ["GEO-TAM-000", "GEO-TAM-001"]
    second = client.get(f"{ADMIN}/trees?pageSize=2&page=3&sort=treeCode", headers=auth_headers).json()
    assert [t["treeCode"] for t in second["items"]] == ["GEO-TAM-004"]
    found = client.get(f"{ADMIN}/trees?q=tam-003", headers=auth_headers).json()
    assert [t["treeCode"] for t in found["items"]] == ["GEO-TAM-003"]
    levels = {t["treeCode"]: t["accuracyLevel"] for t in client.get(f"{ADMIN}/trees?pageSize=10", headers=auth_headers).json()["items"]}
    assert levels["GEO-TAM-000"] == "GOOD" and levels["GEO-TAM-001"] == "ACCEPTABLE" and levels["GEO-TAM-004"] == "LOW"
    assert client.get(f"{ADMIN}/trees?hasImage=true", headers=auth_headers).json()["total"] == 0


def test_metadata_edit_is_a_synced_change_and_never_touches_gps(client, db, auth_headers):
    tree_id = create_tree(client, auth_headers)
    before = stored(db, tree_id)
    response = client.patch(f"{ADMIN}/trees/{tree_id}", json={"age": 12, "notes": "  near the gate ", "latitude": 1.0}, headers=auth_headers)
    assert response.status_code == 200, response.text
    after = stored(db, tree_id)
    assert after["age"] == 12 and after["notes"] == "near the gate"
    assert after["latitude"] == before["latitude"] and after["accuracy_meters"] == before["accuracy_meters"]
    assert after["server_version"] > before["server_version"] and after["updated_at"] > before["updated_at"]
    pulled = changes(client, auth_headers, before["server_version"])
    assert [t["id"] for t in pulled] == [tree_id] and pulled[0]["age"] == 12


def test_tree_code_edit_conflicts_are_explicit(client, auth_headers):
    create_tree(client, auth_headers, tree_code="GEO-TAM-001")
    other = create_tree(client, auth_headers, tree_code="GEO-TAM-002")
    response = client.patch(f"{ADMIN}/trees/{other}", json={"treeCode": "geo-tam-001"}, headers=auth_headers)
    assert response.status_code == 409 and response.json()["error"]["code"] == "TREE_CODE_CONFLICT"
    renamed = client.patch(f"{ADMIN}/trees/{other}", json={"treeCode": "geo-tam-010"}, headers=auth_headers)
    assert renamed.json()["treeCode"] == "GEO-TAM-010"


def test_phone_sync_after_web_edit_follows_newer_updated_at_rule(client, db, auth_headers):
    payload = tree_payload()
    client.post("/api/v1/trees/sync", json=payload, headers=auth_headers)
    client.patch(f"{ADMIN}/trees/{payload['id']}", json={"notes": "web note"}, headers=auth_headers)
    stale = client.post("/api/v1/trees/sync", json={**payload, "notes": "old phone note"}, headers=auth_headers)
    assert stale.json()["result"] == "unchanged" and stored(db, payload["id"])["notes"] == "web note"


def test_archive_is_a_reversible_soft_delete_that_phones_receive(client, db, auth_headers):
    tree_id = create_tree(client, auth_headers)
    assert sync(client, auth_headers, assessment(tree_id)).status_code == 200
    before = stored(db, tree_id)
    archived = client.post(f"{ADMIN}/trees/{tree_id}/archive", headers=auth_headers).json()
    assert archived["archived"] is True and archived["assessmentCount"] == 1
    after = stored(db, tree_id)
    assert after["updated_at"] == before["updated_at"]  # a pending phone edit is not discarded
    pulled = changes(client, auth_headers, before["server_version"])
    assert pulled[0]["archived"] is True
    assert db.leaf_assessments.count_documents({"tree_id": tree_id}) == 1  # history kept
    assert client.get(f"{ADMIN}/trees", headers=auth_headers).json()["total"] == 0
    assert client.get(f"{ADMIN}/trees?status=archived", headers=auth_headers).json()["total"] == 1
    # A phone edit to an archived tree keeps it archived.
    client.post("/api/v1/trees/sync", json=tree_payload(id=tree_id, notes="phone", updated_at=iso(hour=9)), headers=auth_headers)
    assert stored(db, tree_id)["archived"] is True
    restored = client.post(f"{ADMIN}/trees/{tree_id}/restore", headers=auth_headers).json()
    assert restored["archived"] is False


def test_registration_photo_replace_and_delete(client, db, auth_headers, settings):
    tree_id = create_tree(client, auth_headers)
    uploaded = client.post(f"{ADMIN}/trees/{tree_id}/image", files={"file": ("a.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)
    assert uploaded.status_code == 200 and uploaded.json()["imageUrl"].startswith("/uploads/tree_images/")
    first_file = settings.upload_dir / stored(db, tree_id)["image_path"]
    assert first_file.is_file()
    assert client.get(uploaded.json()["imageUrl"], headers=auth_headers).status_code == 200
    bad = client.post(f"{ADMIN}/trees/{tree_id}/image", files={"file": ("x.jpg", b"not an image", "image/jpeg")}, headers=auth_headers)
    assert bad.status_code == 415
    deleted = client.delete(f"{ADMIN}/trees/{tree_id}/image", headers=auth_headers).json()
    assert deleted["imageUrl"] is None and not first_file.exists()


# Tags -------------------------------------------------------------------------------------------
def test_tag_crud_assignment_and_detach_on_delete(client, db, auth_headers):
    created = client.post(f"{ADMIN}/tags", json={"name": " North  Block ", "color": "clay"}, headers=auth_headers)
    assert created.status_code == 201 and created.json()["name"] == "North Block"
    tag_id = created.json()["id"]
    duplicate = client.post(f"{ADMIN}/tags", json={"name": "north block"}, headers=auth_headers)
    assert duplicate.status_code == 409 and duplicate.json()["error"]["code"] == "TAG_NAME_CONFLICT"

    tree_id = create_tree(client, auth_headers)
    version = stored(db, tree_id)["server_version"]
    tagged = client.patch(f"{ADMIN}/trees/{tree_id}", json={"tagIds": [tag_id, tag_id]}, headers=auth_headers).json()
    assert [t["name"] for t in tagged["tags"]] == ["North Block"]
    assert stored(db, tree_id)["server_version"] == version  # tags are web-only: no phone round trip
    assert client.get(f"{ADMIN}/trees?tagId={tag_id}", headers=auth_headers).json()["total"] == 1
    unknown = client.patch(f"{ADMIN}/trees/{tree_id}", json={"tagIds": ["nope"]}, headers=auth_headers)
    assert unknown.status_code == 422 and unknown.json()["error"]["code"] == "TAG_NOT_FOUND"

    # Phone sync never removes web tags.
    client.post("/api/v1/trees/sync", json=tree_payload(id=tree_id, notes="x", updated_at=iso(hour=9)), headers=auth_headers)
    assert stored(db, tree_id)["tag_ids"] == [tag_id]

    assert client.get(f"{ADMIN}/tags", headers=auth_headers).json()["items"][0]["treeCount"] == 1
    renamed = client.patch(f"{ADMIN}/tags/{tag_id}", json={"name": "Block A", "description": "rows 1-4"}, headers=auth_headers)
    assert renamed.json()["name"] == "Block A" and renamed.json()["description"] == "rows 1-4"
    removed = client.delete(f"{ADMIN}/tags/{tag_id}", headers=auth_headers).json()
    assert removed["treesDetached"] == 1 and stored(db, tree_id)["tag_ids"] == []


# Assessments ------------------------------------------------------------------------------------
def test_assessment_review_filters_and_never_changes_history(client, db, auth_headers):
    first = create_tree(client, auth_headers, tree_code="GEO-TAM-001")
    second = create_tree(client, auth_headers, tree_code="GEO-TAM-002")
    a1 = assessment(first, created_at=iso(day=1), class_name="Healthy", confidence=0.91)
    a2 = assessment(first, created_at=iso(day=5), class_name="Spotting")
    a3 = assessment(second, created_at=iso(day=8), status="FAILED", class_id=None, class_name=None, confidence=None,
                    error_code="AI_MODEL_NOT_READY", error_message="not ready", completed_at=None)
    for payload in (a1, a2, a3):
        assert sync(client, auth_headers, payload).status_code == 200
    snapshot = list(db.leaf_assessments.find({}, {"_id": False}).sort("assessment_id"))

    everything = client.get(f"{ADMIN}/assessments", headers=auth_headers).json()
    assert [i["assessmentId"] for i in everything["items"]] == [a3["assessment_id"], a2["assessment_id"], a1["assessment_id"]]
    assert everything["items"][0]["treeCode"] == "GEO-TAM-002"
    by_tree = client.get(f"{ADMIN}/assessments?q=tam-001", headers=auth_headers).json()
    assert by_tree["total"] == 2
    failed = client.get(f"{ADMIN}/assessments?status=FAILED", headers=auth_headers).json()["items"]
    assert [i["errorCode"] for i in failed] == ["AI_MODEL_NOT_READY"]
    dated = client.get(f"{ADMIN}/assessments?from=2026-09-04&to=2026-09-06", headers=auth_headers).json()["items"]
    assert [i["assessmentId"] for i in dated] == [a2["assessment_id"]]
    assert client.get(f"{ADMIN}/assessments?className=Healthy", headers=auth_headers).json()["total"] == 1
    facets = client.get(f"{ADMIN}/assessments/facets", headers=auth_headers).json()
    assert facets["classNames"] == ["Healthy", "Spotting"]
    detail = client.get(f"{ADMIN}/assessments/{a1['assessment_id']}", headers=auth_headers).json()
    assert detail["confidence"] == 0.91 and detail["modelVersion"] == 2
    assert client.get(f"{ADMIN}/assessments/{uuid.uuid4()}", headers=auth_headers).status_code == 404
    assert list(db.leaf_assessments.find({}, {"_id": False}).sort("assessment_id")) == snapshot


# Dashboard and activity -------------------------------------------------------------------------
def test_dashboard_counts_real_records_with_specific_labels(client, auth_headers):
    empty = client.get(f"{ADMIN}/dashboard", headers=auth_headers).json()
    assert empty["trees"]["total"] == 0 and empty["trees"]["completeness"]["percent"] is None
    assert empty["model"]["status"] == "DISABLED" and empty["images"]["training"]["total"] == 0

    complete = create_tree(client, auth_headers, tree_code="GEO-TAM-001", accuracy_meters=4.0, age=10)
    client.post(f"{ADMIN}/trees/{complete}/image", files={"file": ("a.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)
    create_tree(client, auth_headers, tree_code="GEO-TAM-002", accuracy_meters=14.0)
    archived = create_tree(client, auth_headers, tree_code="GEO-TAM-003")
    client.post(f"{ADMIN}/trees/{archived}/archive", headers=auth_headers)
    sync(client, auth_headers, assessment(complete))
    sync(client, auth_headers, assessment(complete, status="FAILED", class_id=None, class_name=None, confidence=None, completed_at=None))

    body = client.get(f"{ADMIN}/dashboard", headers=auth_headers).json()
    trees = body["trees"]
    assert trees["total"] == 2 and trees["archived"] == 1
    assert trees["accuracy"] == {"good": 1, "acceptable": 0, "low": 1}
    assert trees["completeness"]["complete"] == 1 and trees["completeness"]["percent"] == 50.0
    assert "registration photo" in trees["completeness"]["definition"]
    assert body["images"]["treeRegistration"] == 1 and body["images"]["assessmentEvidence"] == 0
    assert body["assessments"]["total"] == 2 and body["assessments"]["byStatus"] == {"COMPLETED": 1, "FAILED": 1}
    assert body["assessments"]["byPredictedClass"] == [{"className": "Spotting", "count": 1}]
    assert body["users"]["byRole"]["ADMIN"] == 1
    assert len(trees["weekly"]) == 12
    assert any(e["action"] == "tree.archive" for e in body["recentActivity"])


def test_activity_log_filters_by_action(client, auth_headers):
    tree_id = create_tree(client, auth_headers)
    client.patch(f"{ADMIN}/trees/{tree_id}", json={"age": 3}, headers=auth_headers)
    client.post(f"{ADMIN}/tags", json={"name": "Shade"}, headers=auth_headers)
    trees_only = client.get(f"{ADMIN}/activity?action=tree", headers=auth_headers).json()
    assert [e["action"] for e in trees_only["items"]] == ["tree.update"]
    assert trees_only["items"][0]["details"] == {"fields": ["age"]}
    assert client.get(f"{ADMIN}/activity?action=DROP", headers=auth_headers).status_code == 422


# Web administration serving ---------------------------------------------------------------------
def test_built_web_app_is_served_without_shadowing_the_api(tmp_path, db):
    from fastapi.testclient import TestClient

    from app.config import Settings
    from app.main import create_app

    dist = tmp_path / "web_dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text("<!doctype html><title>GEO Tree Admin</title>", encoding="utf-8")
    (dist / "assets" / "app.js").write_text("console.log(1)", encoding="utf-8")
    (dist / "favicon.svg").write_text("<svg/>", encoding="utf-8")
    settings = Settings(environment="development", jwt_secret="geo-tree-test-secret-at-least-32-bytes-long",
                        upload_dir=tmp_path / "uploads", web_dist_dir=dist)
    with TestClient(create_app(settings=settings, database=db)) as web:
        page = web.get("/")
        assert page.status_code == 200 and "GEO Tree Admin" in page.text
        assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
        assert "img-src 'self' blob: data: https://tile.openstreetmap.org;" in page.headers["content-security-policy"]
        assert page.headers["referrer-policy"] == "strict-origin-when-cross-origin"
        assert "GEO Tree Admin" in web.get("/trees/123").text  # client-side route
        assert web.get("/assets/app.js").text == "console.log(1)"
        assert web.get("/favicon.svg").status_code == 200
        assert web.get("/api/v1/health").json()["service"] == "geo-tree-api"
        missing = web.get("/api/v1/does-not-exist")
        assert missing.status_code == 404 and missing.json()["error"]["code"] == "NOT_FOUND"
        assert web.get("/uploads/tree_images/x.jpg").status_code in (401, 404)
        assert web.get("/../../etc/passwd").status_code in (200, 404)
        assert "root:" not in web.get("/..%2F..%2Fetc%2Fpasswd").text

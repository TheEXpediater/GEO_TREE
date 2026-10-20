"""Map marker endpoints: real records only, malformed GPS excluded and counted, archive rules,
filters, evidence placed at its tree, no image bytes, and admin-only access."""

import uuid
from datetime import datetime, timezone

from app.services.field_quality import valid_coordinates
from tests.conftest import iso
from tests.test_admin_roles import add_user, headers_for
from tests.test_leaf_assessments import JPEG_BYTES, assessment, create_tree, sync

MAP = "/api/v1/admin/map"


def raw_tree(db, code, **fields):
    """A tree document written directly, to simulate malformed data that the sync API would refuse."""
    tree_id = str(uuid.uuid4())
    now = datetime(2026, 9, 1, tzinfo=timezone.utc)
    db.trees.insert_one({"id": tree_id, "tree_code": code, "tree_code_normalized": code, "accuracy_meters": 4.0,
                         "location_captured_at": now, "created_at": now, "updated_at": now, "server_version": 999,
                         "image_path": None, **fields})
    return tree_id


def test_coordinate_validation_rules():
    assert valid_coordinates(15.21, 120.68)
    for lat, lon in [(None, 120.0), (15.0, None), ("15", "120"), (91, 0), (0, 181), (0, 0), (float("nan"), 1), (True, 1)]:
        assert not valid_coordinates(lat, lon), (lat, lon)


def test_tree_markers_place_valid_trees_and_count_the_rest(client, db, auth_headers):
    good = create_tree(client, auth_headers, tree_code="GEO-TAM-001", latitude=15.2178, longitude=120.6942)
    create_tree(client, auth_headers, tree_code="GEO-TAM-002", latitude=15.2165, longitude=120.6965)
    raw_tree(db, "BAD-NONE", latitude=None, longitude=None)
    raw_tree(db, "BAD-TEXT", latitude="abc", longitude=120.0)
    raw_tree(db, "BAD-ZERO", latitude=0.0, longitude=0.0)
    raw_tree(db, "BAD-RANGE", latitude=95.0, longitude=120.0)
    sync(client, auth_headers, assessment(good))

    body = client.get(f"{MAP}/trees", headers=auth_headers).json()
    assert [m["treeCode"] for m in body["items"]] == ["GEO-TAM-001", "GEO-TAM-002"]
    assert body["total"] == 6 and body["placed"] == 2 and body["excludedCount"] == 4
    reasons = {e["treeCode"]: e["reason"] for e in body["excluded"]}
    assert reasons["BAD-NONE"] == "MISSING_COORDINATES" and reasons["BAD-ZERO"] == "INVALID_COORDINATES"
    first = body["items"][0]
    assert (first["latitude"], first["longitude"]) == (15.2178, 120.6942)  # exactly as captured
    assert first["assessmentCount"] == 1 and first["latestAssessment"]["className"] == "Spotting"
    assert first["accuracyLevel"] == "GOOD" and first["hasPhoto"] is False and first["imageUrl"] is None


def test_tree_markers_follow_archive_rules_and_filters(client, db, auth_headers):
    active = create_tree(client, auth_headers, tree_code="GEO-TAM-001")
    archived = create_tree(client, auth_headers, tree_code="GEO-TAM-002")
    client.post(f"/api/v1/admin/trees/{archived}/archive", headers=auth_headers)
    tag = client.post("/api/v1/admin/tags", json={"name": "North"}, headers=auth_headers).json()
    client.patch(f"/api/v1/admin/trees/{active}", json={"tagIds": [tag["id"]]}, headers=auth_headers)

    assert [m["id"] for m in client.get(f"{MAP}/trees", headers=auth_headers).json()["items"]] == [active]
    archived_only = client.get(f"{MAP}/trees?status=archived", headers=auth_headers).json()["items"]
    assert [m["id"] for m in archived_only] == [archived] and archived_only[0]["archived"] is True
    assert len(client.get(f"{MAP}/trees?status=all", headers=auth_headers).json()["items"]) == 2
    tagged = client.get(f"{MAP}/trees?status=all&tagId={tag['id']}", headers=auth_headers).json()["items"]
    assert [m["id"] for m in tagged] == [active] and tagged[0]["tags"][0]["name"] == "North"
    assert client.get(f"{MAP}/trees?q=tam-002&status=all", headers=auth_headers).json()["placed"] == 1


def test_evidence_is_placed_at_its_tree_and_filters_work(client, db, auth_headers):
    tree = create_tree(client, auth_headers, tree_code="GEO-TAM-001", latitude=15.2178, longitude=120.6942)
    other = create_tree(client, auth_headers, tree_code="GEO-TAM-002", latitude=15.2165, longitude=120.6965)
    client.post(f"/api/v1/admin/trees/{tree}/image", files={"file": ("a.jpg", JPEG_BYTES, "image/jpeg")}, headers=auth_headers)
    scan = assessment(tree, created_at=iso(day=5))
    failed = assessment(other, created_at=iso(day=9), status="FAILED", class_id=None, class_name=None, confidence=None,
                        completed_at=None, error_code="AI_MODEL_NOT_READY")
    sync(client, auth_headers, scan)
    sync(client, auth_headers, failed)
    orphan_tree = raw_tree(db, "BAD-NONE", latitude=None, longitude=None)
    sync(client, auth_headers, assessment(orphan_tree))

    body = client.get(f"{MAP}/evidence", headers=auth_headers).json()
    types = sorted(i["type"] for i in body["items"])
    assert types == ["LEAF_ASSESSMENT", "LEAF_ASSESSMENT", "REGISTRATION_PHOTO"]
    assert body["excludedCount"] == 1 and body["treeCount"] == 2
    scan_item = next(i for i in body["items"] if i["id"] == f"assessment-{scan['assessment_id']}")
    assert (scan_item["latitude"], scan_item["longitude"], scan_item["treeCode"]) == (15.2178, 120.6942, "GEO-TAM-001")
    assert scan_item["assessment"]["className"] == "Spotting" and scan_item["assessment"]["modelVersion"] == 2

    photos = client.get(f"{MAP}/evidence?type=registration", headers=auth_headers).json()["items"]
    assert [p["type"] for p in photos] == ["REGISTRATION_PHOTO"] and photos[0]["imageUrl"].startswith("/uploads/tree_images/")
    failed_only = client.get(f"{MAP}/evidence?status=FAILED", headers=auth_headers).json()["items"]
    assert [i["assessment"]["errorCode"] for i in failed_only] == ["AI_MODEL_NOT_READY"]
    dated = client.get(f"{MAP}/evidence?type=assessment&from=2026-09-04&to=2026-09-06", headers=auth_headers).json()["items"]
    assert [i["id"] for i in dated] == [f"assessment-{scan['assessment_id']}"]
    by_tree = client.get(f"{MAP}/evidence?treeId={other}", headers=auth_headers).json()["items"]
    assert {i["treeId"] for i in by_tree} == {other}
    assert client.get(f"{MAP}/evidence?q=tam-001", headers=auth_headers).json()["treeCount"] == 1


def test_evidence_of_archived_trees_only_on_request(client, auth_headers):
    tree = create_tree(client, auth_headers)
    sync(client, auth_headers, assessment(tree))
    client.post(f"/api/v1/admin/trees/{tree}/archive", headers=auth_headers)
    assert client.get(f"{MAP}/evidence", headers=auth_headers).json()["placed"] == 0
    included = client.get(f"{MAP}/evidence?includeArchived=true", headers=auth_headers).json()["items"]
    assert len(included) == 1 and included[0]["treeArchived"] is True


def test_map_endpoints_are_admin_only(client, db):
    add_user(db, "tester@example.com")
    headers = headers_for(client, "tester@example.com")
    for route in ("/trees", "/evidence"):
        assert client.get(f"{MAP}{route}").status_code == 401
        assert client.get(f"{MAP}{route}", headers=headers).status_code == 403

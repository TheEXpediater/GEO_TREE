import uuid

import pytest

from tests.conftest import iso, tree_payload


def sync(client, headers, payload):
    return client.post("/api/v1/trees/sync", json=payload, headers=headers)


def test_sync_requires_auth(client):
    assert sync(client, {}, tree_payload()).status_code == 401


def test_sync_creates_tree_with_uuid_identity_and_server_version(client, auth_headers, db):
    payload = tree_payload(tree_code="geo-tam-003")
    response = sync(client, auth_headers, payload)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["result"] == "created"
    assert body["tree"]["id"] == payload["id"]
    assert body["tree"]["tree_code"] == "GEO-TAM-003"
    assert body["tree"]["server_version"] == 1
    assert body["tree"]["image_path"] is None
    stored = db.trees.find_one({"id": payload["id"]})
    assert stored["latitude"] == payload["latitude"]
    assert stored["accuracy_meters"] == 4.2


def test_repeated_sync_of_same_uuid_does_not_duplicate(client, auth_headers, db):
    payload = tree_payload()
    first = sync(client, auth_headers, payload).json()
    second = sync(client, auth_headers, payload)
    assert second.status_code == 200
    assert second.json()["result"] == "unchanged"
    assert second.json()["tree"]["server_version"] == first["tree"]["server_version"]
    assert db.trees.count_documents({"id": payload["id"]}) == 1


def test_newer_update_for_same_uuid_updates_and_bumps_version(client, auth_headers, db):
    payload = tree_payload()
    sync(client, auth_headers, payload)
    update = {**payload, "notes": "Near the irrigation canal", "latitude": 15.145, "updated_at": iso(minute=5)}
    response = sync(client, auth_headers, update)
    assert response.json()["result"] == "updated"
    assert response.json()["tree"]["server_version"] == 2
    assert response.json()["tree"]["notes"] == "Near the irrigation canal"
    assert db.trees.count_documents({}) == 1


def test_stale_update_is_ignored(client, auth_headers):
    payload = tree_payload(updated_at=iso(minute=10))
    sync(client, auth_headers, payload)
    stale = {**payload, "notes": "old edit", "updated_at": iso(minute=2)}
    response = sync(client, auth_headers, stale)
    assert response.json()["result"] == "unchanged"
    assert response.json()["tree"]["notes"] is None


def test_duplicate_tree_code_with_different_uuid_is_409(client, auth_headers, db):
    first = tree_payload(tree_code="GEO-TAM-004")
    sync(client, auth_headers, first)
    response = sync(client, auth_headers, tree_payload(tree_code="geo-tam-004"))
    assert response.status_code == 409
    error = response.json()["error"]
    assert error["code"] == "TREE_CODE_CONFLICT"
    assert error["details"]["existing_id"] == first["id"]
    assert db.trees.count_documents({}) == 1


@pytest.mark.parametrize(
    "field,value",
    [("latitude", 90.0001), ("latitude", -91), ("longitude", 180.5), ("longitude", -181), ("accuracy_meters", -0.1)],
)
def test_invalid_coordinates_or_accuracy_are_rejected(client, auth_headers, db, field, value):
    response = sync(client, auth_headers, tree_payload(**{field: value}))
    assert response.status_code == 422
    body = response.json()["error"]
    assert body["code"] == "VALIDATION_ERROR"
    assert any(err["field"] == field for err in body["details"]["errors"])
    assert db.trees.count_documents({}) == 0


def test_invalid_uuid_is_rejected(client, auth_headers):
    assert sync(client, auth_headers, tree_payload(id="15.1,120.5")).status_code == 422


def test_changes_endpoint_filters_by_server_version(client, auth_headers):
    a = tree_payload(tree_code="GEO-TAM-003")
    b = tree_payload(tree_code="GEO-TAM-004")
    c = tree_payload(tree_code="GEO-TAM-005")
    for payload in (a, b, c):
        sync(client, auth_headers, payload)

    everything = client.get("/api/v1/trees/changes", params={"after_version": 0}, headers=auth_headers).json()
    assert [item["tree_code"] for item in everything["items"]] == ["GEO-TAM-003", "GEO-TAM-004", "GEO-TAM-005"]
    assert everything["latest_version"] == 3
    assert everything["has_more"] is False

    after_one = client.get("/api/v1/trees/changes", params={"after_version": 1}, headers=auth_headers).json()
    assert [item["id"] for item in after_one["items"]] == [b["id"], c["id"]]

    paged = client.get("/api/v1/trees/changes", params={"after_version": 0, "limit": 2}, headers=auth_headers).json()
    assert len(paged["items"]) == 2 and paged["has_more"] is True and paged["latest_version"] == 2

    none_left = client.get("/api/v1/trees/changes", params={"after_version": 3}, headers=auth_headers).json()
    assert none_left == {"items": [], "latest_version": 3, "has_more": False}


def test_update_moves_tree_to_end_of_change_feed(client, auth_headers):
    a = tree_payload(tree_code="GEO-TAM-003")
    b = tree_payload(tree_code="GEO-TAM-004")
    sync(client, auth_headers, a)
    sync(client, auth_headers, b)
    sync(client, auth_headers, {**a, "notes": "edited", "updated_at": iso(minute=30)})
    feed = client.get("/api/v1/trees/changes", params={"after_version": 2}, headers=auth_headers).json()
    assert [item["id"] for item in feed["items"]] == [a["id"]]
    assert feed["items"][0]["server_version"] == 3


def test_get_tree_by_id(client, auth_headers):
    payload = tree_payload()
    sync(client, auth_headers, payload)
    assert client.get(f"/api/v1/trees/{payload['id']}", headers=auth_headers).json()["tree_code"] == "GEO-TAM-003"
    missing = client.get(f"/api/v1/trees/{uuid.uuid4()}", headers=auth_headers)
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "TREE_NOT_FOUND"

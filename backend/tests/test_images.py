import uuid

from tests.conftest import tree_payload

JPEG_BYTES = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01" + b"\x00" * 64 + b"\xff\xd9"
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


def create_tree(client, headers):
    payload = tree_payload()
    assert client.post("/api/v1/trees/sync", json=payload, headers=headers).status_code == 200
    return payload


def upload(client, headers, tree_id, data, name="tree.jpg", content_type="image/jpeg"):
    return client.post(f"/api/v1/trees/{tree_id}/image", files={"file": (name, data, content_type)}, headers=headers)


def test_image_upload_associates_server_relative_path(client, auth_headers, db, settings):
    tree = create_tree(client, auth_headers)
    response = upload(client, auth_headers, tree["id"], JPEG_BYTES)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["image_path"].startswith("tree_images/")
    assert body["image_path"].endswith(".jpg")
    assert body["server_version"] == 2  # image attachment is a new server change
    assert db.trees.find_one({"id": tree["id"]})["image_path"] == body["image_path"]
    assert (settings.upload_dir / body["image_path"]).read_bytes() == JPEG_BYTES

    served = client.get(f"/uploads/{body['image_path']}", headers=auth_headers)
    assert served.status_code == 200
    assert served.content == JPEG_BYTES
    assert client.get(f"/uploads/{body['image_path']}").status_code == 401


def test_replacing_image_removes_previous_file(client, auth_headers, settings):
    tree = create_tree(client, auth_headers)
    first = upload(client, auth_headers, tree["id"], JPEG_BYTES).json()["image_path"]
    second = upload(client, auth_headers, tree["id"], PNG_BYTES, "tree.png", "image/png").json()["image_path"]
    assert second.endswith(".png")
    assert not (settings.upload_dir / first).exists()
    assert (settings.upload_dir / second).exists()


def test_metadata_resync_keeps_uploaded_image(client, auth_headers):
    tree = create_tree(client, auth_headers)
    path = upload(client, auth_headers, tree["id"], JPEG_BYTES).json()["image_path"]
    again = client.post("/api/v1/trees/sync", json=tree, headers=auth_headers).json()
    assert again["result"] == "unchanged"
    assert again["tree"]["image_path"] == path


def test_non_image_content_is_rejected_even_with_image_content_type(client, auth_headers, db):
    tree = create_tree(client, auth_headers)
    response = upload(client, auth_headers, tree["id"], b"<?php echo 'x'; ?>", "evil.jpg", "image/jpeg")
    assert response.status_code == 415
    assert response.json()["error"]["code"] == "UNSUPPORTED_IMAGE"
    assert db.trees.find_one({"id": tree["id"]})["image_path"] is None


def test_oversized_image_is_rejected(client, auth_headers, settings):
    tree = create_tree(client, auth_headers)
    too_big = JPEG_BYTES + b"\x00" * settings.max_image_bytes
    assert upload(client, auth_headers, tree["id"], too_big).status_code == 413


def test_upload_for_unknown_tree_is_404(client, auth_headers):
    response = upload(client, auth_headers, str(uuid.uuid4()), JPEG_BYTES)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "TREE_NOT_FOUND"


def test_image_route_rejects_path_traversal(client, auth_headers):
    assert client.get("/uploads/tree_images/..%2F..%2Fmain.py", headers=auth_headers).status_code == 404

"""scripts/replace_classifier_dataset.py: replaces only the classifier training set, keeps class ids,
never touches trees/assessments, leaves no old image/feature behind and records provenance."""

import hashlib
import importlib.util
from pathlib import Path

import pytest

from tests.conftest import CLASS_COLORS, leaf_image, tree_payload
from tests.test_ai_admin import API, classes_by_name, fill_dataset, run_jobs, upload

SCRIPT = Path(__file__).resolve().parent.parent / "scripts" / "replace_classifier_dataset.py"
spec = importlib.util.spec_from_file_location("replace_classifier_dataset", SCRIPT)
replace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replace)


def write_dataset(root: Path) -> list[dict[str, str]]:
    """A small 'ZIP' with train_val, test and excluded rows, two images per leaf group."""
    rows = []
    for name, color in {"Healthy": CLASS_COLORS["Healthy"], "Spotting": CLASS_COLORS["Spotting"], "Powdery Mildew": (230, 230, 230)}.items():
        for i in range(8):
            data = leaf_image(color, 90_000 + hash(name) % 997 * 10 + i)
            entry = f"part/{name}/{i}.png"
            (root / entry).parent.mkdir(parents=True, exist_ok=True)
            (root / entry).write_bytes(data)
            sha = hashlib.sha256(data).hexdigest()
            split = "test" if i == 7 else "excluded" if i == 6 else "train_val"
            rows.append({"zip_entry": entry, "canonical_label": name, "split": split, "sha256": sha, "stored_sha256": sha,
                         "group_id": f"{name}-{i // 2}", "source_part": "part", "original_label": "Mold" if name == "Powdery Mildew" else name})
    return rows


@pytest.fixture
def legacy_dataset(ai_client, admin_headers):
    """The old state: classes Healthy/Spotting/Mold with a trained model, plus field data."""
    classes = classes_by_name(ai_client, admin_headers)
    ai_client.patch(f"{API}/classifiers/{classes['Powdery Mildew']['id']}", json={"name": "Mold"}, headers=admin_headers)
    CLASS_COLORS_OLD = {"Healthy": CLASS_COLORS["Healthy"], "Spotting": CLASS_COLORS["Spotting"], "Mold": CLASS_COLORS["Powdery Mildew"]}
    for name, color in CLASS_COLORS_OLD.items():
        cid = classes_by_name(ai_client, admin_headers)[name]["id"]
        upload(ai_client, admin_headers, cid, [(f"old_{name}_{i}.png", leaf_image(color, 7_000 + i)) for i in range(4)])
    assert run_jobs(ai_client) == 1
    tree = tree_payload()
    assert ai_client.post("/api/v1/trees/sync", json=tree, headers=admin_headers).status_code == 200
    return classes_by_name(ai_client, admin_headers), tree


def test_replacement_keeps_ids_removes_old_images_and_records_provenance(ai_client, admin_headers, legacy_dataset, tmp_path):
    old_classes, tree = legacy_dataset
    service = ai_client.app.state.ai
    db = service.db
    old_features = {p.name for p in (service.storage.root / "embeddings").glob("*.npy")}
    rows = write_dataset(tmp_path / "zip")

    report = replace.replace_dataset(service, rows, tmp_path / "zip", {"Mold": "Powdery Mildew"}, "test")

    assert report["ok"], report["problems"]
    assert report["perClass"] == {"Healthy": 6, "Spotting": 6, "Powdery Mildew": 6}  # test + excluded held out
    assert report["renamedClasses"][0]["classId"] == old_classes["Mold"]["id"]
    now = classes_by_name(ai_client, admin_headers)
    assert now["Powdery Mildew"]["id"] == old_classes["Mold"]["id"] and "Mold" not in now
    assert db.classifier_images.count_documents({}) == 18
    held_out = {r["sha256"] for r in rows if r["split"] != "train_val"}
    assert not held_out & {d["sha256"] for d in db.classifier_images.find()}
    assert not old_features & {p.name for p in (service.storage.root / "embeddings").glob("*.npy")}
    record = db.classifier_images.find_one({"source_entry": "part/Healthy/0.png"})
    assert record["split_group"] == "Healthy-0" and record["source_label"] == "Healthy"
    assert db.trees.count_documents({"id": tree["id"]}) == 1  # field data untouched
    assert db.audit_log.find_one({"action": "dataset.replaced"}) is not None

    # Training on the new set keeps each leaf group on one side of the validation split.
    service.retrain()
    assert run_jobs(ai_client) == 1
    model = db.ai_models.find_one({"active": True})
    assert model["split"]["grouped_by"].startswith("split_group")
    validation = set(model["split"]["validation_image_ids"])
    groups = {}
    for image in db.classifier_images.find():
        groups.setdefault(image["split_group"], set()).add(image["id"] in validation)
    assert all(len(sides) == 1 for sides in groups.values())
    assert [c["name"] for c in ai_client.get(f"{API}/ai/model", headers=admin_headers).json()["activeModel"]["classes"]] == [
        "Healthy", "Spotting", "Powdery Mildew"]


def test_replacement_refuses_unexpected_classes_and_duplicate_rows(ai_client, admin_headers, tmp_path):
    fill_dataset(ai_client, admin_headers)
    ai_client.post(f"{API}/classifiers", json={"name": "Rust"}, headers=admin_headers)
    rows = write_dataset(tmp_path / "zip")
    with pytest.raises(replace.ReplacementError, match="Rust"):
        replace.replace_dataset(ai_client.app.state.ai, rows, tmp_path / "zip", {}, "test")
    assert ai_client.app.state.ai.db.classifier_images.count_documents({}) == 12  # nothing removed
    with pytest.raises(replace.ReplacementError, match="twice"):
        replace.plan(rows + [rows[0]])

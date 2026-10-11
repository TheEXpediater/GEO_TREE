"""The local batch-test and dataset-import scripts, driven through the real API via TestClient."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from ai_batch_test import cleanup_input, evaluate, render_markdown, write_report  # noqa: E402
from ai_import_dataset import split_holdout  # noqa: E402
from local_api import LocalApiError, discover_images, is_local_url, require_local  # noqa: E402

from tests.conftest import CLASS_COLORS, leaf_image  # noqa: E402
from tests.test_ai_admin import train  # noqa: E402


def make_input(root: Path, per_class: int = 3) -> Path:
    folder = root / "ai_test_input"
    for name, color in CLASS_COLORS.items():
        (folder / name.lower()).mkdir(parents=True)
        for i in range(per_class):
            (folder / name.lower() / f"{name.lower()}_{i}.png").write_bytes(leaf_image(color, 50_000 + hash(name) % 1000 + i))
    (folder / "notes.txt").write_text("not an image")
    return folder


def api_assess(client, headers):
    def assess(path: Path) -> dict:
        response = client.post("/api/v1/ai/leaf-assessment", files={"file": (path.name, path.read_bytes(), "image/png")}, headers=headers)
        if response.status_code != 200:
            error = response.json()["error"]
            raise LocalApiError(response.status_code, error["code"], error["message"])
        return response.json()

    return assess


def test_discover_uses_top_level_folder_as_label(tmp_path):
    folder = make_input(tmp_path)
    (folder / "mold" / "nested").mkdir()
    (folder / "mold" / "nested" / "deep.PNG").write_bytes(leaf_image((1, 2, 3), 1))
    found = discover_images(folder)
    assert len(found) == 10
    assert {label for _, label in found} == {"healthy", "spotting", "mold"}
    assert any(path.name == "deep.PNG" and label == "mold" for path, label in found)


def test_batch_evaluation_reports_real_api_predictions_without_touching_the_dataset(ai_client, admin_headers, tmp_path):
    train(ai_client, admin_headers)
    db = ai_client.app.state.ai.db
    before = (db.classifier_images.count_documents({}), db.ai_jobs.count_documents({}), db.ai_models.count_documents({}))
    folder = make_input(tmp_path)

    result = evaluate(discover_images(folder), api_assess(ai_client, admin_headers), ["Healthy", "Spotting", "Mold"], folder)

    assert result["success"] is True
    assert result["summary"]["images"] == 9 and result["summary"]["errors"] == 0
    assert result["summary"]["expectedCounts"] == {"Healthy": 3, "Mold": 3, "Spotting": 3}
    assert result["summary"]["modelVersions"] == [1]
    assert result["rows"][0]["expected"] in CLASS_COLORS  # folder "healthy" matched to class "Healthy"
    assert result["metrics"]["samples"] == 9
    assert result["metrics"]["confusionMatrix"]["labels"] == ["Healthy", "Spotting", "Mold"]
    assert (db.classifier_images.count_documents({}), db.ai_jobs.count_documents({}), db.ai_models.count_documents({})) == before


def test_batch_run_without_a_model_is_not_successful(ai_client, admin_headers, tmp_path):
    folder = make_input(tmp_path, per_class=1)
    result = evaluate(discover_images(folder), api_assess(ai_client, admin_headers), ["Healthy", "Spotting", "Mold"], folder)
    assert result["success"] is False
    assert all(row["error"].startswith("AI_MODEL_NOT_READY") for row in result["rows"])


def test_report_is_written_verified_and_input_cleaned_only_after_success(ai_client, admin_headers, tmp_path):
    train(ai_client, admin_headers)
    folder = make_input(tmp_path)
    result = evaluate(discover_images(folder), api_assess(ai_client, admin_headers), ["Healthy", "Spotting", "Mold"], folder)
    report = {"startedAt": "s", "finishedAt": "f", "server": "http://localhost:8000", "modelDisplayName": "DINOv2 ViT-S/14", "confidenceThreshold": 0.6, **result}

    results = write_report(report, tmp_path / "ai_test_results")
    assert {p.name for p in results.iterdir()} == {"report.json", "report.md", "predictions.csv"}
    assert "Confusion matrix" in render_markdown(report)

    assert cleanup_input(folder, results, success=False) is False and folder.exists()
    renamed = tmp_path / "my_photos"
    renamed.mkdir()
    assert cleanup_input(renamed, results, success=True) is False and renamed.exists()
    assert cleanup_input(folder, results, success=True) is True
    assert not folder.exists()
    assert (results / "report.json").exists()  # results are kept


def test_report_inside_input_folder_blocks_cleanup(tmp_path):
    folder = tmp_path / "ai_test_input"
    results = folder / "results"
    results.mkdir(parents=True)
    (results / "report.json").write_text("{}")
    assert cleanup_input(folder, results, success=True) is False
    assert folder.exists()


def test_scripts_refuse_hosted_servers():
    assert is_local_url("http://localhost:8000") and is_local_url("http://192.168.1.20:8000") and is_local_url("http://127.0.0.1:8000")
    assert not is_local_url("https://geo-tree.onrender.com")
    with pytest.raises(SystemExit):
        require_local("https://geo-tree.onrender.com")


def test_holdout_split_is_deterministic_and_keeps_both_sides(tmp_path):
    images = []
    for name, color in CLASS_COLORS.items():
        for i in range(8):
            path = tmp_path / name / f"{i}.png"
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(leaf_image(color, 70_000 + hash(name) % 1000 + i))
            images.append((path, name))
    keep, holdout = split_holdout(images, 0.25)
    assert len(holdout) == 6 and len(keep) == 18
    assert sorted(label for _, label in holdout) == sorted(["Healthy", "Spotting", "Mold"] * 2)
    assert split_holdout(list(reversed(images)), 0.25)[1] == holdout or {p for p, _ in split_holdout(list(reversed(images)), 0.25)[1]} == {p for p, _ in holdout}
    assert not {p for p, _ in keep} & {p for p, _ in holdout}

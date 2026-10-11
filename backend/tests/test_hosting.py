"""Shared-server hosting: the memory guard around DINOv2 work and the stable APK download page."""

from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app.ai.resources import MemoryGuard, MemorySnapshot, _cgroup_headroom_mb, _host_available_mb
from app.main import create_app
from tests.conftest import CLASS_COLORS, leaf_image

# --- memory guard ------------------------------------------------------------------------------


def host(mb, headroom=None):
    return lambda: MemorySnapshot(mb, headroom)


def test_guard_is_off_by_default_and_allows_unmeasurable_memory():
    assert not MemoryGuard(0, 0).enabled
    assert MemoryGuard(0, 0, probe=host(10)).training().allowed
    assert MemoryGuard(1500, 400, 700, probe=host(None, None)).training().allowed  # cannot measure → allow


def test_guard_compares_host_memory_with_thresholds():
    guard = MemoryGuard(1500, 400, probe=host(900))
    assert not guard.training().allowed
    assert "900 MB free on the host" in guard.training().message
    assert guard.inference().allowed


def test_container_headroom_is_checked_separately_from_host_memory():
    """Regression: the container check must not reuse the host threshold. With a 1536 MB limit the
    container can never have 1500 MB of headroom, which deferred all feature extraction in production."""
    guard = MemoryGuard(1500, 500, 700, probe=host(3700, 1440))  # production at rest
    assert guard.training().allowed and guard.inference().allowed
    low_container = MemoryGuard(1500, 500, 700, probe=host(3700, 600))
    assert not low_container.training().allowed
    assert "600 MB left in the GEO Tree container" in low_container.training().message
    low_host = MemoryGuard(1500, 500, 700, probe=host(1200, 1400))
    assert not low_host.training().allowed and low_host.inference().allowed


def test_inference_uses_its_own_smaller_container_headroom():
    """Regression: with DINOv2 resident (~650 MB) the container had ~630 MB of headroom, below the
    700 MB training requirement, so every assessment answered AI_BUSY although the host had 3 GB free."""
    guard = MemoryGuard(1500, 500, 700, probe=host(3100, 630), container_inference_mb=300)
    assert guard.inference().allowed
    assert not guard.training().allowed  # training still waits for its larger headroom
    assert not MemoryGuard(1500, 500, 700, probe=host(3100, 250), container_inference_mb=300).inference().allowed
    assert MemoryGuard(0, 0, 700).container_inference_mb == 700  # default: same as training


def test_cgroup_headroom_ignores_reclaimable_page_cache(tmp_path):
    limit, current, stat = tmp_path / "max", tmp_path / "current", tmp_path / "stat"
    limit.write_text(str(1536 * 1024**2))
    current.write_text(str(900 * 1024**2))
    stat.write_text(f"anon {650 * 1024**2}\ninactive_file {250 * 1024**2}\nactive_file {0}\n")
    assert _cgroup_headroom_mb(limit, current, stat) == 1536 - 650
    assert _cgroup_headroom_mb(limit, current, tmp_path / "missing") == 1536 - 900  # no stat → conservative


def test_meminfo_and_cgroup_parsing(tmp_path):
    meminfo = tmp_path / "meminfo"
    meminfo.write_text("MemTotal:  8000000 kB\nMemAvailable:  2048000 kB\n")
    assert _host_available_mb(meminfo) == 2000
    limit, current = tmp_path / "max", tmp_path / "current"
    limit.write_text("max\n")
    current.write_text("100\n")
    assert _cgroup_headroom_mb(limit, current, tmp_path / 'nostat') is None
    limit.write_text(str(2 * 1024**3))
    current.write_text(str(512 * 1024**2))
    assert _cgroup_headroom_mb(limit, current, tmp_path / 'nostat') == 1536
    assert _host_available_mb(tmp_path / "missing") is None


class Memory:
    """Scriptable probe: host free memory; the container always has 1200 MB of headroom."""

    def __init__(self, mb):
        self.mb = mb

    def __call__(self):
        return MemorySnapshot(self.mb, 1200)


def _login(client):
    response = client.post("/api/v1/auth/login", json={"email": "admin@gmail.com", "password": "admin123"})
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def _fill(client, headers, per_class=4):
    classes = {c["name"]: c for c in client.get("/api/v1/admin/classifiers", headers=headers).json()["items"]}
    for index, (name, color) in enumerate(CLASS_COLORS.items()):
        files = [("files", (f"{name}{i}.png", leaf_image(color, index * 100 + i), "image/png")) for i in range(per_class)]
        assert client.post(f"/api/v1/admin/classifiers/{classes[name]['id']}/images", files=files, headers=headers).status_code == 200


@pytest.fixture
def guarded(ai_settings, db, fake_extractor):
    memory = Memory(4000)
    app = create_app(settings=ai_settings, database=db, feature_extractor=fake_extractor)
    with TestClient(app) as client:
        app.state.ai.guard = MemoryGuard(1500, 400, 700, probe=memory)
        yield client, memory, app.state.ai


def test_low_memory_defers_feature_extraction_and_training_keeps_previous_model(guarded, fake_extractor):
    client, memory, ai = guarded
    headers = _login(client)
    _fill(client, headers)
    later = datetime.now(timezone.utc) + timedelta(minutes=5)
    assert ai.run_due_jobs(later) == 1
    v1 = client.get("/api/v1/admin/ai/model", headers=headers).json()["activeModel"]["modelVersion"]

    memory.mb = 900  # e.g. the museum's 3D job is running
    healthy = next(c for c in client.get("/api/v1/admin/classifiers", headers=headers).json()["items"] if c["name"] == "Healthy")
    embedded_before = fake_extractor.images_embedded
    upload = client.post(f"/api/v1/admin/classifiers/{healthy['id']}/images",
                         files=[("files", ("late.png", leaf_image((10, 200, 10), 999), "image/png"))], headers=headers)
    assert upload.status_code == 200
    assert upload.json()["embeddingsComputed"] == 0  # stored, embedded later
    assert upload.json()["uploaded"][0]["embeddingStatus"] == "PENDING"
    assert fake_extractor.images_embedded == embedded_before

    assert ai.run_due_jobs(later) == 0  # deferred, not failed
    status = client.get("/api/v1/admin/ai/model", headers=headers).json()
    assert status["status"] == "QUEUED"
    assert status["resourceGuard"]["deferred"] is True
    assert "low on memory" in status["resourceGuard"]["message"]
    assert status["activeModel"]["modelVersion"] == v1  # previous model still answers

    memory.mb = 4000
    assert ai.run_due_jobs(later) == 1
    status = client.get("/api/v1/admin/ai/model", headers=headers).json()
    assert status["status"] == "READY" and status["activeModel"]["modelVersion"] == v1 + 1
    assert status["lastJob"]["embeddingsComputed"] == 1  # only the deferred image, once
    assert status["resourceGuard"]["deferred"] is False


def test_low_memory_answers_assessment_with_ai_busy(guarded):
    client, memory, ai = guarded
    headers = _login(client)
    _fill(client, headers)
    ai.run_due_jobs(datetime.now(timezone.utc) + timedelta(minutes=5))
    memory.mb = 200
    response = client.post("/api/v1/ai/leaf-assessment", files={"file": ("leaf.png", leaf_image((40, 160, 60), 7), "image/png")}, headers=headers)
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "AI_BUSY"
    memory.mb = 4000
    response = client.post("/api/v1/ai/leaf-assessment", files={"file": ("leaf.png", leaf_image((40, 160, 60), 7), "image/png")}, headers=headers)
    assert response.status_code == 200


def test_memory_thresholds_come_from_the_environment(monkeypatch):
    from app.ai.config import load_ai_settings

    monkeypatch.setenv("AI_TRAINING_MIN_FREE_MB", "1500")
    monkeypatch.setenv("AI_INFERENCE_MIN_FREE_MB", "400")
    monkeypatch.setenv("AI_CONTAINER_MIN_HEADROOM_MB", "700")
    settings = load_ai_settings()
    assert (settings.training_min_free_mb, settings.inference_min_free_mb, settings.container_min_headroom_mb) == (1500, 400, 700)


# --- download page -----------------------------------------------------------------------------


def test_download_is_disabled_without_a_distribution_dir(client):
    assert client.get("/download").status_code == 404


def test_download_page_and_apk_are_public_and_stable(settings, db, tmp_path):
    dist = tmp_path / "distribution"
    dist.mkdir()
    with TestClient(create_app(settings=replace(settings, distribution_dir=dist), database=db)) as client:
        assert client.get("/download").json()["error"]["code"] == "NOT_FOUND"  # nothing published yet
        (dist / "index.html").write_text("<h1>GEO Tree</h1>", encoding="utf-8")
        (dist / "GEO_Tree.apk").write_bytes(b"PK\x03\x04apk")
        page = client.get("/download")
        assert page.status_code == 200 and "GEO Tree" in page.text
        assert page.headers["cache-control"] == "no-cache"
        apk = client.get("/download/GEO_Tree.apk")
        assert apk.status_code == 200 and apk.content == b"PK\x03\x04apk"
        assert apk.headers["content-type"] == "application/vnd.android.package-archive"
        assert "GEO_Tree.apk" in apk.headers["content-disposition"]
        assert client.get("/download/", follow_redirects=False).headers["location"] == "/download"
        assert client.get("/download/other.apk").status_code == 404
        assert client.get("/download/..%2Fmain.py").status_code == 404

"""Test fixtures. Every test gets its own in-memory database and temp upload dir,
so tests never touch live development data."""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from datetime import datetime, timezone
from pathlib import Path

import mongomock
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(environment="development", jwt_secret="geo-tree-test-secret-at-least-32-bytes-long", upload_dir=tmp_path / "uploads")


@pytest.fixture
def db():
    return mongomock.MongoClient(tz_aware=True)[f"geo_tree_test_{uuid.uuid4().hex}"]


@pytest.fixture
def client(settings: Settings, db) -> Iterator[TestClient]:
    with TestClient(create_app(settings=settings, database=db)) as test_client:
        yield test_client


@pytest.fixture
def auth_headers(client: TestClient) -> dict[str, str]:
    response = client.post("/api/v1/auth/login", json={"email": "admin@gmail.com", "password": "admin123"})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


# AI ---------------------------------------------------------------------------------------------
# The AI tests never load PyTorch or download DINOv2 weights: FakeExtractor stands in for the
# backbone and derives a deterministic 384-d feature from each image's mean colour.

CLASS_COLORS = {"Healthy": (40, 160, 60), "Spotting": (140, 90, 40), "Powdery Mildew": (205, 205, 190)}


class FakeExtractor:
    model_name = "dinov2_vits14"
    display_name = "DINOv2 ViT-S/14"
    dimension = 384

    def __init__(self) -> None:
        self.cache_key = "fake-dinov2|test|v1"
        self.calls: list[int] = []  # number of images per extract() call
        self.fail: Exception | None = None

    def extract(self, images):
        import hashlib

        import numpy as np

        from app.ai.images import load_rgb

        if self.fail is not None:
            raise self.fail
        self.calls.append(len(images))
        rows = []
        for data in images:
            mean = np.asarray(load_rgb(data), dtype=np.float32).mean(axis=(0, 1)) / 255.0
            rng = np.random.default_rng(int.from_bytes(hashlib.sha256(data).digest()[:8], "little"))
            vector = rng.normal(0.0, 0.05, self.dimension).astype(np.float32)
            vector[:3] += mean * 4
            rows.append(vector)
        return np.stack(rows)

    @property
    def images_embedded(self) -> int:
        return sum(self.calls)

    def status(self):
        return {"loaded": True, "device": "cpu", "error": None}


def leaf_image(color: tuple[int, int, int], seed: int, size: int = 48, fmt: str = "PNG") -> bytes:
    """A small synthetic 'leaf' photo: a solid colour with per-seed noise (unique bytes per seed)."""
    import io

    import numpy as np
    from PIL import Image

    rng = np.random.default_rng(seed)
    pixels = np.clip(np.array(color)[None, None, :] + rng.integers(-20, 21, (size, size, 3)), 0, 255).astype(np.uint8)
    buffer = io.BytesIO()
    Image.fromarray(pixels).save(buffer, format=fmt)
    return buffer.getvalue()


@pytest.fixture
def fake_extractor() -> FakeExtractor:
    return FakeExtractor()


@pytest.fixture
def ai_settings(tmp_path: Path) -> Settings:
    from app.ai.config import AiSettings

    ai = AiSettings(
        enabled=True,
        data_dir=tmp_path / "ai_data",
        min_images_per_class=3,
        validation_fraction=0.34,
        retrain_debounce_seconds=15,
        worker_enabled=False,  # tests run jobs explicitly with run_due_jobs()
        preload_backbone=False,
    )
    return Settings(environment="development", jwt_secret="geo-tree-test-secret-at-least-32-bytes-long", upload_dir=tmp_path / "uploads", ai=ai)


@pytest.fixture
def ai_client(ai_settings: Settings, db, fake_extractor: FakeExtractor) -> Iterator[TestClient]:
    with TestClient(create_app(settings=ai_settings, database=db, feature_extractor=fake_extractor)) as test_client:
        yield test_client


@pytest.fixture
def admin_headers(ai_client: TestClient) -> dict[str, str]:
    response = ai_client.post("/api/v1/auth/login", json={"email": "admin@gmail.com", "password": "admin123"})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def iso(year: int = 2026, month: int = 9, day: int = 28, hour: int = 8, minute: int = 0, second: int = 0) -> str:
    return datetime(year, month, day, hour, minute, second, tzinfo=timezone.utc).isoformat()


def tree_payload(**overrides) -> dict:
    payload = {
        "id": str(uuid.uuid4()),
        "tree_code": "GEO-TAM-003",
        "latitude": 15.1449,
        "longitude": 120.5887,
        "accuracy_meters": 4.2,
        "altitude_meters": None,
        "location_captured_at": iso(minute=0),
        "age": None,
        "taste_category": None,
        "yearly_yield": None,
        "fruit_quality": None,
        "notes": None,
        "created_at": iso(minute=1),
        "updated_at": iso(minute=1),
    }
    payload.update(overrides)
    return payload

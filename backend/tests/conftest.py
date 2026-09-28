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

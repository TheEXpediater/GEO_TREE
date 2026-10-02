"""The admin bootstrap utility, against an isolated in-memory database (never a real cluster)."""
import sys
import uuid
from pathlib import Path

import mongomock
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))

from bootstrap_admin import BootstrapError, bootstrap_admin  # noqa: E402

from app.auth.security import verify_password  # noqa: E402
from app.repositories import user_repository  # noqa: E402


@pytest.fixture()
def db():
    return mongomock.MongoClient(tz_aware=True)[f"geo_tree_bootstrap_{uuid.uuid4().hex}"]


def test_creates_admin_with_hashed_password(db):
    assert bootstrap_admin(db, "Admin@Gmail.com", "field-test-2026!") is True
    user = user_repository.find_by_email(db, "admin@gmail.com")
    assert user is not None
    assert user["password_hash"] != "field-test-2026!"
    assert verify_password("field-test-2026!", user["password_hash"])


def test_is_idempotent_and_never_overwrites(db):
    bootstrap_admin(db, "admin@gmail.com", "first-password-123")
    assert bootstrap_admin(db, "admin@gmail.com", "second-password-456") is False
    assert db.users.count_documents({}) == 1
    assert verify_password("first-password-123", user_repository.find_by_email(db, "admin@gmail.com")["password_hash"])


@pytest.mark.parametrize("password", ["admin123", "short"])
def test_rejects_development_or_weak_passwords_in_production(db, password):
    with pytest.raises(BootstrapError):
        bootstrap_admin(db, "admin@gmail.com", password, production=True)
    assert db.users.count_documents({}) == 0

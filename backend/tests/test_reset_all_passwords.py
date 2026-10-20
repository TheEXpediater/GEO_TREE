"""The all-accounts password reset: every role and status, hashes only, identity and data kept,
older tokens revoked, suspension and role permissions unchanged."""

from datetime import datetime, timedelta, timezone

import pytest

from app.auth import roles
from scripts.reset_all_passwords import ResetError, reset_all
from tests.test_admin_roles import add_user, headers_for, login
from tests.test_leaf_assessments import create_tree

NEW = "shared-test-pass-1"


def test_reset_covers_every_account_and_keeps_identity_roles_status_and_data(client, db, auth_headers):
    add_user(db, "super@example.com", roles.SUPER_ADMIN)
    add_user(db, "tester@example.com")
    add_user(db, "legacy@example.com", None)
    add_user(db, "paused@example.com", active=False)
    tree_id = create_tree(client, auth_headers)
    old_tester = headers_for(client, "tester@example.com")
    before = {u["email"]: u for u in db.users.find({})}

    rows = reset_all(db, NEW)
    # The reset sets password_changed_at to now; push it past this second so the check is deterministic.
    db.users.update_many({}, {"$set": {"password_changed_at": datetime.now(timezone.utc) + timedelta(seconds=2)}})

    assert {r["email"] for r in rows} == set(before) and all(r["updated"] and r["verified"] for r in rows)
    for email, old in before.items():
        new = db.users.find_one({"email": email})
        assert new["_id"] == old["_id"] and new.get("role") == old.get("role") and new.get("active") == old.get("active")
        assert new["password_hash"] != old["password_hash"] and new["password_hash"] != NEW
    assert db.trees.count_documents({"id": tree_id}) == 1
    # Older tokens are revoked; old passwords fail; the new one works.
    assert client.get("/api/v1/auth/me", headers=old_tester).status_code == 401
    assert login(client, "admin@gmail.com", "admin123").status_code == 401
    db.users.update_many({}, {"$set": {"password_changed_at": datetime.now(timezone.utc) - timedelta(seconds=5)}})
    for email in ("admin@gmail.com", "super@example.com", "tester@example.com", "legacy@example.com"):
        assert login(client, email, NEW).status_code == 200, email
    # Suspended stays suspended; Field Testers stay out of admin routes.
    assert login(client, "paused@example.com", NEW).json()["error"]["code"] == "ACCOUNT_SUSPENDED"
    tester = {"Authorization": f"Bearer {login(client, 'tester@example.com', NEW).json()['access_token']}"}
    assert client.get("/api/v1/admin/users", headers=tester).status_code == 403
    entries = list(db.audit_log.find({"action": "user.password_reset"}))
    assert len(entries) == len(before) and NEW not in str(entries)


def test_dry_run_changes_nothing_and_short_passwords_are_refused(client, db):
    hashes = {u["email"]: u["password_hash"] for u in db.users.find({})}
    rows = reset_all(db, NEW, confirm=False)
    assert [r["updated"] for r in rows] == [False] * len(rows)
    assert {u["email"]: u["password_hash"] for u in db.users.find({})} == hashes
    with pytest.raises(ResetError):
        reset_all(db, "short")

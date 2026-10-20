"""Roles, account status, token revocation, sign-in throttling and account management."""

from datetime import datetime, timedelta, timezone

import jwt
import pytest

from app.auth import roles
from app.auth.security import ALGORITHM, hash_password
from scripts.promote_super_admin import PromotionError, promote

PASSWORD = "field-tester-pass-1"
ADMIN_ROUTES = ["/api/v1/admin/dashboard", "/api/v1/admin/users", "/api/v1/admin/trees", "/api/v1/admin/tags",
                "/api/v1/admin/assessments", "/api/v1/admin/activity"]


def add_user(db, email, role=roles.FIELD_TESTER, password=PASSWORD, **extra):
    document = {"email": email, "password_hash": hash_password(password), "display_name": email.split("@")[0],
                "created_at": datetime.now(timezone.utc), **extra}
    if role is not None:
        document["role"] = role
    db.users.insert_one(document)
    return str(db.users.find_one({"email": email})["_id"])


def login(client, email, password=PASSWORD):
    return client.post("/api/v1/auth/login", json={"email": email, "password": password})


def headers_for(client, email, password=PASSWORD):
    response = login(client, email, password)
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


@pytest.fixture
def super_headers(client, db):
    add_user(db, "super@example.com", roles.SUPER_ADMIN)
    return headers_for(client, "super@example.com")


# Role enforcement ----------------------------------------------------------------------------
@pytest.mark.parametrize("role", [roles.FIELD_TESTER, None])
def test_field_testers_and_legacy_users_get_403_on_every_admin_route(client, db, role):
    add_user(db, "tester@example.com", role)
    headers = headers_for(client, "tester@example.com")
    for route in ADMIN_ROUTES:
        response = client.get(route, headers=headers)
        assert response.status_code == 403, route
        assert response.json()["error"]["code"] == "ADMIN_REQUIRED"
    # Field work keeps working.
    assert client.get("/api/v1/trees/changes", headers=headers).status_code == 200


def test_admin_and_super_admin_reach_admin_routes(client, auth_headers, super_headers):
    for headers in (auth_headers, super_headers):
        for route in ADMIN_ROUTES:
            assert client.get(route, headers=headers).status_code == 200, route


def test_admin_routes_require_a_token(client):
    for route in ADMIN_ROUTES:
        assert client.get(route).status_code == 401


# Account status and revocation ------------------------------------------------------------------
def test_suspended_account_cannot_sign_in_and_existing_token_stops_working(client, db):
    add_user(db, "tester@example.com")
    headers = headers_for(client, "tester@example.com")
    db.users.update_one({"email": "tester@example.com"}, {"$set": {"active": False}})
    me = client.get("/api/v1/auth/me", headers=headers)
    assert me.status_code == 401 and me.json()["error"]["code"] == "ACCOUNT_SUSPENDED"
    response = login(client, "tester@example.com")
    assert response.status_code == 403 and response.json()["error"]["code"] == "ACCOUNT_SUSPENDED"
    # A wrong password still gets the generic answer (suspension is not disclosed).
    assert login(client, "tester@example.com", "wrong-password").json()["error"]["code"] == "INVALID_CREDENTIALS"


def test_password_change_revokes_older_tokens(client, db, settings):
    add_user(db, "tester@example.com")
    headers = headers_for(client, "tester@example.com")
    db.users.update_one({"email": "tester@example.com"}, {"$set": {"password_changed_at": datetime.now(timezone.utc) + timedelta(seconds=2)}})
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 401


def test_tokens_without_iat_are_revoked_only_for_accounts_with_a_password_change(client, db, settings):
    add_user(db, "old@example.com")
    add_user(db, "rotated@example.com", password_changed_at=datetime.now(timezone.utc) - timedelta(days=1))
    expires = datetime.now(timezone.utc) + timedelta(days=1)
    for email, expected in (("old@example.com", 200), ("rotated@example.com", 401)):
        legacy = jwt.encode({"sub": email, "exp": expires}, settings.jwt_secret, algorithm=ALGORITHM)  # pre-"iat" token
        assert client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {legacy}"}).status_code == expected


def test_new_tokens_after_password_change_work(client, db):
    add_user(db, "rotated@example.com", password_changed_at=datetime.now(timezone.utc) - timedelta(minutes=5))
    assert client.get("/api/v1/auth/me", headers=headers_for(client, "rotated@example.com")).status_code == 200


def test_repeated_failed_sign_ins_are_throttled_per_email(client, db):
    add_user(db, "tester@example.com")
    for _ in range(10):
        assert login(client, "tester@example.com", "wrong-password").status_code == 401
    blocked = login(client, "tester@example.com")  # even the right password waits
    assert blocked.status_code == 429 and blocked.json()["error"]["code"] == "LOGIN_RATE_LIMITED"
    assert login(client, "admin@gmail.com", "admin123").status_code == 200  # other accounts unaffected


def test_sign_in_records_last_login_and_activity_without_secrets(client, db, auth_headers):
    login(client, "admin@gmail.com", "wrong-password")
    entries = client.get("/api/v1/admin/activity?action=auth", headers=auth_headers).json()["items"]
    actions = {e["action"] for e in entries}
    assert {"auth.login", "auth.login_failed"} <= actions
    assert "wrong-password" not in str(entries) and "admin123" not in str(entries)
    assert db.users.find_one({"email": "admin@gmail.com"})["last_login_at"] is not None


# Account management -----------------------------------------------------------------------------
def new_user(role="FIELD_TESTER", email="new@example.com"):
    return {"email": email, "displayName": "New Person", "role": role, "password": "a-strong-pass-123"}


def test_admin_creates_field_tester_but_not_administrators(client, auth_headers):
    created = client.post("/api/v1/admin/users", json=new_user(), headers=auth_headers)
    assert created.status_code == 201, created.text
    body = created.json()
    assert body["role"] == "FIELD_TESTER" and body["active"] is True
    assert "password" not in str(body).lower().replace("passwordchangedat", "")
    for role in ("ADMIN", "SUPER_ADMIN"):
        response = client.post("/api/v1/admin/users", json=new_user(role, f"{role.lower()}@example.com"), headers=auth_headers)
        assert response.status_code == 403 and response.json()["error"]["code"] == "ROLE_NOT_ALLOWED"
    assert login(client, "new@example.com", "a-strong-pass-123").status_code == 200


def test_duplicate_email_and_weak_password_are_rejected(client, auth_headers):
    assert client.post("/api/v1/admin/users", json=new_user(email="admin@gmail.com"), headers=auth_headers).json()["error"]["code"] == "EMAIL_TAKEN"
    weak = {**new_user(), "password": "short"}
    assert client.post("/api/v1/admin/users", json=weak, headers=auth_headers).status_code == 422


def test_admin_cannot_edit_or_reset_other_administrators(client, db, auth_headers):
    other = add_user(db, "other-admin@example.com", roles.ADMIN)
    assert client.patch(f"/api/v1/admin/users/{other}", json={"active": False}, headers=auth_headers).status_code == 403
    assert client.post(f"/api/v1/admin/users/{other}/password", json={"password": "another-pass-123"}, headers=auth_headers).status_code == 403
    tester = add_user(db, "tester@example.com")
    assert client.patch(f"/api/v1/admin/users/{tester}", json={"role": "ADMIN"}, headers=auth_headers).status_code == 403
    assert client.patch(f"/api/v1/admin/users/{tester}", json={"active": False}, headers=auth_headers).json()["active"] is False


def test_super_admin_manages_roles_but_cannot_change_itself(client, db, super_headers):
    tester = add_user(db, "tester@example.com")
    promoted = client.patch(f"/api/v1/admin/users/{tester}", json={"role": "ADMIN"}, headers=super_headers)
    assert promoted.status_code == 200 and promoted.json()["role"] == "ADMIN"
    me = db.users.find_one({"email": "super@example.com"})
    for change in ({"role": "ADMIN"}, {"active": False}):
        own = client.patch(f"/api/v1/admin/users/{me['_id']}", json=change, headers=super_headers)
        assert own.status_code == 403 and own.json()["error"]["code"] == "SELF_CHANGE_NOT_ALLOWED"
    # Display name of one's own account may change.
    assert client.patch(f"/api/v1/admin/users/{me['_id']}", json={"displayName": "Chief"}, headers=super_headers).json()["displayName"] == "Chief"


def test_a_super_admin_can_demote_another_while_one_remains(client, db, super_headers):
    second = add_user(db, "second@example.com", roles.SUPER_ADMIN)
    assert client.patch(f"/api/v1/admin/users/{second}", json={"role": "ADMIN"}, headers=super_headers).status_code == 200
    assert db.users.count_documents({"role": roles.SUPER_ADMIN, "active": {"$ne": False}}) == 1


def test_password_reset_revokes_the_users_sessions(client, db, auth_headers):
    tester = add_user(db, "tester@example.com")
    tester_headers = headers_for(client, "tester@example.com")
    reset = client.post(f"/api/v1/admin/users/{tester}/password", json={"password": "brand-new-pass-1"}, headers=auth_headers)
    assert reset.status_code == 200
    # The reset sets password_changed_at to now; force it past the token's second for a deterministic check.
    db.users.update_one({"email": "tester@example.com"}, {"$set": {"password_changed_at": datetime.now(timezone.utc) + timedelta(seconds=2)}})
    assert client.get("/api/v1/auth/me", headers=tester_headers).status_code == 401
    assert login(client, "tester@example.com", PASSWORD).status_code == 401
    assert login(client, "tester@example.com", "brand-new-pass-1").status_code == 200


def test_user_list_filters_and_never_exposes_hashes(client, db, auth_headers):
    add_user(db, "tester@example.com")
    add_user(db, "legacy@example.com", None)
    add_user(db, "paused@example.com", active=False)
    listing = client.get("/api/v1/admin/users?role=FIELD_TESTER", headers=auth_headers).json()
    assert {u["email"] for u in listing["items"]} == {"tester@example.com", "legacy@example.com", "paused@example.com"}
    assert "password_hash" not in str(listing) and "$2b$" not in str(listing)
    suspended = client.get("/api/v1/admin/users?status=suspended", headers=auth_headers).json()["items"]
    assert [u["email"] for u in suspended] == ["paused@example.com"]
    searched = client.get("/api/v1/admin/users?q=legacy", headers=auth_headers).json()
    assert searched["total"] == 1 and searched["items"][0]["roleAssigned"] is False


# Super Admin bootstrap --------------------------------------------------------------------------
def test_promote_super_admin_keeps_identity_and_password(client, db):
    before = db.users.find_one({"email": "admin@gmail.com"})
    assert "Dry run" in promote(db, "admin@gmail.com", confirm=False)
    assert db.users.find_one({"email": "admin@gmail.com"})["role"] == roles.ADMIN
    promote(db, "admin@gmail.com")
    after = db.users.find_one({"email": "admin@gmail.com"})
    assert after["role"] == roles.SUPER_ADMIN
    assert after["_id"] == before["_id"] and after["password_hash"] == before["password_hash"]
    assert login(client, "admin@gmail.com", "admin123").status_code == 200
    add_user(db, "other@example.com", roles.ADMIN)
    with pytest.raises(PromotionError):
        promote(db, "other@example.com")
    with pytest.raises(PromotionError):
        promote(db, "missing@example.com")
    assert "already SUPER_ADMIN" in promote(db, "admin@gmail.com")

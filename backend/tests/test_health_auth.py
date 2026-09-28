from app.auth.security import verify_password
from app.config import Settings
from app.services.seed import seed_dev_admin


def test_health_identifies_geo_tree_backend(client):
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "geo-tree-api"
    assert body["database"] == "ok"


def test_dev_admin_seed_is_idempotent_and_hashed(client, db, settings):
    assert seed_dev_admin(db, settings) is False  # already seeded at startup
    users = list(db.users.find({"email": "admin@gmail.com"}))
    assert len(users) == 1
    assert users[0]["password_hash"] != "admin123"
    assert verify_password("admin123", users[0]["password_hash"])


def test_dev_admin_is_not_seeded_outside_development(db, tmp_path):
    production = Settings(environment="production", jwt_secret="x", upload_dir=tmp_path)
    assert seed_dev_admin(db, production) is False
    assert db.users.count_documents({}) == 0


def test_login_success_returns_bearer_token(client):
    response = client.post("/api/v1/auth/login", json={"email": "Admin@Gmail.com", "password": "admin123"})
    assert response.status_code == 200
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]
    assert body["user"]["email"] == "admin@gmail.com"


def test_login_wrong_password_is_structured_401(client):
    response = client.post("/api/v1/auth/login", json={"email": "admin@gmail.com", "password": "wrong"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "INVALID_CREDENTIALS"


def test_login_unknown_user_is_indistinguishable(client):
    response = client.post("/api/v1/auth/login", json={"email": "nobody@example.com", "password": "admin123"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "INVALID_CREDENTIALS"


def test_me_requires_token(client, auth_headers):
    assert client.get("/api/v1/auth/me").status_code == 401
    assert client.get("/api/v1/auth/me", headers={"Authorization": "Bearer not-a-jwt"}).status_code == 401
    response = client.get("/api/v1/auth/me", headers=auth_headers)
    assert response.status_code == 200
    assert response.json()["email"] == "admin@gmail.com"

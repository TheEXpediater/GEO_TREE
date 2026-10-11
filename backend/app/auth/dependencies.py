from __future__ import annotations

from typing import Any

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pymongo.database import Database

from app.auth.security import decode_access_token
from app.config import Settings
from app.errors import ApiError
from app.repositories import user_repository

_bearer = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Database:
    return request.app.state.db


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_db),
) -> dict[str, Any]:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise ApiError(401, "UNAUTHORIZED", "Missing bearer token.")
    email = decode_access_token(credentials.credentials, settings.jwt_secret)
    if email is None:
        raise ApiError(401, "UNAUTHORIZED", "Invalid or expired token.")
    user = user_repository.find_by_email(db, email)
    if user is None:
        raise ApiError(401, "UNAUTHORIZED", "User no longer exists.")
    return user


def require_admin(user: dict[str, Any] = Depends(get_current_user)) -> dict[str, Any]:
    if user.get("role") != user_repository.ROLE_ADMIN:
        raise ApiError(403, "ADMIN_REQUIRED", "Administrator access is required.")
    return user


def get_ai_service(request: Request) -> Any:
    """The AI service, or 503 when this server runs without the AI subsystem (AI_ENABLED=false)."""
    service = getattr(request.app.state, "ai", None)
    if service is None:
        raise ApiError(503, "AI_DISABLED", "Leaf assessment is not enabled on this server. Use the local GEO Tree AI backend.")
    return service

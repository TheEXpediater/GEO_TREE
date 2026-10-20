from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Request
from pymongo.database import Database

from app.auth import roles
from app.auth.dependencies import get_current_user, get_db, get_settings
from app.auth.security import create_access_token, verify_password
from app.config import Settings
from app.errors import ApiError
from app.repositories import user_repository
from app.schemas.auth import LoginRequest, LoginResponse, UserOut
from app.services import audit

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _user_out(user: dict[str, Any]) -> UserOut:
    return UserOut(email=user["email"], display_name=user.get("display_name") or user["email"], role=user.get("role"))


@router.post("/login", response_model=LoginResponse)
def login(body: LoginRequest, request: Request, db: Database = Depends(get_db), settings: Settings = Depends(get_settings)) -> LoginResponse:
    email = user_repository.normalize_email(body.email)
    limiter = request.app.state.login_limiter
    retry_after = limiter.retry_after(email)
    if retry_after is not None:
        raise ApiError(429, "LOGIN_RATE_LIMITED", f"Too many failed sign-in attempts. Try again in {retry_after // 60 + 1} minutes.",
                       {"retryAfterSeconds": retry_after})
    user = user_repository.find_by_email(db, email)
    # Same response for unknown email and wrong password.
    if user is None or not verify_password(body.password, user["password_hash"]):
        limiter.failed(email)
        audit.record(db, {"email": email, "role": None}, "auth.login_failed", f"Failed sign-in for {email}", "user", None)
        raise ApiError(401, "INVALID_CREDENTIALS", "Incorrect email or password.")
    limiter.succeeded(email)
    # Only someone who knows the password learns that the account is suspended.
    if not roles.is_active(user):
        audit.record(db, user, "auth.login_suspended", f"Suspended account {email} tried to sign in", "user", str(user["_id"]))
        raise ApiError(403, "ACCOUNT_SUSPENDED", "This account is suspended. Contact a GEO Tree administrator.")
    token, expires_at = create_access_token(user["email"], settings.jwt_secret, settings.jwt_expire_minutes)
    user_repository.record_login(db, user["_id"], datetime.now(timezone.utc))
    audit.record(db, user, "auth.login", f"{email} signed in", "user", str(user["_id"]))
    return LoginResponse(access_token=token, expires_at=expires_at, user=_user_out(user))


@router.get("/me", response_model=UserOut)
def me(user: dict[str, Any] = Depends(get_current_user)) -> UserOut:
    return _user_out(user)

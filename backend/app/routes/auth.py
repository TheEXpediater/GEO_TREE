from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends
from pymongo.database import Database

from app.auth.dependencies import get_current_user, get_db, get_settings
from app.auth.security import create_access_token, verify_password
from app.config import Settings
from app.errors import ApiError
from app.repositories import user_repository
from app.schemas.auth import LoginRequest, LoginResponse, UserOut

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


def _user_out(user: dict[str, Any]) -> UserOut:
    return UserOut(email=user["email"], display_name=user.get("display_name") or user["email"])


@router.post("/login", response_model=LoginResponse)
def login(body: LoginRequest, db: Database = Depends(get_db), settings: Settings = Depends(get_settings)) -> LoginResponse:
    user = user_repository.find_by_email(db, body.email)
    # Same response for unknown email and wrong password.
    if user is None or not verify_password(body.password, user["password_hash"]):
        raise ApiError(401, "INVALID_CREDENTIALS", "Incorrect email or password.")
    token, expires_at = create_access_token(user["email"], settings.jwt_secret, settings.jwt_expire_minutes)
    return LoginResponse(access_token=token, expires_at=expires_at, user=_user_out(user))


@router.get("/me", response_model=UserOut)
def me(user: dict[str, Any] = Depends(get_current_user)) -> UserOut:
    return _user_out(user)

"""Password hashing (bcrypt) and JWT access tokens."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt

ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except ValueError:
        return False


@dataclass(frozen=True)
class TokenClaims:
    subject: str
    issued_at: int | None  # seconds since the epoch; None for tokens issued before "iat" was added


def create_access_token(subject: str, secret: str, expire_minutes: int) -> tuple[str, datetime]:
    issued_at = datetime.now(timezone.utc)
    expires_at = issued_at + timedelta(minutes=expire_minutes)
    token = jwt.encode({"sub": subject, "iat": int(issued_at.timestamp()), "exp": expires_at}, secret, algorithm=ALGORITHM)
    return token, expires_at


def decode_access_token(token: str, secret: str) -> TokenClaims | None:
    try:
        payload = jwt.decode(token, secret, algorithms=[ALGORITHM])
    except jwt.PyJWTError:
        return None
    subject = payload.get("sub")
    if not isinstance(subject, str):
        return None
    issued_at = payload.get("iat")
    return TokenClaims(subject, int(issued_at) if isinstance(issued_at, (int, float)) else None)


def token_predates_password_change(claims: TokenClaims, password_changed_at: datetime | None) -> bool:
    """A password change (rotation or admin reset) revokes every token issued before it."""
    if password_changed_at is None:
        return False
    if password_changed_at.tzinfo is None:
        password_changed_at = password_changed_at.replace(tzinfo=timezone.utc)
    return claims.issued_at is None or claims.issued_at < int(password_changed_at.timestamp())

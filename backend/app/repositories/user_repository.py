from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.database.mongo import USERS


def normalize_email(email: str) -> str:
    return email.strip().lower()


def find_by_email(db: Database, email: str) -> dict[str, Any] | None:
    return db[USERS].find_one({"email": normalize_email(email)})


ROLE_ADMIN = "ADMIN"


def create_if_missing(db: Database, email: str, password_hash: str, display_name: str, role: str | None = None) -> bool:
    """Insert the user only if the email is unused. Returns True when a user was created."""
    if find_by_email(db, email) is not None:
        return False
    document = {
        "email": normalize_email(email),
        "password_hash": password_hash,
        "display_name": display_name,
        "created_at": datetime.now(timezone.utc),
    }
    if role is not None:
        document["role"] = role
    try:
        db[USERS].insert_one(document)
    except DuplicateKeyError:
        return False
    return True


def assign_role_if_missing(db: Database, email: str, role: str) -> bool:
    """Gives an existing user a role only when it has none. Never changes an existing role."""
    result = db[USERS].update_one({"email": normalize_email(email), "role": {"$exists": False}}, {"$set": {"role": role}})
    return result.modified_count > 0

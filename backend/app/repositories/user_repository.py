from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from bson import ObjectId
from bson.errors import InvalidId
from pymongo import ASCENDING, ReturnDocument
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from app.auth import roles
from app.database.mongo import USERS

ROLE_ADMIN = roles.ADMIN  # kept for existing imports


def normalize_email(email: str) -> str:
    return email.strip().lower()


def find_by_email(db: Database, email: str) -> dict[str, Any] | None:
    return db[USERS].find_one({"email": normalize_email(email)})


def find_by_id(db: Database, user_id: str) -> dict[str, Any] | None:
    try:
        return db[USERS].find_one({"_id": ObjectId(user_id)})
    except (InvalidId, TypeError):
        return None


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


def insert(db: Database, document: dict[str, Any]) -> dict[str, Any] | None:
    """Inserts a new account; returns None when the email is already used."""
    try:
        result = db[USERS].insert_one(dict(document))
    except DuplicateKeyError:
        return None
    return db[USERS].find_one({"_id": result.inserted_id})


def update(db: Database, user_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
    return db[USERS].find_one_and_update({"_id": ObjectId(user_id)}, {"$set": fields}, return_document=ReturnDocument.AFTER)


def record_login(db: Database, user_id: Any, at: datetime) -> None:
    db[USERS].update_one({"_id": user_id}, {"$set": {"last_login_at": at}})


def count_active_super_admins(db: Database) -> int:
    return db[USERS].count_documents({"role": roles.SUPER_ADMIN, "active": {"$ne": False}})


def _filter(query: str | None, role: str | None, status: str | None) -> dict[str, Any]:
    conditions: list[dict[str, Any]] = []
    if query:
        pattern = re.escape(query.strip())
        conditions.append({"$or": [{"email": {"$regex": pattern, "$options": "i"}}, {"display_name": {"$regex": pattern, "$options": "i"}}]})
    if role == roles.FIELD_TESTER:
        # Legacy accounts without a role are Field Testers too.
        conditions.append({"$or": [{"role": roles.FIELD_TESTER}, {"role": {"$exists": False}}, {"role": None}]})
    elif role:
        conditions.append({"role": role})
    if status == "active":
        conditions.append({"active": {"$ne": False}})
    elif status == "suspended":
        conditions.append({"active": False})
    return {"$and": conditions} if conditions else {}


def page(db: Database, query: str | None, role: str | None, status: str | None, skip: int, limit: int) -> tuple[list[dict[str, Any]], int]:
    selector = _filter(query, role, status)
    total = db[USERS].count_documents(selector)
    documents = list(db[USERS].find(selector).sort("email", ASCENDING).skip(skip).limit(limit))
    return documents, total


def summary(db: Database) -> dict[str, Any]:
    counts = {role: 0 for role in roles.ALL_ROLES}
    active = suspended = 0
    for user in db[USERS].find({}, {"role": 1, "active": 1}):
        counts[roles.effective_role(user)] += 1
        if roles.is_active(user):
            active += 1
        else:
            suspended += 1
    return {"total": active + suspended, "active": active, "suspended": suspended, "byRole": counts}

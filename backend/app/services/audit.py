"""Activity / audit log: who did what to which record, and when.

Entries never contain passwords, password hashes, tokens or file system paths. Writing an entry
must never break the action it describes, so failures are logged and swallowed.
"""

from __future__ import annotations

import logging
import re
import uuid
from datetime import datetime, timezone
from typing import Any

from pymongo import DESCENDING
from pymongo.database import Database

from app.database.mongo import AUDIT_LOG

logger = logging.getLogger(__name__)

SYSTEM = {"email": "system", "role": "SYSTEM"}


def record(
    db: Database,
    actor: dict[str, Any] | None,
    action: str,
    summary: str,
    target_type: str | None = None,
    target_id: str | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    actor = actor or SYSTEM
    entry = {
        "id": str(uuid.uuid4()),
        "at": datetime.now(timezone.utc),
        "actor_email": actor.get("email"),
        "actor_role": actor.get("role"),
        "action": action,
        "summary": summary[:300],
        "target_type": target_type,
        "target_id": target_id,
        "details": details or {},
    }
    try:
        db[AUDIT_LOG].insert_one(entry)
    except Exception:  # noqa: BLE001 - auditing must not break the audited action
        logger.exception("Could not write audit entry %s", action)


def page(db: Database, action: str | None, actor: str | None, skip: int, limit: int) -> tuple[list[dict[str, Any]], int]:
    selector: dict[str, Any] = {}
    if action:
        # "classifier" matches classifier.create, classifier.rename, ...
        selector["action"] = {"$regex": f"^{re.escape(action)}"}
    if actor:
        selector["actor_email"] = {"$regex": re.escape(actor.strip()), "$options": "i"}
    total = db[AUDIT_LOG].count_documents(selector)
    documents = list(db[AUDIT_LOG].find(selector, {"_id": False}).sort("at", DESCENDING).skip(skip).limit(limit))
    return documents, total


def view(entry: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": entry["id"],
        "at": entry["at"],
        "actorEmail": entry.get("actor_email"),
        "actorRole": entry.get("actor_role"),
        "action": entry["action"],
        "summary": entry.get("summary"),
        "targetType": entry.get("target_type"),
        "targetId": entry.get("target_id"),
        "details": entry.get("details") or {},
    }

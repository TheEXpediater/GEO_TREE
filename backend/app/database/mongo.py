"""MongoDB connection and index setup. FastAPI is the only gateway to MongoDB."""

from __future__ import annotations

from pymongo import ASCENDING, MongoClient
from pymongo.database import Database

USERS = "users"
TREES = "trees"
COUNTERS = "counters"


def create_client(uri: str) -> MongoClient:
    return MongoClient(uri, serverSelectionTimeoutMS=3000, tz_aware=True)


def ensure_indexes(db: Database) -> None:
    db[USERS].create_index([("email", ASCENDING)], unique=True, name="uniq_email")
    # The device-generated UUID is the shared identity; Mongo's _id stays internal.
    db[TREES].create_index([("id", ASCENDING)], unique=True, name="uniq_tree_id")
    db[TREES].create_index([("tree_code_normalized", ASCENDING)], unique=True, name="uniq_tree_code")
    db[TREES].create_index([("server_version", ASCENDING)], name="server_version")


def ping(db: Database) -> bool:
    try:
        db.client.admin.command("ping")
        return True
    except Exception:  # noqa: BLE001 - health must never raise
        return False

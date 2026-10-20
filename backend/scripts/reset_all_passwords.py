"""
Sets ONE password on EVERY GEO Tree account (all roles, active and suspended) in the configured database.

* The password is read from standard input (never an argument, file or environment variable) and is
  never printed. It is stored only as a bcrypt hash made by the backend's own helper.
* Nothing else changes: account IDs, emails, roles, suspension status and all field data stay as
  they are. No account is created or deleted.
* ``password_changed_at`` is set to now, so every token issued before the reset is rejected
  (see app.auth.security.token_predates_password_change): all users must sign in again.
* Each change is written to the activity log without the password.

Usage (inside the API container, or from backend/ for a local run):

    <password on stdin> | python scripts/reset_all_passwords.py            # dry run: lists accounts
    <password on stdin> | python scripts/reset_all_passwords.py --confirm  # writes
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pymongo.database import Database  # noqa: E402

from app.auth import roles  # noqa: E402
from app.auth.security import hash_password, verify_password  # noqa: E402
from app.database.mongo import USERS  # noqa: E402
from app.schemas.admin import MIN_PASSWORD_LENGTH  # noqa: E402
from app.services import audit  # noqa: E402

ACTOR = {"email": "reset_all_passwords.py", "role": "SYSTEM"}


class ResetError(ValueError):
    pass


def _status(user: dict) -> str:
    return "active" if roles.is_active(user) else "suspended"


def reset_all(db: Database, password: str, confirm: bool = True) -> list[dict]:
    """Returns one summary row per account (email, role, status, updated, verified); never the hash."""
    if len(password) < MIN_PASSWORD_LENGTH:
        raise ResetError(f"Use a password of at least {MIN_PASSWORD_LENGTH} characters.")
    rows = []
    now = datetime.now(timezone.utc)
    for user in db[USERS].find({}).sort("email", 1):
        row = {"email": user["email"], "role": roles.effective_role(user), "roleAssigned": user.get("role"),
               "status": _status(user), "updated": False, "verified": False}
        if confirm:
            result = db[USERS].update_one(
                {"_id": user["_id"]},
                {"$set": {"password_hash": hash_password(password), "password_changed_at": now,
                          "updated_at": now, "updated_by": ACTOR["email"]}},
            )
            stored = db[USERS].find_one({"_id": user["_id"]})
            row["updated"] = result.matched_count == 1
            row["verified"] = verify_password(password, stored["password_hash"]) and stored["password_hash"] != password
            audit.record(db, ACTOR, "user.password_reset", f"Password reset for {user['email']} (all-accounts reset)",
                         "user", str(user["_id"]))
        rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Set one password on every GEO Tree account (password from stdin).")
    parser.add_argument("--confirm", action="store_true", help="required: actually write to the configured database")
    args = parser.parse_args()
    password = sys.stdin.readline().rstrip("\r\n")

    from app.config import load_settings
    from app.database.mongo import create_client

    settings = load_settings()
    db = create_client(settings.mongo_uri)[settings.mongo_database]
    try:
        rows = reset_all(db, password, confirm=args.confirm)
    except ResetError as error:
        print(f"Refused: {error}")
        return 2
    for row in rows:
        state = ("updated, hash verified" if row["verified"] else "UPDATE FAILED") if args.confirm else "would update"
        print(f"{row['email']}  role={row['role']}  status={row['status']}  {state}")
    counts = Counter((row["role"], row["status"]) for row in rows)
    print(f"{len(rows)} account(s) in database '{settings.mongo_database}': "
          + ", ".join(f"{role}/{status}={n}" for (role, status), n in sorted(counts.items())))
    if args.confirm and not all(row["verified"] for row in rows):
        return 1
    if not args.confirm:
        print("Dry run: nothing changed. Re-run with --confirm.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""
One-time promotion of an EXISTING GEO Tree account to SUPER_ADMIN (the first web super admin).

Nothing else about the account changes: its ID, email, password hash and history stay as they
are, so it signs in exactly as before. No password is read, printed or stored by this script.
Refuses when a Super Admin already exists (unless --allow-additional) or the account is missing.

Usage (inside the API container on the server, or from backend/ with backend/.env locally):

    python scripts/promote_super_admin.py --email admin@gmail.com            # dry run
    python scripts/promote_super_admin.py --email admin@gmail.com --confirm  # writes
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pymongo.database import Database  # noqa: E402

from app.auth import roles  # noqa: E402
from app.repositories import user_repository  # noqa: E402
from app.services import audit  # noqa: E402


class PromotionError(ValueError):
    pass


def promote(db: Database, email: str, allow_additional: bool = False, confirm: bool = True) -> str:
    """Returns a one-line description of what was (or, without ``confirm``, would be) done."""
    user = user_repository.find_by_email(db, email)
    if user is None:
        raise PromotionError(f"No account with email {email}. Create it first (scripts/bootstrap_admin.py).")
    if not roles.is_active(user):
        raise PromotionError(f"{email} is suspended; reactivate it before promoting it.")
    if user.get("role") == roles.SUPER_ADMIN:
        return f"{email} is already SUPER_ADMIN; nothing changed."
    existing = user_repository.count_active_super_admins(db)
    if existing and not allow_additional:
        raise PromotionError(f"{existing} Super Admin account(s) already exist. Use --allow-additional to add another.")
    previous = user.get("role")
    if not confirm:
        return f"Dry run: would change {email} from {previous} to SUPER_ADMIN. Re-run with --confirm."
    now = datetime.now(timezone.utc)
    user_repository.update(db, str(user["_id"]), {"role": roles.SUPER_ADMIN, "updated_at": now, "updated_by": "promote_super_admin.py"})
    audit.record(db, {"email": "promote_super_admin.py", "role": "SYSTEM"}, "user.update",
                 f"Promoted {email} to SUPER_ADMIN (bootstrap)", "user", str(user["_id"]), {"role": roles.SUPER_ADMIN, "previousRole": previous})
    return f"{email}: {previous} -> SUPER_ADMIN."


def main() -> int:
    parser = argparse.ArgumentParser(description="Promote an existing GEO Tree account to SUPER_ADMIN.")
    parser.add_argument("--email", required=True)
    parser.add_argument("--confirm", action="store_true", help="required: actually write to the configured database")
    parser.add_argument("--allow-additional", action="store_true", help="allow a second Super Admin")
    args = parser.parse_args()

    from app.config import load_settings
    from app.database.mongo import create_client

    settings = load_settings()
    db = create_client(settings.mongo_uri)[settings.mongo_database]
    try:
        print(promote(db, args.email, args.allow_additional, args.confirm) + f" (database '{settings.mongo_database}')")
    except PromotionError as error:
        print(f"Refused: {error}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""
One-time creation of the GEO Tree *application* admin (the account used to sign in to the app).

This is not the MongoDB Atlas database user. It connects through the normal backend settings
(MONGO_URI / MONGO_DATABASE, from the process environment or backend/.env), hashes the password
with the backend's own bcrypt helper, and creates the user only if that email is unused.

Usage (PowerShell, from the backend folder):

    $env:BOOTSTRAP_ADMIN_EMAIL = "admin@gmail.com"
    $env:BOOTSTRAP_ADMIN_PASSWORD = Read-Host "GEO Tree admin password"
    .\\.venv\\Scripts\\python.exe scripts\\bootstrap_admin.py --confirm
    Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD

Nothing secret is printed: not the password, not the connection string.
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pymongo.database import Database  # noqa: E402

from app.auth.security import hash_password  # noqa: E402
from app.repositories import user_repository  # noqa: E402

MIN_PASSWORD_LENGTH = 10
DEVELOPMENT_PASSWORDS = {"admin123"}


class BootstrapError(ValueError):
    pass


def validate(email: str, password: str, production: bool) -> None:
    if "@" not in email or email.strip() != email or len(email) < 3:
        raise BootstrapError("BOOTSTRAP_ADMIN_EMAIL is not a valid email address.")
    if production and password in DEVELOPMENT_PASSWORDS:
        raise BootstrapError("The development password cannot be used for a hosted/production admin.")
    if production and len(password) < MIN_PASSWORD_LENGTH:
        raise BootstrapError(f"Use a password of at least {MIN_PASSWORD_LENGTH} characters.")


def bootstrap_admin(db: Database, email: str, password: str, production: bool = True) -> bool:
    """Create the admin if missing. Returns True when created, False when it already existed."""
    validate(email, password, production)
    return user_repository.create_if_missing(db, email, hash_password(password), display_name="GEO Tree Admin")


def main() -> int:
    parser = argparse.ArgumentParser(description="Create the GEO Tree app admin if it does not exist.")
    parser.add_argument("--confirm", action="store_true", help="required: actually write to the configured database")
    args = parser.parse_args()

    logging.basicConfig(level=logging.WARNING)
    email = os.environ.get("BOOTSTRAP_ADMIN_EMAIL", "").strip()
    password = os.environ.get("BOOTSTRAP_ADMIN_PASSWORD", "")
    if not email or not password:
        print("Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD first (see this file's docstring).")
        return 2

    from app.config import load_settings
    from app.database.mongo import create_client, ensure_indexes

    settings = load_settings()
    production = not settings.is_development
    try:
        validate(email, password, production)
    except BootstrapError as e:
        print(f"Refused: {e}")
        return 2
    if not args.confirm:
        print(f"Dry run: would create app admin {email} in database '{settings.mongo_database}' "
              f"(environment={settings.environment}) if missing. Re-run with --confirm.")
        return 0

    db = create_client(settings.mongo_uri)[settings.mongo_database]
    ensure_indexes(db)
    created = bootstrap_admin(db, email, password, production)
    print(f"App admin {email}: {'created' if created else 'already exists, left unchanged'} "
          f"in database '{settings.mongo_database}'.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

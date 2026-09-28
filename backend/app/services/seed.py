from __future__ import annotations

import logging

from pymongo.database import Database

from app.auth.security import hash_password
from app.config import Settings
from app.repositories import user_repository

logger = logging.getLogger(__name__)


def seed_dev_admin(db: Database, settings: Settings) -> bool:
    """Create the development admin only in development and only if missing."""
    if not (settings.is_development and settings.seed_dev_admin):
        return False
    created = user_repository.create_if_missing(
        db,
        settings.dev_admin_email,
        hash_password(settings.dev_admin_password),
        display_name="Development Admin",
    )
    if created:
        logger.info("Seeded development admin %s", settings.dev_admin_email)
    return created

"""Runtime configuration read from environment variables.

Kept dependency-free on purpose: a frozen dataclass populated from ``os.environ``.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

from app.ai.config import AiSettings, load_ai_settings

logger = logging.getLogger(__name__)

BACKEND_ROOT = Path(__file__).resolve().parent.parent

# Used only when ENVIRONMENT=development and JWT_SECRET is unset.
_DEV_JWT_SECRET = "geo-tree-development-only-secret-do-not-use-in-production"


@dataclass(frozen=True)
class Settings:
    environment: str = "development"
    mongo_uri: str = "mongodb://localhost:27018"
    mongo_database: str = "geo_tree"
    jwt_secret: str = _DEV_JWT_SECRET
    jwt_expire_minutes: int = 60 * 24 * 7
    upload_dir: Path = BACKEND_ROOT / "uploads"
    max_image_bytes: int = 10 * 1024 * 1024
    seed_dev_admin: bool = True
    dev_admin_email: str = "admin@gmail.com"
    dev_admin_password: str = "admin123"
    # Folder with the published APK download page (index.html + GEO_Tree.apk); None disables /download.
    distribution_dir: Path | None = None
    # Built web administration (npm run build in web/); a missing index.html means API only.
    web_dist_dir: Path | None = None
    # Failed sign-ins per email before a temporary lockout (0 disables), and the window.
    login_max_failures: int = 10
    login_window_seconds: int = 15 * 60
    # "local": tree and leaf-assessment photos are stored under upload_dir (local Docker volume).
    # "disabled": no durable disk (e.g. Render free): photo uploads are acknowledged without storing
    # the bytes, so records sync and each photo stays on the phone that took it.
    image_storage: str = "local"
    ai: AiSettings = field(default_factory=AiSettings)

    @property
    def is_development(self) -> bool:
        return self.environment == "development"

    @property
    def image_storage_enabled(self) -> bool:
        return self.image_storage == "local"

    @property
    def tree_image_dir(self) -> Path:
        return self.upload_dir / "tree_images"


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _load_local_env_file() -> None:
    """Optionally read ``backend/.env`` for local runs (e.g. against MongoDB Atlas).

    Real process environment variables always win (``override=False``), so Render and
    Docker Compose settings are never replaced. A missing file is fine. Values are not logged.
    """
    env_file = BACKEND_ROOT / ".env"
    if env_file.is_file():
        load_dotenv(env_file, override=False)
        logger.info("Loaded local settings from backend/.env (process environment takes precedence).")


IMAGE_STORAGE_MODES = ("local", "disabled")


def _image_storage_mode() -> str:
    """IMAGE_STORAGE wins; otherwise Render (which sets RENDER=true and has an ephemeral disk)
    defaults to "disabled" so photos are never written where they would silently vanish."""
    mode = os.environ.get("IMAGE_STORAGE", "").strip().lower() or ("disabled" if _env_bool("RENDER", False) else "local")
    if mode not in IMAGE_STORAGE_MODES:
        raise RuntimeError(f"IMAGE_STORAGE must be one of {', '.join(IMAGE_STORAGE_MODES)}; got {mode!r}.")
    return mode


def load_settings() -> Settings:
    _load_local_env_file()
    environment = os.environ.get("ENVIRONMENT", "development").strip().lower()
    jwt_secret = os.environ.get("JWT_SECRET", "").strip()
    if not jwt_secret:
        if environment != "development":
            raise RuntimeError("JWT_SECRET must be set outside development.")
        logger.warning("JWT_SECRET not set; using the development-only secret.")
        jwt_secret = _DEV_JWT_SECRET

    return Settings(
        environment=environment,
        mongo_uri=os.environ.get("MONGO_URI", "mongodb://localhost:27018"),
        mongo_database=os.environ.get("MONGO_DATABASE", "geo_tree"),
        jwt_secret=jwt_secret,
        jwt_expire_minutes=int(os.environ.get("JWT_EXPIRE_MINUTES", str(60 * 24 * 7))),
        upload_dir=Path(os.environ.get("UPLOAD_DIR", str(BACKEND_ROOT / "uploads"))),
        # The development admin is never seeded outside development, whatever the flag says.
        seed_dev_admin=environment == "development" and _env_bool("SEED_DEV_ADMIN", True),
        dev_admin_email=os.environ.get("DEV_ADMIN_EMAIL", "admin@gmail.com"),
        dev_admin_password=os.environ.get("DEV_ADMIN_PASSWORD", "admin123"),
        distribution_dir=Path(os.environ["DISTRIBUTION_DIR"]) if os.environ.get("DISTRIBUTION_DIR", "").strip() else None,
        web_dist_dir=Path(os.environ.get("WEB_DIST_DIR", "").strip() or str(BACKEND_ROOT / "web_dist")),
        login_max_failures=int(os.environ.get("LOGIN_MAX_FAILURES", "10")),
        login_window_seconds=int(os.environ.get("LOGIN_WINDOW_SECONDS", str(15 * 60))),
        image_storage=_image_storage_mode(),
        ai=load_ai_settings(),
    )

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI
from pymongo.database import Database

from app.config import Settings, load_settings
from app.database.mongo import create_client, ensure_indexes
from app.errors import install_error_handlers
from app.auth.rate_limit import LoginRateLimiter
from app.routes import (
    admin_assessments,
    admin_classifiers,
    admin_map,
    admin_overview,
    admin_trees,
    admin_users,
    ai,
    auth,
    download,
    health,
    leaf_assessments,
    trees,
    uploads,
)
from app.services.seed import seed_dev_admin
from app.web import install_web

logger = logging.getLogger("geo_tree")


def _create_ai_service(db: Database, settings: Settings, feature_extractor: Any) -> Any:
    # Imported lazily: servers with AI_ENABLED=false never need NumPy, Pillow or PyTorch.
    from app.ai.features import Dinov2FeatureExtractor
    from app.ai.service import AiService

    extractor = feature_extractor or Dinov2FeatureExtractor(settings.ai)
    service = AiService(db, settings.ai, extractor, settings.max_image_bytes)
    service.start(seed_default_classes=settings.is_development)
    logger.info(
        "AI enabled: %s, image %dpx, device=%s, data %s",
        settings.ai.model_name, settings.ai.image_size, settings.ai.device, settings.ai.data_dir,
    )
    return service


def create_app(settings: Settings | None = None, database: Database | None = None, feature_extractor: Any = None) -> FastAPI:
    """Build the app. Tests pass an isolated ``database`` (and a fake ``feature_extractor``);
    otherwise MongoDB is opened from settings and DINOv2 is loaded when AI is enabled."""
    settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        client = None
        db = database
        if db is None:
            client = create_client(settings.mongo_uri)
            db = client[settings.mongo_database]
        settings.tree_image_dir.mkdir(parents=True, exist_ok=True)
        ensure_indexes(db)
        seed_dev_admin(db, settings)
        app.state.db = db
        app.state.settings = settings
        app.state.ai = _create_ai_service(db, settings, feature_extractor) if settings.ai.enabled else None
        logger.info("GEO Tree API ready (environment=%s, database=%s)", settings.environment, db.name)
        yield
        if app.state.ai is not None:
            app.state.ai.stop()
        if client is not None:
            client.close()

    app = FastAPI(title="GEO Tree API", version="0.4.0", lifespan=lifespan)
    app.state.login_limiter = LoginRateLimiter(settings.login_max_failures, settings.login_window_seconds)
    install_error_handlers(app)
    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(trees.router)
    app.include_router(leaf_assessments.router)
    app.include_router(uploads.router)
    app.include_router(download.router)
    app.include_router(admin_classifiers.router)
    app.include_router(ai.router)
    app.include_router(admin_overview.router)
    app.include_router(admin_users.router)
    app.include_router(admin_trees.router)
    app.include_router(admin_assessments.router)
    app.include_router(admin_map.router)
    # Last: the web administration's catch-all never shadows an API route.
    install_web(app, settings.web_dist_dir)
    return app

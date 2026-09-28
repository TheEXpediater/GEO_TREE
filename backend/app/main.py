from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pymongo.database import Database

from app.config import Settings, load_settings
from app.database.mongo import create_client, ensure_indexes
from app.errors import install_error_handlers
from app.routes import auth, health, trees, uploads
from app.services.seed import seed_dev_admin

logger = logging.getLogger("geo_tree")


def create_app(settings: Settings | None = None, database: Database | None = None) -> FastAPI:
    """Build the app. Tests pass an isolated ``database``; otherwise MongoDB is opened from settings."""
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
        logger.info("GEO Tree API ready (environment=%s, database=%s)", settings.environment, db.name)
        yield
        if client is not None:
            client.close()

    app = FastAPI(title="GEO Tree API", version="0.1.0", lifespan=lifespan)
    install_error_handlers(app)
    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(trees.router)
    app.include_router(uploads.router)
    return app

"""Serves the built web administration (``web/`` → ``backend/web_dist``) from the API itself.

Same origin as the API, so no CORS and no extra container or reverse-proxy route. Explicit API,
upload and download routes always win; this catch-all is registered last and never answers for
them. Unknown paths fall back to ``index.html`` so client-side routes survive a reload.
"""

from __future__ import annotations

import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.errors import ApiError

logger = logging.getLogger(__name__)

_RESERVED = ("api/", "uploads/", "download", "docs", "redoc", "openapi.json")

# The page loads only its own bundled scripts, styles and fonts; images may be blob: URLs built
# from authenticated fetches. The one third-party origin is the OpenStreetMap tile server for the
# web maps (images only, used within the OSMF tile usage policy, with attribution on every map).
TILE_ORIGIN = "https://tile.openstreetmap.org"
CONTENT_SECURITY_POLICY = (
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data: " + TILE_ORIGIN + "; "
    "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
)
_PAGE_HEADERS = {
    "Cache-Control": "no-cache",
    "Content-Security-Policy": CONTENT_SECURITY_POLICY,
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    # Cross-origin requests (map tiles) carry only the origin, as the OSM tile policy asks; never paths.
    "Referrer-Policy": "strict-origin-when-cross-origin",
}


def install_web(app: FastAPI, dist_dir: Path | None) -> bool:
    if dist_dir is None or not (dist_dir / "index.html").is_file():
        logger.info("Web administration not installed (no built web_dist); API only.")
        return False
    root = dist_dir.resolve()
    index = root / "index.html"
    if (root / "assets").is_dir():
        app.mount("/assets", StaticFiles(directory=root / "assets"), name="web-assets")

    @app.get("/", include_in_schema=False)
    def web_index() -> FileResponse:
        return FileResponse(index, media_type="text/html; charset=utf-8", headers=_PAGE_HEADERS)

    @app.get("/{path:path}", include_in_schema=False)
    def web_fallback(path: str) -> FileResponse:
        if path.startswith(_RESERVED):
            raise ApiError(404, "NOT_FOUND", "Not found.")
        candidate = (root / path).resolve()
        if candidate.is_file() and candidate.is_relative_to(root) and candidate != index:
            return FileResponse(candidate, headers={"X-Content-Type-Options": "nosniff"})
        return FileResponse(index, media_type="text/html; charset=utf-8", headers=_PAGE_HEADERS)

    logger.info("Web administration served from %s", root)
    return True

"""Minimal standard-library client for the LOCAL GEO Tree API, shared by the AI developer scripts.

These scripts are for local Docker testing only: they refuse any server that is not localhost,
loopback or a private LAN address, so they can never be pointed at the hosted deployment.
"""

from __future__ import annotations

import ipaddress
import json
import mimetypes
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASE_URL = "http://localhost:8000"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}


class LocalApiError(RuntimeError):
    def __init__(self, status: int | None, code: str, message: str):
        super().__init__(f"{code}: {message}" + (f" (HTTP {status})" if status else ""))
        self.status = status
        self.code = code
        self.message = message


def is_local_url(url: str) -> bool:
    host = urllib.parse.urlparse(url).hostname or ""
    if host in {"localhost", "host.docker.internal"}:
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    return address.is_loopback or address.is_private


def require_local(url: str) -> str:
    url = url.rstrip("/")
    if not is_local_url(url):
        raise SystemExit(f"Refusing {url}: AI developer scripts only talk to the local backend (localhost / LAN), never a hosted server.")
    return url


class LocalApi:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, timeout: float = 180.0):
        self.base_url = require_local(base_url)
        self.timeout = timeout
        self.token: str | None = None

    def _request(self, method: str, path: str, body: bytes | None = None, content_type: str | None = None) -> Any:
        request = urllib.request.Request(self.base_url + path, data=body, method=method)
        if content_type:
            request.add_header("Content-Type", content_type)
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read() or b"null")
        except urllib.error.HTTPError as exc:
            try:
                error = json.loads(exc.read())["error"]
                raise LocalApiError(exc.code, error.get("code", "HTTP_ERROR"), error.get("message", "")) from exc
            except (ValueError, KeyError, TypeError):
                raise LocalApiError(exc.code, "HTTP_ERROR", str(exc)) from exc
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            raise LocalApiError(None, "UNREACHABLE", f"{self.base_url} is not reachable: {exc}") from exc

    def get(self, path: str) -> Any:
        return self._request("GET", path)

    def post_json(self, path: str, payload: dict[str, Any] | None = None) -> Any:
        return self._request("POST", path, json.dumps(payload or {}).encode(), "application/json")

    def post_file(self, path: str, field: str, file: Path, extra: dict[str, str] | None = None) -> Any:
        boundary = uuid.uuid4().hex
        content_type = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
        parts: list[bytes] = []
        for name, value in (extra or {}).items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{file.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n".encode()
        )
        parts.append(file.read_bytes())
        parts.append(f"\r\n--{boundary}--\r\n".encode())
        return self._request("POST", path, b"".join(parts), f"multipart/form-data; boundary={boundary}")

    def check_health(self) -> dict[str, Any]:
        health = self.get("/api/v1/health")
        if health.get("service") != "geo-tree-api" or health.get("status") != "ok":
            raise LocalApiError(None, "NOT_GEO_TREE", f"{self.base_url} is not a healthy GEO Tree API: {health}")
        if health.get("ai") != "enabled":
            raise LocalApiError(None, "AI_DISABLED", "The local backend runs without AI. Start it with Docker Compose (AI_ENABLED=true).")
        return health

    def login(self, email: str, password: str) -> None:
        self.token = self.post_json("/api/v1/auth/login", {"email": email, "password": password})["access_token"]


def discover_images(root: Path) -> list[tuple[Path, str | None]]:
    """All supported images under ``root`` (recursive), with the top-level subfolder as the label."""
    found = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS:
            relative = path.relative_to(root)
            found.append((path, relative.parts[0] if len(relative.parts) > 1 else None))
    return found


def label_key(name: str) -> str:
    return "".join(ch for ch in name.lower() if ch.isalnum())

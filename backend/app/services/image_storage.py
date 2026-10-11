"""Tree and leaf-assessment image validation and file storage. MongoDB stores only the
server-relative path. Assessment photos live in their own folder and are never training data."""

from __future__ import annotations

import os
import re
import secrets
from pathlib import Path

from app.errors import ApiError

IMAGE_SUBDIR = "tree_images"
ASSESSMENT_SUBDIR = "leaf_assessments"
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9-]{1,80}\.(jpg|png|webp)$")


def detect_image_extension(data: bytes) -> str | None:
    """Identify the image by its content (magic bytes), never by the client's claims."""
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def _validated_extension(data: bytes, max_bytes: int) -> str:
    if not data:
        raise ApiError(400, "EMPTY_IMAGE", "Uploaded image is empty.")
    if len(data) > max_bytes:
        raise ApiError(413, "IMAGE_TOO_LARGE", f"Image exceeds {max_bytes // (1024 * 1024)} MB.")
    extension = detect_image_extension(data)
    if extension is None:
        raise ApiError(415, "UNSUPPORTED_IMAGE", "Only JPEG, PNG, or WebP images are accepted.")
    return extension


def save_assessment_image(upload_dir: Path, assessment_id: str, data: bytes, max_bytes: int) -> str:
    """Writes ``leaf_assessments/<assessment-id>.<ext>`` atomically (temp file, then rename)."""
    extension = _validated_extension(data, max_bytes)
    target_dir = upload_dir / ASSESSMENT_SUBDIR
    target_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{assessment_id}.{extension}"
    temporary = target_dir / f".{assessment_id}-{secrets.token_hex(4)}.tmp"
    temporary.write_bytes(data)
    os.replace(temporary, target_dir / filename)
    return f"{ASSESSMENT_SUBDIR}/{filename}"


def save_tree_image(upload_dir: Path, tree_id: str, data: bytes, max_bytes: int) -> str:
    extension = _validated_extension(data, max_bytes)

    filename = f"{tree_id}-{secrets.token_hex(6)}.{extension}"
    target_dir = upload_dir / IMAGE_SUBDIR
    target_dir.mkdir(parents=True, exist_ok=True)
    (target_dir / filename).write_bytes(data)
    return f"{IMAGE_SUBDIR}/{filename}"


def delete_image(upload_dir: Path, image_path: str | None) -> None:
    if not image_path:
        return
    resolved = resolve_image(upload_dir, Path(image_path).name)
    if resolved is not None:
        resolved.unlink(missing_ok=True)


def resolve_image(upload_dir: Path, filename: str, subdir: str = IMAGE_SUBDIR) -> Path | None:
    if subdir not in (IMAGE_SUBDIR, ASSESSMENT_SUBDIR) or not SAFE_FILENAME.match(filename):
        return None
    path = upload_dir / subdir / filename
    return path if path.is_file() else None

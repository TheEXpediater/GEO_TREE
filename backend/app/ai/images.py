"""Validation and decoding of classifier dataset and leaf-assessment images.

An upload is accepted only if its bytes decode as a real image (JPEG, PNG, WebP, or HEIC/HEIF
when ``pillow-heif`` is installed). The client's filename and content type are never trusted.
HEIC/HEIF photos (e.g. from iPhones) are normalized to high-quality JPEG for storage so every
stored dataset image can be read by Pillow and shown on Android.
"""

from __future__ import annotations

import hashlib
import io
import logging
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

from app.errors import ApiError
from app.services.image_storage import detect_image_extension

logger = logging.getLogger(__name__)

try:  # Optional HEIC/HEIF support.
    from pillow_heif import register_heif_opener

    register_heif_opener()
    HEIF_SUPPORTED = True
except ImportError:  # pragma: no cover - depends on the installed runtime
    HEIF_SUPPORTED = False

ACCEPTED_FORMATS = "JPEG, PNG, WebP or HEIC" if HEIF_SUPPORTED else "JPEG, PNG or WebP"
MIN_DIMENSION = 32
THUMBNAIL_SIZE = 320
_HEIF_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1"}
_MIME = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


@dataclass(frozen=True)
class ValidatedImage:
    data: bytes  # bytes to store: the original for JPEG/PNG/WebP, a JPEG re-encode for HEIC/HEIF
    extension: str  # jpg | png | webp
    mime_type: str
    width: int
    height: int
    sha256: str  # of the uploaded bytes, for exact-duplicate detection


def _is_heif(data: bytes) -> bool:
    return len(data) >= 12 and data[4:8] == b"ftyp" and data[8:12] in _HEIF_BRANDS


def _unsupported() -> ApiError:
    return ApiError(415, "UNSUPPORTED_IMAGE", f"Only {ACCEPTED_FORMATS} images are accepted.")


def load_rgb(data: bytes) -> Image.Image:
    """Decodes image bytes to an upright RGB image (EXIF orientation applied)."""
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        return ImageOps.exif_transpose(image).convert("RGB")


def validate_image(data: bytes, max_bytes: int) -> ValidatedImage:
    if not data:
        raise ApiError(400, "EMPTY_IMAGE", "Uploaded image is empty.")
    if len(data) > max_bytes:
        raise ApiError(413, "IMAGE_TOO_LARGE", f"Image exceeds {max_bytes // (1024 * 1024)} MB.")
    extension = detect_image_extension(data)
    heif = extension is None and _is_heif(data)
    if extension is None and not (heif and HEIF_SUPPORTED):
        raise _unsupported()
    try:
        image = load_rgb(data)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as exc:
        logger.info("Rejected undecodable image: %s", exc)
        raise _unsupported() from exc
    width, height = image.size
    if min(width, height) < MIN_DIMENSION:
        raise ApiError(422, "IMAGE_TOO_SMALL", f"Image must be at least {MIN_DIMENSION} x {MIN_DIMENSION} pixels.")

    stored = data
    if heif:
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=95)
        stored, extension = buffer.getvalue(), "jpg"
    return ValidatedImage(
        data=stored,
        extension=extension,
        mime_type=_MIME[extension],
        width=width,
        height=height,
        sha256=hashlib.sha256(data).hexdigest(),
    )


def make_thumbnail(data: bytes, size: int = THUMBNAIL_SIZE) -> bytes:
    image = load_rgb(data)
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=85)
    return buffer.getvalue()

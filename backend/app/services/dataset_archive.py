"""Classifier dataset archives (ZIP) uploaded for review: validation, staging and provenance.

An archive is stored under DATASET_ARCHIVE_DIR/<generated id>/ (never under a client-supplied
name) and validated without extracting anything to disk: member names are only ever used as
labels/metadata, never as file system paths. Validation guards against path traversal, absolute
and symlink entries, encrypted members, archive bombs (member count, total size, per-file size,
compression ratio) and files that are not images.

Labels come from folder names. Aliases map to the canonical classes; ``Mold``/``Molds`` map to
``Powdery Mildew`` (owner-authorized mapping, recorded per file as a remap, not as a botanical
fact). Unknown folders are reported, never guessed. Identical bytes under the same label are
duplicates (the first entry is kept); identical bytes under different labels are conflicts and
are excluded for review.

Validation only stages and reports: it never changes the active training dataset. Activation
(backup, replacement, retraining) is a separate, explicit step (README "Replacing the classifier
dataset"), so an upload can never silently start training.

With Pillow installed (the AI backend image) every image is fully decoded; on the lightweight API
image only the file signature is checked, and the report says so.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import logging
import shutil
import stat
import threading
import uuid
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO

from pymongo import DESCENDING
from pymongo.database import Database

from app.database.mongo import CLASSIFIER_IMAGES, DATASET_ARCHIVES
from app.errors import ApiError
from app.services import audit

logger = logging.getLogger(__name__)

CANONICAL_CLASSES = ("Healthy", "Spotting", "Powdery Mildew")
# Folder name (case-insensitive) -> canonical class.
LABEL_ALIASES = {
    "healthy": "Healthy",
    "spotting": "Spotting",
    "spot": "Spotting",
    "spots": "Spotting",
    "powdery mildew": "Powdery Mildew",
    "powdery_mildew": "Powdery Mildew",
    "mold": "Powdery Mildew",
    "molds": "Powdery Mildew",
    "mould": "Powdery Mildew",
}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
IGNORED_NAMES = {".ds_store", "thumbs.db", "desktop.ini"}
MAX_MEMBERS = 5000
MAX_TOTAL_UNCOMPRESSED = 4 * 1024 * 1024 * 1024
MAX_MEMBER_BYTES = 30 * 1024 * 1024
MAX_COMPRESSION_RATIO = 100
MIN_DIMENSION = 32

VALIDATING, READY, REJECTED, FAILED, ACTIVE, RETIRED, DISCARDED = (
    "VALIDATING", "READY", "REJECTED", "FAILED", "ACTIVE", "RETIRED", "DISCARDED",
)
ACCEPTED = "accepted"
MANIFEST_COLUMNS = (
    "entry", "source_part", "source_folder", "source_label", "canonical_label", "format", "bytes",
    "sha256", "width", "height", "status", "duplicate_family", "note",
)

_validation_lock = threading.Lock()

try:  # Full decoding needs Pillow (AI image); the lightweight API checks signatures only.
    from PIL import Image, ImageOps

    try:
        from pillow_heif import register_heif_opener

        register_heif_opener()
        _HEIF = True
    except ImportError:  # pragma: no cover - depends on the installed runtime
        _HEIF = False
    DECODE_AVAILABLE = True
except ImportError:  # pragma: no cover - depends on the installed runtime
    DECODE_AVAILABLE, _HEIF = False, False

_HEIF_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1"}


def _now() -> datetime:
    return datetime.now(timezone.utc)


class ArchiveRejected(ValueError):
    """The archive as a whole cannot be accepted (not a ZIP, too many members, too large...)."""


# Validation ------------------------------------------------------------------------------------

def sniff_format(head: bytes) -> str | None:
    if head.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    if len(head) >= 12 and head[4:8] == b"ftyp" and head[8:12] in _HEIF_BRANDS:
        return "heic"
    return None


def _unsafe_name(name: str) -> str | None:
    if "\x00" in name or "\\" in name:
        return "backslash or NUL in the entry name"
    if name.startswith("/") or (len(name) > 1 and name[1] == ":"):
        return "absolute path"
    if ".." in PurePosixPath(name).parts:
        return "parent-directory (..) component"
    return None


def resolve_label(folders: list[str]) -> tuple[str | None, str | None]:
    """(canonical class, source folder label) from the nearest folder that is a known label."""
    for folder in reversed(folders):
        canonical = LABEL_ALIASES.get(folder.strip().lower())
        if canonical:
            return canonical, folder
    return None, folders[-1] if folders else None


def _decode(data: bytes) -> tuple[int, int]:
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        return ImageOps.exif_transpose(image).size


def validate_archive(path: Path, active_sha256: set[str] | None = None) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """(summary, manifest rows). Raises ArchiveRejected when the archive itself is unacceptable."""
    try:
        archive = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as exc:
        raise ArchiveRejected("The file is not a readable ZIP archive.") from exc
    with archive:
        infos = archive.infolist()
        if len(infos) > MAX_MEMBERS:
            raise ArchiveRejected(f"The archive has {len(infos)} entries; the limit is {MAX_MEMBERS}.")
        declared = sum(i.file_size for i in infos)
        if declared > MAX_TOTAL_UNCOMPRESSED:
            raise ArchiveRejected("The archive expands beyond the size limit.")
        if any(i.flag_bits & 0x1 for i in infos):
            raise ArchiveRejected("The archive contains encrypted entries.")
        rows = [_validate_member(archive, info) for info in infos if not info.is_dir()]

    _mark_duplicates(rows)
    active_sha256 = active_sha256 or set()
    return _summary(rows, declared, sum(i.compress_size for i in infos), active_sha256), rows


def _validate_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> dict[str, Any]:
    name = info.filename
    parts = list(PurePosixPath(name).parts)
    folders = parts[:-1]
    canonical, source_label = resolve_label(folders)
    row: dict[str, Any] = {
        "entry": name,
        "source_part": parts[0] if len(parts) > 1 else "",
        "source_folder": "/".join(folders),
        "source_label": source_label or "",
        "canonical_label": canonical or "",
        "format": "",
        "bytes": info.file_size,
        "sha256": "",
        "width": "",
        "height": "",
        "status": ACCEPTED,
        "duplicate_family": "",
        "note": "",
    }

    def reject(status: str, note: str) -> dict[str, Any]:
        row.update(status=status, note=note)
        return row

    unsafe = _unsafe_name(name)
    if unsafe:
        return reject("unsafe_path", unsafe)
    if stat.S_ISLNK(info.external_attr >> 16):
        return reject("unsafe_path", "symbolic link")
    base = parts[-1].lower()
    if base in IGNORED_NAMES or base.startswith("._") or "__MACOSX" in parts:
        return reject("ignored", "operating-system metadata file")
    if PurePosixPath(base).suffix not in IMAGE_EXTENSIONS:
        return reject("unsupported", "not an image file extension")
    if info.file_size > MAX_MEMBER_BYTES:
        return reject("too_large", f"larger than {MAX_MEMBER_BYTES // (1024 * 1024)} MB")
    if info.compress_size and info.file_size / info.compress_size > MAX_COMPRESSION_RATIO:
        return reject("unsafe_compression", "suspicious compression ratio")

    data = _read_limited(archive, info)
    if data is None:
        return reject("too_large", "expands beyond its declared size")
    row["sha256"] = hashlib.sha256(data).hexdigest()
    fmt = sniff_format(data[:16])
    row["format"] = fmt or ""
    if fmt is None:
        return reject("unsupported", "content is not JPEG, PNG, WebP or HEIC")
    if DECODE_AVAILABLE and (fmt != "heic" or _HEIF):
        try:
            width, height = _decode(data)
        except Exception as exc:  # noqa: BLE001 - any decoder failure means unreadable
            return reject("unreadable", f"cannot be decoded ({exc.__class__.__name__})")
        row.update(width=width, height=height)
        if min(width, height) < MIN_DIMENSION:
            return reject("unreadable", f"smaller than {MIN_DIMENSION} px")
    if canonical is None:
        return reject("unknown_label", f"folder '{source_label or ''}' is not a known class; not mapped")
    if source_label and source_label.strip().lower() != canonical.lower():
        row["note"] = f"label remapped: {source_label} -> {canonical}"
    return row


def _read_limited(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> bytes | None:
    """Reads one member, refusing to inflate past its declared size (a lying header)."""
    with archive.open(info) as fh:
        data = fh.read(info.file_size + 1)
    return None if len(data) > info.file_size else data


def _mark_duplicates(rows: list[dict[str, Any]]) -> None:
    families: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row["status"] == ACCEPTED:
            families[row["sha256"]].append(row)
    for sha, members in families.items():
        if len(members) < 2:
            continue
        family = sha[:12]
        labels = {m["canonical_label"] for m in members}
        # Keep the shortest file name ("x.jpg" over "x (1).jpg" / "x copy.jpg"), then the path.
        members.sort(key=lambda m: (len(PurePosixPath(m["entry"]).name), m["entry"]))
        for index, member in enumerate(members):
            member["duplicate_family"] = family
            if len(labels) > 1:
                member.update(status="conflict", note=f"identical bytes labelled {sorted(labels)}; needs review")
            elif index > 0:
                member.update(status="duplicate", note=f"exact duplicate of {members[0]['entry']}")


def _summary(rows: list[dict[str, Any]], uncompressed: int, compressed: int, active_sha256: set[str]) -> dict[str, Any]:
    statuses = Counter(r["status"] for r in rows)
    images = [r for r in rows if r["format"]]
    accepted = [r for r in rows if r["status"] == ACCEPTED]
    by_class: dict[str, Counter] = defaultdict(Counter)
    by_source: dict[str, Counter] = defaultdict(Counter)
    remaps: Counter = Counter()
    for row in rows:
        if row["canonical_label"]:
            by_class[row["canonical_label"]][row["status"]] += 1
        if row["status"] == ACCEPTED:
            by_source[row["source_part"]][row["canonical_label"]] += 1
            if row["note"].startswith("label remapped"):
                remaps[(row["source_label"], row["canonical_label"])] += 1
    return {
        "entries": len(rows),
        "imageFiles": len(images),
        "accepted": len(accepted),
        "statusCounts": dict(statuses),
        "formats": dict(Counter(r["format"] for r in images)),
        "byClass": {name: dict(by_class.get(name, Counter())) for name in CANONICAL_CLASSES},
        "acceptedByClass": {name: by_class.get(name, Counter())[ACCEPTED] for name in CANONICAL_CLASSES},
        "acceptedBySource": {part: dict(counts) for part, counts in sorted(by_source.items())},
        "remappedLabels": [{"from": src, "to": dst, "count": n} for (src, dst), n in sorted(remaps.items())],
        "unknownLabels": sorted({r["source_label"] for r in rows if r["status"] == "unknown_label"}),
        "conflictFamilies": len({r["duplicate_family"] for r in rows if r["status"] == "conflict"}),
        "alreadyInActiveDataset": sum(1 for r in accepted if r["sha256"] in active_sha256),
        "uncompressedBytes": uncompressed,
        "compressedBytes": compressed,
        "decodeChecked": DECODE_AVAILABLE,
        "heicDecodeChecked": DECODE_AVAILABLE and _HEIF,
        "labelPolicy": "Folder aliases map to Healthy / Spotting / Powdery Mildew; Mold/Molds -> Powdery Mildew is an "
                       "owner-authorized mapping, not a verified diagnosis. Unknown folders are reported, not guessed.",
    }


def write_manifest(rows: list[dict[str, Any]], target: BinaryIO | Path) -> None:
    text = io.StringIO()
    writer = csv.DictWriter(text, fieldnames=MANIFEST_COLUMNS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    payload = text.getvalue().encode("utf-8")
    if isinstance(target, Path):
        target.write_bytes(payload)
    else:
        target.write(payload)


# Storage and records ---------------------------------------------------------------------------

def archive_root(archive_dir: Path | None) -> Path:
    if archive_dir is None:
        raise ApiError(503, "DATASET_ARCHIVES_DISABLED", "Dataset archive uploads are not enabled on this server.")
    archive_dir.mkdir(parents=True, exist_ok=True)
    return archive_dir


def _folder(archive_dir: Path, archive_id: str) -> Path:
    # Ids are generated hex strings; anything else never reaches the file system.
    if len(archive_id) != 32 or any(c not in "0123456789abcdef" for c in archive_id):
        raise ApiError(404, "DATASET_ARCHIVE_NOT_FOUND", "That dataset archive does not exist.")
    return archive_dir / archive_id


def active_dataset_hashes(db: Database) -> set[str]:
    return {d["sha256"] for d in db[CLASSIFIER_IMAGES].find({}, {"_id": False, "sha256": 1}) if d.get("sha256")}


def new_record(archive_id: str, file_name: str, size: int, sha256: str, actor: dict[str, Any] | None, origin: str) -> dict[str, Any]:
    return {
        "id": archive_id,
        "file_name": (file_name or "archive.zip")[:200],
        "bytes": size,
        "sha256": sha256,
        "status": VALIDATING,
        "origin": origin,
        "uploaded_at": _now(),
        "uploaded_by": {"email": (actor or audit.SYSTEM).get("email"), "role": (actor or audit.SYSTEM).get("role")},
        "validated_at": None,
        "summary": None,
        "error": None,
        "split": None,
        "activation": None,
    }


def run_validation(db: Database, archive_dir: Path, archive_id: str) -> dict[str, Any]:
    """Validates a stored archive and records the result (READY, REJECTED or FAILED)."""
    folder = _folder(archive_dir, archive_id)
    try:
        summary, rows = validate_archive(folder / "archive.zip", active_dataset_hashes(db))
        write_manifest(rows, folder / "manifest.csv")
        (folder / "report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        update = {"status": READY if summary["accepted"] else REJECTED, "summary": summary,
                  "error": None if summary["accepted"] else "No acceptable images were found."}
    except ArchiveRejected as exc:
        update = {"status": REJECTED, "error": str(exc)}
    except Exception as exc:  # noqa: BLE001 - recorded on the archive, never crashes the server
        logger.exception("Dataset archive %s validation failed", archive_id)
        update = {"status": FAILED, "error": f"Validation failed ({exc.__class__.__name__})."}
    update["validated_at"] = _now()
    db[DATASET_ARCHIVES].update_one({"id": archive_id}, {"$set": update})
    return db[DATASET_ARCHIVES].find_one({"id": archive_id}, {"_id": False})


def store_upload(db: Database, archive_dir: Path, source: BinaryIO, file_name: str, max_bytes: int,
                 actor: dict[str, Any]) -> dict[str, Any]:
    """Streams an uploaded archive into a new private folder (size-limited, hashed on the way)."""
    root = archive_root(archive_dir)
    if not _validation_lock.acquire(blocking=False):
        raise ApiError(409, "ARCHIVE_VALIDATION_BUSY", "Another dataset archive is being validated. Try again when it finishes.")
    archive_id = uuid.uuid4().hex
    folder = _folder(root, archive_id)
    try:
        folder.mkdir()
        digest, size = hashlib.sha256(), 0
        with (folder / "archive.zip").open("wb") as out:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                size += len(chunk)
                if size > max_bytes:
                    raise ApiError(413, "ARCHIVE_TOO_LARGE", f"The archive exceeds {max_bytes // (1024 * 1024)} MB.")
                digest.update(chunk)
                out.write(chunk)
        if size == 0:
            raise ApiError(400, "EMPTY_ARCHIVE", "The uploaded archive is empty.")
    except BaseException:
        shutil.rmtree(folder, ignore_errors=True)
        _validation_lock.release()
        raise
    record = new_record(archive_id, file_name, size, digest.hexdigest(), actor, "upload")
    db[DATASET_ARCHIVES].insert_one(dict(record))
    audit.record(db, actor, "dataset_archive.upload", f"Uploaded dataset archive {record['file_name']} ({size} bytes)",
                 "dataset_archive", archive_id, {"sha256": record["sha256"]})

    def work() -> None:
        try:
            run_validation(db, root, archive_id)
        finally:
            _validation_lock.release()

    threading.Thread(target=work, name=f"archive-validate-{archive_id[:8]}", daemon=True).start()
    return record


def fail_interrupted(db: Database) -> None:
    """At startup: a validation cut off by a restart is reported as FAILED instead of hanging."""
    db[DATASET_ARCHIVES].update_many(
        {"status": VALIDATING},
        {"$set": {"status": FAILED, "error": "Validation was interrupted by a server restart. Upload the archive again."}},
    )


def get(db: Database, archive_id: str) -> dict[str, Any]:
    record = db[DATASET_ARCHIVES].find_one({"id": archive_id}, {"_id": False})
    if record is None:
        raise ApiError(404, "DATASET_ARCHIVE_NOT_FOUND", "That dataset archive does not exist.")
    return record


def manifest_path(archive_dir: Path | None, archive_id: str) -> Path:
    path = _folder(archive_root(archive_dir), archive_id) / "manifest.csv"
    if not path.is_file():
        raise ApiError(404, "MANIFEST_NOT_AVAILABLE", "This archive has no validation manifest.")
    return path


def discard(db: Database, archive_dir: Path | None, archive_id: str, actor: dict[str, Any]) -> dict[str, Any]:
    """Deletes a staged (never activated) archive's files; the record stays for the audit trail."""
    record = get(db, archive_id)
    if record["status"] in (ACTIVE, RETIRED):
        raise ApiError(409, "ARCHIVE_IN_USE", "An active or previously active dataset archive is kept for rollback.")
    if record["status"] == VALIDATING:
        raise ApiError(409, "ARCHIVE_VALIDATING", "Wait for validation to finish.")
    if record["status"] == DISCARDED:
        return record
    shutil.rmtree(_folder(archive_root(archive_dir), archive_id), ignore_errors=True)
    db[DATASET_ARCHIVES].update_one({"id": archive_id}, {"$set": {"status": DISCARDED, "discarded_at": _now()}})
    audit.record(db, actor, "dataset_archive.discard", f"Discarded staged dataset archive {record['file_name']}",
                 "dataset_archive", archive_id, {"sha256": record["sha256"]})
    return get(db, archive_id)


def page(db: Database, skip: int, limit: int) -> tuple[list[dict[str, Any]], int]:
    total = db[DATASET_ARCHIVES].count_documents({})
    records = db[DATASET_ARCHIVES].find({}, {"_id": False}).sort("uploaded_at", DESCENDING).skip(skip).limit(limit)
    return [view(r) for r in records], total


def view(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": record["id"],
        "fileName": record.get("file_name"),
        "bytes": record.get("bytes"),
        "sha256": record.get("sha256"),
        "status": record.get("status"),
        "origin": record.get("origin"),
        "uploadedAt": record.get("uploaded_at"),
        "uploadedBy": record.get("uploaded_by"),
        "validatedAt": record.get("validated_at"),
        "summary": record.get("summary"),
        "error": record.get("error"),
        "split": record.get("split"),
        "activation": record.get("activation"),
    }

"""Android release records for Mobile Settings: what the download route serves, and its history.

The download route serves whatever ``GEO_Tree.apk`` is in DISTRIBUTION_DIR (copied there by the
release procedure, README section 12). A release record is created by an administrator
*registering* that served file: every value in the record is read from the APK itself
(``apk_inspect``), never typed in, so the history cannot drift from the bytes people download.

History starts when tracking starts. A first record may be a *baseline* for an APK that was
already being served: its original publication date is unknown and stored as ``None``.

Statuses: PUBLISHED (the one current release), SUPERSEDED (replaced by a newer version) and
ROLLED_BACK (replaced by re-publishing an older recorded APK).
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pymongo import DESCENDING
from pymongo.database import Database

from app.config import Settings
from app.database.mongo import APP_RELEASES, DATASET_ARCHIVES
from app.errors import ApiError
from app.services import apk_inspect, audit

APK_FILE_NAME = "GEO_Tree.apk"
PACKAGE_NAME = "com.geotree.app"
PUBLISHED, SUPERSEDED, ROLLED_BACK = "PUBLISHED", "SUPERSEDED", "ROLLED_BACK"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def served_apk_path(settings: Settings) -> Path | None:
    if settings.distribution_dir is None:
        return None
    path = settings.distribution_dir / APK_FILE_NAME
    return path if path.is_file() else None


def download_links(settings: Settings) -> dict[str, Any]:
    page = settings.download_public_url
    return {
        "configured": page is not None,
        "pageUrl": page,
        "apkUrl": f"{page.rstrip('/')}/{APK_FILE_NAME}" if page else None,
        "https": bool(page and page.lower().startswith("https://")),
        "distributionConfigured": settings.distribution_dir is not None,
    }


def inspect_served(settings: Settings) -> tuple[dict[str, Any] | None, str | None]:
    """(facts about the served APK, error message). Both None when nothing is published."""
    path = served_apk_path(settings)
    if path is None:
        return None, None
    try:
        return apk_inspect.inspect_apk(path), None
    except (apk_inspect.ApkError, OSError) as exc:
        return None, str(exc)


def release_checks(apk: dict[str, Any], settings: Settings) -> list[dict[str, Any]]:
    """Release gates evaluated on the APK bytes. ``ok`` None means "not configured, not checked"."""
    certs = apk["signing"]["certificateSha256"]
    model = apk.get("bundledModel")
    pinned = settings.release_signer_sha256
    return [
        {"id": "package", "label": f"Package is {PACKAGE_NAME}", "ok": apk.get("packageName") == PACKAGE_NAME},
        {"id": "not_debuggable", "label": "Release build (not debuggable)", "ok": not apk.get("debuggable")},
        {"id": "signed", "label": "Signed with APK Signature Scheme v2/v3", "ok": bool(certs)},
        {
            "id": "signer_pinned",
            "label": "Signing certificate matches the established release key",
            "ok": (pinned in certs) if pinned else None,
        },
        {
            "id": "model_bundle",
            "label": "Bundled leaf model matches its manifest checksums",
            "ok": bool(model and model.get("integrityOk")),
        },
    ]


def _current(db: Database) -> dict[str, Any] | None:
    return db[APP_RELEASES].find_one({"status": PUBLISHED}, {"_id": False}, sort=[("registered_at", DESCENDING)])


def view(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": record["id"],
        "status": record["status"],
        "baseline": record.get("baseline", False),
        "versionName": record.get("version_name"),
        "versionCode": record.get("version_code"),
        "packageName": record.get("package_name"),
        "apkFileName": record.get("apk_file_name"),
        "apkBytes": record.get("apk_bytes"),
        "apkSha256": record.get("apk_sha256"),
        "signerSha256": record.get("signer_sha256") or [],
        "signatureSchemes": record.get("signature_schemes") or [],
        "minSdk": record.get("min_sdk"),
        "targetSdk": record.get("target_sdk"),
        "debuggable": record.get("debuggable"),
        "model": record.get("model"),
        "dataset": record.get("dataset"),
        "notes": record.get("notes") or "",
        "downloadUrl": record.get("download_url"),
        "registeredAt": record.get("registered_at"),
        "originalPublishedAt": record.get("original_published_at"),
        "registeredBy": record.get("registered_by"),
        "events": record.get("events") or [],
    }


def overview(db: Database, settings: Settings) -> dict[str, Any]:
    """The served APK, the current release record and whether they agree."""
    apk, error = inspect_served(settings)
    current = _current(db)
    checks = release_checks(apk, settings) if apk else []
    matching = db[APP_RELEASES].find_one({"apk_sha256": apk["sha256"]}, {"_id": False}) if apk else None

    if error:
        health, reason = "UNHEALTHY", f"The served APK cannot be read: {error}"
    elif apk is None:
        health, reason = "NOT_PUBLISHED", "No APK is in the distribution folder, so /download has nothing to serve."
    elif current is None and matching is None:
        health, reason = "UNREGISTERED", "An APK is served but no release record describes it yet."
    elif current is None or current["apk_sha256"] != apk["sha256"]:
        health, reason = "UNHEALTHY", "The served APK is not the current release record (its SHA-256 differs)."
    elif any(c["ok"] is False for c in checks):
        health, reason = "UNHEALTHY", "The served APK fails a release check."
    else:
        health, reason = "HEALTHY", "The served APK matches the current release record."

    return {
        "health": health,
        "healthReason": reason,
        "download": download_links(settings),
        "servedApk": apk,
        "servedApkError": error,
        "checks": checks,
        "pinnedSignerSha256": settings.release_signer_sha256,
        "currentRelease": view(current) if current else None,
        "servedRelease": view(matching) if matching else None,
    }


def history(db: Database, skip: int, limit: int) -> tuple[list[dict[str, Any]], int]:
    total = db[APP_RELEASES].count_documents({})
    records = db[APP_RELEASES].find({}, {"_id": False}).sort("registered_at", DESCENDING).skip(skip).limit(limit)
    return [view(r) for r in records], total


def _dataset_ref(db: Database, archive_id: str | None) -> dict[str, Any] | None:
    if not archive_id:
        return None
    archive = db[DATASET_ARCHIVES].find_one({"id": archive_id}, {"_id": False, "id": 1, "file_name": 1, "sha256": 1})
    if archive is None:
        raise ApiError(404, "DATASET_ARCHIVE_NOT_FOUND", "That dataset archive does not exist.")
    return {"archiveId": archive["id"], "fileName": archive.get("file_name"), "sha256": archive.get("sha256")}


def register(
    db: Database,
    settings: Settings,
    actor: dict[str, Any],
    notes: str,
    dataset_archive_id: str | None,
    baseline: bool,
) -> dict[str, Any]:
    """Records the APK currently served by /download as the published release (or re-publishes an
    older recorded APK, which is a rollback). Every value comes from the APK bytes."""
    apk, error = inspect_served(settings)
    if error:
        raise ApiError(422, "APK_UNREADABLE", f"The served APK cannot be read: {error}")
    if apk is None:
        raise ApiError(409, "NO_SERVED_APK", "No APK is in the distribution folder. Copy the release there first.")
    failed = [c["label"] for c in release_checks(apk, settings) if c["ok"] is False]
    if failed:
        raise ApiError(422, "RELEASE_CHECK_FAILED", "The served APK fails release checks: " + "; ".join(failed))

    now = _now()
    actor_ref = {"email": actor.get("email"), "role": actor.get("role")}
    current = _current(db)
    existing = db[APP_RELEASES].find_one({"apk_sha256": apk["sha256"]}, {"_id": False})

    if existing is not None:
        if existing["status"] == PUBLISHED:
            raise ApiError(409, "RELEASE_ALREADY_CURRENT", "The served APK is already the current release.")
        # Rollback: the served file is an older recorded release.
        if current is not None:
            db[APP_RELEASES].update_one(
                {"id": current["id"]},
                {"$set": {"status": ROLLED_BACK}, "$push": {"events": {"at": now, "action": "rolled_back", "actor": actor_ref}}},
            )
        db[APP_RELEASES].update_one(
            {"id": existing["id"]},
            {"$set": {"status": PUBLISHED}, "$push": {"events": {"at": now, "action": "republished", "actor": actor_ref}}},
        )
        audit.record(db, actor, "release.rollback", f"Re-published Android {existing['version_name']} ({existing['version_code']})",
                     "app_release", existing["id"], {"replaced": current["id"] if current else None})
        return view(db[APP_RELEASES].find_one({"id": existing["id"]}, {"_id": False}))

    if baseline and db[APP_RELEASES].count_documents({}) > 0:
        raise ApiError(409, "BASELINE_NOT_FIRST", "Only the first release record can be a baseline.")
    if current is not None and (apk.get("versionCode") or 0) <= (current.get("version_code") or 0):
        raise ApiError(
            409, "VERSION_NOT_INCREASED",
            f"versionCode {apk.get('versionCode')} is not higher than the current release's {current.get('version_code')}; "
            "installed apps would not upgrade.",
        )

    model = apk.get("bundledModel") or {}
    record = {
        "id": uuid.uuid4().hex,
        "status": PUBLISHED,
        "baseline": baseline,
        "version_name": apk.get("versionName"),
        "version_code": apk.get("versionCode"),
        "package_name": apk.get("packageName"),
        "apk_file_name": APK_FILE_NAME,
        "apk_bytes": apk["bytes"],
        "apk_sha256": apk["sha256"],
        "signer_sha256": apk["signing"]["certificateSha256"],
        "signature_schemes": apk["signing"]["schemes"],
        "min_sdk": apk.get("minSdk"),
        "target_sdk": apk.get("targetSdk"),
        "debuggable": apk.get("debuggable"),
        "model": {
            "modelVersion": model.get("modelVersion"),
            "backbone": model.get("backbone"),
            "classes": model.get("classes"),
            "headSha256": model.get("headSha256"),
            "onnxSha256": model.get("onnxSha256"),
        } if model else None,
        "dataset": _dataset_ref(db, dataset_archive_id),
        "notes": notes.strip(),
        "download_url": download_links(settings)["apkUrl"],
        "registered_at": now,
        # A baseline was already being served before tracking began; when is not known.
        "original_published_at": None if baseline else now,
        "registered_by": actor_ref,
        "events": [{"at": now, "action": "baseline_registered" if baseline else "published", "actor": actor_ref}],
    }
    if current is not None:
        db[APP_RELEASES].update_one(
            {"id": current["id"]},
            {"$set": {"status": SUPERSEDED}, "$push": {"events": {"at": now, "action": "superseded", "actor": actor_ref}}},
        )
    db[APP_RELEASES].insert_one(dict(record))
    audit.record(
        db, actor, "release.register",
        f"Registered Android {record['version_name']} ({record['version_code']}){' as baseline' if baseline else ''}",
        "app_release", record["id"], {"sha256": record["apk_sha256"], "superseded": current["id"] if current else None},
    )
    return view(record)

"""
Registers a dataset archive that is already on the server (e.g. data-sets.zip transferred
privately) in Mobile Settings: copies it into DATASET_ARCHIVE_DIR/<new id>/, verifies the copy's
SHA-256, validates it exactly like a web upload, and stores the record. Optionally attaches the
per-class split counts from its provenance manifest and marks it as the ACTIVE dataset.

--active only RECORDS which archive the current training dataset came from (after it was
activated with replace_classifier_dataset.py); it does not change any training image. A previous
ACTIVE archive becomes RETIRED (kept for rollback).

Usage (inside the API container on the server, or from backend/ with backend/.env locally):

    python scripts/register_dataset_archive.py --archive /data/data-sets.zip \
        --expect-sha256 82775506... --manifest dataset_manifest_final.csv --active \
        --activated-at 2026-10-20T08:15:18Z --note "v3 dataset"     # dry run (validates, writes nothing)
    ... --confirm                                                   # writes
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pymongo.database import Database  # noqa: E402

from app.database.mongo import DATASET_ARCHIVES  # noqa: E402
from app.services import audit, dataset_archive, mobile_profile  # noqa: E402

ACTOR = {"email": "register_dataset_archive.py", "role": "SYSTEM"}


class RegisterError(ValueError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def register(db: Database, archive_dir: Path, archive: Path, expect_sha256: str | None, manifest: Path | None,
             active: bool, activated_at: datetime | None, note: str, confirm: bool = True) -> dict[str, Any]:
    sha = _sha256(archive)
    if expect_sha256 and sha != expect_sha256.lower():
        raise RegisterError(f"SHA-256 {sha} does not match the expected {expect_sha256}.")
    if db[DATASET_ARCHIVES].find_one({"sha256": sha, "status": {"$ne": dataset_archive.DISCARDED}}):
        raise RegisterError("An archive with this SHA-256 is already registered.")
    summary, rows = dataset_archive.validate_archive(archive, dataset_archive.active_dataset_hashes(db))
    split = mobile_profile.archive_split_from_manifest(manifest) if manifest else None
    if split is not None:
        split["manifestSha256"] = _sha256(manifest)
    if not confirm:
        return {"sha256": sha, "summary": summary, "split": split, "written": False}

    archive_id = uuid.uuid4().hex
    folder = dataset_archive.archive_root(archive_dir) / archive_id
    folder.mkdir()
    shutil.copyfile(archive, folder / "archive.zip")
    if _sha256(folder / "archive.zip") != sha:
        shutil.rmtree(folder, ignore_errors=True)
        raise RegisterError("The copied archive's SHA-256 differs from the source; nothing was registered.")
    dataset_archive.write_manifest(rows, folder / "manifest.csv")
    (folder / "report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    if manifest:
        shutil.copyfile(manifest, folder / "provenance_manifest.csv")

    record = dataset_archive.new_record(archive_id, archive.name, archive.stat().st_size, sha, ACTOR, "server_file")
    record.update(
        status=dataset_archive.ACTIVE if active else dataset_archive.READY,
        summary=summary, validated_at=datetime.now(timezone.utc), split=split,
        activation={"activatedAt": activated_at, "method": "replace_classifier_dataset.py", "note": note} if active else None,
    )
    if active:
        db[DATASET_ARCHIVES].update_many({"status": dataset_archive.ACTIVE}, {"$set": {"status": dataset_archive.RETIRED}})
    db[DATASET_ARCHIVES].insert_one(dict(record))
    audit.record(db, ACTOR, "dataset_archive.register", f"Registered dataset archive {archive.name}{' as active' if active else ''}",
                 "dataset_archive", archive_id, {"sha256": sha})
    return {"id": archive_id, "sha256": sha, "summary": summary, "split": split, "written": True}


def main() -> int:
    parser = argparse.ArgumentParser(description="Register an on-server dataset archive for Mobile Settings.")
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--expect-sha256")
    parser.add_argument("--manifest", type=Path, help="provenance manifest with canonical_label and split columns")
    parser.add_argument("--active", action="store_true", help="record it as the archive the active training dataset came from")
    parser.add_argument("--activated-at", help="ISO time the dataset was activated (omit if unknown)")
    parser.add_argument("--note", default="")
    parser.add_argument("--confirm", action="store_true", help="required: actually copy and write")
    args = parser.parse_args()

    from app.config import load_settings
    from app.database.mongo import create_client

    settings = load_settings()
    if settings.dataset_archive_dir is None:
        print("Refused: DATASET_ARCHIVE_DIR is not configured.")
        return 2
    db = create_client(settings.mongo_uri)[settings.mongo_database]
    activated_at = datetime.fromisoformat(args.activated_at.replace("Z", "+00:00")) if args.activated_at else None
    try:
        result = register(db, settings.dataset_archive_dir, args.archive, args.expect_sha256, args.manifest,
                          args.active, activated_at, args.note, args.confirm)
    except (RegisterError, dataset_archive.ArchiveRejected) as error:
        print(f"Refused: {error}")
        return 2
    print(json.dumps(result, indent=2, default=str))
    if not args.confirm:
        print("Dry run: re-run with --confirm to copy and write.")
    print(f"(database '{settings.mongo_database}')")
    return 0


if __name__ == "__main__":
    sys.exit(main())

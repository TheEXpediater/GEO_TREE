"""Replaces the active classifier TRAINING dataset with the images listed in a provenance manifest.

Only classifier training images and what depends on them (their files, thumbnails, cached DINOv2
features) are replaced. Trees, accounts, leaf assessments and their photos are never touched.
Everything goes through the service's own operations: classes are renamed in place (ids, and so
existing model/assessment references, are kept), old images are removed with ``delete_image``
(record + file + thumbnail + cached feature) and new ones are added with ``add_images``
(content validation, HEIC normalization, exact-duplicate check, DINOv2 feature caching).

Manifest (CSV): zip_entry, canonical_label, split, sha256, stored_sha256, group_id, source_part,
original_label. Only rows with ``split == train_val`` are imported; ``test`` rows stay out of the
active dataset as the locked held-out set, ``excluded`` rows are skipped. Each imported record gets
``split_group`` (training keeps a group on one side of the validation split) and provenance fields.

Run inside the AI backend image, next to MongoDB, with the AI volume mounted read-write. Take a
backup first (README "Replacing the classifier dataset"). Without --confirm nothing is written.

    python scripts/replace_classifier_dataset.py --manifest /manifests/dataset_manifest.csv \
        --root /data --rename "Mold=Powdery Mildew" --confirm [--retrain]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import logging
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.ai.service import AiService, slugify  # noqa: E402
from app.repositories import ai_repository, classifier_repository  # noqa: E402
from app.services import audit  # noqa: E402

logger = logging.getLogger("replace_classifier_dataset")
BATCH = 50  # AiSettings.max_files_per_upload


class ReplacementError(RuntimeError):
    pass


def load_manifest(path: Path) -> list[dict[str, str]]:
    rows = list(csv.DictReader(path.open(encoding="utf-8")))
    required = {"zip_entry", "canonical_label", "split", "sha256", "stored_sha256", "group_id", "source_part", "original_label"}
    missing = required - set(rows[0] if rows else {})
    if missing:
        raise ReplacementError(f"Manifest lacks columns: {sorted(missing)}")
    return rows


def plan(rows: list[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    """Canonical class name -> manifest rows to import (train_val only)."""
    by_class: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        if row["split"] == "train_val":
            by_class[row["canonical_label"]].append(row)
    shas = Counter(r["sha256"] for rs in by_class.values() for r in rs)
    if any(n > 1 for n in shas.values()):
        raise ReplacementError("The manifest imports the same file twice; resolve duplicates first.")
    return dict(by_class)


def replace_dataset(service: AiService, rows: list[dict[str, str]], root: Path, renames: dict[str, str], actor_note: str) -> dict[str, Any]:
    db = service.db
    to_import = plan(rows)
    if ai_repository.find_job_with_status(db, "TRAINING"):
        raise ReplacementError("A training job is running; wait for it to finish.")

    # 1. Classes: rename in place (keeps ids), then require exactly the canonical set.
    renamed = []
    for old, new in renames.items():
        holder = classifier_repository.find_class_by_slug(db, slugify(old))
        if holder is not None:
            service.rename_class(holder["id"], new)
            renamed.append({"classId": holder["id"], "from": holder["name"], "to": new})
    classes = {c["name"]: c for c in classifier_repository.list_classes(db)}
    for name in to_import:
        if name not in classes:
            classes[name] = classifier_repository.find_class(db, service.create_class(name)["id"])
    unexpected = sorted(set(classes) - set(to_import))
    if unexpected:
        raise ReplacementError(f"Classes not in the new dataset exist: {unexpected}. Resolve them explicitly first.")

    # 2. Remove every old training image (record, file, thumbnail, cached feature).
    old_images = list(db[classifier_repository.CLASSIFIER_IMAGES].find({}, {"_id": False}))
    for image in old_images:
        if image.get("active", True):
            service.delete_image(image["class_id"], image["id"])
        else:  # inactive leftovers would otherwise survive
            classifier_repository.delete_image(db, image["id"])
            service._delete_image_files(image)  # noqa: SLF001 - same cleanup as delete_image
    if db[classifier_repository.CLASSIFIER_IMAGES].count_documents({}):
        raise ReplacementError("Old classifier images are still present after removal.")

    # 3. Import the new images per class, in upload-sized batches.
    rejected: list[dict[str, Any]] = []
    imported: dict[str, int] = {}
    for name, items in sorted(to_import.items()):
        class_id = classes[name]["id"]
        for start in range(0, len(items), BATCH):
            chunk = items[start:start + BATCH]
            result = service.add_images(class_id, [(Path(r["zip_entry"]).name, (root / r["zip_entry"]).read_bytes()) for r in chunk])
            rejected += result["rejected"]
            for row in chunk:
                record = classifier_repository.find_image_by_hash(db, row["sha256"])
                if record is None:
                    continue
                classifier_repository.update_image(db, record["id"], {
                    "split_group": row["group_id"],
                    "source_part": row["source_part"],
                    "source_label": row["original_label"],
                    "source_entry": row["zip_entry"],
                })
        imported[name] = sum(1 for r in items if classifier_repository.find_image_by_hash(db, r["sha256"]))
    if rejected:
        raise ReplacementError(f"{len(rejected)} image(s) were rejected: {rejected[:5]}")

    report = verify(service, to_import, classes)
    report.update(renamedClasses=renamed, removedOldImages=len(old_images), imported=imported)
    audit.record(db, None, "dataset.replaced", f"Classifier training dataset replaced ({sum(imported.values())} images)",
                 "classifier_dataset", "active", {"imported": imported, "removed": len(old_images), "note": actor_note})
    return report


def verify(service: AiService, to_import: dict[str, list[dict[str, str]]], classes: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """Every record resolves to its manifest file, label, checksum and cached feature; nothing else remains."""
    db, storage = service.db, service.storage
    expected = {r["sha256"]: (name, r) for name, rows in to_import.items() for r in rows}
    problems: list[str] = []
    records = list(db[classifier_repository.CLASSIFIER_IMAGES].find({}, {"_id": False}))
    per_class: Counter = Counter()
    referenced: set[str] = set()
    for record in records:
        entry = expected.get(record["sha256"])
        if entry is None:
            problems.append(f"{record['id']}: not in the manifest (old or foreign image)")
            continue
        name, row = entry
        per_class[name] += 1
        if record["class_id"] != classes[name]["id"]:
            problems.append(f"{record['id']}: class {record['class_id']} != {name}")
        path = storage.resolve(record["stored_path"])
        referenced.add(path.as_posix())
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != row["stored_sha256"]:
            problems.append(f"{record['id']}: stored file missing or checksum differs")
        if not service._embedding_usable(record):  # noqa: SLF001
            problems.append(f"{record['id']}: no usable cached feature for {service.extractor.cache_key}")
        if not storage.thumbnail_path(record["id"]).is_file():
            problems.append(f"{record['id']}: thumbnail missing")
        if record.get("split_group") != row["group_id"]:
            problems.append(f"{record['id']}: split_group not recorded")
    on_disk = {p.as_posix() for p in (storage.root / "classifier_images").rglob("*") if p.is_file()}
    orphans = sorted(on_disk - referenced)
    stale_features = sorted(p.name for p in (storage.root / "embeddings").glob("*.npy") if p.stem not in {r["id"] for r in records})
    if orphans:
        problems.append(f"{len(orphans)} classifier image file(s) on disk without a record")
    if stale_features:
        problems.append(f"{len(stale_features)} cached feature file(s) without a record")
    for name, rows in to_import.items():
        if per_class[name] != len(rows):
            problems.append(f"{name}: {per_class[name]} records, manifest expects {len(rows)}")
    return {"records": len(records), "perClass": dict(per_class), "orphanFiles": orphans[:10], "staleFeatures": stale_features[:10],
            "cacheKey": service.extractor.cache_key, "problems": problems, "ok": not problems}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True, help="folder the manifest's zip_entry paths are relative to")
    parser.add_argument("--rename", action="append", default=[], help='"Old=New" class rename (keeps the class id)')
    parser.add_argument("--confirm", action="store_true", help="actually replace the dataset")
    parser.add_argument("--retrain", action="store_true", help="queue one manual training job when done")
    parser.add_argument("--report", type=Path, help="write the JSON report here")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    rows = load_manifest(args.manifest)
    to_import = plan(rows)
    print("will import:", {k: len(v) for k, v in sorted(to_import.items())}, "| held out (test):",
          sum(1 for r in rows if r["split"] == "test"), "| excluded:", sum(1 for r in rows if r["split"] == "excluded"))
    if not args.confirm:
        print("Dry run: pass --confirm to replace the dataset.")
        return 0

    from pymongo import MongoClient

    from app.ai.config import load_ai_settings
    from app.ai.features import Dinov2FeatureExtractor

    settings = load_ai_settings()
    db = MongoClient(os.environ.get("MONGO_URI", "mongodb://localhost:27018"))[os.environ.get("MONGO_DATABASE", "geo_tree")]
    service = AiService(db, settings, Dinov2FeatureExtractor(settings), 10 * 1024 * 1024)  # not started: no worker here
    service.storage.prepare()
    renames = dict(item.split("=", 1) for item in args.rename)
    report = replace_dataset(service, rows, args.root, renames, f"manifest {args.manifest.name}")
    if args.retrain and report["ok"]:
        report["job"] = service.retrain()  # MANUAL: the running backend's worker trains it now
    text = json.dumps(report, indent=2, default=str)
    print(text)
    if args.report:
        args.report.write_text(text, encoding="utf-8")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())

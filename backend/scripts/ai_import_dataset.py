"""
Bulk-adds labeled TRAINING images to the classifier dataset through the LOCAL admin API
(the same validation, duplicate check and DINOv2 feature caching as uploads from Android).

    <folder>/
      Healthy/  *.jpg|png|webp|heic
      Spotting/
      Mold/

Usage (from the repository root):

    backend\\.venv\\Scripts\\python.exe backend\\scripts\\ai_import_dataset.py <folder>
    ... --holdout-fraction 0.25   # first copy a deterministic per-class share to ai_test_input/
                                  # as held-out TEST images; only the rest is uploaded
    ... --create-missing          # create classes for folders without a matching class

Folder names are matched to class names case-insensitively. Each upload queues the coalesced
classifier-head rebuild automatically. The source folder is never modified.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from local_api import DEFAULT_BASE_URL, REPO_ROOT, LocalApi, LocalApiError, discover_images, label_key  # noqa: E402

DEFAULT_HOLDOUT_DIR = REPO_ROOT / "ai_test_input"


def split_holdout(images: list[tuple[Path, str]], fraction: float, seed: int = 42) -> tuple[list[tuple[Path, str]], list[tuple[Path, str]]]:
    """Per class, a deterministic share (ordered by a seeded hash of the file content) is held out."""
    if fraction <= 0:
        return images, []
    keep, holdout = [], []
    for label in sorted({label for _, label in images}):
        members = [item for item in images if item[1] == label]
        members.sort(key=lambda item: hashlib.sha256(str(seed).encode() + item[0].read_bytes()).hexdigest())
        count = min(max(1, round(len(members) * fraction)), len(members) - 1)
        holdout += members[:count]
        keep += members[count:]
    return keep, holdout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Upload a labeled folder of training images to the LOCAL GEO Tree classifier.")
    parser.add_argument("folder", type=Path)
    parser.add_argument("--base-url", default=os.environ.get("GEO_TREE_LOCAL_API", DEFAULT_BASE_URL))
    parser.add_argument("--email", default=os.environ.get("GEO_TREE_EMAIL", "admin@gmail.com"))
    parser.add_argument("--password", default=os.environ.get("GEO_TREE_PASSWORD", "admin123"))
    parser.add_argument("--create-missing", action="store_true")
    parser.add_argument("--holdout-fraction", type=float, default=0.0)
    parser.add_argument("--holdout-dir", type=Path, default=DEFAULT_HOLDOUT_DIR)
    args = parser.parse_args(argv)

    source = args.folder.resolve()
    holdout_dir = args.holdout_dir.resolve()
    if source.is_relative_to(holdout_dir) or holdout_dir.is_relative_to(source):
        print("Refusing: test images (ai_test_input) must never become training data.")
        return 2
    images = [(path, label) for path, label in discover_images(source) if label]
    if not images:
        print(f"No labeled images under {source} (expected <folder>/<class>/<image>).")
        return 2

    api = LocalApi(args.base_url)
    try:
        api.check_health()
        api.login(args.email, args.password)
        classes = {label_key(c["name"]): c for c in api.get("/api/v1/admin/classifiers")["items"]}
    except LocalApiError as exc:
        print(f"ERROR: {exc}")
        return 1

    train, holdout = split_holdout(images, args.holdout_fraction)
    for path, label in holdout:
        target = holdout_dir / label.lower() / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    if holdout:
        print(f"Held out {len(holdout)} test image(s) in {holdout_dir} (not uploaded).")

    uploaded = rejected = 0
    for index, (path, label) in enumerate(train, start=1):
        target = classes.get(label_key(label))
        if target is None:
            if not args.create_missing:
                print(f"  skip {path.name}: no class matches folder '{label}' (use --create-missing)")
                continue
            target = api.post_json("/api/v1/admin/classifiers", {"name": label})
            classes[label_key(label)] = target
        try:
            api.post_file(f"/api/v1/admin/classifiers/{target['id']}/images", "files", path)
            uploaded += 1
            print(f"  [{index}/{len(train)}] {target['name']}: {path.name}")
        except LocalApiError as exc:
            rejected += 1
            print(f"  [{index}/{len(train)}] {target['name']}: {path.name} REJECTED {exc.code} {exc.message}")

    status = api.get("/api/v1/admin/ai/model")
    print(f"Uploaded {uploaded}, rejected {rejected}. Model status: {status['status']}"
          + (f" (job {status['currentJob']['jobId']})" if status.get("currentJob") else ""))
    for problem in status["readiness"]["problems"]:
        print(f"  not ready: {problem}")
    return 0 if uploaded and not rejected else 1


if __name__ == "__main__":
    sys.exit(main())

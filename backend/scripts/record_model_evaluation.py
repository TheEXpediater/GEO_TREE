"""
Records a classifier evaluation report (written by evaluate_classifier_holdout.py) so Mobile
Settings can show it next to the model it measured, with its evaluation type, sample count and
limitations. Values are copied from the report file, never typed in; the file's SHA-256 is stored.

Adds a "locked_test" record and, when the report has one, a "leave_one_source_out" record.
Refuses to record the same report file twice for the same model version.

Usage (inside the API container on the server, or from backend/ with backend/.env locally):

    python scripts/record_model_evaluation.py --report evaluation_v3.json \
        --head-sha256 4ddbdc65... --evaluated-at 2026-10-20T08:40:00Z \
        --limitations "Test photos come from the same sessions as training; may be optimistic."   # dry run
    ... --confirm                                                                                   # writes
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pymongo.database import Database  # noqa: E402

from app.database.mongo import MODEL_EVALUATIONS  # noqa: E402
from app.services import audit, mobile_profile  # noqa: E402


class RecordError(ValueError):
    pass


def record(db: Database, report_path: Path, limitations: str, head_sha256: str | None, evaluated_at: datetime | None,
           confirm: bool = True) -> list[dict[str, Any]]:
    data = report_path.read_bytes()
    report = json.loads(data)
    if "model" not in report or "test" not in report:
        raise RecordError("Not an evaluate_classifier_holdout.py report (needs 'model' and 'test').")
    sha = hashlib.sha256(data).hexdigest()
    version = report["model"]["version"]
    if db[MODEL_EVALUATIONS].find_one({"source_sha256": sha, "model_version": version}):
        raise RecordError(f"This report is already recorded for model v{version}.")
    records = mobile_profile.evaluation_records(report, report_path.name, sha, head_sha256, evaluated_at, limitations)
    if confirm:
        db[MODEL_EVALUATIONS].insert_many([dict(r) for r in records])
        audit.record(db, {"email": "record_model_evaluation.py", "role": "SYSTEM"}, "model.evaluation_recorded",
                     f"Recorded {len(records)} evaluation(s) for model v{version}", "ai_model", str(version), {"sourceSha256": sha})
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description="Record a classifier evaluation report for Mobile Settings.")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--limitations", required=True, help="honest limitations shown with the metrics")
    parser.add_argument("--head-sha256", help="SHA-256 of the classifier_head.json the evaluation applies to")
    parser.add_argument("--evaluated-at", help="ISO time the evaluation ran (omit if unknown)")
    parser.add_argument("--confirm", action="store_true", help="required: actually write to the configured database")
    args = parser.parse_args()

    from app.config import load_settings
    from app.database.mongo import create_client

    settings = load_settings()
    db = create_client(settings.mongo_uri)[settings.mongo_database]
    evaluated_at = datetime.fromisoformat(args.evaluated_at.replace("Z", "+00:00")) if args.evaluated_at else None
    try:
        records = record(db, args.report, args.limitations, args.head_sha256, evaluated_at, args.confirm)
    except RecordError as error:
        print(f"Refused: {error}")
        return 2
    for r in records:
        print(f"{'Recorded' if args.confirm else 'Would record'}: model v{r['model_version']} {r['evaluation_type']} "
              f"samples={r['samples']} accuracy={r['accuracy']}")
    if not args.confirm:
        print("Dry run: re-run with --confirm to write.")
    print(f"(database '{settings.mongo_database}')")
    return 0


if __name__ == "__main__":
    sys.exit(main())

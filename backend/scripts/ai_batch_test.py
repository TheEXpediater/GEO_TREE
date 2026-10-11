"""
Local batch evaluation of the tamarind leaf classifier through the real recognition API.

Put labeled TEST images (never training images) in the repository root:

    ai_test_input/
      healthy/   *.jpg|png|webp|heic
      spotting/
      mold/

then run (Windows, from the repository root):

    ai_batch_test.bat                  # evaluate and write a report
    ai_batch_test.bat --cleanup-input  # ...and delete ai_test_input/ only if everything succeeded

Each image is sent to POST /api/v1/ai/leaf-assessment on the LOCAL backend (the same endpoint
Android uses). The folder name is the expected class (matched case-insensitively to the class
names). Results go to ai_test_results/<timestamp>/ (report.md, report.json, predictions.csv).

The script is read-only for the server: it never uploads to or changes the classifier dataset.
It never deletes backend/ai_data, Docker volumes, MongoDB data or model artifacts.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import sys
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))  # backend/, for app.ai.metrics
sys.path.insert(0, str(Path(__file__).resolve().parent))

from local_api import DEFAULT_BASE_URL, REPO_ROOT, LocalApi, LocalApiError, discover_images, label_key  # noqa: E402

from app.ai.metrics import classification_metrics  # noqa: E402

DEFAULT_INPUT = REPO_ROOT / "ai_test_input"
DEFAULT_RESULTS = REPO_ROOT / "ai_test_results"

Assess = Callable[[Path], dict[str, Any]]


def evaluate(images: list[tuple[Path, str | None]], assess: Assess, class_names: list[str], input_root: Path) -> dict[str, Any]:
    by_key = {label_key(name): name for name in class_names}
    rows: list[dict[str, Any]] = []
    for index, (path, folder) in enumerate(images, start=1):
        expected = by_key.get(label_key(folder), folder) if folder else None
        row: dict[str, Any] = {"file": path.relative_to(input_root).as_posix(), "expected": expected}
        started = time.perf_counter()
        try:
            result = assess(path)
            row.update(
                predicted=result["className"],
                confidence=round(float(result["confidence"]), 4),
                uncertain=bool(result.get("uncertain", False)),
                model=result.get("model"),
                modelVersion=result.get("modelVersion"),
                correct=expected is not None and result["className"] == expected,
                error=None,
            )
        except LocalApiError as exc:
            row.update(predicted=None, confidence=None, uncertain=None, model=None, modelVersion=None, correct=False, error=f"{exc.code}: {exc.message}")
        row["latencyMs"] = round((time.perf_counter() - started) * 1000)
        rows.append(row)
        status = row["predicted"] or row["error"]
        print(f"  [{index}/{len(images)}] {row['file']} -> {status}" + (f" ({row['confidence']:.2f})" if row["confidence"] is not None else ""))

    scored = [r for r in rows if r["predicted"] is not None and r["expected"] is not None]
    labels = [name for name in class_names if any(r["expected"] == name or r["predicted"] == name for r in scored)]
    metrics = classification_metrics([r["expected"] for r in scored], [r["predicted"] for r in scored], labels=labels) if scored else None
    versions = sorted({r["modelVersion"] for r in rows if r["modelVersion"] is not None})
    errors = [r for r in rows if r["error"]]
    unlabeled = [r for r in rows if r["expected"] is None]
    unknown_labels = sorted({r["expected"] for r in rows if r["expected"] is not None and r["expected"] not in class_names})
    return {
        "rows": rows,
        "metrics": metrics,
        "summary": {
            "images": len(rows),
            "assessed": sum(1 for r in rows if r["predicted"] is not None),
            "errors": len(errors),
            "unlabeled": len(unlabeled),
            "uncertain": sum(1 for r in rows if r["uncertain"]),
            "correct": sum(1 for r in rows if r["correct"]),
            "modelVersions": versions,
            "unknownExpectedLabels": unknown_labels,
            "expectedCounts": {name: sum(1 for r in rows if r["expected"] == name) for name in sorted({r["expected"] for r in rows if r["expected"]})},
        },
        # Success: every image was assessed by one model version and at least one labeled image was scored.
        "success": bool(scored) and not errors and len(versions) == 1,
    }


def _pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def render_markdown(report: dict[str, Any]) -> str:
    summary, metrics = report["summary"], report["metrics"]
    lines = [
        "# GEO Tree - local leaf classifier batch test",
        "",
        f"- Run: {report['startedAt']} to {report['finishedAt']}",
        f"- Server: {report['server']} (local Docker backend)",
        f"- Model: {report['modelDisplayName']} - v{', v'.join(map(str, summary['modelVersions'])) or '?'}",
        f"- Images: {summary['images']}, assessed {summary['assessed']}, errors {summary['errors']}, uncertain {summary['uncertain']} (below {report['confidenceThreshold']:.0%})",
        f"- Expected per class: {', '.join(f'{k} {v}' for k, v in summary['expectedCounts'].items()) or '-'}",
        f"- Result: {'SUCCESS' if report['success'] else 'INCOMPLETE - see errors'}",
        "",
    ]
    if metrics:
        lines += [
            "## Metrics (held-out test images)",
            "",
            "| Accuracy | Macro precision | Macro recall | Macro F1 |",
            "|---|---|---|---|",
            f"| {_pct(metrics['accuracy'])} | {_pct(metrics['macroPrecision'])} | {_pct(metrics['macroRecall'])} | {_pct(metrics['macroF1'])} |",
            "",
            "| Class | Precision | Recall | F1 | Support |",
            "|---|---|---|---|---|",
        ]
        lines += [f"| {r['label']} | {_pct(r['precision'])} | {_pct(r['recall'])} | {_pct(r['f1'])} | {r['support']} |" for r in metrics["perClass"]]
        labels = metrics["confusionMatrix"]["labels"]
        lines += ["", "## Confusion matrix (rows = expected, columns = predicted)", "", "| | " + " | ".join(labels) + " |", "|---" * (len(labels) + 1) + "|"]
        lines += [f"| **{label}** | " + " | ".join(map(str, row)) + " |" for label, row in zip(labels, metrics["confusionMatrix"]["matrix"])]
    lines += ["", "## Predictions", "", "| File | Expected | Predicted | Confidence | Note |", "|---|---|---|---|---|"]
    for row in report["rows"]:
        note = row["error"] or ("uncertain" if row["uncertain"] else ("" if row["correct"] else "wrong"))
        confidence = f"{row['confidence']:.2f}" if row["confidence"] is not None else "-"
        lines.append(f"| {row['file']} | {row['expected'] or '-'} | {row['predicted'] or '-'} | {confidence} | {note} |")
    lines += ["", "Metrics describe only these images. They are not a general accuracy claim for DINOv2 or for other field conditions.", ""]
    return "\n".join(lines)


def write_report(report: dict[str, Any], results_root: Path) -> Path:
    """Writes report.json, report.md and predictions.csv, then reads them back to verify."""
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    directory = results_root / stamp
    suffix = 1
    while directory.exists():
        directory = results_root / f"{stamp}-{suffix}"
        suffix += 1
    directory.mkdir(parents=True)
    (directory / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (directory / "report.md").write_text(render_markdown(report), encoding="utf-8")
    with open(directory / "predictions.csv", "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["file", "expected", "predicted", "confidence", "uncertain", "correct", "model", "modelVersion", "latencyMs", "error"])
        writer.writeheader()
        writer.writerows(report["rows"])
    verified = json.loads((directory / "report.json").read_text(encoding="utf-8"))
    with open(directory / "predictions.csv", encoding="utf-8") as handle:
        csv_rows = sum(1 for _ in csv.DictReader(handle))
    if len(verified["rows"]) != len(report["rows"]) or csv_rows != len(report["rows"]) or not (directory / "report.md").stat().st_size:
        raise RuntimeError(f"Report verification failed in {directory}")
    return directory


def cleanup_input(input_root: Path, results_dir: Path, success: bool) -> bool:
    """Deletes the temporary input folder, and nothing else, only after a successful saved run."""
    input_root = input_root.resolve()
    if not success:
        print("Keeping the input folder: the run was not fully successful.")
        return False
    if input_root.name != "ai_test_input":
        print(f"Keeping {input_root}: only a folder named ai_test_input is ever deleted.")
        return False
    if results_dir.resolve().is_relative_to(input_root):
        print("Keeping the input folder: the report is inside it.")
        return False
    if not (results_dir / "report.json").is_file():
        print("Keeping the input folder: the saved report could not be found.")
        return False
    shutil.rmtree(input_root)
    print(f"Deleted temporary test input {input_root}")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate held-out leaf images against the LOCAL GEO Tree AI API.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--base-url", default=os.environ.get("GEO_TREE_LOCAL_API", DEFAULT_BASE_URL))
    parser.add_argument("--email", default=os.environ.get("GEO_TREE_EMAIL", "admin@gmail.com"))
    parser.add_argument("--password", default=os.environ.get("GEO_TREE_PASSWORD", "admin123"))
    parser.add_argument("--cleanup-input", action="store_true", help="delete the input folder after a fully successful, saved run")
    args = parser.parse_args(argv)
    # Windows consoles may not be UTF-8; never let printing break a run after the report is saved.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

    input_root = args.input.resolve()
    if not input_root.is_dir():
        print(f"No input folder at {input_root}. Create ai_test_input/<class>/ and add test images.")
        return 2
    images = discover_images(input_root)
    if not images:
        print(f"No supported images under {input_root}.")
        return 2

    api = LocalApi(args.base_url)
    try:
        api.check_health()
        api.login(args.email, args.password)
        status = api.get("/api/v1/ai/status")
    except LocalApiError as exc:
        print(f"ERROR: {exc}")
        return 1
    if not status.get("ready"):
        print("ERROR: no trained model is active on the local backend (AI_MODEL_NOT_READY). Train the classifier first.")
        return 1

    print(f"Evaluating {len(images)} image(s) with {status['modelDisplayName']} v{status['modelVersion']} at {api.base_url}")
    started = datetime.now(timezone.utc).isoformat()
    result = evaluate(images, lambda path: api.post_file("/api/v1/ai/leaf-assessment", "file", path), status["classes"], input_root)
    report = {
        "startedAt": started,
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "server": api.base_url,
        "inputFolder": str(input_root),
        "modelDisplayName": status["modelDisplayName"],
        "confidenceThreshold": status["confidenceThreshold"],
        "classes": status["classes"],
        **result,
    }
    results_dir = write_report(report, args.results.resolve())
    print()
    print(render_markdown(report).split("## Predictions")[0])
    print(f"Report saved to {results_dir}")
    if args.cleanup_input:
        cleanup_input(input_root, results_dir, report["success"])
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())

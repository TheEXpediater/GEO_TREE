"""Held-out evaluation of a trained classifier head (NumPy/scikit-learn only; no PyTorch).

Scores the exact model artifact (``models/model_v<N>.npz``) on DINOv2 features of images that were
never in its training set, using the backend's own metrics:

    python scripts/evaluate_classifier_holdout.py --manifest dataset_manifest.csv --features features.npz \
        --model-json model_v3.json --artifact model_v3.npz [--baseline old_model_v2.npz] [--loso] --out report.json

``--features``: npz with ``entries`` (manifest zip_entry), ``features`` (N x 384) and ``cache_key``, computed
with the server's extractor (it must equal the model's ``embedding_model``). Manifest columns used:
zip_entry, canonical_label, split (``test`` = locked held-out), group_id, source_part, in_old_dataset.

``--loso`` adds a leave-one-source-out check: for each source part, a head is fitted (same procedure,
group-aware) on the other parts and scored on the held-out part. It does not select the deployed model;
it shows how well the features transfer to a new capture session.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np

BACKEND_ROOT = Path(__file__).resolve().parent.parent
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.ai.head import LinearHead, fit_head  # noqa: E402
from app.ai.metrics import classification_metrics  # noqa: E402


def load_features(path: Path) -> tuple[dict[str, np.ndarray], str]:
    data = np.load(path, allow_pickle=False)
    return {str(e): v for e, v in zip(data["entries"], data["features"])}, str(data["cache_key"])


def score(head: LinearHead, features: np.ndarray, expected: list[str], names: dict[str, str], threshold: float) -> dict:
    probabilities = head.predict_proba(features)
    predicted = [head.class_ids[i] for i in probabilities.argmax(axis=1)]
    metrics = classification_metrics(expected, predicted, labels=head.class_ids)
    confidence = probabilities.max(axis=1)
    confident = confidence >= threshold
    named = {
        "samples": metrics["samples"],
        "accuracy": metrics["accuracy"],
        "balancedAccuracy": metrics["macroRecall"],
        "macroPrecision": metrics["macroPrecision"],
        "macroRecall": metrics["macroRecall"],
        "macroF1": metrics["macroF1"],
        "perClass": [{**r, "label": names[r["label"]]} for r in metrics["perClass"]],
        "confusionMatrix": {"labels": [names[x] for x in metrics["confusionMatrix"]["labels"]],
                            "rowsExpected_colsPredicted": metrics["confusionMatrix"]["matrix"]},
        "correct": int(sum(p == e for p, e in zip(predicted, expected))),
        "belowThreshold": int((~confident).sum()),
        "accuracyAboveThreshold": float(np.mean([p == e for p, e, c in zip(predicted, expected, confident) if c])) if confident.any() else None,
    }
    return {"metrics": named, "predicted": predicted, "confidence": confidence.tolist()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument("--model-json", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, help="an earlier model artifact (same class ids) to compare on the same images")
    parser.add_argument("--threshold", type=float, default=0.6, help="the app's 'uncertain' threshold")
    parser.add_argument("--loso", action="store_true")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    meta = json.loads(args.model_json.read_text(encoding="utf-8"))
    features, cache_key = load_features(args.features)
    if cache_key != meta["embedding_model"]:
        raise SystemExit(f"Features were computed for {cache_key!r}, the model for {meta['embedding_model']!r}.")
    head = LinearHead.load(args.artifact)
    names = {e["class_id"]: e["name"] for e in meta["class_mapping"]}
    by_name = {v: k for k, v in names.items()}
    rows = [r for r in csv.DictReader(args.manifest.open(encoding="utf-8")) if r["split"] in ("train_val", "test")]
    test = [r for r in rows if r["split"] == "test"]
    X = np.stack([features[r["zip_entry"]] for r in test])
    y = [by_name[r["canonical_label"]] for r in test]

    result = score(head, X, y, names, args.threshold)
    report: dict = {
        "model": {"version": meta["model_version"], "artifact": args.artifact.name, "embeddingModel": meta["embedding_model"],
                  "classOrder": [names[c] for c in head.class_ids], "c": meta["head"]["c"]},
        "testSet": {"images": len(test), "perClass": dict(Counter(r["canonical_label"] for r in test)),
                    "perSource": dict(Counter(f"{r['canonical_label']} / {r['source_part']}" for r in test)), "groups": len({r["group_id"] for r in test})},
        "test": result["metrics"],
        "errors": [{"entry": r["zip_entry"], "expected": r["canonical_label"], "predicted": names[p], "confidence": round(c, 4)}
                   for r, p, c in zip(test, result["predicted"], result["confidence"]) if names[p] != r["canonical_label"]],
    }
    # Background / capture setup breakdown (hand-held over soil vs leaf on paper).
    setups = {"Annah": "hand-held over soil", "Kyla": "hand-held over soil", "part 1": "on paper", "part 2": "on paper"}
    report["testBySetup"] = {}
    for setup in sorted(set(setups.values())):
        idx = [i for i, r in enumerate(test) if setups.get(r["source_part"]) == setup]
        if idx:
            sub = score(head, X[idx], [y[i] for i in idx], names, args.threshold)["metrics"]
            report["testBySetup"][setup] = {k: sub[k] for k in ("samples", "accuracy", "balancedAccuracy", "macroF1", "confusionMatrix")}

    if args.baseline:
        base = LinearHead.load(args.baseline)
        if set(base.class_ids) != set(head.class_ids):
            raise SystemExit("Baseline uses different class ids; it cannot be compared on these labels.")
        report["baseline"] = {"artifact": args.baseline.name, "allTest": score(base, X, y, names, args.threshold)["metrics"]}
        unseen = [i for i, r in enumerate(test) if r.get("in_old_dataset") != "yes"]
        if unseen:
            report["baseline"]["testImagesNotInItsTraining"] = score(base, X[unseen], [y[i] for i in unseen], names, args.threshold)["metrics"]
            report["newModelOnSameUnseenSubset"] = score(head, X[unseen], [y[i] for i in unseen], names, args.threshold)["metrics"]

    if args.loso:
        report["leaveOneSourceOut"] = {}
        for part in sorted({r["source_part"] for r in rows}):
            train = [r for r in rows if r["source_part"] != part]
            held = [r for r in rows if r["source_part"] == part]
            labels = [by_name[r["canonical_label"]] for r in train]
            present = [c for c in head.class_ids if c in labels]
            loso_head = fit_head(np.stack([features[r["zip_entry"]] for r in train]), labels, present, seed=args.seed,
                                 groups=[r["group_id"] for r in train])
            sub = score(loso_head, np.stack([features[r["zip_entry"]] for r in held]), [by_name[r["canonical_label"]] for r in held], names, args.threshold)
            report["leaveOneSourceOut"][part] = {k: sub["metrics"][k] for k in ("samples", "accuracy", "balancedAccuracy", "macroF1", "perClass", "confusionMatrix")}
            report["leaveOneSourceOut"][part]["trainedOn"] = dict(Counter(r["canonical_label"] for r in train))

    args.out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    t = report["test"]
    print(f"model v{meta['model_version']} on {len(test)} locked test images: accuracy {t['accuracy']:.3f} ({t['correct']}/{len(test)}), "
          f"balanced {t['balancedAccuracy']:.3f}, macro-F1 {t['macroF1']:.3f}")
    print("confusion (rows expected, cols predicted)", t["confusionMatrix"]["labels"])
    for label, row in zip(t["confusionMatrix"]["labels"], t["confusionMatrix"]["rowsExpected_colsPredicted"]):
        print(f"  {label:15s} {row}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

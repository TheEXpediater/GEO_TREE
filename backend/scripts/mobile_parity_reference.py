"""Server-side reference for the Android on-device parity check, and the comparison report.

1. Reference (in the AI backend image, PyTorch needed):

    docker run --rm -v geo_tree_ai_data:/app/ai_data:ro -v <repo>/backend:/src:ro -v <photos>:/photos:ro \
        -v <work>:/work -e PYTHONPATH=/src geo_tree-backend:latest \
        python /src/scripts/mobile_parity_reference.py reference --photos /photos \
            --model-json /app/ai_data/models/model_v2.json --work /work

   Each photo goes through the server's own upload validation (HEIC → JPEG exactly as stored), the
   server feature extractor and the server's head artifact. The stored bytes are written to
   <work>/inputs (push these to the phone) and the results to <work>/reference.json.

2. Run android LeafModelParityTest on the phone, pull parity/android_results.json into <work>.

3. Compare (NumPy only):  python mobile_parity_reference.py compare --work <work>

Photos are read-only inputs; <work> holds copies of user images and must not be committed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}


def reference(args: argparse.Namespace) -> None:
    from app.ai.config import load_ai_settings
    from app.ai.features import Dinov2FeatureExtractor, preprocess
    from app.ai.head import LinearHead
    from app.ai.images import load_rgb, validate_image

    settings = load_ai_settings()
    metadata = json.loads(args.model_json.read_text(encoding="utf-8"))
    head = LinearHead.load(settings.data_dir / metadata["artifact_path"])
    extractor = Dinov2FeatureExtractor(settings)
    inputs = args.work / "inputs"
    inputs.mkdir(parents=True, exist_ok=True)
    rows = []
    for path in sorted(p for p in args.photos.rglob("*") if p.suffix.lower() in SUFFIXES):
        label = path.parent.name
        image = validate_image(path.read_bytes(), 50 * 1024 * 1024)
        name = f"{label}__{path.stem}.{image.extension}"
        (inputs / name).write_bytes(image.data)
        upright = load_rgb(image.data)
        tensor = preprocess(upright, settings.image_size)
        feature = extractor.extract([image.data])[0]  # the server's assessment path
        probabilities = head.predict_proba(feature[None])[0]
        rows.append({
            "name": name,
            "label": label,
            "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "width": upright.width,
            "height": upright.height,
            "rgbSha256": hashlib.sha256(np.asarray(upright, dtype=np.uint8).tobytes()).hexdigest(),
            "tensorSha256": hashlib.sha256(np.ascontiguousarray(tensor, dtype="<f4").tobytes()).hexdigest(),
            "features": feature.astype(float).tolist(),
            "probabilities": probabilities.tolist(),
            "predictedClassId": head.class_ids[int(probabilities.argmax())],
        })
    classes = {e["class_id"]: e["name"] for e in metadata["class_mapping"]}
    (args.work / "reference.json").write_text(json.dumps({"classes": classes, "rows": rows}), encoding="utf-8")
    print(f"{len(rows)} reference predictions; inputs in {inputs}")


def compare(args: argparse.Namespace) -> None:
    ref = json.loads((args.work / "reference.json").read_text(encoding="utf-8"))
    android = json.loads((args.work / "android_results.json").read_text(encoding="utf-8"))
    by_name = {row["name"]: row for row in android["rows"]}
    classes = ref["classes"]
    pairs = [(r, by_name[r["name"]]) for r in ref["rows"] if r["name"] in by_name]
    missing = [r["name"] for r in ref["rows"] if r["name"] not in by_name]
    cosines, prob_diffs, conf_diffs, disagreements = [], [], [], []
    same_rgb = same_tensor = 0
    for server, phone in pairs:
        a, b = np.asarray(server["features"]), np.asarray(phone["features"])
        cosines.append(float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b))))
        p, q = np.asarray(server["probabilities"]), np.asarray(phone["probabilities"])
        prob_diffs.append(float(np.abs(p - q).max()))
        conf_diffs.append(abs(float(p.max()) - float(q.max())))
        same_rgb += server["rgbSha256"] == phone["rgbSha256"]
        same_tensor += server["tensorSha256"] == phone["tensorSha256"]
        if server["predictedClassId"] != phone["predictedClassId"]:
            disagreements.append(f"{server['name']}: server {classes[server['predictedClassId']]} {p.max():.3f}, "
                                 f"phone {classes[phone['predictedClassId']]} {q.max():.3f}")
    n = len(pairs)
    timing = {key: [row[key] for row in android["rows"]] for key in ("decodeMs", "preprocessMs", "inferenceMs", "classifyCallMs")}
    report = {
        "device": android["device"], "abi": android["abi"], "sdk": android["sdk"],
        "modelVersion": android["modelVersion"], "headSource": android["headSource"],
        "images": n, "missingOnPhone": missing,
        "classAgreement": f"{n - len(disagreements)}/{n}",
        "identicalDecodedPixels": f"{same_rgb}/{n}",
        "identicalInputTensors": f"{same_tensor}/{n}",
        "featureCosine": {"min": min(cosines), "median": float(np.median(cosines))},
        "maxProbabilityDiff": max(prob_diffs),
        "maxConfidenceDiff": max(conf_diffs),
        "disagreements": disagreements,
        "sessionLoadMs": android["sessionLoadMs"],
        "medianMs": {k: float(np.median(v)) for k, v in timing.items()},
        "maxMs": {k: max(v) for k, v in timing.items()},
        "nativeHeapMbAfter": android["nativeHeapMbAfter"],
        "javaHeapMbAfter": android["javaHeapMbAfter"],
    }
    labelled = [(s, ph) for s, ph in pairs if s["label"] in classes.values()]
    if labelled:
        correct = sum(classes[ph["predictedClassId"]] == s["label"] for s, ph in labelled)
        report["phoneAgreementWithFolderLabel"] = f"{correct}/{len(labelled)} (includes training images: not an accuracy estimate)"
    print(json.dumps(report, indent=2))
    (args.work / "parity_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    ref = sub.add_parser("reference")
    ref.add_argument("--photos", type=Path, required=True)
    ref.add_argument("--model-json", type=Path, required=True)
    ref.add_argument("--work", type=Path, required=True)
    cmp = sub.add_parser("compare")
    cmp.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    reference(args) if args.command == "reference" else compare(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())

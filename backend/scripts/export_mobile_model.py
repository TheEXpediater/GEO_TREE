"""Exports the server's leaf-assessment model for on-device (Android) inference.

    docker run --rm -v geo_tree_ai_data:/app/ai_data:ro -v <repo>/backend:/src:ro -v <out>:/out \
        -e PYTHONPATH=/src geo_tree-backend:latest \
        sh -c "pip install --user -r /src/scripts/requirements-export.txt && \
               python /src/scripts/export_mobile_model.py --model-json /app/ai_data/models/model_v2.json \
                      --model-version 1 --out /out"

Writes three files for ``android/app/src/main/assets/leaf_model/``:

    dinov2_vits14_336.onnx   the frozen DINOv2 backbone, input (1, 3, S, S) → CLS feature (1, 384)
    classifier_head.json     the trained head (arrays + class mapping) from the server's .npz artifact
    manifest.json            preprocessing, compatibility key, checksums and the export's own parity check

The backbone is the exact module the server loads (``Dinov2FeatureExtractor`` from the torch.hub
cache in AI_DATA_DIR, no download). Because the input size is fixed, its interpolated position
embedding is computed once by PyTorch and stored as a constant, so ONNX never re-implements the
bicubic interpolation. The head is copied, never retrained. The export fails unless ONNX Runtime
reproduces the PyTorch features on real dataset images (cosine ≥ 0.9999) and the predicted
classes agree on every image.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

from app.ai.config import load_ai_settings
from app.ai.features import IMAGENET_MEAN, IMAGENET_STD, PREPROCESSING_VERSION, Dinov2FeatureExtractor, preprocess, resize_short_side
from app.ai.head import LinearHead
from app.ai.images import load_rgb
from app.ai.mobile import HEAD_FORMAT_VERSION, head_document

FORMAT_VERSION = HEAD_FORMAT_VERSION
MIN_COSINE = 0.9999


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fixed_size_backbone(model, image_size: int):
    """The DINOv2 module with its position embedding precomputed for one input size."""
    import torch

    with torch.inference_mode():
        probe = torch.zeros(1, 3, image_size, image_size)
        tokens = model.patch_embed(probe)
        tokens = torch.cat((model.cls_token.expand(1, -1, -1), tokens), dim=1)
        pos_embed = model.interpolate_pos_encoding(tokens, image_size, image_size).detach().clone()

    class FixedSize(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.model = model
            self.register_buffer("pos_embed", pos_embed)

        def forward(self, x):
            m = self.model
            x = m.patch_embed(x)
            x = torch.cat((m.cls_token.expand(x.shape[0], -1, -1), x), dim=1) + self.pos_embed
            for block in m.blocks:
                x = block(x)
            return m.norm(x)[:, 0]

    wrapped = FixedSize().eval()
    with torch.inference_mode():
        check = torch.randn(2, 3, image_size, image_size)
        if not torch.allclose(wrapped(check), model(check), atol=1e-5):
            raise SystemExit("The fixed-size wrapper does not reproduce the DINOv2 forward pass.")
    return wrapped


def export_onnx(wrapped, image_size: int, path: Path) -> None:
    import torch

    dummy = torch.zeros(1, 3, image_size, image_size)
    kwargs = dict(input_names=["pixel_values"], output_names=["features"], opset_version=17, do_constant_folding=True)
    try:
        torch.onnx.export(wrapped, (dummy,), str(path), dynamo=False, **kwargs)
    except TypeError:  # an exporter without the dynamo switch
        torch.onnx.export(wrapped, (dummy,), str(path), **kwargs)
    import onnx

    onnx.checker.check_model(str(path))


def dataset_images(data_dir: Path, limit: int) -> list[Path]:
    paths = sorted(p for p in (data_dir / "classifier_images").rglob("*") if p.suffix.lower() in {".jpg", ".png", ".webp"})
    return paths[:limit] if limit else paths


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-json", type=Path, required=True, help="the server's models/model_v<N>.json")
    parser.add_argument("--model-version", type=int, help="version recorded on assessments (default: from the JSON)")
    parser.add_argument("--artifact", type=Path, help="head .npz (default: <AI_DATA_DIR>/<artifact_path from the JSON>); "
                        "e.g. a read-only copy of the production server's active artifact")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--verify-images", type=int, default=0, help="dataset images to verify (0 = all)")
    args = parser.parse_args()

    import onnxruntime as ort
    import torch

    settings = load_ai_settings()
    metadata = json.loads(args.model_json.read_text(encoding="utf-8"))
    artifact = args.artifact or settings.data_dir / metadata["artifact_path"]
    extractor = Dinov2FeatureExtractor(settings)
    if metadata["embedding_model"] != extractor.cache_key:
        raise SystemExit(f"Model was trained for {metadata['embedding_model']!r}, backbone is {extractor.cache_key!r}.")
    head = LinearHead.load(artifact)
    mapping = sorted(metadata["class_mapping"], key=lambda entry: entry["index"])
    if [entry["class_id"] for entry in mapping] != head.class_ids:
        raise SystemExit("The class mapping in the JSON does not match the artifact's class order.")

    extractor.load()
    model = extractor._model  # the exact module the server runs
    size = settings.image_size
    args.out.mkdir(parents=True, exist_ok=True)
    onnx_path = args.out / f"{settings.model_name}_{size}.onnx"
    export_onnx(fixed_size_backbone(model, size), size, onnx_path)

    # Parity: PyTorch (server) vs ONNX Runtime on the same preprocessed tensors.
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    images = dataset_images(settings.data_dir, args.verify_images)
    if not images:
        raise SystemExit("No dataset images found to verify the export.")
    cosines, max_abs, prob_diff, agree = [], 0.0, 0.0, 0
    for path in images:
        tensor = preprocess(load_rgb(path.read_bytes()), size)[None]
        with torch.inference_mode():
            reference = model(torch.from_numpy(tensor)).float().numpy()
        exported = session.run(["features"], {"pixel_values": tensor})[0]
        cosines.append(float((reference @ exported.T)[0, 0] / (np.linalg.norm(reference) * np.linalg.norm(exported))))
        max_abs = max(max_abs, float(np.abs(reference - exported).max()))
        p_ref, p_exp = head.predict_proba(reference)[0], head.predict_proba(exported)[0]
        prob_diff = max(prob_diff, float(np.abs(p_ref - p_exp).max()))
        agree += int(p_ref.argmax() == p_exp.argmax())
    parity = {
        "images": len(images),
        "minCosine": min(cosines),
        "maxAbsFeatureDiff": max_abs,
        "maxProbabilityDiff": prob_diff,
        "classAgreement": agree / len(images),
        "onnxruntime": ort.__version__,
        "torch": torch.__version__,
    }
    print(json.dumps(parity, indent=2))
    if parity["minCosine"] < MIN_COSINE or agree != len(images):
        onnx_path.unlink()
        raise SystemExit("ONNX export does not reproduce the server's features; nothing written.")

    version = args.model_version or int(metadata["model_version"])
    head_path = args.out / "classifier_head.json"
    names = {e["class_id"]: e["name"] for e in mapping}
    head_path.write_text(json.dumps(head_document(metadata, head, names, model_version=version, artifact_sha256=sha256(artifact))),
                         encoding="utf-8")

    manifest = {
        "formatVersion": FORMAT_VERSION,
        "model": settings.model_name,
        "modelDisplayName": settings.display_name,
        "embeddingModel": extractor.cache_key,
        "embeddingDimension": extractor.dimension,
        "preprocessing": {
            "version": PREPROCESSING_VERSION,
            "imageSize": size,
            "resizeShortSide": resize_short_side(size),
            "resample": "pillow-bicubic",
            "crop": "center",
            "mean": IMAGENET_MEAN.tolist(),
            "std": IMAGENET_STD.tolist(),
            "layout": "NCHW",
        },
        "confidenceThreshold": settings.confidence_threshold,
        "backbone": {"file": onnx_path.name, "sha256": sha256(onnx_path), "bytes": onnx_path.stat().st_size,
                     "input": "pixel_values", "output": "features", "opset": 17},
        "head": {"file": head_path.name, "sha256": sha256(head_path)},
        "exportParity": parity,
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Wrote {onnx_path.name} ({onnx_path.stat().st_size / 1e6:.1f} MB), {head_path.name}, manifest.json "
          f"for model v{version} ({', '.join(e['name'] for e in mapping)}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

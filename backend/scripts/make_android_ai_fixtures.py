"""Generates the Android unit-test fixtures that pin on-device preprocessing and the classifier
head to the server implementation (bit-exact preprocessing, float64 head probabilities).

    backend/.venv/Scripts/python backend/scripts/make_android_ai_fixtures.py

Writes android/app/src/test/resources/leaf_model/{preprocess_cases.json,head_case.json}. Images are
procedural (no user photos) and the head is random (no trained weights), so the fixtures are safe
to commit. Needs only NumPy and Pillow (no PyTorch).
"""

from __future__ import annotations

import hashlib
import io
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from app.ai.features import IMAGENET_MEAN, IMAGENET_STD, preprocess, resize_short_side  # noqa: E402
from app.ai.head import LinearHead  # noqa: E402

OUT = BACKEND.parent / "android" / "app" / "src" / "test" / "resources" / "leaf_model"


def pattern(width: int, height: int) -> Image.Image:
    """Same integer formula as PreprocessParityTest.pattern: high-frequency content for resampling."""
    y, x = np.mgrid[0:height, 0:width].astype(np.int64)
    channels = [
        (x * 73 + y * 151 + c * 29 + ((x * y) % 97) * 3 + ((x ^ y) & 31) * 5) & 0xFF
        for c in range(3)
    ]
    return Image.fromarray(np.stack(channels, axis=-1).astype(np.uint8), "RGB")


def with_orientation(image: Image.Image, orientation: int) -> Image.Image:
    """Round-trips through a PNG carrying the EXIF orientation, then applies Pillow's own exif_transpose."""
    exif = Image.Exif()
    exif[0x0112] = orientation
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", exif=exif.tobytes())
    with Image.open(io.BytesIO(buffer.getvalue())) as reopened:
        reopened.load()
        return ImageOps.exif_transpose(reopened).convert("RGB")


def tensor_sha(tensor: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(tensor, dtype="<f4").tobytes()).hexdigest()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    real = 336
    specs = [
        # (width, height, orientation, image_size)
        (640, 480, 1, real),  # downscale both axes
        (300, 200, 1, real),  # upscale
        (384, 500, 1, real),  # short side already 384: no resampling at all
        (520, 384, 1, real),
        (401, 390, 1, real),  # slight downscale
        (390, 1000, 1, real),
        (97, 61, 1, 28),  # small configuration: 28 px crop, 32 px short side
        (61, 97, 1, 28),
        (33, 32, 1, 28),
    ] + [(150, 110, orientation, 28) for orientation in range(1, 9)] + [(520, 390, 6, real), (520, 390, 8, real)]
    for width, height, orientation, size in specs:
        upright = with_orientation(pattern(width, height), orientation)
        tensor = preprocess(upright, size)
        cases.append({
            "width": width,
            "height": height,
            "orientation": orientation,
            "imageSize": size,
            "resizeShortSide": resize_short_side(size),
            "uprightWidth": upright.width,
            "uprightHeight": upright.height,
            "tensorSha256": tensor_sha(tensor),
            "first": [float(v) for v in tensor.reshape(-1)[:8]],
            "sum": float(tensor.astype(np.float64).sum()),
        })
    (OUT / "preprocess_cases.json").write_text(json.dumps({
        "mean": IMAGENET_MEAN.tolist(),
        "std": IMAGENET_STD.tolist(),
        "cases": cases,
    }, indent=1), encoding="utf-8")

    rng = np.random.default_rng(7)
    dimension, classes = 384, 3
    features = rng.normal(size=(6, dimension)).astype(np.float32)
    head = LinearHead(
        class_ids=["healthy-id", "spotting-id", "mold-id"],
        weights=rng.normal(scale=0.5, size=(classes, dimension)),
        bias=rng.normal(size=classes),
        mean=rng.normal(scale=0.01, size=dimension),
        scale=np.full(dimension, 1.0 / np.sqrt(dimension)),
    )
    (OUT / "head_case.json").write_text(json.dumps({
        "head": {
            "formatVersion": 1,
            "modelVersion": 4,
            "embeddingModel": "dinov2_vits14|336px|resize384-bicubic-centercrop|imagenet-norm|cls|v1",
            "embeddingDimension": dimension,
            "classes": [{"index": i, "classId": cid, "name": name} for i, (cid, name) in enumerate(zip(head.class_ids, ["Healthy", "Spotting", "Mold"]))],
            "weights": head.weights.tolist(),
            "bias": head.bias.tolist(),
            "mean": head.mean.tolist(),
            "scale": head.scale.tolist(),
        },
        "features": features.tolist(),
        "probabilities": head.predict_proba(features).tolist(),
    }), encoding="utf-8")
    print(f"Wrote {len(cases)} preprocessing cases and a head case to {OUT}")


if __name__ == "__main__":
    main()

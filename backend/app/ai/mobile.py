"""The classifier head in the Android on-device format (android core/ai/LeafModelSpec.kt).

Used by ``GET /api/v1/ai/model/head`` (phones refresh their head when online) and by
``scripts/export_mobile_model.py`` (the head bundled in the APK). Only arrays, the class order and
the compatibility key are included; the phone refuses a head whose ``embeddingModel`` differs from
its bundled DINOv2 backbone.
"""

from __future__ import annotations

from typing import Any

from app.ai.head import LinearHead

HEAD_FORMAT_VERSION = 1


def head_document(
    model: dict[str, Any],
    head: LinearHead,
    names: dict[str, str],
    *,
    model_version: int | None = None,
    artifact_sha256: str | None = None,
) -> dict[str, Any]:
    mapping = sorted(model["class_mapping"], key=lambda entry: entry["index"])
    if [entry["class_id"] for entry in mapping] != head.class_ids:
        raise ValueError("The model's class mapping does not match the head artifact's class order.")
    return {
        "formatVersion": HEAD_FORMAT_VERSION,
        "modelVersion": int(model_version if model_version is not None else model["model_version"]),
        "embeddingModel": model["embedding_model"],
        "embeddingDimension": head.dimension,
        "classes": [
            {"index": entry["index"], "classId": entry["class_id"], "name": names.get(entry["class_id"], entry["name"])}
            for entry in mapping
        ],
        "weights": head.weights.tolist(),
        "bias": head.bias.tolist(),
        "mean": head.mean.tolist(),
        "scale": head.scale.tolist(),
        "trainedAt": str(model.get("trained_at")),
        "serverArtifactSha256": artifact_sha256,
    }

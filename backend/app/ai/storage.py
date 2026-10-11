"""Filesystem layout of the persistent AI data directory (a Docker named volume in development).

    <data_dir>/
      classifier_images/<class-id>/<image-id>.<jpg|png|webp>
      thumbnails/<image-id>.jpg
      embeddings/<image-id>.npy
      models/model_v<version>.npz      classifier-head artifact
      models/model_v<version>.json     human-readable metadata (MongoDB is authoritative)
      model_cache/hub/                 torch.hub DINOv2 code + weights

Paths are built only from server-generated ids that match :data:`ID_PATTERN`, and every
resolved path is checked to stay inside the data directory. Clients never see these paths.
"""

from __future__ import annotations

import json
import os
import re
import shutil
from pathlib import Path
from typing import Any

import numpy as np

ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")


class AiStorage:
    def __init__(self, root: Path):
        self.root = root

    def prepare(self) -> None:
        for name in ("classifier_images", "thumbnails", "embeddings", "models"):
            (self.root / name).mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _check_id(value: str) -> str:
        if not ID_PATTERN.match(value):
            raise ValueError("invalid id")
        return value

    def resolve(self, relative: str) -> Path:
        path = (self.root / relative).resolve()
        if not path.is_relative_to(self.root.resolve()):
            raise ValueError("path escapes the AI data directory")
        return path

    # Dataset images -------------------------------------------------------------------------
    def image_relative_path(self, class_id: str, image_id: str, extension: str) -> str:
        return f"classifier_images/{self._check_id(class_id)}/{self._check_id(image_id)}.{extension}"

    def thumbnail_path(self, image_id: str) -> Path:
        return self.root / "thumbnails" / f"{self._check_id(image_id)}.jpg"

    def embedding_relative_path(self, image_id: str) -> str:
        return f"embeddings/{self._check_id(image_id)}.npy"

    def write_bytes(self, relative: str, data: bytes) -> Path:
        path = self.resolve(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + ".tmp")
        temporary.write_bytes(data)
        os.replace(temporary, path)
        return path

    def write_embedding(self, image_id: str, vector: np.ndarray) -> str:
        relative = self.embedding_relative_path(image_id)
        path = self.resolve(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + ".tmp")
        with open(temporary, "wb") as handle:
            np.save(handle, np.asarray(vector, dtype=np.float32), allow_pickle=False)
        os.replace(temporary, path)
        return relative

    def read_embedding(self, relative: str) -> np.ndarray:
        return np.load(self.resolve(relative), allow_pickle=False)

    def delete(self, relative: str | None) -> None:
        if relative:
            self.resolve(relative).unlink(missing_ok=True)

    def delete_class_directory(self, class_id: str) -> None:
        directory = self.root / "classifier_images" / self._check_id(class_id)
        if directory.is_dir():
            shutil.rmtree(directory)

    # Models ---------------------------------------------------------------------------------
    def model_relative_path(self, version: int) -> str:
        return f"models/model_v{int(version)}.npz"

    def write_model_metadata(self, version: int, metadata: dict[str, Any]) -> None:
        payload = json.dumps(metadata, indent=2, default=str).encode("utf-8")
        self.write_bytes(f"models/model_v{int(version)}.json", payload)

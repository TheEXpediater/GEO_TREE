"""Frozen DINOv2 feature extraction.

The backbone is loaded once through the official ``torch.hub`` entry point of
``facebookresearch/dinov2`` and never trained. Its normalized CLS token (384 values for
ViT-S/14) is the visual feature that the lightweight classifier head consumes.

``cache_key`` fingerprints the backbone and preprocessing. Cached embeddings computed with a
different key are incompatible and are recomputed; a model trained under a different key is
never used for prediction.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Sequence
from typing import Any, Protocol

import numpy as np
from PIL import Image

from app.ai.config import AiSettings
from app.ai.images import load_rgb

logger = logging.getLogger(__name__)

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
PREPROCESSING_VERSION = 1


class BackboneUnavailable(RuntimeError):
    """The DINOv2 backbone could not be loaded or run (missing runtime, no weights, ...)."""


class FeatureExtractor(Protocol):
    model_name: str
    display_name: str
    dimension: int
    cache_key: str

    def extract(self, images: Sequence[bytes]) -> np.ndarray:
        """Returns one float32 feature row per image, shape (len(images), dimension)."""
        ...

    def status(self) -> dict[str, Any]:
        ...


def resize_short_side(image_size: int) -> int:
    # DINOv2's evaluation transform resizes to 256 and center-crops 224; keep that ratio.
    return round(image_size * 256 / 224)


def preprocess(image: Image.Image, image_size: int) -> np.ndarray:
    """Resize (bicubic, short side) → center crop → ImageNet normalization. Returns CHW float32."""
    short = resize_short_side(image_size)
    width, height = image.size
    scale = short / min(width, height)
    resized = image.resize(
        (max(short, round(width * scale)), max(short, round(height * scale))),
        Image.Resampling.BICUBIC,
    )
    left = (resized.width - image_size) // 2
    top = (resized.height - image_size) // 2
    cropped = resized.crop((left, top, left + image_size, top + image_size))
    array = np.asarray(cropped, dtype=np.float32) / 255.0
    return ((array - IMAGENET_MEAN) / IMAGENET_STD).transpose(2, 0, 1).copy()


class _PreprocessedImages:
    """Map-style dataset for ``torch.utils.data.DataLoader`` (decoding happens in workers)."""

    def __init__(self, images: Sequence[bytes], image_size: int):
        self._images = list(images)
        self._size = image_size

    def __len__(self) -> int:
        return len(self._images)

    def __getitem__(self, index: int) -> np.ndarray:
        return preprocess(load_rgb(self._images[index]), self._size)


class Dinov2FeatureExtractor:
    def __init__(self, settings: AiSettings):
        self._settings = settings
        self.model_name = settings.model_name
        self.display_name = settings.display_name
        self.dimension = settings.embedding_dimension
        self.cache_key = (
            f"{settings.model_name}|{settings.image_size}px|resize{resize_short_side(settings.image_size)}"
            f"-bicubic-centercrop|imagenet-norm|cls|v{PREPROCESSING_VERSION}"
        )
        self._lock = threading.RLock()
        self._model: Any = None
        self._device: str | None = None
        self._error: str | None = None

    def status(self) -> dict[str, Any]:
        return {"loaded": self._model is not None, "device": self._device, "error": self._error}

    def load(self) -> None:
        with self._lock:
            if self._model is None:
                self._load_locked()

    def _load_locked(self) -> None:
        try:
            import torch
        except ImportError as exc:
            self._error = "PyTorch is not installed on this server (install requirements-ai-torch.txt)."
            raise BackboneUnavailable(self._error) from exc

        cache_dir = self._settings.resolved_model_cache_dir / "hub"
        cache_dir.mkdir(parents=True, exist_ok=True)
        torch.hub.set_dir(str(cache_dir))
        logger.info("Loading %s from torch.hub (%s); cache %s", self.model_name, self._settings.hub_repo, cache_dir)
        try:
            model = torch.hub.load(self._settings.hub_repo, self.model_name, trust_repo=True, skip_validation=True)
        except Exception as exc:  # noqa: BLE001 - network, missing weights, incompatible hub code
            self._error = f"Could not load {self.model_name}: {exc}"
            raise BackboneUnavailable(self._error) from exc
        model.eval()
        for parameter in model.parameters():
            parameter.requires_grad_(False)

        device = self._pick_device(torch)
        if device == "cuda":
            try:
                model.to("cuda")
                with torch.inference_mode():
                    model(torch.zeros(1, 3, self._settings.image_size, self._settings.image_size, device="cuda"))
            except Exception as exc:  # noqa: BLE001 - e.g. a GPU architecture the CUDA build does not support
                logger.warning("CUDA inference failed (%s); falling back to CPU.", exc)
                model.to("cpu")
                device = "cpu"
        self._model, self._device, self._error = model, device, None
        logger.info("%s ready on %s (embedding dimension %d)", self.display_name, device, self.dimension)

    def _pick_device(self, torch: Any) -> str:
        wanted = self._settings.device
        if wanted in {"auto", "cuda"} and torch.cuda.is_available():
            return "cuda"
        if wanted == "cuda":
            logger.warning("DINOV2_DEVICE=cuda but CUDA is not available; using CPU.")
        return "cpu"

    def extract(self, images: Sequence[bytes]) -> np.ndarray:
        if not images:
            return np.zeros((0, self.dimension), dtype=np.float32)
        self.load()
        import torch
        from torch.utils.data import DataLoader

        loader = DataLoader(
            _PreprocessedImages(images, self._settings.image_size),
            batch_size=self._settings.batch_size,
            num_workers=self._settings.num_workers,
            shuffle=False,
        )
        rows: list[np.ndarray] = []
        # One inference at a time: keeps memory predictable on the development PC.
        with self._lock, torch.inference_mode():
            for batch in loader:
                output = self._model(batch.to(self._device))
                rows.append(output.float().cpu().numpy())
        features = np.concatenate(rows).astype(np.float32)
        if features.shape != (len(images), self.dimension):
            raise BackboneUnavailable(f"Unexpected feature shape {features.shape}.")
        return features

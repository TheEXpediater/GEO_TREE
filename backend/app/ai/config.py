"""AI runtime settings, read from environment variables. Dependency-free on purpose."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parent.parent.parent

PATCH_SIZE = 14

# Supported frozen backbones: torch.hub name -> (display name, embedding dimension).
# ViT-S/14 is the default for the development PC; ViT-B/14 is allowed only as an explicit
# experiment. ViT-L/ViT-g are deliberately not supported on this hardware.
BACKBONES: dict[str, tuple[str, int]] = {
    "dinov2_vits14": ("DINOv2 ViT-S/14", 384),
    "dinov2_vitb14": ("DINOv2 ViT-B/14", 768),
}


@dataclass(frozen=True)
class AiSettings:
    enabled: bool = False
    data_dir: Path = BACKEND_ROOT / "ai_data"
    model_cache_dir: Path | None = None  # default: <data_dir>/model_cache
    hub_repo: str = "facebookresearch/dinov2"
    model_name: str = "dinov2_vits14"
    device: str = "auto"  # auto | cpu | cuda (cuda falls back to CPU when unavailable)
    image_size: int = 336
    batch_size: int = 4
    num_workers: int = 0
    # A class needs at least this many images before a model is trained.
    min_images_per_class: int = 10
    validation_fraction: float = 0.2
    # Dataset changes within this window are coalesced into one training job.
    retrain_debounce_seconds: float = 15.0
    # Predictions below this probability are reported as uncertain.
    confidence_threshold: float = 0.6
    max_files_per_upload: int = 50
    worker_enabled: bool = True
    preload_backbone: bool = True
    # Memory guard (MB of free memory required before DINOv2 work); 0 disables it.
    training_min_free_mb: int = 0
    inference_min_free_mb: int = 0
    # Headroom required below the container's own memory limit; must be below that limit.
    container_min_headroom_mb: int = 0
    # Smaller headroom for one assessment (model already resident); None = same as training.
    container_inference_headroom_mb: int | None = None
    split_seed: int = 42

    @property
    def display_name(self) -> str:
        return BACKBONES[self.model_name][0]

    @property
    def embedding_dimension(self) -> int:
        return BACKBONES[self.model_name][1]

    @property
    def resolved_model_cache_dir(self) -> Path:
        return self.model_cache_dir or self.data_dir / "model_cache"

    def validate(self) -> "AiSettings":
        if self.model_name not in BACKBONES:
            raise RuntimeError(f"DINOV2_MODEL must be one of {sorted(BACKBONES)}; got {self.model_name!r}.")
        if self.image_size < PATCH_SIZE * 8 or self.image_size % PATCH_SIZE:
            raise RuntimeError(f"DINOV2_IMAGE_SIZE must be a multiple of {PATCH_SIZE} and at least {PATCH_SIZE * 8}.")
        if self.device not in {"auto", "cpu", "cuda"}:
            raise RuntimeError("DINOV2_DEVICE must be auto, cpu or cuda.")
        if not 0 < self.validation_fraction <= 0.5:
            raise RuntimeError("AI_VALIDATION_FRACTION must be in (0, 0.5].")
        if not 0 <= self.confidence_threshold <= 1:
            raise RuntimeError("AI_CONFIDENCE_THRESHOLD must be in [0, 1].")
        if self.min_images_per_class < 2:
            raise RuntimeError("AI_MIN_IMAGES_PER_CLASS must be at least 2 (one training and one validation image).")
        if self.batch_size < 1 or self.num_workers < 0:
            raise RuntimeError("DINOV2_BATCH_SIZE must be >= 1 and DINOV2_NUM_WORKERS >= 0.")
        return self


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _env(name: str, default: str) -> str:
    value = os.environ.get(name, "").strip()
    return value or default


def load_ai_settings() -> AiSettings:
    defaults = AiSettings()
    cache_dir = os.environ.get("AI_MODEL_CACHE_DIR", "").strip()
    return AiSettings(
        enabled=_env_bool("AI_ENABLED", defaults.enabled),
        data_dir=Path(_env("AI_DATA_DIR", str(defaults.data_dir))),
        model_cache_dir=Path(cache_dir) if cache_dir else None,
        hub_repo=_env("DINOV2_HUB_REPO", defaults.hub_repo),
        model_name=_env("DINOV2_MODEL", defaults.model_name),
        device=_env("DINOV2_DEVICE", defaults.device).lower(),
        image_size=int(_env("DINOV2_IMAGE_SIZE", str(defaults.image_size))),
        batch_size=int(_env("DINOV2_BATCH_SIZE", str(defaults.batch_size))),
        num_workers=int(_env("DINOV2_NUM_WORKERS", str(defaults.num_workers))),
        min_images_per_class=int(_env("AI_MIN_IMAGES_PER_CLASS", str(defaults.min_images_per_class))),
        validation_fraction=float(_env("AI_VALIDATION_FRACTION", str(defaults.validation_fraction))),
        retrain_debounce_seconds=float(_env("AI_RETRAIN_DEBOUNCE_SECONDS", str(defaults.retrain_debounce_seconds))),
        confidence_threshold=float(_env("AI_CONFIDENCE_THRESHOLD", str(defaults.confidence_threshold))),
        max_files_per_upload=int(_env("AI_MAX_FILES_PER_UPLOAD", str(defaults.max_files_per_upload))),
        preload_backbone=_env_bool("AI_PRELOAD_BACKBONE", defaults.preload_backbone),
        training_min_free_mb=int(_env("AI_TRAINING_MIN_FREE_MB", str(defaults.training_min_free_mb))),
        inference_min_free_mb=int(_env("AI_INFERENCE_MIN_FREE_MB", str(defaults.inference_min_free_mb))),
        container_min_headroom_mb=int(_env("AI_CONTAINER_MIN_HEADROOM_MB", str(defaults.container_min_headroom_mb))),
        container_inference_headroom_mb=int(os.environ["AI_CONTAINER_INFERENCE_HEADROOM_MB"]) if os.environ.get("AI_CONTAINER_INFERENCE_HEADROOM_MB", "").strip() else None,
    ).validate()

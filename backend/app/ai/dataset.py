"""Dataset readiness rules and the deterministic train/validation split."""

from __future__ import annotations

import hashlib
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ClassCount:
    class_id: str
    name: str
    image_count: int


@dataclass(frozen=True)
class Readiness:
    can_train: bool
    min_images_per_class: int
    problems: list[str] = field(default_factory=list)


def readiness(classes: list[ClassCount], min_images_per_class: int) -> Readiness:
    """A model is trained only when every class has enough images; nothing is silently skipped."""
    problems: list[str] = []
    if len(classes) < 2:
        problems.append("Add at least two classes.")
    for item in classes:
        if item.image_count < min_images_per_class:
            missing = min_images_per_class - item.image_count
            problems.append(
                f"{item.name} needs {missing} more image{'s' if missing != 1 else ''} "
                f"({item.image_count} of {min_images_per_class})."
            )
    return Readiness(can_train=not problems, min_images_per_class=min_images_per_class, problems=problems)


@dataclass(frozen=True)
class SplitItem:
    image_id: str
    class_id: str
    sha256: str


def _order_key(seed: int, sha256: str) -> str:
    return hashlib.sha256(f"{seed}:{sha256}".encode()).hexdigest()


def split_train_validation(items: Iterable[SplitItem], fraction: float, seed: int) -> tuple[list[SplitItem], list[SplitItem]]:
    """Stratified per class, deterministic for a given seed and dataset, independent of upload order.

    Images with identical content (same SHA-256) form one group and always land on the same side,
    so an exact duplicate can never leak from training into validation. Each class keeps at least
    one group on each side.
    """
    groups: dict[str, dict[str, list[SplitItem]]] = defaultdict(lambda: defaultdict(list))
    for item in items:
        groups[item.class_id][item.sha256].append(item)

    train: list[SplitItem] = []
    validation: list[SplitItem] = []
    for class_id in sorted(groups):
        by_hash = groups[class_id]
        ordered = sorted(by_hash, key=lambda sha: _order_key(seed, sha))
        if len(ordered) < 2:
            raise ValueError(f"Class {class_id} needs at least two distinct images for a validation split.")
        validation_groups = min(max(1, round(len(ordered) * fraction)), len(ordered) - 1)
        for i, sha in enumerate(ordered):
            (validation if i < validation_groups else train).extend(by_hash[sha])
    return train, validation

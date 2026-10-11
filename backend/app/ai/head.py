"""The trainable part of the recognizer: a multinomial logistic-regression head over frozen
DINOv2 features.

Features are L2-normalized, centered on the training mean and scaled by sqrt(dimension) so
values are O(1) while keeping DINOv2's own variance structure (no per-dimension
standardization, which would inflate near-constant noise dimensions and overfit small
datasets). The regularization strength C is chosen by stratified cross-validation inside the
training split only; the validation split is never seen during fitting or model selection.

scikit-learn fits the head; prediction is plain NumPy. The artifact stores only arrays and the
class mapping (``.npz`` loaded with ``allow_pickle=False``), never a pickled Python object.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

C_GRID = (0.01, 0.1, 1.0, 10.0)
DEFAULT_C = 1.0
MAX_CV_FOLDS = 5
MAX_ITERATIONS = 5000


def l2_normalize(features: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(features, axis=1, keepdims=True)
    return features / np.maximum(norms, 1e-12)


@dataclass(frozen=True)
class LinearHead:
    class_ids: list[str]  # row i of ``weights`` scores class_ids[i]
    weights: np.ndarray  # (classes, dimension)
    bias: np.ndarray  # (classes,)
    mean: np.ndarray  # (dimension,)
    scale: np.ndarray  # (dimension,)
    c: float = DEFAULT_C
    cv_scores: dict[str, float] = field(default_factory=dict)  # mean CV macro-F1 per C (not persisted)

    @property
    def dimension(self) -> int:
        return int(self.weights.shape[1])

    def predict_proba(self, features: np.ndarray) -> np.ndarray:
        x = (l2_normalize(np.asarray(features, dtype=np.float64)) - self.mean) / self.scale
        logits = x @ self.weights.T + self.bias
        logits -= logits.max(axis=1, keepdims=True)
        exp = np.exp(logits)
        return exp / exp.sum(axis=1, keepdims=True)

    def predict(self, features: np.ndarray) -> list[str]:
        return [self.class_ids[i] for i in self.predict_proba(features).argmax(axis=1)]

    def save(self, path: Path) -> None:
        """Atomic write: a reader never sees a half-written artifact."""
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + ".tmp")
        with open(temporary, "wb") as handle:
            np.savez(
                handle,
                class_ids=np.array(self.class_ids, dtype=str),
                weights=self.weights,
                bias=self.bias,
                mean=self.mean,
                scale=self.scale,
                c=np.array(self.c),
            )
        os.replace(temporary, path)

    @classmethod
    def load(cls, path: Path) -> "LinearHead":
        with np.load(path, allow_pickle=False) as data:
            return cls(
                class_ids=[str(value) for value in data["class_ids"]],
                weights=data["weights"],
                bias=data["bias"],
                mean=data["mean"],
                scale=data["scale"],
                c=float(data["c"]) if "c" in data else DEFAULT_C,
            )


def _prepare(features: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    x = l2_normalize(np.asarray(features, dtype=np.float64))
    mean = x.mean(axis=0)
    scale = np.full(x.shape[1], 1.0 / np.sqrt(x.shape[1]))
    return (x - mean) / scale, mean, scale


def _fit(x: np.ndarray, y: np.ndarray, c: float, seed: int):
    from sklearn.linear_model import LogisticRegression

    model = LogisticRegression(C=c, class_weight="balanced", max_iter=MAX_ITERATIONS, random_state=seed)
    model.fit(x, y)
    return model


def _select_c(x: np.ndarray, y: np.ndarray, seed: int) -> tuple[float, dict[str, float]]:
    """Mean macro-F1 over stratified folds of the training split. Ties go to the smaller C."""
    from sklearn.model_selection import StratifiedKFold

    from app.ai.metrics import classification_metrics

    folds = min(MAX_CV_FOLDS, int(np.bincount(y).min()))
    if folds < 2:
        return DEFAULT_C, {}
    splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=seed)
    scores: dict[str, float] = {}
    for c in C_GRID:
        fold_scores = []
        for train_index, test_index in splitter.split(x, y):
            model = _fit(x[train_index], y[train_index], c, seed)
            predicted = model.predict(x[test_index])
            fold_scores.append(classification_metrics([str(v) for v in y[test_index]], [str(v) for v in predicted])["macroF1"])
        scores[str(c)] = float(np.mean(fold_scores))
    best = max(C_GRID, key=lambda c: (round(scores[str(c)], 6), -c))
    return best, scores


def fit_head(features: np.ndarray, labels: list[str], class_ids: list[str], seed: int) -> LinearHead:
    """Fits the head. Every class in ``class_ids`` must appear in ``labels``."""
    if len(class_ids) < 2:
        raise ValueError("At least two classes are required.")
    missing = set(class_ids) - set(labels)
    if missing:
        raise ValueError(f"Classes without training samples: {sorted(missing)}")
    index = {class_id: i for i, class_id in enumerate(class_ids)}
    y = np.array([index[label] for label in labels])
    x, mean, scale = _prepare(features)

    c, scores = _select_c(x, y, seed)
    model = _fit(x, y, c, seed)
    weights, bias = model.coef_, model.intercept_
    if len(class_ids) == 2:
        # Binary logistic regression scores only class 1; [0, z] under softmax is identical.
        weights = np.vstack([np.zeros_like(weights[0]), weights[0]])
        bias = np.array([0.0, bias[0]])
    return LinearHead(class_ids=list(class_ids), weights=weights, bias=bias, mean=mean, scale=scale, c=c, cv_scores=scores)

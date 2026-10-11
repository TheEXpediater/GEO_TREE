"""Classification metrics in plain Python, shared by model validation and the local batch test.

Conventions (same as scikit-learn with ``zero_division=0``):

* precision of a class that was never predicted is 0; recall of a class with no samples is 0;
* macro averages are taken over the labels that have at least one expected sample, so a class
  that is merely predicted (but absent from the evaluated set) does not distort the average;
* the confusion matrix rows are expected labels and the columns are predicted labels.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any


def _ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def classification_metrics(expected: Sequence[str], predicted: Sequence[str], labels: Sequence[str] | None = None) -> dict[str, Any]:
    if len(expected) != len(predicted):
        raise ValueError("expected and predicted must have the same length")
    ordered = list(labels) if labels is not None else []
    for label in [*expected, *predicted]:
        if label not in ordered:
            ordered.append(label)
    position = {label: i for i, label in enumerate(ordered)}
    matrix = [[0] * len(ordered) for _ in ordered]
    for truth, guess in zip(expected, predicted):
        matrix[position[truth]][position[guess]] += 1

    per_class = []
    for i, label in enumerate(ordered):
        true_positive = matrix[i][i]
        support = sum(matrix[i])
        predicted_count = sum(row[i] for row in matrix)
        precision = _ratio(true_positive, predicted_count)
        recall = _ratio(true_positive, support)
        f1 = _ratio(2 * true_positive, support + predicted_count)
        per_class.append({"label": label, "precision": precision, "recall": recall, "f1": f1, "support": support, "predicted": predicted_count})

    scored = [row for row in per_class if row["support"] > 0]

    def macro(key: str) -> float:
        return sum(row[key] for row in scored) / len(scored) if scored else 0.0

    total = len(expected)
    return {
        "samples": total,
        "accuracy": _ratio(sum(matrix[i][i] for i in range(len(ordered))), total),
        "macroPrecision": macro("precision"),
        "macroRecall": macro("recall"),
        "macroF1": macro("f1"),
        "perClass": per_class,
        "confusionMatrix": {"labels": ordered, "matrix": matrix},
    }

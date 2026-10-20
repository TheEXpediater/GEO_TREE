"""Field-record quality rules shared by the dashboard and the tree list.

The GPS thresholds match the Android app (GpsAccuracyPolicy): <= 5 m Good, <= 10 m Acceptable,
otherwise Low. A tree record is *complete* when it has a registration photo, Good or Acceptable
GPS accuracy, and at least one descriptive field (age, taste category, yearly yield or fruit quality).
"""

from __future__ import annotations

import math
from typing import Any

GOOD_ACCURACY_METERS = 5.0
ACCEPTABLE_ACCURACY_METERS = 10.0
DESCRIPTIVE_FIELDS = ("age", "taste_category", "yearly_yield", "fruit_quality")

COMPLETENESS_DEFINITION = (
    "A tree record is complete when it has a registration photo, GPS accuracy of 10 m or better "
    "(Good or Acceptable), and at least one descriptive field: age, taste category, yearly yield or fruit quality."
)


def accuracy_level(accuracy_meters: float | None) -> str:
    if accuracy_meters is None:
        return "LOW"
    if accuracy_meters <= GOOD_ACCURACY_METERS:
        return "GOOD"
    if accuracy_meters <= ACCEPTABLE_ACCURACY_METERS:
        return "ACCEPTABLE"
    return "LOW"


def has_descriptive_data(tree: dict[str, Any]) -> bool:
    return any(tree.get(name) not in (None, "") for name in DESCRIPTIVE_FIELDS)


def completeness(tree: dict[str, Any]) -> dict[str, bool]:
    checks = {
        "photo": bool(tree.get("image_path")),
        "accuracy": accuracy_level(tree.get("accuracy_meters")) != "LOW",
        "descriptive": has_descriptive_data(tree),
    }
    checks["complete"] = all(checks.values())
    return checks


def valid_coordinates(latitude: object, longitude: object) -> bool:
    """Usable map position: real finite numbers in range, and not the 0,0 "null island" that a
    missing fix can produce. Records failing this are counted, never placed on a map."""
    if isinstance(latitude, bool) or isinstance(longitude, bool):
        return False
    if not isinstance(latitude, (int, float)) or not isinstance(longitude, (int, float)):
        return False
    if not (math.isfinite(latitude) and math.isfinite(longitude)):
        return False
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        return False
    return not (latitude == 0 and longitude == 0)

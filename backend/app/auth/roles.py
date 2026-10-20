"""Account roles and what each may do. FastAPI enforces these; clients only hide UI.

* SUPER_ADMIN: everything, including managing Admin and Field Tester accounts.
* ADMIN: field records, tags, classifier classes/images, model training, assessments,
  analytics, and Field Tester accounts. Cannot create, edit or promote administrators.
* FIELD_TESTER: Android field work only (trees, sync, leaf assessment, history).

A user stored before roles existed has no role; it is treated as a Field Tester (never admin).
"""

from __future__ import annotations

from typing import Any

SUPER_ADMIN = "SUPER_ADMIN"
ADMIN = "ADMIN"
FIELD_TESTER = "FIELD_TESTER"

ALL_ROLES = (SUPER_ADMIN, ADMIN, FIELD_TESTER)
ADMIN_ROLES = frozenset({SUPER_ADMIN, ADMIN})


def effective_role(user: dict[str, Any]) -> str:
    role = user.get("role")
    return role if role in ALL_ROLES else FIELD_TESTER


def is_admin(user: dict[str, Any]) -> bool:
    return user.get("role") in ADMIN_ROLES


def is_active(user: dict[str, Any]) -> bool:
    # Accounts created before the flag existed have no "active" field and stay active.
    return user.get("active", True) is not False


def can_manage_role(actor: dict[str, Any], role: str) -> bool:
    """Whether ``actor`` may create, edit, suspend or reset an account that has ``role``."""
    actor_role = actor.get("role")
    if actor_role == SUPER_ADMIN:
        return True
    if actor_role == ADMIN:
        return role == FIELD_TESTER
    return False

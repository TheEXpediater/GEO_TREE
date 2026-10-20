"""Account management for the web administration.

Permission matrix (enforced here, whatever the client shows):

* SUPER_ADMIN: create/edit/suspend/reset any account and assign any role.
* ADMIN: view all accounts; create/edit/suspend/reset Field Tester accounts only.
* Nobody can change their own role or suspend themselves, and the last active Super Admin can
  never be demoted or suspended, so the system cannot be locked out.

There is no email/reset infrastructure: a password reset is admin-assisted (the administrator
sets a new password and passes it to the user). Changing a password revokes older tokens.
Accounts are suspended, never deleted, so history keeps its authors.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Query
from pymongo.database import Database

from app.auth import roles
from app.auth.dependencies import get_db, require_admin
from app.auth.security import hash_password
from app.errors import ApiError
from app.repositories import user_repository
from app.routes.pagination import PageParams, page_params
from app.schemas.admin import PasswordReset, UserCreate, UserUpdate
from app.services import audit

router = APIRouter(prefix="/api/v1/admin/users", tags=["admin: users"])


def _view(user: dict[str, Any], actor: dict[str, Any]) -> dict[str, Any]:
    role = roles.effective_role(user)
    is_self = user["_id"] == actor["_id"]
    return {
        "id": str(user["_id"]),
        "email": user["email"],
        "displayName": user.get("display_name") or user["email"],
        "role": role,
        "roleAssigned": user.get("role") in roles.ALL_ROLES,
        "active": roles.is_active(user),
        "temporary": bool(user.get("temporary", False)),
        "createdAt": user.get("created_at"),
        "lastLoginAt": user.get("last_login_at"),
        "passwordChangedAt": user.get("password_changed_at"),
        "isSelf": is_self,
        "canManage": roles.can_manage_role(actor, role) and not is_self,
    }


def _target(db: Database, user_id: str) -> dict[str, Any]:
    user = user_repository.find_by_id(db, user_id)
    if user is None:
        raise ApiError(404, "USER_NOT_FOUND", "User not found.")
    return user


def _require_manageable(actor: dict[str, Any], role: str) -> None:
    if not roles.can_manage_role(actor, role):
        raise ApiError(403, "ROLE_NOT_ALLOWED", "Your role cannot manage accounts with this role.", {"role": role})


@router.get("")
def list_users(
    q: str | None = Query(default=None, max_length=100),
    role: str | None = Query(default=None, pattern="^(SUPER_ADMIN|ADMIN|FIELD_TESTER)$"),
    status: str | None = Query(default=None, pattern="^(active|suspended)$"),
    paging: PageParams = Depends(page_params),
    db: Database = Depends(get_db),
    actor: dict[str, Any] = Depends(require_admin),
) -> dict[str, Any]:
    documents, total = user_repository.page(db, q, role, status, paging.skip, paging.page_size)
    return paging.response([_view(u, actor) for u in documents], total)


@router.post("", status_code=201)
def create_user(body: UserCreate, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    _require_manageable(actor, body.role)
    now = datetime.now(timezone.utc)
    created = user_repository.insert(db, {
        "email": body.email,
        "password_hash": hash_password(body.password),
        "display_name": body.display_name,
        "role": body.role,
        "active": True,
        "created_at": now,
        "created_by": actor["email"],
        "password_changed_at": now,
    })
    if created is None:
        raise ApiError(409, "EMAIL_TAKEN", "An account with this email already exists.", {"email": body.email})
    audit.record(db, actor, "user.create", f"Created {body.role} account {body.email}", "user", str(created["_id"]), {"role": body.role})
    return _view(created, actor)


@router.patch("/{user_id}")
def update_user(user_id: str, body: UserUpdate, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    target = _target(db, user_id)
    current_role = roles.effective_role(target)
    is_self = target["_id"] == actor["_id"]
    fields: dict[str, Any] = {}
    changes: dict[str, Any] = {}

    if body.display_name is not None and body.display_name.strip() != target.get("display_name"):
        fields["display_name"] = changes["displayName"] = body.display_name.strip()
    role_changes = body.role is not None and (body.role != target.get("role"))
    status_changes = body.active is not None and body.active != roles.is_active(target)

    if is_self and (role_changes or status_changes):
        raise ApiError(403, "SELF_CHANGE_NOT_ALLOWED", "You cannot change your own role or suspend your own account.")
    if not is_self or role_changes or status_changes:
        _require_manageable(actor, current_role)
    if role_changes:
        _require_manageable(actor, body.role)  # type: ignore[arg-type]
        fields["role"] = changes["role"] = body.role
    if status_changes:
        fields["active"] = changes["active"] = body.active

    removes_super_admin = current_role == roles.SUPER_ADMIN and roles.is_active(target) and (
        (role_changes and body.role != roles.SUPER_ADMIN) or (status_changes and body.active is False)
    )
    if removes_super_admin and user_repository.count_active_super_admins(db) <= 1:
        raise ApiError(409, "LAST_SUPER_ADMIN", "The last active Super Admin cannot be demoted or suspended.")

    if not fields:
        return _view(target, actor)
    fields["updated_at"] = datetime.now(timezone.utc)
    fields["updated_by"] = actor["email"]
    updated = user_repository.update(db, user_id, fields)
    action = "user.suspend" if changes.get("active") is False else "user.reactivate" if changes.get("active") is True else "user.update"
    audit.record(db, actor, action, f"Updated account {target['email']}", "user", user_id, changes)
    return _view(updated, actor)  # type: ignore[arg-type]


@router.post("/{user_id}/password")
def reset_password(user_id: str, body: PasswordReset, db: Database = Depends(get_db), actor: dict[str, Any] = Depends(require_admin)) -> dict[str, Any]:
    target = _target(db, user_id)
    if target["_id"] != actor["_id"]:
        _require_manageable(actor, roles.effective_role(target))
    now = datetime.now(timezone.utc)
    updated = user_repository.update(db, user_id, {"password_hash": hash_password(body.password), "password_changed_at": now,
                                                   "updated_at": now, "updated_by": actor["email"]})
    audit.record(db, actor, "user.password_reset", f"Reset the password of {target['email']}", "user", user_id)
    return _view(updated, actor)  # type: ignore[arg-type]

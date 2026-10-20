"""Request bodies for the web administration routes (/api/v1/admin/...).

Responses are explicit camelCase whitelists built in the route modules (like the AI/admin routes):
password hashes, tokens and server file paths are never returned.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from app.auth import roles
from app.schemas.tree import TREE_CODE_PATTERN

EMAIL_PATTERN = r"^[^@\s]+@[^@\s]+\.[^@\s]+$"
MIN_PASSWORD_LENGTH = 10
TAG_COLORS = ("forest", "moss", "clay", "amber", "sky", "stone", "berry")

Role = Literal["SUPER_ADMIN", "ADMIN", "FIELD_TESTER"]
assert set(Role.__args__) == set(roles.ALL_ROLES)  # type: ignore[attr-defined]


def _strip(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    return value or None


class UserCreate(BaseModel):
    email: str = Field(max_length=254, pattern=EMAIL_PATTERN)
    display_name: str = Field(min_length=1, max_length=80, alias="displayName")
    role: Role
    password: str = Field(min_length=MIN_PASSWORD_LENGTH, max_length=256)

    model_config = {"populate_by_name": True}

    @field_validator("email")
    @classmethod
    def _email(cls, value: str) -> str:
        return value.strip().lower()

    @field_validator("display_name")
    @classmethod
    def _name(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Display name is required.")
        return value


class UserUpdate(BaseModel):
    display_name: str | None = Field(default=None, min_length=1, max_length=80, alias="displayName")
    role: Role | None = None
    active: bool | None = None

    model_config = {"populate_by_name": True}


class PasswordReset(BaseModel):
    password: str = Field(min_length=MIN_PASSWORD_LENGTH, max_length=256)


class TreeUpdate(BaseModel):
    """Editable tree metadata. GPS coordinates, accuracy and capture time are field evidence and
    are never edited from the web. Only the fields present in the request are changed."""

    tree_code: str | None = Field(default=None, pattern=TREE_CODE_PATTERN, alias="treeCode")
    age: int | None = Field(default=None, ge=0, le=2000)
    taste_category: str | None = Field(default=None, max_length=40, alias="tasteCategory")
    yearly_yield: float | None = Field(default=None, ge=0, alias="yearlyYield")
    fruit_quality: str | None = Field(default=None, max_length=40, alias="fruitQuality")
    notes: str | None = Field(default=None, max_length=2000)
    tag_ids: list[str] | None = Field(default=None, max_length=50, alias="tagIds")

    model_config = {"populate_by_name": True}

    @field_validator("tree_code")
    @classmethod
    def _code(cls, value: str | None) -> str | None:
        return value.strip().upper() if value is not None else None

    @field_validator("taste_category", "fruit_quality", "notes")
    @classmethod
    def _text(cls, value: str | None) -> str | None:
        return _strip(value)


class TagCreate(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    color: Literal["forest", "moss", "clay", "amber", "sky", "stone", "berry"] = "moss"
    description: str | None = Field(default=None, max_length=200)

    @field_validator("name")
    @classmethod
    def _name(cls, value: str) -> str:
        value = " ".join(value.split())
        if not value:
            raise ValueError("Tag name is required.")
        return value

    @field_validator("description")
    @classmethod
    def _description(cls, value: str | None) -> str | None:
        return _strip(value)


class TagUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=40)
    color: Literal["forest", "moss", "clay", "amber", "sky", "stone", "berry"] | None = None
    description: str | None = Field(default=None, max_length=200)

    @field_validator("name")
    @classmethod
    def _name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = " ".join(value.split())
        if not value:
            raise ValueError("Tag name is required.")
        return value

    @field_validator("description")
    @classmethod
    def _description(cls, value: str | None) -> str | None:
        return _strip(value)

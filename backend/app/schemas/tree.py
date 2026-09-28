from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field, field_validator

TREE_CODE_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$"


def _as_utc(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class TreeSyncRequest(BaseModel):
    """A tree as the device knows it. The device-generated UUID is the identity."""

    id: UUID
    tree_code: str = Field(pattern=TREE_CODE_PATTERN)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_meters: float = Field(ge=0)
    altitude_meters: float | None = None
    location_captured_at: datetime
    age: int | None = Field(default=None, ge=0, le=2000)
    taste_category: str | None = Field(default=None, max_length=40)
    yearly_yield: float | None = Field(default=None, ge=0)
    fruit_quality: str | None = Field(default=None, max_length=40)
    notes: str | None = Field(default=None, max_length=2000)
    created_at: datetime
    updated_at: datetime

    @field_validator("tree_code")
    @classmethod
    def _normalize_code(cls, value: str) -> str:
        return value.strip().upper()

    @field_validator("location_captured_at", "created_at", "updated_at")
    @classmethod
    def _utc(cls, value: datetime) -> datetime:
        return _as_utc(value)


class TreeOut(BaseModel):
    id: str
    tree_code: str
    latitude: float
    longitude: float
    accuracy_meters: float
    altitude_meters: float | None = None
    location_captured_at: datetime
    age: int | None = None
    taste_category: str | None = None
    yearly_yield: float | None = None
    fruit_quality: str | None = None
    notes: str | None = None
    image_path: str | None = None
    created_at: datetime
    updated_at: datetime
    server_version: int

    @classmethod
    def from_document(cls, doc: dict) -> "TreeOut":
        data = {key: doc.get(key) for key in cls.model_fields}
        for key in ("location_captured_at", "created_at", "updated_at"):
            data[key] = _as_utc(data[key])
        return cls(**data)


class TreeSyncResponse(BaseModel):
    result: Literal["created", "updated", "unchanged"]
    tree: TreeOut


class TreeChangesResponse(BaseModel):
    items: list[TreeOut]
    latest_version: int
    has_more: bool

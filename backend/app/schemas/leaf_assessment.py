"""Leaf assessment history records (one document per scan). Follows the tree-sync conventions:
snake_case keys, ISO-8601 UTC timestamps, device-generated UUID identity, server_version cursor."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field, field_validator, model_validator

AssessmentStatus = Literal["COMPLETED", "UNCERTAIN", "FAILED"]


def _as_utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class LeafAssessmentSyncRequest(BaseModel):
    """A finished scan as recorded on the device. Only final results are synced; a scan that is
    still processing stays on the phone until it completes or fails."""

    assessment_id: UUID
    tree_id: UUID
    status: AssessmentStatus
    class_id: str | None = Field(default=None, max_length=64)
    class_name: str | None = Field(default=None, max_length=80)
    confidence: float | None = Field(default=None, ge=0, le=1)
    model_name: str | None = Field(default=None, max_length=64)
    model_display_name: str | None = Field(default=None, max_length=80)
    model_version: int | None = Field(default=None, ge=1)
    error_code: str | None = Field(default=None, max_length=64)
    error_message: str | None = Field(default=None, max_length=500)
    created_at: datetime
    completed_at: datetime | None = None
    updated_at: datetime

    @field_validator("created_at", "completed_at", "updated_at")
    @classmethod
    def _utc(cls, value: datetime | None) -> datetime | None:
        return _as_utc(value)

    @model_validator(mode="after")
    def _result_fields(self) -> "LeafAssessmentSyncRequest":
        if self.status in ("COMPLETED", "UNCERTAIN"):
            missing = [
                name
                for name in ("class_id", "class_name", "confidence", "model_name", "model_version", "completed_at")
                if getattr(self, name) is None
            ]
            if missing:
                raise ValueError(f"A {self.status} assessment needs {', '.join(missing)}.")
        return self


class LeafAssessmentOut(BaseModel):
    assessment_id: str
    tree_id: str
    status: AssessmentStatus
    class_id: str | None = None
    class_name: str | None = None
    confidence: float | None = None
    model_name: str | None = None
    model_display_name: str | None = None
    model_version: int | None = None
    error_code: str | None = None
    error_message: str | None = None
    image_path: str | None = None
    created_at: datetime
    completed_at: datetime | None = None
    updated_at: datetime
    server_version: int

    @classmethod
    def from_document(cls, doc: dict) -> "LeafAssessmentOut":
        data = {key: doc.get(key) for key in cls.model_fields}
        for key in ("created_at", "completed_at", "updated_at"):
            data[key] = _as_utc(data[key])
        return cls(**data)


class LeafAssessmentSyncResponse(BaseModel):
    result: Literal["created", "unchanged"]
    assessment: LeafAssessmentOut


class LeafAssessmentListResponse(BaseModel):
    items: list[LeafAssessmentOut]


class LeafAssessmentChangesResponse(BaseModel):
    items: list[LeafAssessmentOut]
    latest_version: int
    has_more: bool

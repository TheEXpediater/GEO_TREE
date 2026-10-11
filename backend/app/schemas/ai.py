"""Request bodies for the AI/admin routes.

AI/admin responses use camelCase keys (``classId``, ``modelVersion``, ...) as specified for the
leaf-assessment contract; the older tree/auth routes keep their snake_case keys unchanged.
Responses are built as explicit whitelists in :mod:`app.ai.service`, so server paths, hashes
and artifact locations are never exposed.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class ClassifierCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class ClassifierUpdate(BaseModel):
    name: str = Field(min_length=1, max_length=80)

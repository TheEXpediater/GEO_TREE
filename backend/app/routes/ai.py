"""Leaf assessment and the on-device model head for signed-in field users, plus training-job lookup for administrators."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, File, Form, UploadFile
from starlette.concurrency import run_in_threadpool

from app.auth.dependencies import get_ai_service, get_current_user, require_admin

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])


@router.post("/leaf-assessment")
async def leaf_assessment(
    file: UploadFile = File(...),
    tree_id: str | None = Form(default=None, alias="treeId", max_length=64),
    user: dict[str, Any] = Depends(get_current_user),
    ai: Any = Depends(get_ai_service),
) -> dict[str, Any]:
    """Classifies one leaf photo. Read-only: never adds to or changes the training dataset."""
    data = await file.read(ai.max_image_bytes + 1)
    result = await run_in_threadpool(ai.assess, data)
    logger.info(
        "Leaf assessment by %s (tree %s): %s %.3f model v%s",
        user["email"], tree_id or "-", result["className"], result["confidence"], result["modelVersion"],
    )
    return result


@router.get("/status")
def ai_status(_: dict[str, Any] = Depends(get_current_user), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.public_status()


@router.get("/model/head")
def model_head(_: dict[str, Any] = Depends(get_current_user), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    """The active classifier head in the Android on-device format, so phones can update offline inference."""
    return ai.mobile_head()


@router.get("/jobs/{job_id}")
def training_job(job_id: str, _: dict[str, Any] = Depends(require_admin), ai: Any = Depends(get_ai_service)) -> dict[str, Any]:
    return ai.job(job_id)

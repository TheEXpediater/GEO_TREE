from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi import Query


@dataclass(frozen=True)
class PageParams:
    page: int
    page_size: int

    @property
    def skip(self) -> int:
        return (self.page - 1) * self.page_size

    def response(self, items: list[Any], total: int) -> dict[str, Any]:
        return {"items": items, "total": total, "page": self.page, "pageSize": self.page_size}


def page_params(
    page: int = Query(default=1, ge=1, le=10_000),
    page_size: int = Query(default=25, ge=1, le=100, alias="pageSize"),
) -> PageParams:
    return PageParams(page, page_size)

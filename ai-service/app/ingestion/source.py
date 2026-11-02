"""Pull material snapshots from the Spring Boot backend.

The backend owns moderation and the MySQL source of truth; the AI service only
mirrors ACTIVE materials.  Endpoint contract:

    GET /api/internal/ai/materials?page=0&size=100[&since=2026-01-01T00:00:00]
    -> {"items": [...], "page": 0, "size": 100, "has_more": false, "total": 31}
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import datetime

import httpx

from app.config import Settings
from app.schemas import MaterialSnapshot


class MaterialsSourceError(RuntimeError):
    pass


class SpringMaterialsSource:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: httpx.AsyncClient | None = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                base_url=self._settings.spring_base_url,
                timeout=self._settings.spring_timeout,
                headers={self._settings.ai_internal_token_header: self._settings.ai_internal_token},
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def fetch_all(self, *, since: datetime | None = None) -> AsyncIterator[MaterialSnapshot]:
        client = await self._get_client()
        page = 0
        while True:
            params: dict[str, object] = {"page": page, "size": 100}
            if since is not None:
                params["since"] = since.isoformat()
            try:
                response = await client.get("/api/internal/ai/materials", params=params)
            except httpx.HTTPError as exc:
                raise MaterialsSourceError(f"无法访问 Spring 资料接口：{exc}") from exc
            if response.status_code != 200:
                raise MaterialsSourceError(
                    f"Spring 资料接口返回 HTTP {response.status_code}：{response.text[:200]}"
                )
            payload = response.json()
            items = payload.get("items") or []
            for item in items:
                yield MaterialSnapshot.model_validate(item)
            if not items or not payload.get("has_more"):
                break
            page += 1

"""Semantic retrieval on top of pgvector."""

from __future__ import annotations

from app.config import Settings
from app.core.llm import LlmClient
from app.db.store import PgChunkStore, SearchHit


class Retriever:
    def __init__(self, *, llm: LlmClient, store: PgChunkStore, settings: Settings) -> None:
        self._llm = llm
        self._store = store
        self._settings = settings

    async def search(
        self,
        query: str,
        *,
        top_k: int | None = None,
        min_score: float | None = None,
        category_id: int | None = None,
    ) -> list[SearchHit]:
        stripped = query.strip()
        if not stripped:
            return []
        vector = (await self._llm.embed([stripped]))[0]
        return await self._store.search(
            vector,
            top_k=top_k or self._settings.retrieval_top_k,
            min_score=self._settings.retrieval_min_score if min_score is None else min_score,
            category_id=category_id,
        )

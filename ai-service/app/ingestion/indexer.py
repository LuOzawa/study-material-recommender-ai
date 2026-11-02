"""Incremental vector index maintenance."""

from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass

from app.config import Settings
from app.core.llm import LlmClient
from app.db.store import PgChunkStore
from app.ingestion.chunking import build_document_text, split_chunks
from app.ingestion.source import SpringMaterialsSource
from app.schemas import IndexOutcome, MaterialSnapshot, RebuildResponse


@dataclass
class RebuildStats:
    indexed: int = 0
    skipped: int = 0
    removed: int = 0
    failed: list[int] | None = None
    chunks: int = 0


class MaterialIndexer:
    def __init__(
        self,
        *,
        settings: Settings,
        llm: LlmClient,
        store: PgChunkStore,
        source: SpringMaterialsSource,
    ) -> None:
        self._settings = settings
        self._llm = llm
        self._store = store
        self._source = source

    def _digest(self, text: str) -> str:
        payload = f"{text}|{self._settings.chunk_size}|{self._settings.chunk_overlap}"
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    async def sync(self, material: MaterialSnapshot, *, force: bool = False) -> IndexOutcome:
        material_id = material.id
        if material.status != "ACTIVE":
            await self._store.delete_material(material_id)
            return IndexOutcome(material_id=material_id, status="removed", chunks=0)

        text = build_document_text(
            title=material.title,
            description=material.description,
            tags=material.tags,
            category_name=material.category_name,
            author=material.author,
        )
        digest = self._digest(text)
        if not force and await self._store.content_hash(material_id) == digest:
            existing = len(await self._store.material_chunks(material_id))
            return IndexOutcome(material_id=material_id, status="skipped", chunks=existing)

        chunks = split_chunks(text, size=self._settings.chunk_size, overlap=self._settings.chunk_overlap)
        if not chunks:
            await self._store.delete_material(material_id)
            return IndexOutcome(material_id=material_id, status="removed", chunks=0)

        embeddings = await self._llm.embed([chunk.content for chunk in chunks])
        await self._store.replace_material(
            material_id=material_id,
            title=material.title,
            tags=material.tags,
            category_id=material.category_id,
            chunks=chunks,
            embeddings=embeddings,
            content_hash=digest,
            source_updated_at=material.updated_at,
        )
        return IndexOutcome(material_id=material_id, status="indexed", chunks=len(chunks))

    async def rebuild(self, *, concurrency: int = 4) -> RebuildResponse:
        started = time.perf_counter()
        semaphore = asyncio.Semaphore(concurrency)
        stats = RebuildStats(failed=[])
        seen: list[int] = []
        outcomes: list[IndexOutcome] = []

        async def worker(material: MaterialSnapshot) -> None:
            async with semaphore:
                try:
                    outcomes.append(await self.sync(material))
                except Exception:  # noqa: BLE001 - one bad material must not abort the rebuild
                    stats.failed.append(material.id)  # type: ignore[union-attr]

        tasks: list[asyncio.Task[None]] = []
        async for material in self._source.fetch_all():
            seen.append(material.id)
            tasks.append(asyncio.create_task(worker(material)))
        if tasks:
            await asyncio.gather(*tasks)

        indexed_ids = await self._store.indexed_ids()
        stale = sorted(set(indexed_ids) - set(seen))
        if stale:
            await self._store.delete_material_many(stale)

        stats.indexed = sum(1 for outcome in outcomes if outcome.status == "indexed")
        stats.skipped = sum(1 for outcome in outcomes if outcome.status == "skipped")
        stats.removed = len(stale)
        stats.chunks = sum(outcome.chunks for outcome in outcomes)
        return RebuildResponse(
            indexed=stats.indexed,
            skipped=stats.skipped,
            removed=stats.removed,
            failed=stats.failed or [],
            chunks=stats.chunks,
            duration_ms=int((time.perf_counter() - started) * 1000),
        )

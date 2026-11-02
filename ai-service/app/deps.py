"""Shared service dependencies, built once at application startup."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import asyncpg

from app.config import Settings
from app.core.llm import LlmClient
from app.db.store import PgChunkStore
from app.graph.builder import build_chat_graph
from app.ingestion.indexer import MaterialIndexer
from app.ingestion.source import SpringMaterialsSource
from app.rag.retriever import Retriever


@dataclass
class Deps:
    settings: Settings
    llm: LlmClient
    store: PgChunkStore
    retriever: Retriever
    source: SpringMaterialsSource
    indexer: MaterialIndexer
    graph: Any
    pool: asyncpg.Pool | None = None

    async def close(self) -> None:
        if self.pool is not None:
            await self.pool.close()
        await self.source.aclose()
        await self.llm.aclose()


async def build_deps(settings: Settings) -> Deps:
    pool = await asyncpg.create_pool(dsn=settings.database_url, min_size=1, max_size=10)
    store = PgChunkStore(pool, embedding_dim=settings.embedding_dim)
    await store.init_schema(settings.schema_path)

    llm = LlmClient(settings)
    retriever = Retriever(llm=llm, store=store, settings=settings)
    source = SpringMaterialsSource(settings)
    indexer = MaterialIndexer(settings=settings, llm=llm, store=store, source=source)
    graph = build_chat_graph(llm=llm, retriever=retriever, settings=settings)

    return Deps(
        settings=settings,
        llm=llm,
        store=store,
        retriever=retriever,
        source=source,
        indexer=indexer,
        graph=graph,
        pool=pool,
    )

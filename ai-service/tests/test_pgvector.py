"""Integration tests against a real PostgreSQL + pgvector server.

Locally the suite starts an embedded PostgreSQL with the pgvector extension via
`pgserver`; in CI it connects to the `TEST_DATABASE_URL` service container.
"""

from __future__ import annotations

import os
import tempfile
import uuid

import asyncpg
import pytest

from app.config import Settings
from app.db.store import PgChunkStore
from app.ingestion.indexer import MaterialIndexer
from app.schemas import MaterialSnapshot

from tests.conftest import FakeSource, ScriptedLlm


@pytest.fixture(scope="session")
def pg_url():
    url = os.getenv("TEST_DATABASE_URL")
    if url:
        yield url
        return
    pgserver = pytest.importorskip("pgserver")
    # initdb cannot open directories created by tempfile.mkdtemp on Windows,
    # so hand pgserver a fresh, non-existent path and let it create the cluster.
    data_dir = os.path.join(
        tempfile.gettempdir(), f"study-ai-pg-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    )
    server = pgserver.get_server(data_dir)
    try:
        yield server.get_uri()
    finally:
        server.cleanup()


@pytest.fixture
def schema_path() -> str:
    return str(Settings().schema_path)


@pytest.fixture
async def store(pg_url, schema_path):
    pool = await asyncpg.create_pool(dsn=pg_url, min_size=1, max_size=4)
    async with pool.acquire() as conn:
        await conn.execute(
            "DROP TABLE IF EXISTS ai_material_chunks, ai_material_index_state, ai_index_meta"
        )
    instance = PgChunkStore(pool, embedding_dim=3)
    await instance.init_schema(Settings().schema_path)
    yield instance
    await pool.close()


async def _index(store, material_id: int, vector: list[float], *, chunks: int = 1, title: str = "资料") -> None:
    from app.ingestion.chunking import TextChunk

    payload = [TextChunk(index=i, content=f"片段{i}") for i in range(chunks)]
    await store.replace_material(
        material_id=material_id,
        title=title,
        tags="tag",
        category_id=1,
        chunks=payload,
        embeddings=[vector for _ in payload],
        content_hash=f"hash-{material_id}-{chunks}",
        source_updated_at=None,
    )


async def test_schema_initialisation_is_idempotent(store, schema_path) -> None:
    await store.init_schema(Settings().schema_path)
    stats = await store.stats()
    assert stats == {"materials": 0, "chunks": 0, "last_indexed_at": None}


async def test_cosine_search_orders_by_similarity(store) -> None:
    await _index(store, 1, [1.0, 0.0, 0.0], title="数学")
    await _index(store, 2, [0.0, 1.0, 0.0], title="英语")
    await _index(store, 3, [0.9, 0.1, 0.0], title="数学真题")

    hits = await store.search([1.0, 0.0, 0.0], top_k=3, min_score=-1.0)
    assert [hit.material_id for hit in hits] == [1, 3, 2]
    assert hits[0].score == pytest.approx(1.0)
    assert hits[2].score == pytest.approx(0.0, abs=1e-6)


async def test_min_score_filters_low_relevance(store) -> None:
    await _index(store, 1, [1.0, 0.0, 0.0])
    await _index(store, 2, [0.0, 1.0, 0.0])
    hits = await store.search([1.0, 0.0, 0.0], top_k=5, min_score=0.5)
    assert [hit.material_id for hit in hits] == [1]


async def test_reindexing_drops_stale_chunks(store) -> None:
    await _index(store, 1, [1.0, 0.0, 0.0], chunks=3)
    assert len(await store.material_chunks(1)) == 3
    await _index(store, 1, [1.0, 0.0, 0.0], chunks=1)
    assert len(await store.material_chunks(1)) == 1
    stats = await store.stats()
    assert stats["chunks"] == 1


async def test_delete_material_removes_chunks_and_state(store) -> None:
    await _index(store, 5, [1.0, 0.0, 0.0], chunks=2)
    assert await store.content_hash(5) == "hash-5-2"
    assert await store.delete_material(5) == 2
    assert await store.content_hash(5) is None
    assert await store.material_chunks(5) == []


async def test_delete_missing_keeps_requested_ids(store) -> None:
    await _index(store, 1, [1.0, 0.0, 0.0])
    await _index(store, 2, [0.0, 1.0, 0.0])
    await store.delete_missing([1])
    assert await store.indexed_ids() == [1]


async def test_rebuild_indexes_skips_and_removes(settings, store) -> None:
    indexer_settings = settings.model_copy(update={"embedding_dim": 3})
    material = MaterialSnapshot(
        id=1,
        title="数学一真题",
        description="极限、导数、积分。",
        tags="数学",
        category_id=1,
    )
    source = FakeSource([material])
    indexer = MaterialIndexer(
        settings=indexer_settings,
        llm=ScriptedLlm(dim=3),
        store=store,
        source=source,
    )

    first = await indexer.rebuild()
    assert (first.indexed, first.skipped, first.removed, first.chunks) == (1, 0, 0, 1)

    second = await indexer.rebuild()
    assert (second.indexed, second.skipped, second.removed) == (0, 1, 0)

    source.materials = []
    third = await indexer.rebuild()
    assert (third.indexed, third.skipped, third.removed) == (0, 0, 1)
    assert (await store.stats())["chunks"] == 0


async def test_pending_material_is_removed_from_index(settings, store) -> None:
    await _index(store, 9, [1.0, 0.0, 0.0])
    indexer = MaterialIndexer(
        settings=settings.model_copy(update={"embedding_dim": 3}),
        llm=ScriptedLlm(dim=3),
        store=store,
        source=FakeSource([]),
    )
    outcome = await indexer.sync(MaterialSnapshot(id=9, title="待审核资料", status="PENDING"))
    assert outcome.status == "removed"
    assert await store.indexed_ids() == []

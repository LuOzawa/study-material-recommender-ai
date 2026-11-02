"""pgvector persistence layer.

All vector work lives here: schema bootstrap, chunk upserts, cosine search and
index bookkeeping.  The rest of the service only sees plain dictionaries.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import asyncpg

from app.core.llm import vector_literal
from app.ingestion.chunking import TextChunk


@dataclass(frozen=True)
class SearchHit:
    material_id: int
    chunk_index: int
    title: str
    content: str
    tags: str | None
    category_id: int | None
    score: float


class PgChunkStore:
    def __init__(self, pool: asyncpg.Pool, *, embedding_dim: int) -> None:
        self._pool = pool
        self.embedding_dim = embedding_dim

    # ------------------------------------------------------------------
    # Schema
    # ------------------------------------------------------------------
    async def init_schema(self, schema_path: Path) -> None:
        sql = schema_path.read_text(encoding="utf-8").replace("${dim}", str(self.embedding_dim))
        async with self._pool.acquire() as conn:
            await conn.execute(sql)
            row = await conn.fetchrow("SELECT value FROM ai_index_meta WHERE key = $1", "embedding_dim")
            if row is None:
                await conn.execute(
                    "INSERT INTO ai_index_meta (key, value) VALUES ($1, $2)", "embedding_dim", str(self.embedding_dim)
                )
            elif int(row["value"]) != self.embedding_dim:
                raise RuntimeError(
                    f"向量表维度 {row['value']} 与 EMBEDDING_DIM={self.embedding_dim} 不一致；"
                    "请清空 ai_material_chunks 后重建索引。"
                )

    # ------------------------------------------------------------------
    # Bookkeeping
    # ------------------------------------------------------------------
    async def content_hash(self, material_id: int) -> str | None:
        async with self._pool.acquire() as conn:
            return await conn.fetchval(
                "SELECT content_hash FROM ai_material_index_state WHERE material_id = $1", material_id
            )

    async def replace_material(
        self,
        *,
        material_id: int,
        title: str,
        tags: str | None,
        category_id: int | None,
        chunks: list[TextChunk],
        embeddings: list[list[float]],
        content_hash: str,
        source_updated_at: datetime | None,
    ) -> None:
        """Upsert chunks with material level metadata (title/tags/category)."""

        if len(chunks) != len(embeddings):
            raise ValueError("chunks and embeddings must align")

        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute(
                "DELETE FROM ai_material_chunks WHERE material_id = $1 AND chunk_index >= $2",
                material_id,
                len(chunks),
            )
            await conn.executemany(
                """
                INSERT INTO ai_material_chunks
                    (material_id, chunk_index, title, content, tags, category_id, embedding)
                VALUES ($1, $2, $3, $4, $5, $6, $7::vector)
                ON CONFLICT (material_id, chunk_index) DO UPDATE SET
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    tags = EXCLUDED.tags,
                    category_id = EXCLUDED.category_id,
                    embedding = EXCLUDED.embedding,
                    updated_at = now()
                """,
                [
                    (
                        material_id,
                        chunk.index,
                        title,
                        chunk.content,
                        tags,
                        category_id,
                        vector_literal(embeddings[position]),
                    )
                    for position, chunk in enumerate(chunks)
                ],
            )
            await conn.execute(
                """
                INSERT INTO ai_material_index_state
                    (material_id, content_hash, chunk_count, source_updated_at, indexed_at)
                VALUES ($1, $2, $3, $4, now())
                ON CONFLICT (material_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    chunk_count = EXCLUDED.chunk_count,
                    source_updated_at = EXCLUDED.source_updated_at,
                    indexed_at = now()
                """,
                material_id,
                content_hash,
                len(chunks),
                source_updated_at,
            )

    async def delete_material(self, material_id: int) -> int:
        async with self._pool.acquire() as conn, conn.transaction():
            deleted = await conn.execute("DELETE FROM ai_material_chunks WHERE material_id = $1", material_id)
            await conn.execute("DELETE FROM ai_material_index_state WHERE material_id = $1", material_id)
        return int(deleted.split()[-1])

    async def delete_material_many(self, material_ids: list[int]) -> int:
        if not material_ids:
            return 0
        async with self._pool.acquire() as conn, conn.transaction():
            deleted = await conn.execute(
                "DELETE FROM ai_material_chunks WHERE material_id = ANY($1::bigint[])", material_ids
            )
            await conn.execute(
                "DELETE FROM ai_material_index_state WHERE material_id = ANY($1::bigint[])", material_ids
            )
        return int(deleted.split()[-1])

    async def delete_missing(self, keep_ids: list[int]) -> int:
        async with self._pool.acquire() as conn, conn.transaction():
            if keep_ids:
                result = await conn.execute(
                    "DELETE FROM ai_material_chunks WHERE NOT (material_id = ANY($1::bigint[]))", keep_ids
                )
                await conn.execute(
                    "DELETE FROM ai_material_index_state WHERE NOT (material_id = ANY($1::bigint[]))", keep_ids
                )
            else:
                result = await conn.execute("DELETE FROM ai_material_chunks")
                await conn.execute("DELETE FROM ai_material_index_state")
        return int(result.split()[-1])

    # ------------------------------------------------------------------
    # Search
    # ------------------------------------------------------------------
    async def search(
        self,
        embedding: list[float],
        *,
        top_k: int,
        min_score: float,
        category_id: int | None = None,
    ) -> list[SearchHit]:
        literal = vector_literal(embedding)
        if category_id is None:
            rows = await self._pool.fetch(
                """
                SELECT material_id, chunk_index, title, content, tags, category_id,
                       1 - (embedding <=> $1::vector) AS score
                FROM ai_material_chunks
                WHERE 1 - (embedding <=> $1::vector) >= $2
                ORDER BY embedding <=> $1::vector
                LIMIT $3
                """,
                literal,
                min_score,
                top_k,
            )
        else:
            rows = await self._pool.fetch(
                """
                SELECT material_id, chunk_index, title, content, tags, category_id,
                       1 - (embedding <=> $1::vector) AS score
                FROM ai_material_chunks
                WHERE category_id = $4
                  AND 1 - (embedding <=> $1::vector) >= $2
                ORDER BY embedding <=> $1::vector
                LIMIT $3
                """,
                literal,
                min_score,
                top_k,
                category_id,
            )
        return [
            SearchHit(
                material_id=row["material_id"],
                chunk_index=row["chunk_index"],
                title=row["title"],
                content=row["content"],
                tags=row["tags"],
                category_id=row["category_id"],
                score=float(row["score"]),
            )
            for row in rows
        ]

    async def material_chunks(self, material_id: int) -> list[SearchHit]:
        rows = await self._pool.fetch(
            """
            SELECT material_id, chunk_index, title, content, tags, category_id, 1.0::float AS score
            FROM ai_material_chunks
            WHERE material_id = $1
            ORDER BY chunk_index
            """,
            material_id,
        )
        return [
            SearchHit(
                material_id=row["material_id"],
                chunk_index=row["chunk_index"],
                title=row["title"],
                content=row["content"],
                tags=row["tags"],
                category_id=row["category_id"],
                score=1.0,
            )
            for row in rows
        ]

    async def stats(self) -> dict:
        row = await self._pool.fetchrow(
            """
            SELECT
                (SELECT count(DISTINCT material_id) FROM ai_material_chunks) AS materials,
                (SELECT count(*) FROM ai_material_chunks) AS chunks,
                (SELECT max(indexed_at) FROM ai_material_index_state) AS last_indexed_at
            """
        )
        return {
            "materials": int(row["materials"] or 0),
            "chunks": int(row["chunks"] or 0),
            "last_indexed_at": row["last_indexed_at"],
        }

    async def indexed_ids(self) -> list[int]:
        rows = await self._pool.fetch("SELECT material_id FROM ai_material_index_state")
        return [int(row["material_id"]) for row in rows]

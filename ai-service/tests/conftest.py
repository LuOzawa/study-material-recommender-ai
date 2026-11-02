from __future__ import annotations

from collections.abc import Sequence

import pytest

from app.config import Settings
from app.core.llm import LlmBadResponse
from app.db.store import SearchHit


@pytest.fixture
def settings() -> Settings:
    return Settings(
        database_url="postgresql://unused:unused@127.0.0.1:1/unused",
        ai_internal_token="test-internal-token",
        embedding_dim=8,
        chunk_size=60,
        chunk_overlap=10,
        retrieval_top_k=3,
        retrieval_min_score=0.0,
        max_query_rewrites=1,
        vllm_base_url="http://127.0.0.1:1/v1",
    )


class ScriptedLlm:
    """Deterministic stand-in for vLLM, keyed on the system prompt role."""

    def __init__(
        self,
        *,
        route: str = "materials",
        search_query: str = "数学一 复习资料",
        relevant: list[int] | None = None,
        rewrite: str = "数学 高数 复习资料",
        answer: str = "数学一重点是极限与导数[1]。",
        vectors: dict[str, list[float]] | None = None,
        dim: int = 8,
        fail_chat: bool = False,
        grade_raises: bool = False,
    ) -> None:
        self.route = route
        self.search_query = search_query
        self.relevant = [1] if relevant is None else relevant
        self.rewrite = rewrite
        self.answer = answer
        self.vectors = vectors or {}
        self.dim = dim
        self.fail_chat = fail_chat
        self.grade_raises = grade_raises
        self.chat_calls: list[list[dict[str, str]]] = []
        self.json_calls: list[str] = []
        self.embed_calls: list[list[str]] = []

    async def chat(self, messages, *, temperature: float = 0.2, max_tokens: int = 1024) -> str:
        self.chat_calls.append(messages)
        if self.fail_chat:
            from app.core.llm import LlmUnavailable

            raise LlmUnavailable("vLLM 不可达：connection refused")
        return self.answer

    async def chat_json(self, *, system: str, user: str, max_tokens: int = 512) -> dict:
        self.json_calls.append(system)
        if "意图路由器" in system:
            return {"route": self.route, "search_query": self.search_query}
        if "相关性评审" in system:
            if self.grade_raises:
                raise LlmBadResponse("模型输出不是 JSON")
            return {"relevant": self.relevant}
        if "改写成" in system:
            return {"query": self.rewrite}
        raise AssertionError(f"unexpected prompt: {system[:40]}")

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        self.embed_calls.append(list(texts))
        vectors = []
        for text in texts:
            if text in self.vectors:
                vectors.append(self.vectors[text])
            else:
                seed = sum(ord(char) for char in text) % 7 + 1
                vector = [0.0] * self.dim
                vector[seed % self.dim] = 1.0
                vectors.append(vector)
        return vectors

    async def health(self) -> dict:
        return {"reachable": not self.fail_chat, "models": ["fake-chat", "fake-embed"]}

    async def aclose(self) -> None:
        return None


class FakeRetriever:
    def __init__(self, hits: list[SearchHit] | None = None) -> None:
        self.hits = hits or [
            SearchHit(
                material_id=11,
                chunk_index=0,
                title="数学一高数复习提纲",
                content="极限、导数、积分是数学一的高频考点。",
                tags="数学,高数",
                category_id=1,
                score=0.91,
            ),
            SearchHit(
                material_id=12,
                chunk_index=0,
                title="英语阅读真题",
                content="阅读理解的题型与解题步骤。",
                tags="英语",
                category_id=2,
                score=0.42,
            ),
        ]
        self.queries: list[str] = []

    async def search(self, query: str, *, top_k: int | None = None, min_score: float | None = None, category_id=None):
        self.queries.append(query)
        return self.hits[: (top_k or len(self.hits))]


class FakeStore:
    def __init__(self) -> None:
        self.deleted: list[int] = []
        self.chunks_by_material: dict[int, list[SearchHit]] = {}

    async def stats(self) -> dict:
        return {"materials": 2, "chunks": 3, "last_indexed_at": None}

    async def material_chunks(self, material_id: int) -> list[SearchHit]:
        return self.chunks_by_material.get(material_id, [])

    async def delete_material(self, material_id: int) -> int:
        self.deleted.append(material_id)
        return 1

    async def content_hash(self, material_id: int) -> str | None:
        return None

    async def replace_material(self, **kwargs) -> None:
        return None

    async def delete_material_many(self, material_ids: list[int]) -> int:
        self.deleted.extend(material_ids)
        return len(material_ids)

    async def indexed_ids(self) -> list[int]:
        return []


class FakeSource:
    def __init__(self, materials: list | None = None) -> None:
        self.materials = materials or []

    async def fetch_all(self, *, since=None):
        for material in self.materials:
            yield material

    async def aclose(self) -> None:
        return None


class FakeIndexer:
    def __init__(self) -> None:
        self.synced: list[int] = []

    async def sync(self, material, *, force: bool = False):
        from app.schemas import IndexOutcome

        self.synced.append(material.id)
        return IndexOutcome(material_id=material.id, status="indexed", chunks=1)

    async def rebuild(self):
        from app.schemas import RebuildResponse

        return RebuildResponse(indexed=0, skipped=0, removed=0, failed=[], chunks=0, duration_ms=1)

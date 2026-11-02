from __future__ import annotations

from types import SimpleNamespace

import pytest
from httpx import ASGITransport, AsyncClient

from app.core.llm import LlmUnavailable
from app.graph.builder import build_chat_graph
from app.main import create_app

from tests.conftest import FakeIndexer, FakeRetriever, FakeSource, FakeStore, ScriptedLlm


@pytest.fixture
def deps(settings):
    llm = ScriptedLlm(relevant=[1])
    retriever = FakeRetriever()
    graph = build_chat_graph(llm=llm, retriever=retriever, settings=settings)
    return SimpleNamespace(
        settings=settings,
        llm=llm,
        store=FakeStore(),
        retriever=retriever,
        source=FakeSource(),
        indexer=FakeIndexer(),
        graph=graph,
        pool=None,
        close=lambda: None,
    )


@pytest.fixture
def app(settings, deps):
    return create_app(settings=settings, deps=deps)


@pytest.fixture
async def client(app):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as http:
        yield http


def auth(settings) -> dict[str, str]:
    return {settings.ai_internal_token_header: settings.ai_internal_token}


async def test_health_endpoint_is_public(client) -> None:
    response = await client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_internal_endpoints_require_token(client, settings) -> None:
    assert (await client.post("/internal/v1/agent/chat", json={"user_id": 1, "question": "x"})).status_code == 401
    assert (await client.get("/internal/v1/index/stats")).status_code == 401
    wrong = {settings.ai_internal_token_header: "nope"}
    assert (await client.get("/internal/v1/index/stats", headers=wrong)).status_code == 401


async def test_chat_returns_answer_and_citations(client, settings) -> None:
    response = await client.post(
        "/internal/v1/agent/chat",
        headers=auth(settings),
        json={"user_id": 1, "question": "数学一重点？"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["answer"]
    assert body["citations"][0]["material_id"] == 11
    assert body["model"] == settings.vllm_chat_model
    assert body["route"] == "materials"


async def test_search_returns_vector_matches(client, settings) -> None:
    response = await client.get(
        "/internal/v1/search",
        headers=auth(settings),
        params={"q": "数学 极限", "top_k": 1},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["query"] == "数学 极限"
    assert len(body["matches"]) == 1
    assert body["matches"][0]["material_id"] == 11
    assert body["matches"][0]["tags"] == "数学,高数"


async def test_index_push_and_delete(client, settings, deps) -> None:
    payload = {
        "id": 77,
        "title": "政治冲刺背诵手册",
        "description": "马原、毛中特、史纲、思修重点。",
        "tags": "政治,背诵",
        "category_id": 3,
        "category_name": "政治",
        "status": "ACTIVE",
        "updated_at": "2026-10-01T08:00:00",
    }
    response = await client.post("/internal/v1/index/materials", headers=auth(settings), json=payload)
    assert response.status_code == 200
    assert response.json() == {"material_id": 77, "status": "indexed", "chunks": 1}
    assert deps.indexer.synced == [77]

    deleted = await client.delete("/internal/v1/index/materials/77", headers=auth(settings))
    assert deleted.status_code == 200
    assert deleted.json() == {"material_id": 77, "deleted_chunks": 1}
    assert deps.store.deleted == [77]


async def test_llm_outage_maps_to_503(settings, deps) -> None:
    class BrokenGraph:
        async def ainvoke(self, state, config=None):
            raise LlmUnavailable("vLLM 不可达：connection refused")

    deps.graph = BrokenGraph()
    app = create_app(settings=settings, deps=deps)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as http:
        response = await http.post(
            "/internal/v1/agent/chat",
            headers=auth(settings),
            json={"user_id": 1, "question": "数学"},
        )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "LLM_UNAVAILABLE"


async def test_summary_falls_back_to_snapshot_when_not_indexed(settings, deps) -> None:
    deps.store.chunks_by_material[404] = []
    payload = {
        "user_id": 1,
        "material_id": 404,
        "material": {
            "id": 404,
            "title": "线性代数讲义",
            "description": "矩阵、特征值、二次型。",
            "tags": "数学,线代",
        },
    }
    app = create_app(settings=settings, deps=deps)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as http:
        response = await http.post("/internal/v1/agent/summarize", headers=auth(settings), json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["grounded"] is False
    assert body["chunks"] == 0


async def test_summary_without_snapshot_returns_404(settings, deps) -> None:
    app = create_app(settings=settings, deps=deps)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as http:
        response = await http.post(
            "/internal/v1/agent/summarize",
            headers=auth(settings),
            json={"user_id": 1, "material_id": 999},
        )
    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "MATERIAL_NOT_INDEXED"

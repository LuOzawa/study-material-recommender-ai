"""HTTP surface for the Spring Boot backend and probes."""

from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request

from app.deps import Deps
from app.schemas import (
    ChatRequest,
    ChatResponse,
    IndexOutcome,
    MaterialSnapshot,
    RebuildResponse,
    SearchResponse,
    StatsResponse,
    SuggestRequest,
    SuggestResponse,
    SummarizeRequest,
    SummarizeResponse,
)
from app.services import assistant

router = APIRouter()


def get_deps(request: Request) -> Deps:
    deps = getattr(request.app.state, "deps", None)
    if deps is None:  # pragma: no cover - guarded by lifespan
        raise HTTPException(status_code=503, detail="服务尚未初始化")
    return deps


def require_internal_token(
    request: Request,
    deps: Deps = Depends(get_deps),
) -> None:
    expected = deps.settings.ai_internal_token
    if not expected:
        raise HTTPException(status_code=500, detail="AI_INTERNAL_TOKEN 未配置")
    provided = request.headers.get(deps.settings.ai_internal_token_header, "")
    if not secrets.compare_digest(provided, expected):
        raise HTTPException(status_code=401, detail="内部令牌无效")


protected = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_internal_token)])


@router.get("/healthz")
async def healthz() -> dict:
    return {"status": "ok"}


@router.get("/readyz")
async def readyz(deps: Deps = Depends(get_deps)) -> dict:
    db_ok = False
    db_error = None
    llm_health = {"reachable": False}
    try:
        if deps.pool is not None:
            await deps.pool.fetchval("SELECT 1")
        db_ok = True
    except Exception as exc:  # noqa: BLE001 - readiness reports instead of raising
        db_error = str(exc)
    try:
        llm_health = await deps.llm.health()
    except Exception as exc:  # noqa: BLE001
        llm_health = {"reachable": False, "error": str(exc)}
    return {
        "status": "ready" if db_ok else "degraded",
        "checks": {"postgres": {"ok": db_ok, "error": db_error}, "vllm": llm_health},
    }


@protected.post("/agent/chat", response_model=ChatResponse)
async def chat(request: ChatRequest, deps: Deps = Depends(get_deps)) -> ChatResponse:
    return await assistant.answer_question(deps, request)


@protected.post("/agent/summarize", response_model=SummarizeResponse)
async def summarize(request: SummarizeRequest, deps: Deps = Depends(get_deps)) -> SummarizeResponse:
    return await assistant.summarize_material(deps, material_id=request.material_id, snapshot=request.material)


@protected.post("/agent/suggest", response_model=SuggestResponse)
async def suggest(request: SuggestRequest, deps: Deps = Depends(get_deps)) -> SuggestResponse:
    return await assistant.suggest_plan(deps, request)


@protected.get("/search", response_model=SearchResponse)
async def search(
    q: str = Query(min_length=1, max_length=500),
    top_k: int = Query(default=8, ge=1, le=50),
    deps: Deps = Depends(get_deps),
) -> SearchResponse:
    return await assistant.semantic_search(deps, query=q, top_k=top_k)


@protected.post("/index/materials", response_model=IndexOutcome)
async def index_material(material: MaterialSnapshot, deps: Deps = Depends(get_deps)) -> IndexOutcome:
    return await deps.indexer.sync(material)


@protected.delete("/index/materials/{material_id}")
async def delete_material(material_id: int, deps: Deps = Depends(get_deps)) -> dict:
    deleted = await deps.store.delete_material(material_id)
    return {"material_id": material_id, "deleted_chunks": deleted}


@protected.post("/index/rebuild", response_model=RebuildResponse)
async def rebuild(deps: Deps = Depends(get_deps)) -> RebuildResponse:
    return await deps.indexer.rebuild()


@protected.get("/index/stats", response_model=StatsResponse)
async def index_stats(deps: Deps = Depends(get_deps)) -> StatsResponse:
    stats = await deps.store.stats()
    return StatsResponse(
        materials=stats["materials"],
        chunks=stats["chunks"],
        embedding_dim=deps.settings.embedding_dim,
        last_indexed_at=stats["last_indexed_at"],
        llm_model=deps.settings.vllm_chat_model,
        embedding_model=deps.settings.vllm_embedding_model,
    )

"""FastAPI entrypoint for the study material AI service."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.api.routes import internal
from app.config import Settings
from app.core.llm import LlmBadResponse, LlmUnavailable
from app.deps import Deps, build_deps
from app.ingestion.source import MaterialsSourceError
from app.services.assistant import MaterialNotIndexed

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, deps: Deps | None = None) -> FastAPI:
    resolved = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        logging.basicConfig(
            level=getattr(logging, resolved.log_level.upper(), logging.INFO),
            format="%(asctime)s %(levelname)s %(name)s %(message)s",
        )
        if app.state.deps is None:
            logger.info("initialising AI dependencies (pgvector=%s, vllm=%s)", resolved.database_url, resolved.vllm_base_url)
            app.state.deps = await build_deps(resolved)
        yield
        current = app.state.deps
        if current is not None:
            await current.close()

    app = FastAPI(
        title="study-ai-service",
        version="1.0.0",
        description="LangGraph + vLLM + pgvector service for the study material recommender",
        lifespan=lifespan,
    )
    app.state.settings = resolved
    app.state.deps = deps
    app.include_router(internal.router)
    app.include_router(internal.protected)

    @app.exception_handler(LlmUnavailable)
    async def _llm_unavailable(_request, exc: LlmUnavailable):
        logger.warning("vLLM unavailable: %s", exc)
        return JSONResponse(status_code=503, content={"detail": {"code": "LLM_UNAVAILABLE", "message": str(exc)}})

    @app.exception_handler(LlmBadResponse)
    async def _llm_bad_response(_request, exc: LlmBadResponse):
        logger.warning("vLLM bad response: %s", exc)
        return JSONResponse(status_code=502, content={"detail": {"code": "LLM_BAD_RESPONSE", "message": str(exc)}})

    @app.exception_handler(MaterialsSourceError)
    async def _source_error(_request, exc: MaterialsSourceError):
        return JSONResponse(status_code=502, content={"detail": {"code": "SPRING_SOURCE_ERROR", "message": str(exc)}})

    @app.exception_handler(MaterialNotIndexed)
    async def _not_indexed(_request, exc: MaterialNotIndexed):
        return JSONResponse(status_code=404, content={"detail": {"code": "MATERIAL_NOT_INDEXED", "message": str(exc)}})

    return app


app = create_app()

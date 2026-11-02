"""Runtime configuration for the study AI service.

Every field can be overridden with an environment variable of the same name
(case-insensitive), which keeps the container image config-free.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent
PROJECT_DIR = BASE_DIR.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=PROJECT_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- PostgreSQL + pgvector -------------------------------------------
    database_url: str = "postgresql://postgres:postgres@localhost:5432/study_ai"
    embedding_dim: int = 1024

    # --- vLLM (OpenAI-compatible) ----------------------------------------
    vllm_base_url: str = "http://localhost:8000/v1"
    # Optional: serve chat and embeddings from different vLLM processes.
    vllm_embedding_base_url: str | None = None
    vllm_api_key: str = "EMPTY"
    vllm_chat_model: str = "qwen2.5-7b-instruct"
    vllm_embedding_model: str = "bge-m3"
    vllm_chat_timeout: float = 90.0
    vllm_embedding_timeout: float = 60.0

    # --- Spring Boot source of truth -------------------------------------
    spring_base_url: str = "http://localhost:8080"
    spring_timeout: float = 15.0
    ai_internal_token: str = "change-me-internal-token"
    ai_internal_token_header: str = "X-Internal-Token"

    # --- Retrieval / indexing --------------------------------------------
    chunk_size: int = 420
    chunk_overlap: int = 80
    retrieval_top_k: int = 6
    retrieval_min_score: float = 0.25
    max_query_rewrites: int = 1
    embedding_batch_size: int = 16

    # --- Server -----------------------------------------------------------
    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"

    @field_validator("embedding_dim")
    @classmethod
    def _check_dim(cls, value: int) -> int:
        if value <= 0:
            raise ValueError("EMBEDDING_DIM must be positive")
        return value

    @field_validator("chunk_overlap")
    @classmethod
    def _check_overlap(cls, value: int, info) -> int:
        chunk_size = info.data.get("chunk_size", 420)
        if value < 0 or value >= chunk_size:
            raise ValueError("CHUNK_OVERLAP must be >= 0 and smaller than CHUNK_SIZE")
        return value

    @field_validator("retrieval_top_k")
    @classmethod
    def _check_top_k(cls, value: int) -> int:
        if not 1 <= value <= 50:
            raise ValueError("RETRIEVAL_TOP_K must be within 1..50")
        return value

    @property
    def schema_path(self) -> Path:
        return BASE_DIR / "db" / "schema.sql"

"""Pydantic contracts shared by the HTTP API and the Spring client."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class MaterialSnapshot(BaseModel):
    """Material payload owned by the Spring Boot backend."""

    model_config = ConfigDict(extra="ignore")

    id: int
    title: str
    description: str | None = None
    tags: str | None = None
    category_id: int | None = None
    category_name: str | None = None
    status: str = "ACTIVE"
    author: str | None = None
    rating_avg: float | None = None
    updated_at: datetime | None = None


class ChatTurn(BaseModel):
    question: str
    answer: str


class ChatRequest(BaseModel):
    user_id: int
    question: str = Field(min_length=1, max_length=4000)
    history: list[ChatTurn] = Field(default_factory=list, max_length=6)
    session_id: str | None = None


class Citation(BaseModel):
    index: int = Field(description="1-based marker used in the answer text")
    material_id: int
    title: str
    chunk_index: int
    score: float
    snippet: str


class Reference(BaseModel):
    material_id: int
    title: str
    chunk_index: int
    score: float
    snippet: str


class ChatResponse(BaseModel):
    answer: str
    citations: list[Citation]
    references: list[Reference]
    route: Literal["materials", "general"]
    query: str
    rewrites: int
    model: str
    latency_ms: int


class SummarizeRequest(BaseModel):
    user_id: int
    material_id: int
    material: MaterialSnapshot | None = None


class SummarizeResponse(BaseModel):
    summary: str
    citations: list[Citation]
    grounded: bool
    chunks: int
    model: str


class SuggestRequest(BaseModel):
    user_id: int
    target_school: str | None = None
    major: str | None = None
    study_phase: str | None = None
    favorite_titles: list[str] = Field(default_factory=list, max_length=10)


class SuggestResponse(BaseModel):
    suggestions: str
    references: list[Reference]
    model: str


class SearchMatch(BaseModel):
    material_id: int
    title: str
    chunk_index: int
    score: float
    snippet: str
    tags: str | None = None


class SearchResponse(BaseModel):
    query: str
    matches: list[SearchMatch]


class IndexOutcome(BaseModel):
    material_id: int
    status: Literal["indexed", "skipped", "removed"]
    chunks: int


class RebuildResponse(BaseModel):
    indexed: int
    skipped: int
    removed: int
    failed: list[int]
    chunks: int
    duration_ms: int


class StatsResponse(BaseModel):
    materials: int
    chunks: int
    embedding_dim: int
    last_indexed_at: datetime | None = None
    llm_model: str
    embedding_model: str

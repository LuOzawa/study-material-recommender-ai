"""LangGraph state and nodes for agentic retrieval-augmented answering."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, TypedDict

from app.config import Settings
from app.core.llm import LlmBadResponse, LlmClient
from app.graph import prompts
from app.rag.retriever import Retriever

logger = logging.getLogger(__name__)


class AgentState(TypedDict, total=False):
    question: str
    history: list[dict[str, str]]
    route: str
    query: str
    documents: list[dict[str, Any]]
    answer: str
    rewrites: int
    max_rewrites: int
    notes: list[str]


@dataclass
class AgentDeps:
    llm: LlmClient
    retriever: Retriever
    settings: Settings


def _snippet(text: str, limit: int = 180) -> str:
    return " ".join(text.split())[:limit]


class AgentNodes:
    def __init__(self, deps: AgentDeps) -> None:
        self._llm = deps.llm
        self._retriever = deps.retriever
        self._settings = deps.settings

    # ------------------------------------------------------------------
    async def analyze(self, state: AgentState) -> dict:
        question = state["question"]
        try:
            payload = await self._llm.chat_json(
                system=prompts.ANALYZE_SYSTEM,
                user=f"用户问题：{question}",
                max_tokens=256,
            )
            route = payload.get("route")
            query = str(payload.get("search_query") or question).strip()
        except LlmBadResponse as exc:
            logger.warning("analyze fallback: %s", exc)
            return {"route": "materials", "query": question, "notes": ["analyze_fallback"]}

        if route not in {"materials", "general"}:
            route = "materials"
        return {"route": route, "query": query or question}

    async def retrieve(self, state: AgentState) -> dict:
        query = state.get("query") or state["question"]
        hits = await self._retriever.search(query)
        documents = [
            {
                "index": position + 1,
                "material_id": hit.material_id,
                "chunk_index": hit.chunk_index,
                "title": hit.title,
                "content": hit.content,
                "tags": hit.tags,
                "score": round(hit.score, 4),
            }
            for position, hit in enumerate(hits)
        ]
        return {"documents": documents}

    async def grade(self, state: AgentState) -> dict:
        documents = state.get("documents") or []
        if not documents:
            return {}

        listing = "\n\n".join(
            f"[{document['index']}] 《{document['title']}》\n{_snippet(document['content'], 300)}"
            for document in documents
        )
        try:
            payload = await self._llm.chat_json(
                system=prompts.GRADE_SYSTEM,
                user=f"用户问题：{state['question']}\n\n候选片段：\n{listing}",
                max_tokens=256,
            )
            raw = payload.get("relevant", [])
            keep = {int(value) for value in raw if isinstance(value, (int, str)) and str(value).isdigit()}
        except (LlmBadResponse, ValueError, TypeError) as exc:
            logger.warning("grade fallback (keep all): %s", exc)
            return {"notes": (state.get("notes") or []) + ["grade_fallback"]}

        kept = [document for document in documents if document["index"] in keep]
        for position, document in enumerate(kept):
            document["index"] = position + 1
        return {"documents": kept}

    async def rewrite(self, state: AgentState) -> dict:
        question = state["question"]
        attempt = state.get("rewrites", 0)
        try:
            payload = await self._llm.chat_json(
                system=prompts.REWRITE_SYSTEM,
                user=f"用户问题：{question}\n上一轮检索查询：{state.get('query') or question}",
                max_tokens=128,
            )
            query = str(payload.get("query") or "").strip()
        except LlmBadResponse as exc:
            logger.warning("rewrite fallback: %s", exc)
            query = ""
        if not query:
            query = f"{question} 复习资料"
        return {"query": query, "rewrites": attempt + 1}

    async def answer(self, state: AgentState) -> dict:
        documents = state.get("documents") or []
        if documents:
            context = "\n\n".join(
                f"[{document['index']}] 《{document['title']}》"
                + (f" 标签：{document['tags']}" if document.get("tags") else "")
                + f"\n{document['content']}"
                for document in documents
            )
        else:
            context = "（资料库未检索到相关片段）"

        messages: list[dict[str, str]] = [{"role": "system", "content": prompts.ANSWER_SYSTEM}]
        for turn in state.get("history") or []:
            question = str(turn.get("question", "")).strip()
            answer = str(turn.get("answer", "")).strip()
            if question and answer:
                messages.append({"role": "user", "content": question[:500]})
                messages.append({"role": "assistant", "content": answer[:800]})
        messages.append(
            {
                "role": "user",
                "content": f"【资料片段】\n{context[:6000]}\n\n【用户问题】\n{state['question']}",
            }
        )
        text = await self._llm.chat(messages, temperature=0.3, max_tokens=900)
        return {"answer": text}


def route_after_analyze(state: AgentState) -> str:
    return "retrieve" if state.get("route", "materials") == "materials" else "answer"


def route_after_grade(state: AgentState) -> str:
    if state.get("documents"):
        return "answer"
    if state.get("rewrites", 0) < (state.get("max_rewrites", 1)):
        return "rewrite"
    return "answer"

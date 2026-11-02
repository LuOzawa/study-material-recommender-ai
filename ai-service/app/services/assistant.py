"""Higher-level AI operations (chat / summarize / suggest / search)."""

from __future__ import annotations

import re
import time

from app.deps import Deps
from app.graph import prompts
from app.schemas import (
    ChatRequest,
    ChatResponse,
    ChatTurn,
    Citation,
    Reference,
    SearchMatch,
    SearchResponse,
    SuggestRequest,
    SuggestResponse,
    SummarizeResponse,
)

_CITATION = re.compile(r"\[(\d+)\]")


class MaterialNotIndexed(RuntimeError):
    pass


def _snippet(text: str, limit: int = 180) -> str:
    return " ".join(text.split())[:limit]


def _reference(document: dict) -> Reference:
    return Reference(
        material_id=document["material_id"],
        title=document["title"],
        chunk_index=document["chunk_index"],
        score=float(document["score"]),
        snippet=_snippet(document["content"]),
    )


def _citations(answer: str, documents: list[dict]) -> list[Citation]:
    by_index = {document["index"]: document for document in documents}
    seen: set[int] = set()
    citations: list[Citation] = []
    for raw in _CITATION.findall(answer):
        position = int(raw)
        if position in seen or position not in by_index:
            continue
        seen.add(position)
        document = by_index[position]
        citations.append(
            Citation(
                index=position,
                material_id=document["material_id"],
                title=document["title"],
                chunk_index=document["chunk_index"],
                score=float(document["score"]),
                snippet=_snippet(document["content"]),
            )
        )
    return sorted(citations, key=lambda item: item.index)


async def answer_question(deps: Deps, request: ChatRequest) -> ChatResponse:
    started = time.perf_counter()
    initial = {
        "question": request.question,
        "history": [turn.model_dump() for turn in request.history],
        "rewrites": 0,
        "max_rewrites": deps.settings.max_query_rewrites,
        "documents": [],
        "notes": [],
    }
    result = await deps.graph.ainvoke(initial, config={"recursion_limit": 10})

    documents = result.get("documents") or []
    answer = (result.get("answer") or "").strip() or "暂时无法生成回答，请稍后重试。"
    return ChatResponse(
        answer=answer,
        citations=_citations(answer, documents),
        references=[_reference(document) for document in documents],
        route="materials" if result.get("route") == "materials" else "general",
        query=result.get("query") or request.question,
        rewrites=int(result.get("rewrites") or 0),
        model=deps.settings.vllm_chat_model,
        latency_ms=int((time.perf_counter() - started) * 1000),
    )


async def summarize_material(deps: Deps, *, material_id: int, snapshot=None) -> SummarizeResponse:
    chunks = await deps.store.material_chunks(material_id)
    if chunks:
        body = "\n\n".join(
            f"[{position + 1}] 《{chunk.title}》\n{chunk.content}" for position, chunk in enumerate(chunks)
        )[:6000]
        user = f"资料编号：{material_id}\n\n资料片段：\n{body}"
        grounded = True
    elif snapshot is not None:
        body = "\n".join(
            part
            for part in [
                f"标题：{snapshot.title}",
                f"标签：{snapshot.tags}" if snapshot.tags else "",
                f"简介：{snapshot.description}" if snapshot.description else "",
            ]
            if part
        )
        user = f"资料编号：{material_id}\n\n资料信息（尚未建立向量索引）：\n{body}"
        grounded = False
    else:
        raise MaterialNotIndexed(f"资料 {material_id} 尚未建立向量索引，请先同步索引")

    summary = await deps.llm.chat(
        [
            {"role": "system", "content": prompts.SUMMARY_SYSTEM},
            {"role": "user", "content": user},
        ],
        temperature=0.2,
        max_tokens=400,
    )

    documents = [
        {
            "index": position + 1,
            "material_id": chunk.material_id,
            "chunk_index": chunk.chunk_index,
            "title": chunk.title,
            "content": chunk.content,
            "score": 1.0,
        }
        for position, chunk in enumerate(chunks)
    ]
    return SummarizeResponse(
        summary=summary,
        citations=_citations(summary, documents),
        grounded=grounded,
        chunks=len(chunks),
        model=deps.settings.vllm_chat_model,
    )


async def suggest_plan(deps: Deps, request: SuggestRequest) -> SuggestResponse:
    profile = "；".join(
        part
        for part in [
            f"目标院校：{request.target_school}" if request.target_school else "",
            f"目标专业：{request.major}" if request.major else "",
            f"备考阶段：{request.study_phase}" if request.study_phase else "",
        ]
        if part
    ) or "未填写备考信息"

    query = " ".join(
        part
        for part in [request.target_school, request.major, request.study_phase, "备考资料 复习规划"]
        if part
    )
    hits = await deps.retriever.search(query, top_k=5)

    context = (
        "\n\n".join(
            f"[{position + 1}] 《{hit.title}》\n{_snippet(hit.content, 300)}" for position, hit in enumerate(hits)
        )
        or "（资料库未检索到匹配的备考资料）"
    )
    favorites = "、".join(title for title in request.favorite_titles if title) or "暂无收藏"
    user = f"考生信息：{profile}\n已收藏资料：{favorites}\n\n资料片段：\n{context}\n\n请给出个性化备考建议。"
    suggestions = await deps.llm.chat(
        [
            {"role": "system", "content": prompts.SUGGESTION_SYSTEM},
            {"role": "user", "content": user},
        ],
        temperature=0.4,
        max_tokens=900,
    )
    references = [
        Reference(
            material_id=hit.material_id,
            title=hit.title,
            chunk_index=hit.chunk_index,
            score=round(hit.score, 4),
            snippet=_snippet(hit.content),
        )
        for hit in hits
    ]
    return SuggestResponse(suggestions=suggestions, references=references, model=deps.settings.vllm_chat_model)


async def semantic_search(deps: Deps, *, query: str, top_k: int = 8) -> SearchResponse:
    hits = await deps.retriever.search(query, top_k=top_k)
    return SearchResponse(
        query=query,
        matches=[
            SearchMatch(
                material_id=hit.material_id,
                title=hit.title,
                chunk_index=hit.chunk_index,
                score=round(hit.score, 4),
                snippet=_snippet(hit.content),
                tags=hit.tags,
            )
            for hit in hits
        ],
    )

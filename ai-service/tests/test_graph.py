from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.graph.builder import build_chat_graph
from app.schemas import ChatRequest
from app.services.assistant import answer_question

from tests.conftest import FakeRetriever, ScriptedLlm


def _deps(settings, llm, retriever):
    graph = build_chat_graph(llm=llm, retriever=retriever, settings=settings)
    return SimpleNamespace(settings=settings, graph=graph, llm=llm, retriever=retriever)


async def test_material_route_returns_citations(settings) -> None:
    llm = ScriptedLlm(relevant=[1], answer="数学一重点是极限与导数[1]，其次是级数。")
    retriever = FakeRetriever()
    response = await answer_question(
        _deps(settings, llm, retriever),
        ChatRequest(user_id=1, question="数学一的高频考点有哪些？"),
    )

    assert response.route == "materials"
    assert response.query == "数学一 复习资料"
    assert len(response.citations) == 1
    citation = response.citations[0]
    assert citation.index == 1
    assert citation.material_id == 11
    assert "极限" in citation.snippet
    assert [reference.material_id for reference in response.references] == [11]
    assert response.rewrites == 0
    assert response.latency_ms >= 0


async def test_empty_grade_triggers_single_rewrite_then_answers(settings) -> None:
    llm = ScriptedLlm(relevant=[], answer="资料库未检索到直接相关内容，建议先过一遍考纲。")
    retriever = FakeRetriever()
    response = await answer_question(
        _deps(settings, llm, retriever),
        ChatRequest(user_id=1, question="帮我找数学资料"),
    )

    assert retriever.queries == ["数学一 复习资料", "数学 高数 复习资料"]
    assert response.rewrites == 1
    assert response.citations == []
    assert response.references == []
    assert "资料库未检索到" in response.answer


async def test_general_route_skips_retrieval(settings) -> None:
    llm = ScriptedLlm(route="general", answer="保持规律作息即可。")
    retriever = FakeRetriever()
    response = await answer_question(
        _deps(settings, llm, retriever),
        ChatRequest(user_id=1, question="考前焦虑怎么调整？"),
    )

    assert retriever.queries == []
    assert response.route == "general"
    assert response.references == []
    assert response.answer.startswith("保持规律")


async def test_grade_failure_keeps_documents(settings) -> None:
    llm = ScriptedLlm(relevant=[], grade_raises=True, answer="根据资料[1]，先复习极限。")
    retriever = FakeRetriever()
    response = await answer_question(
        _deps(settings, llm, retriever),
        ChatRequest(user_id=1, question="数学怎么复习？"),
    )

    # Fail-open: the grader crashed, so both retrieved chunks survive and the
    # answer marker [1] resolves to the top hit.
    assert len(response.references) == 2
    assert [citation.material_id for citation in response.citations] == [11]


async def test_history_is_forwarded_to_answer_prompt(settings) -> None:
    llm = ScriptedLlm(relevant=[1], answer="接着上文，继续做真题[1]。")
    retriever = FakeRetriever()
    response = await answer_question(
        _deps(settings, llm, retriever),
        ChatRequest(
            user_id=1,
            question="那下一步呢？",
            history=[
                {"question": "我该先做什么？", "answer": "先过一遍高数教材。"},
            ],
        ),
    )

    answer_messages = llm.chat_calls[-1]
    roles = [message["role"] for message in answer_messages]
    assert roles == ["system", "user", "assistant", "user"]
    assert "先过一遍高数教材" in answer_messages[2]["content"]
    assert "那下一步呢？" in answer_messages[-1]["content"]
    assert response.answer


async def test_citation_markers_beyond_context_are_ignored(settings) -> None:
    llm = ScriptedLlm(relevant=[1], answer="引用 [1] 与不存在的 [9]。")
    response = await answer_question(
        _deps(settings, llm, FakeRetriever()),
        ChatRequest(user_id=1, question="数学"),
    )
    assert [citation.index for citation in response.citations] == [1]

"""Agentic RAG graph: analyze -> retrieve -> grade -> (rewrite -> retrieve)* -> answer."""

from __future__ import annotations

from langgraph.graph import END, StateGraph

from app.config import Settings
from app.core.llm import LlmClient
from app.graph.nodes import AgentDeps, AgentNodes, AgentState, route_after_analyze, route_after_grade
from app.rag.retriever import Retriever


def build_chat_graph(*, llm: LlmClient, retriever: Retriever, settings: Settings):
    nodes = AgentNodes(AgentDeps(llm=llm, retriever=retriever, settings=settings))

    graph: StateGraph = StateGraph(AgentState)
    graph.add_node("analyze", nodes.analyze)
    graph.add_node("retrieve", nodes.retrieve)
    graph.add_node("grade", nodes.grade)
    graph.add_node("rewrite", nodes.rewrite)
    graph.add_node("answer", nodes.answer)

    graph.set_entry_point("analyze")
    graph.add_conditional_edges(
        "analyze",
        route_after_analyze,
        {"retrieve": "retrieve", "answer": "answer"},
    )
    graph.add_edge("retrieve", "grade")
    graph.add_conditional_edges(
        "grade",
        route_after_grade,
        {"answer": "answer", "rewrite": "rewrite"},
    )
    graph.add_edge("rewrite", "retrieve")
    graph.add_edge("answer", END)
    return graph.compile()

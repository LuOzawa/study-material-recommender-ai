"""A tiny OpenAI-compatible server used for offline demos and smoke tests.

It implements just enough of `/v1/chat/completions`, `/v1/embeddings` and
`/v1/models` for the AI service, so the full LangGraph + pgvector flow can be
exercised without a GPU.  Never use it as a production model.

    uv run python scripts/stub_vllm.py --port 18000
"""

from __future__ import annotations

import argparse
import hashlib
import time
import uuid

import uvicorn
from fastapi import FastAPI, Request

app = FastAPI(title="stub-vllm")

DIM = 8
TOPICS = {
    "数学": [1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    "英语": [0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    "政治": [0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    "专业课": [0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0],
    "计算机": [0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0],
    "真题": [0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0],
    "规划": [0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0],
    "备考": [0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0],
    "复习": [0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0],
}


def embed(text: str) -> list[float]:
    vector = [0.0] * DIM
    for keyword, weight in TOPICS.items():
        if keyword in text:
            vector = [value + weight[index] for index, value in enumerate(vector)]
    if not any(vector):
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        vector = [digest[index] / 255.0 for index in range(DIM)]
    return vector


def _answer(messages: list[dict]) -> str:
    system = messages[0]["content"] if messages else ""
    user = messages[-1]["content"] if messages else ""
    if "意图路由器" in system:
        route = "general" if any(word in user for word in ["心态", "焦虑", "作息"]) else "materials"
        return '{"route": "%s", "search_query": "%s"}' % (
            route,
            "备考资料 复习规划" if route == "general" else "数学一 复习资料",
        )
    if "相关性评审" in system:
        markers = [line for line in user.splitlines() if line.startswith("[")]
        relevant = [int(marker.strip("[] ").split("]")[0]) for marker in markers[:2]]
        return '{"relevant": %s}' % relevant
    if "改写成" in system:
        return '{"query": "数学 高数 复习资料"}'
    if "学术内容摘要" in system:
        return "该资料整理了核心考点与历年真题，适合强化阶段使用。"
    if "考研辅导老师" in system:
        return "建议按阶段推进：基础期通读资料[1]，强化期做真题[2]，冲刺期复盘错题。"
    if "资料库未检索到" in user:
        return "资料库未检索到直接相关内容，建议先复习基础教材，再结合真题训练。"
    if "[1]" in user:
        return "数学一的核心是极限、导数与积分，建议先梳理高数主线[1]，再用真题验证。"
    return "先明确目标院校与专业，再按阶段安排复习。"


@app.get("/v1/models")
async def models() -> dict:
    return {
        "object": "list",
        "data": [
            {"id": "qwen2.5-7b-instruct", "object": "model", "created": 0, "owned_by": "stub"},
            {"id": "bge-m3", "object": "model", "created": 0, "owned_by": "stub"},
        ],
    }


@app.post("/v1/chat/completions")
async def chat(request: Request) -> dict:
    payload = await request.json()
    messages = payload.get("messages") or []
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex[:8]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": payload.get("model", "stub"),
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": _answer(messages)},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
    }


@app.post("/v1/embeddings")
async def embeddings(request: Request) -> dict:
    payload = await request.json()
    inputs = payload.get("input")
    if isinstance(inputs, str):
        inputs = [inputs]
    return {
        "object": "list",
        "model": payload.get("model", "stub-embedding"),
        "data": [
            {"object": "embedding", "index": index, "embedding": embed(text)}
            for index, text in enumerate(inputs or [])
        ],
        "usage": {"prompt_tokens": 1, "total_tokens": 1},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18000)
    args = parser.parse_args()
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")

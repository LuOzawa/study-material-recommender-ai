"""End-to-end smoke test: pgvector + stub vLLM + real uvicorn process.

Run with `uv run python scripts/smoke_e2e.py`.  It starts an embedded
PostgreSQL (pgserver), a stub OpenAI-compatible model server, boots the FastAPI
service as a subprocess and exercises indexing, vector search and the LangGraph
agent over HTTP.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid

import asyncpg
import httpx
import uvicorn

from stub_vllm import app as stub_app

MATERIALS = [
    {
        "id": 1,
        "title": "数学一高数复习提纲",
        "description": "极限、导数、积分是数学一的高频考点，配合历年真题使用。",
        "tags": "数学,高数",
        "category_id": 1,
        "category_name": "数学",
        "status": "ACTIVE",
        "updated_at": "2026-10-01T08:00:00",
    },
    {
        "id": 2,
        "title": "英语阅读真题精讲",
        "description": "阅读理解的题型、长难句分析与解题步骤。",
        "tags": "英语,阅读",
        "category_id": 2,
        "category_name": "英语",
        "status": "ACTIVE",
        "updated_at": "2026-10-02T08:00:00",
    },
    {
        "id": 3,
        "title": "政治冲刺背诵手册",
        "description": "马原、毛中特、史纲、思修的重点背诵条目。",
        "tags": "政治,背诵",
        "category_id": 3,
        "category_name": "政治",
        "status": "ACTIVE",
        "updated_at": "2026-10-03T08:00:00",
    },
    {
        "id": 4,
        "title": "计算机专业课 408 真题汇编",
        "description": "数据结构、操作系统、计算机组成原理与计算机网络真题。",
        "tags": "专业课,计算机,真题",
        "category_id": 4,
        "category_name": "专业课",
        "status": "ACTIVE",
        "updated_at": "2026-10-04T08:00:00",
    },
]


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_for(url: str, timeout: float = 30.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if httpx.get(url, timeout=1.0).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"timeout waiting for {url}")


def reset_shared_database(url: str) -> None:
    """The CI suite shares one Postgres; drop index tables left by pytest."""

    async def _reset() -> None:
        connection = await asyncpg.connect(url)
        try:
            await connection.execute(
                "DROP TABLE IF EXISTS ai_material_chunks, ai_material_index_state, ai_index_meta"
            )
        finally:
            await connection.close()

    asyncio.run(_reset())


def main() -> int:
    test_db = os.getenv("TEST_DATABASE_URL")
    pg = None
    if test_db:
        database_url = test_db
        reset_shared_database(database_url)
    else:
        import pgserver

        data_dir = os.path.join(tempfile.gettempdir(), f"study-ai-smoke-{uuid.uuid4().hex[:8]}")
        pg = pgserver.get_server(data_dir)
        database_url = pg.get_uri()

    stub_port = free_port()
    app_port = free_port()
    token = "smoke-internal-token"

    stub_config = uvicorn.Config(stub_app, host="127.0.0.1", port=stub_port, log_level="warning")
    stub_server = uvicorn.Server(stub_config)
    threading.Thread(target=stub_server.run, daemon=True).start()
    wait_for(f"http://127.0.0.1:{stub_port}/v1/models")

    env = {
        **os.environ,
        "DATABASE_URL": database_url,
        "VLLM_BASE_URL": f"http://127.0.0.1:{stub_port}/v1",
        "AI_INTERNAL_TOKEN": token,
        "EMBEDDING_DIM": "8",
        "RETRIEVAL_MIN_SCORE": "0.1",
        "LOG_LEVEL": "WARNING",
    }
    process = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(app_port)],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    logs: list[str] = []

    def drain() -> None:
        for line in process.stdout or []:
            logs.append(line)

    threading.Thread(target=drain, daemon=True).start()

    base = f"http://127.0.0.1:{app_port}"
    headers = {"X-Internal-Token": token}
    checks: list[tuple[str, bool, str]] = []
    try:
        try:
            wait_for(f"{base}/healthz")
        except Exception:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
            print("--- ai-service output ---")
            print("".join(logs[-60:]))
            raise
        with httpx.Client(base_url=base, headers=headers, timeout=30.0) as client:
            for material in MATERIALS:
                response = client.post("/internal/v1/index/materials", json=material)
                response.raise_for_status()
                checks.append((f"index material {material['id']}", response.json()["status"] == "indexed", str(response.json())))

            stats = client.get("/internal/v1/index/stats").json()
            checks.append(("index stats", stats["materials"] == 4 and stats["chunks"] >= 3, json.dumps(stats)))

            search = client.get("/internal/v1/search", params={"q": "数学一 极限", "top_k": 3}).json()
            top = search["matches"][0]
            checks.append(("vector search ranks math first", top["material_id"] == 1, json.dumps(search["matches"][:1], ensure_ascii=False)))

            chat = client.post("/internal/v1/agent/chat", json={"user_id": 1, "question": "数学一的高频考点有哪些？"}).json()
            checks.append(
                (
                    "agent returns cited answer",
                    chat["route"] == "materials" and bool(chat["citations"]) and "[1]" in chat["answer"],
                    json.dumps({k: chat[k] for k in ("answer", "route", "citations")}, ensure_ascii=False),
                )
            )

            summary = client.post(
                "/internal/v1/agent/summarize",
                json={"user_id": 1, "material_id": 1},
            ).json()
            checks.append(("summarize grounded", summary["grounded"] is True and summary["chunks"] >= 1, json.dumps(summary, ensure_ascii=False)))

            suggestion = client.post(
                "/internal/v1/agent/suggest",
                json={"user_id": 1, "target_school": "中山大学", "major": "计算机", "study_phase": "强化"},
            ).json()
            checks.append(("suggestions grounded", bool(suggestion["references"]), json.dumps(suggestion, ensure_ascii=False)))

            ready = client.get("/readyz").json()
            checks.append(("readiness", ready["checks"]["postgres"]["ok"] and ready["checks"]["vllm"]["reachable"], json.dumps(ready)))

            client.delete("/internal/v1/index/materials/2").raise_for_status()
            stats_after = client.get("/internal/v1/index/stats").json()
            checks.append(("delete removes material", stats_after["materials"] == 3, json.dumps(stats_after)))
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
        stub_server.should_exit = True
        if pg is not None:
            pg.cleanup()

    failed = [name for name, ok, _ in checks if not ok]
    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    if failed:
        print("SMOKE FAILED:", ", ".join(failed))
        return 1
    print(f"SMOKE PASSED ({len(checks)} checks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

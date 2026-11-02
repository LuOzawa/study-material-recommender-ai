# study-ai-service

考研资料平台的 AI 服务：**FastAPI + LangGraph + vLLM + pgvector**。
Spring Boot 通过内部令牌调用它；它只读取 MySQL 里的资料快照，不直接连业务库。

## 能力

| 接口 | 作用 |
| --- | --- |
| `POST /internal/v1/agent/chat` | 意图路由 → pgvector 检索 → 相关性评分 → 查询改写 → 带 `[n]` 引用的回答 |
| `POST /internal/v1/agent/summarize` | 基于已索引分块生成资料摘要；未索引时可回落 Spring 传来的快照 |
| `POST /internal/v1/agent/suggest` | 结合目标院校/专业/阶段与收藏，给出有资料依据的备考建议 |
| `GET /internal/v1/search?q=` | 语义检索，返回命中片段与余弦分数 |
| `POST /internal/v1/index/materials` | 单条资料增量入库 |
| `DELETE /internal/v1/index/materials/{id}` | 从向量索引删除 |
| `POST /internal/v1/index/rebuild` | 从 Spring 全量同步 ACTIVE 资料，并清理已下架数据 |
| `GET /internal/v1/index/stats` | 索引统计 |
| `GET /healthz` / `GET /readyz` | 存活 / 就绪（PG + vLLM） |

所有 `/internal/**` 接口要求 `X-Internal-Token`，且与 Spring 的
`ai.service.internal-token` 一致。

## 快速开始

```bash
cp .env.example .env
uv sync
uv run uvicorn app.main:app --port 8090
```

依赖：PostgreSQL（启用 `vector` 扩展）、vLLM（OpenAI 兼容）。
建表与 HNSW 索引在启动时幂等执行，无需手工迁移。

## 测试

```bash
uv run pytest                      # 30 个用例；无 PG 时自动用 pgserver 起嵌入式实例
uv run pytest -m integration       # 只跑 pgvector 集成用例
uv run python scripts/smoke_e2e.py # 端到端：pgvector + stub vLLM + 真实 uvicorn
```

CI 使用 `pgvector/pgvector:pg16` 服务容器，并通过 `TEST_DATABASE_URL` 复用。

## 关键设计

- **不做静默降级**：vLLM 不可达时返回 503，而不是返回空答案；健康检查单独暴露。
- **不伪造模型**：仓库里只有 `scripts/stub_vllm.py` 这一个明确标注的联调替身，生产路径永远走真实 vLLM。
- **索引幂等**：`content_hash` 判重，未变化跳过；分块变少时删除旧分块。
- **维度守卫**：`ai_index_meta` 记录 embedding 维度，换模型后启动即报错，避免静默错配。
- **无状态请求**：LangGraph 每次请求独立执行，多轮上下文由 Spring 从 `ai_conversations` 取最近 3 轮传入。

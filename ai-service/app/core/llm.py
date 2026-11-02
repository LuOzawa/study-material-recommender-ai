"""Thin async client for a vLLM OpenAI-compatible server.

vLLM serves `/v1/chat/completions`, `/v1/embeddings` and `/v1/models`, so the
official OpenAI SDK works unchanged.  Failures are surfaced as typed errors so
the API layer can answer 503 instead of hanging or returning empty text.
"""

from __future__ import annotations

import json
import re
from collections.abc import Sequence

from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AsyncOpenAI,
)

from app.config import Settings

_JSON_BLOCK = re.compile(r"\{.*\}", re.DOTALL)


class LlmError(RuntimeError):
    """Base class for vLLM failures."""


class LlmUnavailable(LlmError):
    """The model server cannot be reached or returned an error status."""


class LlmBadResponse(LlmError):
    """The model answered, but the payload is unusable."""


def _vector_literal(values: Sequence[float]) -> str:
    return "[" + ",".join(f"{float(value):.7f}" for value in values) + "]"


class LlmClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._chat_client = AsyncOpenAI(
            base_url=settings.vllm_base_url,
            api_key=settings.vllm_api_key or "EMPTY",
            timeout=settings.vllm_chat_timeout,
            max_retries=1,
        )
        self._embedding_client = AsyncOpenAI(
            base_url=settings.vllm_embedding_base_url or settings.vllm_base_url,
            api_key=settings.vllm_api_key or "EMPTY",
            timeout=settings.vllm_embedding_timeout,
            max_retries=1,
        )

    async def aclose(self) -> None:
        await self._chat_client.close()
        await self._embedding_client.close()

    # ------------------------------------------------------------------
    # Chat
    # ------------------------------------------------------------------
    async def chat(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.2,
        max_tokens: int = 1024,
    ) -> str:
        try:
            response = await self._chat_client.chat.completions.create(
                model=self.settings.vllm_chat_model,
                messages=messages,  # type: ignore[arg-type]
                temperature=temperature,
                max_tokens=max_tokens,
            )
        except (APIConnectionError, APITimeoutError) as exc:
            raise LlmUnavailable(f"vLLM 不可达：{exc}") from exc
        except APIStatusError as exc:
            raise LlmUnavailable(f"vLLM 返回 HTTP {exc.status_code}") from exc

        if not response.choices:
            raise LlmBadResponse("vLLM 返回空的 choices")
        content = response.choices[0].message.content
        if not content or not content.strip():
            raise LlmBadResponse("vLLM 返回空文本")
        return content.strip()

    async def chat_json(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = 512,
    ) -> dict:
        text = await self.chat(
            [
                {"role": "system", "content": system + "\n只输出一个 JSON 对象，不要输出解释或 Markdown 代码块。"},
                {"role": "user", "content": user},
            ],
            temperature=0.0,
            max_tokens=max_tokens,
        )
        match = _JSON_BLOCK.search(text)
        if not match:
            raise LlmBadResponse(f"模型输出不是 JSON：{text[:120]}")
        try:
            payload = json.loads(match.group(0))
        except json.JSONDecodeError as exc:
            raise LlmBadResponse(f"JSON 解析失败：{exc}") from exc
        if not isinstance(payload, dict):
            raise LlmBadResponse("模型输出的 JSON 不是对象")
        return payload

    # ------------------------------------------------------------------
    # Embeddings
    # ------------------------------------------------------------------
    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        cleaned = [text.strip() for text in texts]
        if not cleaned:
            return []
        vectors: list[list[float]] = []
        batch_size = max(1, self.settings.embedding_batch_size)
        for start in range(0, len(cleaned), batch_size):
            batch = cleaned[start:start + batch_size]
            try:
                response = await self._embedding_client.embeddings.create(
                    model=self.settings.vllm_embedding_model,
                    input=batch,
                )
            except (APIConnectionError, APITimeoutError) as exc:
                raise LlmUnavailable(f"vLLM embedding 不可达：{exc}") from exc
            except APIStatusError as exc:
                raise LlmUnavailable(f"vLLM embedding 返回 HTTP {exc.status_code}") from exc

            if len(response.data) != len(batch):
                raise LlmBadResponse("embedding 返回条数与输入不一致")
            for item in response.data:
                vector = list(item.embedding)
                if len(vector) != self.settings.embedding_dim:
                    raise LlmBadResponse(
                        f"embedding 维度 {len(vector)} 与配置 EMBEDDING_DIM={self.settings.embedding_dim} 不一致"
                    )
                vectors.append(vector)
        return vectors

    # ------------------------------------------------------------------
    # Observability
    # ------------------------------------------------------------------
    async def health(self) -> dict:
        try:
            models = await self._chat_client.models.list()
            return {"reachable": True, "models": [item.id for item in models.data]}
        except Exception as exc:  # noqa: BLE001 - health endpoint must not raise
            return {"reachable": False, "error": str(exc)}


def vector_literal(values: Sequence[float]) -> str:
    return _vector_literal(values)

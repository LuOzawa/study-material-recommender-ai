"""Chinese-aware chunking for material documents.

The corpus is short study-material pages (title + tags + description), so a
sentence based greedy packer with character overlap is enough.  The algorithm
never emits a chunk longer than ``chunk_size`` and never drops input text.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_SENTENCE_END = re.compile(r"(?<=[。！？!?；;])")
_WHITESPACE = re.compile(r"[ \t\u3000]+")


@dataclass(frozen=True)
class TextChunk:
    index: int
    content: str


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _WHITESPACE.sub(" ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_sentences(text: str) -> list[str]:
    sentences = [part for part in _SENTENCE_END.split(text) if part.strip()]
    if not sentences:
        return [text] if text.strip() else []
    return sentences


def _hard_split(text: str, size: int, overlap: int) -> list[str]:
    step = max(1, size - overlap)
    return [text[start:start + size] for start in range(0, len(text), step)]


def split_chunks(text: str, *, size: int, overlap: int) -> list[TextChunk]:
    if size <= 0:
        raise ValueError("size must be positive")
    if not 0 <= overlap < size:
        raise ValueError("overlap must be within [0, size)")

    normalized = normalize_text(text)
    if not normalized:
        return []
    if len(normalized) <= size:
        return [TextChunk(0, normalized)]

    raw_chunks: list[str] = []
    current = ""

    for sentence in split_sentences(normalized):
        if len(sentence) > size:
            if current.strip():
                raw_chunks.append(current.strip())
                current = ""
            raw_chunks.extend(part.strip() for part in _hard_split(sentence, size, overlap) if part.strip())
            continue

        if len(current) + len(sentence) <= size:
            current += sentence
            continue

        if current.strip():
            raw_chunks.append(current.strip())
        tail = current[-overlap:] if overlap else ""
        candidate = tail + sentence
        current = candidate if len(candidate) <= size else sentence

    if current.strip():
        raw_chunks.append(current.strip())

    chunks: list[TextChunk] = []
    for raw in raw_chunks:
        chunks.append(TextChunk(len(chunks), raw))
    return chunks


def build_document_text(
    *,
    title: str,
    description: str | None = None,
    tags: str | None = None,
    category_name: str | None = None,
    author: str | None = None,
) -> str:
    """Flatten a material into the text that is chunked and embedded."""

    parts = [f"《{title.strip()}》"]
    if category_name:
        parts.append(f"分类：{category_name.strip()}")
    if tags:
        parts.append(f"标签：{tags.strip()}")
    if author:
        parts.append(f"作者：{author.strip()}")
    if description and description.strip():
        parts.append(f"简介：{description.strip()}")
    return "\n".join(parts)

from __future__ import annotations

import pytest

from app.ingestion.chunking import build_document_text, normalize_text, split_chunks


def test_short_document_stays_single_chunk() -> None:
    chunks = split_chunks("极限与连续是数学一的基础。", size=100, overlap=20)
    assert [chunk.content for chunk in chunks] == ["极限与连续是数学一的基础。"]
    assert chunks[0].index == 0


def test_empty_document_returns_no_chunks() -> None:
    assert split_chunks("   \n\t ", size=100, overlap=20) == []


def test_chunks_respect_size_limit() -> None:
    text = "。".join(["这是第{}句关于线性代数的说明，包含矩阵与特征值的内容".format(i) for i in range(1, 40)]) + "。"
    chunks = split_chunks(text, size=120, overlap=30)
    assert len(chunks) > 1
    assert all(len(chunk.content) <= 120 for chunk in chunks)
    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))


def test_no_sentence_is_dropped() -> None:
    sentences = [f"第{i}条复习要点描述概率论与数理统计的考点" for i in range(1, 25)]
    text = "。".join(sentences) + "。"
    chunks = split_chunks(text, size=100, overlap=20)
    joined = "".join(chunk.content for chunk in chunks)
    for sentence in sentences:
        assert sentence in joined


def test_long_sentence_is_hard_split_with_overlap() -> None:
    text = "概" * 250
    chunks = split_chunks(text, size=100, overlap=20)
    assert len(chunks) == 4
    assert all(len(chunk.content) <= 100 for chunk in chunks)
    assert "".join(chunk.content for chunk in chunks).count("概") >= 250
    assert chunks[1].content.startswith("概" * 20)


def test_invalid_configuration_rejected() -> None:
    with pytest.raises(ValueError):
        split_chunks("abc", size=10, overlap=10)
    with pytest.raises(ValueError):
        split_chunks("abc", size=0, overlap=0)


def test_document_text_contains_material_metadata() -> None:
    text = build_document_text(
        title="数学一真题",
        description="2015-2025 年真题与解析。",
        tags="数学,真题",
        category_name="数学",
        author="研途",
    )
    assert text.startswith("《数学一真题》")
    assert "分类：数学" in text
    assert "标签：数学,真题" in text
    assert "简介：2015-2025 年真题与解析。" in text


def test_normalize_collapses_spaces_but_keeps_paragraphs() -> None:
    assert normalize_text("a   b\n\n\n\nc") == "a b\n\nc"

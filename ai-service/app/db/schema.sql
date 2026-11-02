-- study-ai-service schema (idempotent, executed on every startup)
-- ${dim} is substituted with EMBEDDING_DIM before execution.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ai_material_chunks (
    id           BIGSERIAL PRIMARY KEY,
    material_id  BIGINT     NOT NULL,
    chunk_index  INTEGER    NOT NULL,
    title        TEXT       NOT NULL,
    content      TEXT       NOT NULL,
    tags         TEXT,
    category_id  BIGINT,
    embedding    vector(${dim}) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (material_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_ai_material_chunks_material
    ON ai_material_chunks (material_id);

CREATE TABLE IF NOT EXISTS ai_material_index_state (
    material_id       BIGINT PRIMARY KEY,
    content_hash      TEXT        NOT NULL,
    chunk_count       INTEGER     NOT NULL,
    source_updated_at TIMESTAMPTZ,
    indexed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_index_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- HNSW keeps cosine search logarithmic as the corpus grows.
-- pgvector >= 0.5 is required; older servers fall back to a sequential scan.
CREATE INDEX IF NOT EXISTS idx_ai_material_chunks_embedding
    ON ai_material_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Phase 0: PostgreSQL schema with pgvector extension
-- Idempotent: safe to run multiple times

CREATE EXTENSION IF NOT EXISTS vector;

-- RAG: DLMS Blue/Green Books + SICONIA docs
CREATE TABLE IF NOT EXISTS embeddings_dlms_knowledge (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT      NOT NULL,
    embedding  vector(384) NOT NULL,
    metadata   JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_embeddings_dlms_hnsw
    ON embeddings_dlms_knowledge USING hnsw (embedding vector_cosine_ops);

-- RAG: Corporate Confluence pages
CREATE TABLE IF NOT EXISTS embeddings_confluence_knowledge (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT      NOT NULL,
    embedding  vector(384) NOT NULL,
    metadata   JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_embeddings_confluence_hnsw
    ON embeddings_confluence_knowledge USING hnsw (embedding vector_cosine_ops);

-- Knowledge graph nodes
CREATE TABLE IF NOT EXISTS kg_nodes (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type     TEXT NOT NULL CHECK (type IN ('IC','OBIS','AXDR_TYPE','APDU','ALARM','COMM_LAYER','DCU_COMPONENT','HES_COMPONENT')),
    label    TEXT NOT NULL,
    metadata JSONB
);

-- Unique constraint for idempotent seeding (type + label is the natural key)
ALTER TABLE kg_nodes DROP CONSTRAINT IF EXISTS uq_kg_nodes_type_label;
ALTER TABLE kg_nodes ADD CONSTRAINT uq_kg_nodes_type_label UNIQUE (type, label);

-- Knowledge graph edges
CREATE TABLE IF NOT EXISTS kg_edges (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES kg_nodes(id),
    target_id UUID NOT NULL REFERENCES kg_nodes(id),
    edge_type TEXT NOT NULL CHECK (edge_type IN ('belongs_to','returns_type','affects','has_attribute')),
    metadata  JSONB
);

-- Unique constraint for idempotent edge seeding
ALTER TABLE kg_edges DROP CONSTRAINT IF EXISTS uq_kg_edges_source_target_type;
ALTER TABLE kg_edges ADD CONSTRAINT uq_kg_edges_source_target_type UNIQUE (source_id, target_id, edge_type);

-- Short-term DLMS protocol memory (10 protocol fields + session key)
CREATE TABLE IF NOT EXISTS stm_entries (
    session_id        TEXT PRIMARY KEY,
    hdlc_client_sap   TEXT,
    hdlc_server_sap   TEXT,
    frame_counter     BIGINT,
    frame_counter_hex TEXT,
    security_suite    INTEGER,
    invoke_id         TEXT,
    association_state TEXT,
    max_pdu_size      INTEGER,
    last_obis         TEXT,
    last_ic           INTEGER,
    updated_at        TIMESTAMP DEFAULT NOW()
);

-- Multi-frame session history
CREATE TABLE IF NOT EXISTS episodic_blocks (
    id                UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        TEXT      NOT NULL,
    frame_number      INTEGER   NOT NULL,
    apdu_type         TEXT,
    decode_stage      TEXT,
    association_state TEXT,
    obis              TEXT,
    ic                INTEGER,
    errors            JSONB,
    warnings          JSONB,
    anomalies         JSONB,
    timestamp         TIMESTAMP DEFAULT NOW()
);

-- User accounts
CREATE TABLE IF NOT EXISTS users (
    user_id       UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT      NOT NULL UNIQUE,
    password_hash TEXT      NOT NULL,
    role          TEXT      NOT NULL CHECK (role IN ('VIEWER','ENGINEER','ADMIN','viewer','engineer','admin')),
    email         TEXT,
    active        BOOLEAN   DEFAULT TRUE,
    created_at    TIMESTAMP DEFAULT NOW()
);

-- Idempotent: add missing columns if they don't exist (for older schema versions)
ALTER TABLE users ADD COLUMN IF NOT EXISTS email TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE;

-- Set defaults for existing rows if needed
UPDATE users SET active = TRUE WHERE active IS NULL AND user_id IS NOT NULL;
UPDATE users SET email = COALESCE(email, username || '@system.local') WHERE email IS NULL AND user_id IS NOT NULL;

-- Add unique constraint on email (if column exists and has data)
CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON users (email) WHERE email IS NOT NULL;

-- Refresh tokens for JWT rotation
CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_id      UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID      NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash    TEXT      NOT NULL UNIQUE,
    expires_at    TIMESTAMP NOT NULL,
    revoked       BOOLEAN   DEFAULT FALSE,
    created_at    TIMESTAMP DEFAULT NOW()
);

-- Add missing revoked column if it doesn't exist
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS revoked BOOLEAN DEFAULT FALSE;

-- Conversations per user
CREATE TABLE IF NOT EXISTS conversations (
    conversation_id UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID      NOT NULL REFERENCES users(user_id),
    title           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- Individual messages within conversations
CREATE TABLE IF NOT EXISTS messages (
    message_id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID      NOT NULL REFERENCES conversations(conversation_id),
    role                TEXT      NOT NULL,
    input_class         TEXT,
    intent              TEXT,
    raw_input           TEXT,
    decode_result_json  JSONB,
    strategy_metadata_json JSONB,
    orchestration_mode  TEXT,
    planner_used        BOOLEAN,
    tool_trace_json     JSONB,
    planner_fallback_reason TEXT,
    explanation         TEXT,
    session_id          TEXT,
    used_mcp_fallback   BOOLEAN   DEFAULT FALSE,
    explanation_mode    TEXT,
    tool_provenance     TEXT,
    timestamp           TIMESTAMP DEFAULT NOW()
);

ALTER TABLE messages ADD COLUMN IF NOT EXISTS intent TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS strategy_metadata_json JSONB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS orchestration_mode TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS planner_used BOOLEAN;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_trace_json JSONB;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS planner_fallback_reason TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS explanation_mode TEXT;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS tool_provenance TEXT;

-- Append-only audit trail with HMAC-SHA256 integrity
CREATE TABLE IF NOT EXISTS audit_log (
    entry_id       UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp      TIMESTAMP DEFAULT NOW(),
    action         TEXT      NOT NULL,
    entity         TEXT,
    user_id        UUID,
    details_json   JSONB,
    hmac_signature TEXT      NOT NULL
);

-- Enforce append-only: block UPDATE and DELETE at the DB rule level
CREATE OR REPLACE RULE audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE OR REPLACE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;

-- Phase 1: Deduplication columns for idempotent re-ingestion
-- content_hash: SHA-256 of chunk text; source_file: original filename
-- UNIQUE(source_file, content_hash) enables ON CONFLICT DO NOTHING in ingest.py

ALTER TABLE embeddings_dlms_knowledge
    ADD COLUMN IF NOT EXISTS content_hash TEXT,
    ADD COLUMN IF NOT EXISTS source_file  TEXT;
ALTER TABLE embeddings_dlms_knowledge
    DROP CONSTRAINT IF EXISTS uq_dlms_source_hash;
ALTER TABLE embeddings_dlms_knowledge
    ADD CONSTRAINT uq_dlms_source_hash UNIQUE (source_file, content_hash);

ALTER TABLE embeddings_confluence_knowledge
    ADD COLUMN IF NOT EXISTS content_hash TEXT,
    ADD COLUMN IF NOT EXISTS source_file  TEXT;
ALTER TABLE embeddings_confluence_knowledge
    DROP CONSTRAINT IF EXISTS uq_confluence_source_hash;
ALTER TABLE embeddings_confluence_knowledge
    ADD CONSTRAINT uq_confluence_source_hash UNIQUE (source_file, content_hash);

-- Phase 015: Reflection Agent — global behavioral signal counters
CREATE TABLE IF NOT EXISTS reflection_stats (
  id           UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
  stat_type    TEXT      NOT NULL,
  stat_key     TEXT      NOT NULL,
  stat_value   BIGINT    NOT NULL DEFAULT 0,
  last_updated TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE (stat_type, stat_key)
);

-- Phase 015b: Per-message feedback storage for pseudo-RLHF
CREATE TABLE IF NOT EXISTS message_feedback (
  id                UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id        UUID,
  conversation_id   UUID,
  user_id           UUID      NOT NULL,
  intent            TEXT      NOT NULL,
  input_class       TEXT,
  feedback          TEXT      NOT NULL CHECK (feedback IN ('like', 'dislike')),
  prompt_snapshot   TEXT,
  response_snapshot TEXT,
  model_name        TEXT,
  created_at        TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feedback_intent
  ON message_feedback(intent, feedback);
CREATE INDEX IF NOT EXISTS idx_feedback_created
  ON message_feedback(created_at DESC);

-- AI Gateway P1 foundation schema.
-- PostgreSQL is the final source for budgets, request logs, and semantic cache entries.

CREATE EXTENSION IF NOT EXISTS vector;

-- Tenants and credentials -----------------------------------------------------

CREATE TABLE tenants (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id   TEXT NOT NULL REFERENCES tenants (id),
    key_hash    TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT api_keys_pk PRIMARY KEY (id),
    CONSTRAINT api_keys_key_hash_uq UNIQUE (key_hash)
);

-- Routing ---------------------------------------------------------------------

CREATE TABLE model_aliases (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT NOT NULL REFERENCES tenants (id),
    alias           TEXT NOT NULL,
    target_provider TEXT NOT NULL,
    target_model    TEXT NOT NULL,
    CONSTRAINT model_aliases_uq UNIQUE (tenant_id, alias, target_provider, target_model)
);

CREATE TABLE routing_policies (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants (id),
    alias       TEXT NOT NULL,
    strategy    TEXT NOT NULL,        -- FIXED / LEAST_COST / LEAST_LATENCY
    params      JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT routing_policies_uq UNIQUE (tenant_id, alias)
);

-- Quota and budget ------------------------------------------------------------

CREATE TABLE rate_limits (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT NOT NULL REFERENCES tenants (id),
    window_seconds  INT NOT NULL,
    max_requests    INT NOT NULL,
    CONSTRAINT rate_limits_uq UNIQUE (tenant_id, window_seconds),
    CONSTRAINT rate_limits_positive CHECK (max_requests > 0 AND window_seconds > 0)
);

CREATE TABLE budgets (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants (id),
    period      TEXT NOT NULL,        -- e.g. 2026-06 or 2026-06-01
    token_limit BIGINT NOT NULL,
    cost_limit  BIGINT NOT NULL,
    consumed    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT budgets_uq UNIQUE (tenant_id, period),
    CONSTRAINT budgets_consumed_nonneg CHECK (consumed >= 0)
);

-- Cache -----------------------------------------------------------------------

CREATE TABLE cache_entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants (id),
    prompt_hash TEXT NOT NULL,
    embedding   vector(1536),
    response    JSONB NOT NULL,
    model       TEXT NOT NULL,
    hit_count   BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cache_entries_uq UNIQUE (tenant_id, prompt_hash)
);

CREATE INDEX cache_entries_embedding_idx
    ON cache_entries USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Observability ---------------------------------------------------------------

CREATE TABLE request_logs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         TEXT NOT NULL REFERENCES tenants (id),
    alias             TEXT NOT NULL,
    mode              TEXT NOT NULL,        -- PASSTHROUGH / CACHE_ONLY / ROUTED / ROUTED_RESILIENT
    chosen_provider   TEXT,
    chosen_model      TEXT,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    latency_ms        BIGINT NOT NULL DEFAULT 0,
    cost              BIGINT NOT NULL DEFAULT 0,
    cache_type        TEXT NOT NULL DEFAULT 'NONE',  -- NONE / EXACT / SEMANTIC
    fallback_count    INT NOT NULL DEFAULT 0,
    guardrail_result  TEXT NOT NULL DEFAULT 'PASS',  -- PASS / BLOCKED_INPUT / BLOCKED_OUTPUT
    budget_outcome    TEXT NOT NULL DEFAULT 'ALLOWED', -- ALLOWED / RATE_LIMITED / BUDGET_EXCEEDED
    message           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX request_logs_tenant_created_idx ON request_logs (tenant_id, created_at);

CREATE TABLE fallback_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id  BIGINT NOT NULL REFERENCES request_logs (id),
    attempt_no  INT NOT NULL,
    provider    TEXT NOT NULL,
    model       TEXT NOT NULL,
    error_type  TEXT,
    outcome     TEXT NOT NULL         -- SUCCESS / FAILED
);

CREATE TABLE guardrail_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id  BIGINT NOT NULL REFERENCES request_logs (id),
    stage       TEXT NOT NULL,        -- INPUT / OUTPUT
    rule        TEXT NOT NULL,
    action      TEXT NOT NULL         -- PASS / BLOCK
);

CREATE TABLE usage_rollups (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants (id),
    day         DATE NOT NULL,
    model       TEXT NOT NULL,
    calls       BIGINT NOT NULL DEFAULT 0,
    tokens      BIGINT NOT NULL DEFAULT 0,
    cost        BIGINT NOT NULL DEFAULT 0,
    cache_hits  BIGINT NOT NULL DEFAULT 0,
    fallbacks   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT usage_rollups_uq UNIQUE (tenant_id, day, model)
);

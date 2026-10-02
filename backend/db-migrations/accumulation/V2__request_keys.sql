-- db-migrations/accumulation/V2__request_keys.sql
-- One row per Idempotency-Key the console sent on a request that creates something: a top-up, a
-- withdrawal, a transfer in, an adjustment, a rate declaration.
--
-- Found by a live check on 2026-10-02: the same top-up sent twice with the same key -- what the
-- console does when it retries after a timeout -- was collected and credited twice. The ledger's own
-- once-only index could not stop it, because each retry minted a NEW request with its own source
-- reference. The key is the customer's intent; this table is where it is held.
--
-- A table of its own, not a column on each request table: the five entities stay as they are, so
-- only code that goes through the HTTP path reads this, and only its tests need this migration.
CREATE TABLE accumulation.request_key (
    tenant_id       UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    -- What the key was used for, and on what. A key reused for a different request is refused
    -- rather than answered with somebody else's result.
    operation       VARCHAR(30) NOT NULL,
    target          VARCHAR(100) NOT NULL,
    resource_id     UUID NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- THE guarantee: two copies of one request racing each other cannot both commit.
    PRIMARY KEY (tenant_id, idempotency_key)
);

ALTER TABLE accumulation.request_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY request_key_tenant_isolation ON accumulation.request_key
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON accumulation.request_key TO app_role;

-- db-migrations/claims/V11__claim_document_request.sql
-- A document claims staff ask the claimant for (2026-10-08, the customer portal design step 4; PRD section 19).
--
-- An assessor who needs a certified death certificate, a police report or proof of relationship used to have no way to
-- ask on the record: the claimant was phoned, and nothing showed what was asked for, why, or whether it came. Now the
-- request is a row; the claimant sees it in the portal as "action required" and uploads against it, which links the
-- uploaded evidence and marks the request received. The claim goes on through the existing workflow -- a request
-- decides nothing.

CREATE TABLE claims.claim_document_request (
    request_id         UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL,
    claim_id           UUID         NOT NULL REFERENCES claims.claim (claim_id),
    document           VARCHAR(200) NOT NULL,
    reason             VARCHAR(1000),
    requested_by       VARCHAR(255) NOT NULL,
    requested_by_name  VARCHAR(255),
    requested_at       TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'RECEIVED', 'WITHDRAWN')),
    claim_evidence_id  UUID REFERENCES claims.claim_evidence (claim_evidence_id),
    received_at        TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_claim_document_request_received
        CHECK ((status = 'RECEIVED') = (claim_evidence_id IS NOT NULL AND received_at IS NOT NULL))
);

CREATE INDEX ix_claim_document_request_claim ON claims.claim_document_request (tenant_id, claim_id);

ALTER TABLE claims.claim_document_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_document_request_tenant_isolation ON claims.claim_document_request
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON claims.claim_document_request TO app_role;

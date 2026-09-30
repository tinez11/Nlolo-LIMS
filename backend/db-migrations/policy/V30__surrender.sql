-- Surrender: the customer cashes in a savings policy for its surrender value (product step 1,
-- task 4; guide §21.3). Replaces the deferred 501. A two-person request/approve flow, then a payout
-- through the existing disbursement rail -- the same choreography claims settlement uses.

-- When cover stopped, for wasOnRiskOn. A death the day before the surrender's effective date is
-- covered; on or after it is not. Null on a policy surrendered by a settled claim (that path
-- discharges cover through the claim, and carries no separate surrender date).
ALTER TABLE policy.policy ADD COLUMN surrender_effective_date DATE;

CREATE TABLE policy.surrender_request (
    surrender_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    policy_number        VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    -- REQUESTED -> APPROVED (cover stops, payout requested) -> PAID | FAILED | IN_DOUBT.
    status               VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','APPROVED','PAID','FAILED','IN_DOUBT')),
    quoted_value_amount  NUMERIC(19,2) NOT NULL CHECK (quoted_value_amount > 0),
    quoted_value_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    payee_ref            VARCHAR(200) NOT NULL,
    requested_by         VARCHAR(100) NOT NULL,
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by          VARCHAR(100),
    approved_at          TIMESTAMPTZ,
    disbursement_id      UUID,
    -- The requester may not approve their own surrender (two-person rule, like claims).
    CONSTRAINT surrender_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
-- At most one live request per policy: a second cannot be raised while one is REQUESTED or APPROVED.
CREATE UNIQUE INDEX ux_surrender_request_live
    ON policy.surrender_request (policy_number) WHERE status IN ('REQUESTED','APPROVED');
CREATE INDEX idx_surrender_request_policy ON policy.surrender_request (policy_number);

ALTER TABLE policy.surrender_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY surrender_request_tenant_isolation ON policy.surrender_request
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy.surrender_request TO app_role;

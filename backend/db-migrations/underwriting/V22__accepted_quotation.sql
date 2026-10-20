-- db-migrations/underwriting/V22__accepted_quotation.sql
-- The quotation a customer AGREED to (2026-10-20, the agents' prospect-to-proposal flow). Quoting stores nothing; this
-- row is written only when the agent confirms that the customer accepts, and it is what onboarding is filled in from
-- and what the proposal is opened from.
--
-- inputs: what was quoted, exactly as the calculator sent it (the person, the plan) -- the proposal is built from
--   these, never from a second copy typed again.
-- quoted: the server's own figures for those inputs on quoted_on, priced again on the server at confirmation.
-- case_id: the proposal opened from it. NULL while the agent is still onboarding; a quotation never submitted is
--   discarded (user, 2026-10-20) -- by the agent's cancel, or by the end-of-day sweep below. Kept on this side so the
--   case table, which sixty-odd test fixtures build by hand, is not altered.

CREATE TABLE underwriting.accepted_quotation (
    quotation_id        UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    owner_subject       VARCHAR(255) NOT NULL,
    applicant_party_id  UUID,
    product_id          UUID NOT NULL,
    product_version_id  UUID NOT NULL,
    inputs              JSONB NOT NULL,
    quoted              JSONB NOT NULL,
    quoted_on           DATE NOT NULL,
    valid_until         DATE NOT NULL,
    case_id             UUID UNIQUE REFERENCES underwriting.underwriting_case (case_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at        TIMESTAMPTZ,
    CONSTRAINT accepted_quotation_submitted_shape CHECK ((case_id IS NULL) = (submitted_at IS NULL)),
    CONSTRAINT accepted_quotation_validity CHECK (valid_until >= quoted_on)
);

CREATE INDEX accepted_quotation_owner ON underwriting.accepted_quotation (tenant_id, owner_subject);

ALTER TABLE underwriting.accepted_quotation ENABLE ROW LEVEL SECURITY;
CREATE POLICY accepted_quotation_tenant_isolation ON underwriting.accepted_quotation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.accepted_quotation TO app_role;

-- Confirmed quotations never submitted, across tenants, for the end-of-day sweep: confirmed before the civil day
-- `before` began. Ids and tenants only; each is deleted under its own tenant.
CREATE OR REPLACE FUNCTION underwriting.unsubmitted_quotations_before(before TIMESTAMPTZ)
RETURNS TABLE (quotation_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT q.quotation_id, q.tenant_id
      FROM underwriting.accepted_quotation q
     WHERE q.case_id IS NULL AND q.created_at < before
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION underwriting.unsubmitted_quotations_before(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION underwriting.unsubmitted_quotations_before(TIMESTAMPTZ) TO app_role;

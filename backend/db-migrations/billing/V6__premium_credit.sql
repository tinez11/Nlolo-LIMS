-- Premium given back when a loan ends before its term.
--
-- Credit life charges a single premium for the whole term at enrolment (spec 2.8). When the
-- borrower repays in month nine of eighteen, half of what was charged was never earned, and
-- the insurer owes it back. Without this the insurer keeps premium for cover it did not
-- provide -- on a product whose early-settlement volume the lender controls entirely.

CREATE TABLE billing.premium_credit (
    credit_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,

    -- WHO the refund is for. A scheme has hundreds of borrowers and they settle one at a
    -- time, so a credit against the policy alone could never be reconciled to a person.
    policy_member_id    UUID NOT NULL,

    -- WHICH charge it reverses. Answerable only because premium_invoice carries
    -- enrolment_submission_id: a borrower who enrolled eight files ago is exactly the one who
    -- settles early, and without this the answer would be a date guess.
    original_invoice_id UUID NOT NULL,

    amount              NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency            CHAR(3) NOT NULL DEFAULT 'TZS',

    -- Why the loan ended. Carried onto the credit so a finance officer reading this row alone
    -- can see it, and so the commission clawback that follows has it without a second lookup.
    exit_reason         VARCHAR(30) NOT NULL,
    exit_date           DATE NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ONE credit per member, ever. A member can be exited twice -- a resent exits file, or a
    -- redelivered AFTER_COMMIT event -- and a second credit would refund the same premium
    -- again. This is the guarantee; the listener's own pre-check is the readable half.
    CONSTRAINT ux_premium_credit_per_member UNIQUE (tenant_id, policy_member_id)
);

-- Every credit raised against one invoice, for reconciling a file's charge against what came
-- back off it.
CREATE INDEX idx_premium_credit_invoice
    ON billing.premium_credit (tenant_id, original_invoice_id);

CREATE INDEX idx_premium_credit_policy
    ON billing.premium_credit (tenant_id, policy_number);

-- RLS in the post-V12 form. NULLIF, not a bare cast: TenantAwareDataSource RESETs the GUC on
-- a pooled connection borrowed without a tenant, Postgres returns the empty string, and
-- ''::uuid RAISES rather than filtering. No tenant must mean no rows.
ALTER TABLE billing.premium_credit ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_credit_tenant_isolation ON billing.premium_credit
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON billing.premium_credit TO app_role;

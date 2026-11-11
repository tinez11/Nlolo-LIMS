-- db-migrations/billing/V20__collection_action.sql
-- What was done to collect a premium (2026-11-11, analytics plan 1): a call (its outcome, an optional promise to pay
-- and a note), a reminder SMS, or a mobile-money payment request. The record staff and agents read on "Premiums to
-- follow up". A promise is a note only: nothing here holds back reminders or dunning (the user, 2026-11-11).

CREATE TABLE IF NOT EXISTS billing.collection_action (
    action_id      UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    invoice_id     UUID          NOT NULL,
    policy_number  VARCHAR(20)   NOT NULL,
    kind           VARCHAR(20)   NOT NULL CHECK (kind IN ('CALL', 'REMINDER', 'PAYMENT_REQUEST')),
    outcome        VARCHAR(30)   CHECK (outcome IS NULL
                                        OR outcome IN ('WILL_PAY', 'CANNOT_PAY_NOW', 'NO_ANSWER', 'WRONG_NUMBER')),
    promised_on    DATE,
    note           VARCHAR(500),
    recorded_by    VARCHAR(100)  NOT NULL,
    recorded_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT collection_action_call_has_outcome CHECK ((kind = 'CALL') = (outcome IS NOT NULL)),
    CONSTRAINT collection_action_promise_on_call CHECK (promised_on IS NULL OR kind = 'CALL')
);
CREATE INDEX IF NOT EXISTS idx_collection_action_invoice
    ON billing.collection_action (tenant_id, invoice_id, recorded_at DESC);

ALTER TABLE billing.collection_action ENABLE ROW LEVEL SECURITY;
CREATE POLICY collection_action_tenant_isolation ON billing.collection_action
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON billing.collection_action TO app_role;

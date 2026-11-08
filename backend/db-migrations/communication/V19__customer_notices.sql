-- db-migrations/communication/V19__customer_notices.sql
-- The messages a customer expected and never got (2026-11-08, the user): a policy lapsed or reinstated, a claim
-- received, approved, declined or paid, a surrender, a loan or a benefit paid.
--
-- 1. communication.recipient -- who to tell about a policy, a claim or a loan. Most of these events name only a policy
--    (or a claim, or a loan), and communication may not read policy, claims or policyloan. So it remembers what their
--    events already told it: a policy's holder (PolicyIssued, PolicyActivated, PolicyholderChanged), a claim's
--    claimant (ClaimRegistered -- on a death claim the holder may be the one who died), a loan's policy
--    (LoanOriginated). Backfilled once from those modules' own tables where this database has them.
-- 2. The templates, platform defaults in Swahili and English, SMS and email.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS communication.recipient (
    tenant_id     UUID         NOT NULL,
    ref_type      VARCHAR(10)  NOT NULL CHECK (ref_type IN ('POLICY', 'CLAIM', 'LOAN')),
    ref_id        VARCHAR(64)  NOT NULL,
    party_id      UUID,
    policy_number VARCHAR(20),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, ref_type, ref_id)
);

ALTER TABLE communication.recipient ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS recipient_tenant_isolation ON communication.recipient;
CREATE POLICY recipient_tenant_isolation ON communication.recipient
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON communication.recipient TO app_role;

DO $$
BEGIN
    IF to_regclass('policy.policy') IS NOT NULL THEN
        INSERT INTO communication.recipient (tenant_id, ref_type, ref_id, party_id, policy_number)
        SELECT p.tenant_id, 'POLICY', p.policy_number, p.policyholder_party_id, p.policy_number FROM policy.policy p
        ON CONFLICT (tenant_id, ref_type, ref_id) DO NOTHING;
    END IF;
    IF to_regclass('claims.claim') IS NOT NULL THEN
        INSERT INTO communication.recipient (tenant_id, ref_type, ref_id, party_id, policy_number)
        SELECT c.tenant_id, 'CLAIM', c.claim_id::text, c.claimant_party_id, c.policy_number FROM claims.claim c
        ON CONFLICT (tenant_id, ref_type, ref_id) DO NOTHING;
    END IF;
    IF to_regclass('policyloan.policy_loan') IS NOT NULL THEN
        INSERT INTO communication.recipient (tenant_id, ref_type, ref_id, party_id, policy_number)
        SELECT l.tenant_id, 'LOAN', l.loan_id::text, NULL, l.policy_number FROM policyloan.policy_loan l
        ON CONFLICT (tenant_id, ref_type, ref_id) DO NOTHING;
    END IF;
END;
$$;

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template)
SELECT '00000000-0000-0000-0000-000000000000'::uuid, t.template_key, c.channel, t.language, t.body_template
FROM (VALUES
  ('POLICY_LAPSED', 'sw', 'Bima {{policyNumber}} imesitishwa kwa sababu ada hazikulipwa. Huna kinga tena. Wasiliana nasi ili kuirejesha.'),
  ('POLICY_LAPSED', 'en', 'Policy {{policyNumber}} has lapsed because premiums were not paid. You are no longer covered. Contact us to reinstate it.'),
  ('POLICY_REINSTATED', 'sw', 'Bima {{policyNumber}} imerejeshwa. Una kinga tena.'),
  ('POLICY_REINSTATED', 'en', 'Policy {{policyNumber}} has been reinstated. You are covered again.'),
  ('CLAIM_RECEIVED', 'sw', 'Tumepokea dai lako kwenye bima {{policyNumber}}. Tutawasiliana nawe kuhusu nyaraka zinazohitajika.'),
  ('CLAIM_RECEIVED', 'en', 'We have received your claim on policy {{policyNumber}}. We will contact you about any documents we need.'),
  ('CLAIM_APPROVED', 'sw', 'Dai lako kwenye bima {{policyNumber}} limeidhinishwa kwa {{amount}}. Malipo yatafuata.'),
  ('CLAIM_APPROVED', 'en', 'Your claim on policy {{policyNumber}} has been approved for {{amount}}. Payment will follow.'),
  ('CLAIM_DECLINED', 'sw', 'Dai lako kwenye bima {{policyNumber}} halijaidhinishwa. Wasiliana nasi kujua sababu na hatua unazoweza kuchukua.'),
  ('CLAIM_DECLINED', 'en', 'Your claim on policy {{policyNumber}} has not been approved. Contact us to hear why and what you can do.'),
  ('CLAIM_PAID', 'sw', 'Tumelipa {{amount}} kwa dai lako la bima {{policyNumber}}.'),
  ('CLAIM_PAID', 'en', 'We have paid {{amount}} on your claim for policy {{policyNumber}}.'),
  ('SURRENDER_PAID', 'sw', 'Tumelipa {{amount}} kwa kusitisha bima {{policyNumber}}. Bima hii imefikia mwisho.'),
  ('SURRENDER_PAID', 'en', 'We have paid {{amount}} for the surrender of policy {{policyNumber}}. The policy has ended.'),
  ('LOAN_PAID_OUT', 'sw', 'Mkopo wako wa {{amount}} kwenye bima {{policyNumber}} umelipwa.'),
  ('LOAN_PAID_OUT', 'en', 'Your loan of {{amount}} against policy {{policyNumber}} has been paid out to you.'),
  ('BENEFIT_PAID', 'sw', 'Tumelipa {{amount}} inayostahili kwenye bima {{policyNumber}}.'),
  ('BENEFIT_PAID', 'en', 'We have paid {{amount}} due on policy {{policyNumber}}.')
) AS t(template_key, language, body_template)
CROSS JOIN (VALUES ('SMS'), ('EMAIL')) AS c(channel)
WHERE NOT EXISTS (
  SELECT 1 FROM communication.notification_template n
   WHERE n.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid AND n.template_key = t.template_key
     AND n.channel = c.channel AND n.language = t.language);

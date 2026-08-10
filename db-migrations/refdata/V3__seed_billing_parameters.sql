-- M4 (billing) additions. V1/V2 are already taken by prior milestones' seeds -- this file is
-- additive, same convention V2's own header comment established.

-- Consumed by policy.application.UnderwritingDecisionEventListener when auto-issuing a policy
-- with no human-supplied premium: annualPremium = sumAssuredAmount * (rate/1000) * (1 +
-- loadingPercent/100). No actuarial rating engine exists anywhere in this codebase (M2's
-- SimpleRulesEngine is a deliberate placeholder) -- this is a flat simplification, not a
-- confirmed rate, exactly like TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE's own precedent.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_BASE_PREMIUM_RATE_PER_MILLE', 'DEFAULT', 'Base annual premium rate per mille of sum assured', '5.0', 'TZ'); -- PLACEHOLDER, pending Actuarial sign-off

-- Consumed by billing.sweep_billing_state() (Task 5) to escalate an ArrearsCase's dunningLevel
-- over time. docs/03-aggregate-design.md names the 5 levels and their actions but specifies no
-- day thresholds between them -- externalized here rather than hardcoded in SQL, following the
-- same "flag it, don't guess silently" convention as every other placeholder in this file.
-- Level 1 begins the moment an ArrearsCase opens; no threshold row is needed for it.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('DUNNING_ESCALATION_DAYS', 'LEVEL_2', 'Days overdue before dunning level 2 (follow-up SMS)', '7', 'TZ'),   -- PLACEHOLDER, pending Collections sign-off
    ('DUNNING_ESCALATION_DAYS', 'LEVEL_3', 'Days overdue before dunning level 3 (call-center escalation)', '14', 'TZ'), -- PLACEHOLDER, pending Collections sign-off
    ('DUNNING_ESCALATION_DAYS', 'LEVEL_4', 'Days overdue before dunning level 4 (final notice)', '21', 'TZ'),   -- PLACEHOLDER, pending Collections sign-off
    ('DUNNING_ESCALATION_DAYS', 'LEVEL_5', 'Days overdue before dunning level 5 (PolicyLapseRecommended)', '30', 'TZ'); -- PLACEHOLDER, pending Collections sign-off

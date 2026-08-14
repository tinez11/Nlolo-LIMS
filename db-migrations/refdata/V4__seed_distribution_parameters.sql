-- db-migrations/refdata/V4__seed_distribution_parameters.sql
-- M7 (distribution) additions. V1-V3 are taken by prior milestones; this file is additive, the
-- same convention V2's and V3's own headers established.
--
-- EVERY VALUE HERE IS INVENTED. Di1 (docs/03-aggregate-design.md:148-154) specifies the shape of
-- CommissionPlan/CommissionRule and names five tier types, but defines NO calculation semantics
-- whatsoever, and the word "clawback" appears in no Phase 0 document. These are externalized
-- rather than hardcoded so correcting them is a data change, following the same "flag it, don't
-- guess silently" convention as TZ_BASE_PREMIUM_RATE_PER_MILLE and DUNNING_ESCALATION_DAYS.

-- Consumed by distribution.application.PolicyEventListener's clawback path (via
-- CommissionCalculator): a policy that lapses within this many months of its issue date reverses
-- its FIRST_YEAR accrual. 12 months is the ordinary first-year commission-earning period in life
-- insurance, but no doc, statute or TIRA guidance in this repository states a figure.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_COMMISSION_CLAWBACK_MONTHS', 'DEFAULT', 'Months after issue within which a lapse reverses first-year commission', '12', 'TZ'); -- PLACEHOLDER, pending Legal/Compliance and Distribution sign-off

-- NOTE, deliberately only ONE parameter in this file. A license-expiry warning threshold was
-- considered and left out: nothing in M7 reads it, because the distribution.AgentLicenseExpiring
-- event is declared in api/asyncapi-events.yaml but has no producer in this plan (deferred, see
-- Self-Review Notes). Seeding a parameter no code consumes is the same "value that exists and is
-- reachable from nowhere" smell a prior milestone's review flagged for unreachable enum values --
-- so it lands with the sweep that needs it, not before. distribution/V1 already ships the
-- supporting partial index (idx_agent_license_expiry) for whoever builds it.

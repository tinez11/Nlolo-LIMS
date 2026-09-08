-- db-migrations/underwriting/V5__explicit_decision.sql
-- Splits "what the engine suggests" from "what a person decided".
--
-- Until now submitAssessment ran SimpleRulesEngine and wrote its output straight into the
-- decision_* columns -- an algorithm whose own comment says its thresholds are "illustrative,
-- not actuarially validated" was the sole author of every underwriting decision on the
-- platform, and an accepted decision issued a real policy. There was no decide endpoint, no
-- override, and decision_decided_by did not exist: no decision on this platform recorded who
-- made it.
--
-- The recommendation_* columns hold the engine's output. The decision_* columns keep their
-- meaning but are now written only by an explicit human decision, which also records its
-- author and whether it departed from the recommendation.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN recommendation_outcome         VARCHAR(10)
        CHECK (recommendation_outcome IN ('ACCEPT','LOADED','DECLINED','POSTPONED')),
    ADD COLUMN recommendation_loading_percent NUMERIC(5,2),
    ADD COLUMN recommendation_reason          VARCHAR(500),
    ADD COLUMN recommendation_at              TIMESTAMPTZ,
    ADD COLUMN decision_decided_by            VARCHAR(100),
    ADD COLUMN decision_overrode_recommendation BOOLEAN NOT NULL DEFAULT FALSE;

-- Deliberately no backfill of decision_decided_by. Every case decided before this migration
-- was decided by the engine with no human involved, and inventing a name for that would
-- assert a fact that is not true. NULL here means exactly "decided before decisions had
-- authors", and readers must render it as such rather than as an unknown person.
COMMENT ON COLUMN underwriting.underwriting_case.decision_decided_by IS
    'Staff subject who made the decision. NULL for pre-V5 cases decided by the rules engine alone.';

COMMENT ON COLUMN underwriting.underwriting_case.recommendation_outcome IS
    'What SimpleRulesEngine suggested. Advisory only -- the decision_* columns are authoritative.';

-- No CHECK pairing recommendation_loading_percent with recommendation_outcome = 'LOADED', unlike
-- chk_loading_only_when_loaded on the decision columns. The decision constraint protects a
-- contractual figure; this one is advice that is recomputed from scratch every time evidence
-- arrives, and a constraint here would turn a future engine change into a migration.

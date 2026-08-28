-- Let an underwriting case record who sold it.
--
-- Auto-issuance is the platform's NORMAL path: an ACCEPT or LOADED decision publishes
-- UnderwritingDecisionMade, and policy's UnderwritingDecisionEventListener issues the
-- policy. That listener passed `null` for agentOfRecordId with the comment "agentOfRecordId
-- isn't part of an UnderwritingCase at all" -- which was true, and is what this column
-- fixes.
--
-- The consequence was a money bug, not a cosmetic gap. distribution's PolicyEventListener
-- reads agentOfRecordId off PolicyIssued and returns early when it is null ("sold direct --
-- no commission to accrue"). So no commission has ever accrued on an automatically issued
-- policy. Commission only ever fired on POST /policies/manual-issue, which the docs call
-- the exception path for staff overrides -- exactly backwards from how the business is
-- meant to work, and silent, because "no agent" is a legitimate state for a direct sale.
--
-- Nullable, because direct sales are real: a customer applying through self-service has no
-- agent, and forcing one would invent a commission payee.
--
-- An opaque reference with no cross-schema foreign key, matching every other outbound id on
-- this table (applicant_party_id, product_id, product_version_id all carry the same comment
-- in V1). The agent lives in `distribution`; underwriting does not depend on that module and
-- does not need to in order to carry an id through to issuance.

ALTER TABLE underwriting.underwriting_case
    ADD COLUMN agent_of_record_id UUID;

COMMENT ON COLUMN underwriting.underwriting_case.agent_of_record_id IS
    'Opaque ref into distribution.agent_profile -- no cross-schema FK. Null means a direct sale.';

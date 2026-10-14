-- db-migrations/party/V7__kyc_decided_by_name.sql
-- Name the staff member who decided a KYC case, at the moment they decided it.
--
-- THE DEFECT THIS CLOSES, and it is V5's defect again one table across. `verified_by` holds the
-- deciding login's Keycloak SUBJECT -- a uuid minted at realm import that nothing on this
-- platform can turn back into a person. Until now that cost nothing, because NOTHING READ THIS
-- TABLE AT ALL: `KycRecord` had no getters, `KycRecordRepository` was a bare JpaRepository, and
-- no endpoint exposed a row. Every KYC decision this platform has ever taken was written down
-- and never read. Opening that history (GET /parties/{id}/kyc-decisions) is what makes the
-- column's emptiness visible, so the two land together rather than shipping a decision trail
-- whose actor is a uuid.
--
-- `verified_by` is NOT replaced, for V5's reason: a display name is neither unique nor stable,
-- and separation of duties -- the assessor who may not also decide -- is compared on subjects.
--
-- NO BACKFILL, unlike V5. V5 could fill a client's registrar from the agent party id recorded
-- beside it; there is no second record of who took a KYC decision, so every existing row stays
-- NULL and the console says "name not recorded" rather than inventing one from a subject.
--
-- The column is named for the table's own vocabulary (`verified_by` -> `verified_by_name`) even
-- though a REJECTED row is not a verification. Renaming the pair would be a bigger, riskier
-- change than this is; the API view calls them `decidedBy`/`decidedByName`, which is what a
-- reader sees.
ALTER TABLE party.kyc_record
    ADD COLUMN verified_by_name VARCHAR(255);

COMMENT ON COLUMN party.kyc_record.verified_by_name IS
    'Display name of whoever took this KYC decision, captured from their token at the time. '
    'For display only -- verified_by (the subject) is the identity any rule compares. NULL for '
    'every row written before V7, and for any decision taken without a token.';

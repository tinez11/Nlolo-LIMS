-- A lender's credit-life enrolment schedule is a document this platform stores.
--
-- Kept exactly as submitted, for the reason a claim keeps its evidence: when a lender
-- disputes whether a borrower was declared, the answer is the bytes they sent and not our
-- reading of them.
--
-- The type list exists twice -- as document.api.DocumentType and as this CHECK -- because
-- a migration cannot call Java.
--
-- Constraint name verified against a live database rather than assumed:
--   SELECT conname FROM pg_constraint
--    WHERE conrelid = 'document.document_record'::regclass AND contype = 'c';
-- returns document_record_document_type_check. DROP CONSTRAINT with a wrong name is a
-- silent no-op that would leave the old five-value check in force, and every enrolment
-- upload would fail with no clue why.

ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE'));

-- db-migrations/document/V6__account_statement_document_type.sql
-- Product step 3: a savings account's statement, filed against its policy.
--
-- Its own type rather than POLICY_DOCUMENT: a statement is a record of money at a date, regenerated
-- each year, and one filed as "the policy document" is the wrong evidence in a dispute about either.
-- The list exists twice, as document.api.DocumentType and as this CHECK. The constraint name is the
-- one V4 created and V5 replaced -- a DROP with a wrong name would be a silent no-op.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT'));

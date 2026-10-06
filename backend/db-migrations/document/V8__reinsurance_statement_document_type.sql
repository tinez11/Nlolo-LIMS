-- db-migrations/document/V8__reinsurance_statement_document_type.sql
-- IFRS 17 I3d: the reinsurer's quarterly statement, attached to the statement that settles it. The list exists twice,
-- as document.api.DocumentType and as this CHECK, under the constraint name V4 created and V5-V7 replaced.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT',
                             'JOURNAL_SUPPORT','REINSURANCE_STATEMENT'));

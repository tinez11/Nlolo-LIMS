-- db-migrations/document/V7__journal_support_document_type.sql
-- IFRS 17 I4: the supporting document a manual journal must carry (guide 2.3, Part 4) -- a board resolution, a fund
-- manager's report, a broker statement, a payroll summary. The list exists twice, as document.api.DocumentType and
-- as this CHECK, under the constraint name V4 created and V5/V6 replaced.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT',
                             'JOURNAL_SUPPORT'));

-- db-migrations/document/V9__ifrs17_engine_document_types.sql
-- IFRS 17 I5a: the engine period cycle's documents -- the extract the engine is sent, the results file it returned, and
-- the appointed actuary's report an approval rests on. The list exists twice, as document.api.DocumentType and as this
-- CHECK, under the constraint name V4 created and V5-V8 replaced.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT',
                             'JOURNAL_SUPPORT','REINSURANCE_STATEMENT','IFRS17_EXTRACT','IFRS17_RESULTS',
                             'ACTUARIAL_REPORT'));

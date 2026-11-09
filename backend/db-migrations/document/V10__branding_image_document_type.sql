-- db-migrations/document/V10__branding_image_document_type.sql
-- Organisation settings (2026-11-09): a company's logo and its signatories' signature images. The list exists twice, as
-- document.api.DocumentType and as this CHECK, under the constraint name V4 created and V5-V9 replaced.
ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT',
                             'JOURNAL_SUPPORT','REINSURANCE_STATEMENT','IFRS17_EXTRACT','IFRS17_RESULTS',
                             'ACTUARIAL_REPORT','BRANDING_IMAGE'));

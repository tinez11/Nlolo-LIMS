-- A lender's monthly exits file is a document this platform stores.
--
-- A DISTINCT type rather than reusing ENROLMENT_SCHEDULE, although both are CSVs from the
-- same lender in the same shape of spreadsheet. The two say opposite things -- one puts
-- borrowers on risk, the other takes them off -- and a stored file labelled as the wrong one
-- is evidence that argues against itself in exactly the dispute it exists to settle.
--
-- The type list exists twice, as document.api.DocumentType and as this CHECK, because a
-- migration cannot call Java. Constraint name is the one V4 created; a DROP with a wrong name
-- is a silent no-op that would leave the six-value check in force and fail every exits upload
-- with no clue why.

ALTER TABLE document.document_record
    DROP CONSTRAINT IF EXISTS document_record_document_type_check;

ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE'));

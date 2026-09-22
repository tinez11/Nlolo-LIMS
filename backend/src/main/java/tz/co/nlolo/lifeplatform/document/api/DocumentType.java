package tz.co.nlolo.lifeplatform.document.api;

public enum DocumentType {
    KYC_EVIDENCE, POLICY_DOCUMENT, CLAIM_EVIDENCE, SIGNED_FORM, UNDERWRITING_EVIDENCE,

    /**
     * A lender's credit-life enrolment schedule, kept exactly as submitted.
     *
     * <p>Stored for the reason a claim keeps its evidence: when a lender disputes whether
     * a borrower was declared, the answer is the bytes they sent, not our reading of them.
     *
     * <p>Routed to the general {@code policy-documents} bucket by
     * {@code MinioDocumentStorage.bucketFor}'s default arm. Deliberate rather than
     * overlooked -- a schedule belongs to a policy, and a bucket of its own would buy
     * nothing the tenant-prefixed object key does not already give.
     */
    ENROLMENT_SCHEDULE,

    /**
     * A lender's monthly exits file: which loans ended, when and why.
     *
     * <p>Distinct from ENROLMENT_SCHEDULE although both are CSVs from the same lender in the
     * same shape of spreadsheet. The two say opposite things — one puts borrowers on risk, the
     * other takes them off — and a stored file labelled as the wrong one is evidence that
     * argues against itself in exactly the dispute it exists to settle.
     */
    EXITS_FILE
}

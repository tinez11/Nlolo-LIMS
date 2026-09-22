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
    ENROLMENT_SCHEDULE
}

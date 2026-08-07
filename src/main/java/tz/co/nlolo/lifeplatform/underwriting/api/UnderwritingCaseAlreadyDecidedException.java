package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

/**
 * Thrown when an assessment is submitted against a case whose status is already
 * DECIDED. Submitting a new assessment recomputes and overwrites decision_outcome
 * /decision_loading_percent/decision_decline_reason/decision_decided_at in place with
 * no audit trail of the prior decision -- silently allowing that (final review finding
 * 3) makes "why was this applicant declined, and was that decision later changed" an
 * unanswerable compliance question. A case that needs re-assessment after its initial
 * decision (e.g. new medical evidence) must go through an explicit re-open step (not
 * yet modeled) rather than have submitAssessment silently recompute over it.
 */
public class UnderwritingCaseAlreadyDecidedException extends RuntimeException {
    public UnderwritingCaseAlreadyDecidedException(UUID caseId) {
        super("Underwriting case " + caseId + " is already DECIDED and cannot accept a new assessment without an explicit re-open step");
    }
}

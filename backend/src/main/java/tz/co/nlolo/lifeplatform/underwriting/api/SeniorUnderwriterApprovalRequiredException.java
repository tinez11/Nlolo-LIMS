package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

/**
 * A junior underwriter tried to decide against the rules engine's recommendation.
 *
 * <p>Deciding in line with the recommendation is ordinary work and needs no approval.
 * Departing from it is the judgement call, and it is the one this platform could not express
 * at all until the decision step existed: the engine's verdict was written straight into the
 * decision columns, so there was nothing to disagree with and nobody who could.
 *
 * <p>The sign-off is the senior underwriter's own decision, not a two-step request-and-approve
 * workflow. A senior deciding against the recommendation IS the approval, recorded against
 * their name with {@code decision_overrode_recommendation} set.
 */
public class SeniorUnderwriterApprovalRequiredException extends RuntimeException {
    public SeniorUnderwriterApprovalRequiredException(UUID caseId, String recommended, String attempted) {
        super("Case " + caseId + " was recommended " + recommended + " and a decision of " + attempted
            + " departs from it -- only a senior underwriter may do that");
    }
}

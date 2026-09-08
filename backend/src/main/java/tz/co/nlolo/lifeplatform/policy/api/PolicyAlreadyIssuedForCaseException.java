package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

/**
 * An underwriting case that already has a policy was issued again.
 *
 * <p>Names the existing policy number deliberately. Almost everyone who reaches this believes
 * the first issuance failed -- {@code UnderwritingDecisionEventListener} tells them to retry
 * through {@code POST /policies/manual-issue} when automatic issuance errors -- and the only
 * useful answer is where to find the one that succeeded. "Conflict" on its own sends them
 * looking for a bug.
 */
public class PolicyAlreadyIssuedForCaseException extends RuntimeException {
    public PolicyAlreadyIssuedForCaseException(UUID underwritingCaseId, String existingPolicyNumber) {
        super("Underwriting case " + underwritingCaseId + " was already issued as policy "
            + existingPolicyNumber);
    }
}

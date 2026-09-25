package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

/**
 * The person deciding a case is the person who opened it or assessed it.
 *
 * <p>The mirror of the rule claims already enforces between its assessor and its decider. A role
 * check cannot express it: one person can hold UNDERWRITER and have captured the proposal, and a
 * case captured, assessed and accepted by the same login is one nobody but that person has looked
 * at. That is exactly how a single-life policy came to be issued on a group product to a
 * corporate "life assured", with one user doing all three steps inside twenty seconds.
 *
 * <p>Answered as a 403 like the senior sign-off: the decision may be perfectly correct, and the
 * fix is not to change the request but to have somebody else make it.
 */
public class UnderwritingSeparationOfDutiesException extends RuntimeException {
    /** @param role "opened" or "assessed". The message is read by the person refused, so it says
     *  "you" rather than printing their subject back at them as a uuid. */
    public UnderwritingSeparationOfDutiesException(UUID caseId, String role) {
        super("Separation of duties: you " + role + " case " + caseId
            + ", so you cannot also decide it -- another underwriter must");
    }
}

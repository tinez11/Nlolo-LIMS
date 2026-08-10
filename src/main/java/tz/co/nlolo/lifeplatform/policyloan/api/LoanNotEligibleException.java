package tz.co.nlolo.lifeplatform.policyloan.api;

/** Mapped to 409 Conflict -- a loan-status guard was violated (e.g. originating against a
 * policy that isn't in force, or repaying a loan that was never disbursed). */
public class LoanNotEligibleException extends RuntimeException {
    public LoanNotEligibleException(String message) {
        super(message);
    }
}

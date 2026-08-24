package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 409 Conflict -- Module-Architecture-B1's reserve step found insufficient
 * available loan value (Task 3). */
public class InsufficientLoanValueException extends RuntimeException {
    public InsufficientLoanValueException(String message) {
        super(message);
    }
}

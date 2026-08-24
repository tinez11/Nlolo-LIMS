package tz.co.nlolo.lifeplatform.reinsurance.api;

/** 409 at the REST boundary (ReinsuranceExceptionHandler, Task 7). */
public class InvalidRecoveryStateException extends RuntimeException {
    public InvalidRecoveryStateException(String message) { super(message); }
}

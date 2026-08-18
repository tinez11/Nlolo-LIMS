package tz.co.nlolo.lifeplatform.reinsurance.api;

/** 404 at the REST boundary (ReinsuranceExceptionHandler, Task 7). */
public class RecoveryNotFoundException extends RuntimeException {
    public RecoveryNotFoundException(String message) { super(message); }
}

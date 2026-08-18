package tz.co.nlolo.lifeplatform.reinsurance.api;

/** 422 at the REST boundary (ReinsuranceExceptionHandler, Task 7). */
public class ReinsuranceValidationException extends RuntimeException {
    public ReinsuranceValidationException(String message) { super(message); }
}

package tz.co.nlolo.lifeplatform.reinsurance.api;

/** 404 at the REST boundary (ReinsuranceExceptionHandler, Task 7). */
public class TreatyNotFoundException extends RuntimeException {
    public TreatyNotFoundException(String message) { super(message); }
}

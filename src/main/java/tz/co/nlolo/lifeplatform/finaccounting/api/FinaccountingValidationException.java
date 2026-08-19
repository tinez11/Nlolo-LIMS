package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 422 at the REST boundary (FinaccountingExceptionHandler, Task 8). */
public class FinaccountingValidationException extends RuntimeException {
    public FinaccountingValidationException(String message) { super(message); }
}

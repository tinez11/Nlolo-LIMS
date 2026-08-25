package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 404 at the REST boundary (FinaccountingExceptionHandler). */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) { super(message); }
}

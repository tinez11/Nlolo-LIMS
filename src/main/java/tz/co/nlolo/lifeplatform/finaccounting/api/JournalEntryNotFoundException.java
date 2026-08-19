package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 404 at the REST boundary (FinaccountingExceptionHandler, Task 8). */
public class JournalEntryNotFoundException extends RuntimeException {
    public JournalEntryNotFoundException(String message) { super(message); }
}

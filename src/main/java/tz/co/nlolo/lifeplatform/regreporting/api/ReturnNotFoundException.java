package tz.co.nlolo.lifeplatform.regreporting.api;

/** 404 at the REST boundary (RegreportingExceptionHandler, Task 8). */
public class ReturnNotFoundException extends RuntimeException {
    public ReturnNotFoundException(String message) { super(message); }
}

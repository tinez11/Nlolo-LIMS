package tz.co.nlolo.lifeplatform.finaccounting.api;

public class UnpostedEventNotFoundException extends RuntimeException {
    public UnpostedEventNotFoundException(String message) {
        super(message);
    }
}

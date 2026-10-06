package tz.co.nlolo.lifeplatform.finaccounting.api;

/** An unposted event already posted or dismissed: there is nothing left to retry or dismiss. */
public class UnpostedEventResolvedException extends RuntimeException {
    public UnpostedEventResolvedException(String message) {
        super(message);
    }
}

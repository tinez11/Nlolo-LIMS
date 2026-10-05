package tz.co.nlolo.lifeplatform.finaccounting.api;

/** An accounting period refused a transition in its current state (a 409), in the period's own words. */
public class PeriodStateException extends RuntimeException {
    public PeriodStateException(String message) {
        super(message);
    }
}

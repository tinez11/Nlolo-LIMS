package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * A step of the IFRS 17 engine cycle that the period, the run's state or its people refuse (409, IFRS 17 I5a): an
 * extract of a period not yet closing or with events unposted, an approval by the uploader, a replacement in a locked
 * period.
 */
public class EngineStateException extends RuntimeException {
    public EngineStateException(String message) {
        super(message);
    }
}

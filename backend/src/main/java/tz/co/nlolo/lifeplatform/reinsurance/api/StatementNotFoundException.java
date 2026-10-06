package tz.co.nlolo.lifeplatform.reinsurance.api;

/** No statement with this id in the caller's tenant (404). */
public class StatementNotFoundException extends RuntimeException {
    public StatementNotFoundException(String message) {
        super(message);
    }
}

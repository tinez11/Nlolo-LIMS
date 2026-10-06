package tz.co.nlolo.lifeplatform.reinsurance.api;

/**
 * A step the statement's state, the quarter or the people refuse (409): the quarter has not ended, a bordereau is not
 * written, the quarter already has a statement, or the preparer tried to decide their own statement.
 */
public class StatementStateException extends RuntimeException {
    public StatementStateException(String message) {
        super(message);
    }
}

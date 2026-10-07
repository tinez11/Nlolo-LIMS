package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * A step of the year-end close that the periods, the close's state or its people refuse (409, IFRS 17 I6): December not
 * closing, an earlier month with postings still open, nothing to close, a second close while one awaits a decision, a
 * decision by its preparer.
 */
public class YearEndCloseStateException extends RuntimeException {
    public YearEndCloseStateException(String message) {
        super(message);
    }
}

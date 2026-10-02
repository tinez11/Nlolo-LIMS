package tz.co.nlolo.lifeplatform.bonus.api;

/** A rule refused the request. 422 BONUS_REFUSED, shown verbatim by the console. */
public class BonusStateException extends RuntimeException {
    public BonusStateException(String message) { super(message); }
}

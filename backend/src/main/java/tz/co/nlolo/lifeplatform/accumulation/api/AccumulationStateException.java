package tz.co.nlolo.lifeplatform.accumulation.api;

/** Understood and refused on a rule, not malformed. 422 ACCUMULATION_REFUSED, message shown verbatim. */
public class AccumulationStateException extends RuntimeException {
    public AccumulationStateException(String message) { super(message); }
}

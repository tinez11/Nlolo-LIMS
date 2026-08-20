package tz.co.nlolo.lifeplatform.regreporting.api;

/** 422 at the REST boundary: an unknown return type, or a period whose format does not match
 * the return type's declared {@code period_kind}. */
public class RegreportingValidationException extends RuntimeException {
    public RegreportingValidationException(String message) { super(message); }
}

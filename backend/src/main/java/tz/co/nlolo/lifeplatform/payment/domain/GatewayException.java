package tz.co.nlolo.lifeplatform.payment.domain;

/** A transport/protocol failure talking to the rail (timeout, 5xx, unparseable body) — as
 * opposed to a GatewayResult with accepted=false, which is the rail deliberately declining. */
public class GatewayException extends RuntimeException {
    public GatewayException(String message, Throwable cause) { super(message, cause); }
    public GatewayException(String message) { super(message); }
}

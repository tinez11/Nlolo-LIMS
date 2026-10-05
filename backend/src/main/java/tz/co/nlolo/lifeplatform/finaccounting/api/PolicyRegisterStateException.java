package tz.co.nlolo.lifeplatform.finaccounting.api;

/** An accounting policy election refused in its current state (a 409): the proposer deciding it, or deciding twice. */
public class PolicyRegisterStateException extends RuntimeException {
    public PolicyRegisterStateException(String message) {
        super(message);
    }
}

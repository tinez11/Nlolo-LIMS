package tz.co.nlolo.lifeplatform.unitlinked.api;

/** 409 UNIT_LINKED_STATE: the request is well formed but the register or the policy is not in a state to take it. */
public class UnitLinkedStateException extends RuntimeException {
    public UnitLinkedStateException(String message) {
        super(message);
    }
}

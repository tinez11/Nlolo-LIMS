package tz.co.nlolo.lifeplatform.iam.api;

/**
 * A change to someone's access that the rules refuse -- your own access, the last administrator, a second signature
 * given by the person who asked for it. Carries the code the console keys its message on.
 */
public class AccessChangeRefusedException extends RuntimeException {

    public enum Reason { SELF_CHANGE, LAST_ADMINISTRATOR, SAME_PERSON_APPROVES, NOT_PENDING, UNKNOWN_ROLE,
        UNKNOWN_PERMISSION, ROLE_CODE_TAKEN, USERNAME_TAKEN, NOT_FOUND, LOGIN_PROVIDER }

    private final Reason reason;

    public AccessChangeRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}

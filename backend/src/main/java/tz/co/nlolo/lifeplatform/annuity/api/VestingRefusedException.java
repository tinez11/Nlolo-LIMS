package tz.co.nlolo.lifeplatform.annuity.api;

/** A vesting instruction or re-confirmation refused, in words a person can act on (product step 5 D2). A 422. */
public class VestingRefusedException extends RuntimeException {
    public VestingRefusedException(String message) {
        super(message);
    }
}

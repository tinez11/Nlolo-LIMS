package tz.co.nlolo.lifeplatform.unitlinked.api;

/** 404 FUND_NOT_FOUND: no fund with this code, or no price with this id, in the caller's register. */
public class FundNotFoundException extends RuntimeException {
    public FundNotFoundException(String what) {
        super(what + " is not in the fund register");
    }
}

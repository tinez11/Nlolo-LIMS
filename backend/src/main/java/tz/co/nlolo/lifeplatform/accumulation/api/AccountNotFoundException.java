package tz.co.nlolo.lifeplatform.accumulation.api;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String policyNumber) {
        super("Policy " + policyNumber + " has no savings account");
    }
}

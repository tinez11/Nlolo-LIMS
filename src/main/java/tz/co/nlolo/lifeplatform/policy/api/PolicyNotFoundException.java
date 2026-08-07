package tz.co.nlolo.lifeplatform.policy.api;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String policyNumber) {
        super("No policy found for policyNumber " + policyNumber);
    }
}

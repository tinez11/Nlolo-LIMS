package tz.co.nlolo.lifeplatform.policy.api;

import java.util.List;
import java.util.UUID;

/**
 * The account charges a savings policy is charged by (2026-10-09, product V32): copied from its case at issue, or chosen
 * on the manual issue screen. Empty means its product version's own charges.
 */
public interface PolicyAccountChargeApi {

    List<UUID> accountCharges(String policyNumber);

    /**
     * Replace the charges chosen at issue -- the manual issue screen's choice, made in the request that issues the policy
     * (the only caller). Refused unless the version keeps an account and every charge is still offered.
     */
    void assignAccountCharges(String policyNumber, List<UUID> chargeIds);
}

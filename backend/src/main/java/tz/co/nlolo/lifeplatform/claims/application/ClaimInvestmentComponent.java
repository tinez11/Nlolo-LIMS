package tz.co.nlolo.lifeplatform.claims.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How much of an approved claim is an investment component (IFRS 17 I3b, posting guide C-04): money the policy repays
 * in all circumstances, which IFRS 17 para 85 keeps out of the insurance service expense. Computed here, by the
 * accounting policy register's INVESTMENT_COMPONENT_RULE for the policy's portfolio -- the ledger never computes a
 * split (user decision 4).
 * <ul>
 *   <li>SURRENDER_VALUE (and SURRENDER_VALUE_WITH_BONUSES, until bonuses reach the cash value): the policy's cash
 *       value, never more than the claim;</li>
 *   <li>NONE and PREMIUMS_RETURNED: nothing -- a return of premium is a maturity benefit, not part of a death claim;</li>
 *   <li>FUND_VALUE: nothing here -- a unit-linked claim's fund leaves 2131 through the exit sale (posting guide F-09
 *       comes later);</li>
 *   <li>WHOLE_BALANCE: an IFRS 9 contract, whose claim the ledger takes from its own account ledger.</li>
 * </ul>
 * A policy issued before IFRS 17 I2 carries no portfolio and has none.
 */
@Component
class ClaimInvestmentComponent {

    private final PolicyApi policyApi;
    private final FinaccountingApi finaccountingApi;

    ClaimInvestmentComponent(PolicyApi policyApi, FinaccountingApi finaccountingApi) {
        this.policyApi = policyApi;
        this.finaccountingApi = finaccountingApi;
    }

    BigDecimal of(String policyNumber, LocalDate dateOfEvent, BigDecimal approved) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (policy.portfolioCode() == null) {
            return BigDecimal.ZERO;
        }
        // A policy with no cash value has no investment component whatever the rule: most claims (term, credit life,
        // funeral) end here without asking the register.
        BigDecimal cashValue;
        try {
            cashValue = policyApi.getCashValue(policyNumber).cashValueAmount();
        } catch (PolicyNotFoundException noAccount) {
            return BigDecimal.ZERO;   // a contract with no value account (a group scheme) has no cash value
        }
        if (cashValue == null || cashValue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        String rule = finaccountingApi.investmentComponentRule(policy.portfolioCode(), dateOfEvent).orElse("NONE");
        if (!"SURRENDER_VALUE".equals(rule) && !"SURRENDER_VALUE_WITH_BONUSES".equals(rule)) {
            return BigDecimal.ZERO;
        }
        return cashValue.min(approved);
    }
}

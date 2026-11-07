package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.policy.api.CashValueView;
import tz.co.nlolo.lifeplatform.policy.api.LoanNetting;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;

/**
 * An account's payout repays the policy's loans first (2026-11-07, the user, decision E7): a surrender, a fixed
 * deposit's maturity, a term account's maturity. Only the rest is paid; the ledger clears the payable 2340 by the
 * loans' part (E-03-SURRENDER-IFRS9, E-03-ACCOUNT) and by what the rail paid.
 *
 * <p>Asked before the payout empties the account, while the policy still shows its value: an account with a balance
 * asks every time, so a retried payout -- keyed on the deposit period or the term, never the attempt -- is answered
 * with what the first attempt already took, and pays only the rest.
 */
@Component
class AccountLoanNetting {

    private final PolicyApi policyApi;
    private LoanNetting netting;

    AccountLoanNetting(PolicyApi policyApi) {
        this.policyApi = policyApi;
    }

    /** Implemented by policyloan; absent in a slice without it, which nets nothing. */
    @Autowired(required = false)
    void setNetting(LoanNetting netting) {
        this.netting = netting;
    }

    /** What the payout repaid of the policy's loans, at most {@code gross}; zero where it has none. */
    BigDecimal repay(String policyNumber, BigDecimal gross, String currency, LoanNetting.Payout payout, String payoutRef,
                     String by) {
        if (netting == null || gross == null || gross.signum() <= 0) return BigDecimal.ZERO;
        CashValueView value = policyApi.getCashValue(policyNumber);
        if (value.cashValueAmount().signum() <= 0 && value.loanEncumbranceAmount().signum() <= 0) return BigDecimal.ZERO;
        return netting.settleFromPayout(policyNumber, gross, currency, payout, payoutRef, by).total().min(gross);
    }
}

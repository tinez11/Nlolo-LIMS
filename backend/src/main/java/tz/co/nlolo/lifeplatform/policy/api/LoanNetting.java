package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;

/**
 * A payout repays the policy's loans before anything is paid out (2026-11-07, the user, decision E7: a death claim,
 * maturity or surrender pays what is left after the loan; this replaces Q1's "repay it before surrendering").
 *
 * <p>A port, as iam's StaffTexts is: declared here, where every payout module already looks, and implemented by
 * policyloan, which owns the loans and already depends on policy. So claims, benefitpayout, accumulation and policy
 * itself net a loan off a payout with no new module dependency and no cycle. Docs 05 §6 called this "loan netting".
 */
public interface LoanNetting {

    /** What a payout repaid: interest first, then principal, as every repayment is split. */
    record Netted(BigDecimal total, BigDecimal interest, BigDecimal principal) {
        public static final Netted NONE = new Netted(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The payout that repays a loan, which is also the ledger's payable the repayment clears. */
    enum Payout { CLAIM, SURRENDER, MATURITY, ACCOUNT }

    /**
     * Repay the policy's loans from a payout of {@code gross}, oldest loan first, at most what they owe. Pays nothing
     * out itself: the caller pays {@code gross − total}. Idempotent on {@code (payout, payoutRef)} -- asked again for
     * the same payout, it answers what it took the first time and takes nothing more.
     */
    Netted settleFromPayout(String policyNumber, BigDecimal gross, String currency, Payout payout, String payoutRef,
                            String by);

    /** What the policy's loans owe now, principal and interest: what a payout would repay first. */
    BigDecimal owedOn(String policyNumber);

    /** What this payout already repaid, zero if nothing: a read, for the payout's paid event, that takes nothing. */
    BigDecimal nettedFor(Payout payout, String payoutRef);
}

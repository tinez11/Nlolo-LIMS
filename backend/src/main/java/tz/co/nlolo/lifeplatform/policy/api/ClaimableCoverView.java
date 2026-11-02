package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What may be claimed against this contract, for this life, on this date.
 *
 * <p>One answer for two very different contracts, and that is the point: {@code claims} must
 * not branch on product category. It cannot, in fact — {@code product::api} is not among its
 * allowed dependencies, so a {@code ProductCategory} anywhere in claims' bytecode fails
 * {@code ModularityTests}. Group-versus-individual is therefore decided here, in the module
 * that owns the member schedule.
 *
 * @param amount on a group scheme, the member's {@code covered_amount} in force on the date of
 *     event — already capped at the free cover limit wherever the limit bit, so it must NOT be
 *     capped again by a reader. On individual business, the policy's sum assured. On credit life,
 *     the loan's principal still owed by the scheme's own schedule: the figure a claim pays when the
 *     lender's statement is not on it.
 * @param currencyCode the scheme's currency, or the policy's. An amount travelling without one
 *     is how a figure ends up rendered as the wrong money somewhere downstream.
 * @param policyMemberId echoed back on a group scheme, null on individual business.
 * @param loanCover credit life only (2026-10-30): the most a claim on this borrower can pay — their
 *     cover before it declines, already capped at the free cover limit. The lender's statement is paid
 *     up to this. Null on every other product.
 * @param coverIncludesInterest credit life only: whether the lender's agreement in force when the loan
 *     started covers interest, so the statement's interest is paid too. Null on every other product.
 * @param loanPrincipal credit life only (2026-11-02): what was lent. The principal a lender says is still owed can
 *     never be more, so a statement claiming more is refused at registration. Null on every other product.
 */
public record ClaimableCoverView(BigDecimal amount, String currencyCode, UUID policyMemberId,
                                 BigDecimal loanCover, Boolean coverIncludesInterest, BigDecimal loanPrincipal) {

    /** Every product but credit life. */
    public ClaimableCoverView(BigDecimal amount, String currencyCode, UUID policyMemberId) {
        this(amount, currencyCode, policyMemberId, null, null, null);
    }

    /** Whether this is a credit-life borrower's cover, which a lender's statement may value. */
    public boolean isLoanCover() {
        return loanCover != null;
    }
}

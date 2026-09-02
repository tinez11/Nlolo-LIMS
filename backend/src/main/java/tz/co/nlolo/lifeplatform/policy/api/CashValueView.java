package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;

/**
 * Read view of {@code policy.domain.PolicyAccount}'s money columns, for the forced-lapse
 * shortfall test: {@code docs/01-domain-map.md:224} defines Forced Lapse as firing when "loan
 * balance plus interest exceeds cash value", and cash value lives in {@code policy.policy_account}
 * -- a table {@code docs/01-domain-map.md:46} says {@code policyloan} reads "via its public API,
 * never its tables directly".
 *
 * <p><b>Why not {@link PolicyApi#quoteSurrenderValue}, which also reads this account.</b> Two
 * reasons, both disqualifying. It applies the product's surrender charge, so it answers "what
 * would we pay out on surrender", not "what is the policy's cash value" -- the charge would make
 * every loan look closer to shortfall than it is, force-lapsing policies early. And it publishes
 * {@code policy.SurrenderValueCalculated}, so using it as a periodic health check would emit a
 * surrender-quote event for policies nobody asked to surrender, misleading every consumer of that
 * event and polluting the audit trail.
 *
 * <p>{@code loanEncumbranceAmount} is carried alongside deliberately, even though the shortfall
 * test compares against {@code cashValueAmount} alone: it is what makes a forced-lapse decision
 * auditable after the fact, since it shows how much of that cash value was already pledged.
 */
public record CashValueView(String policyNumber, BigDecimal cashValueAmount, String cashValueCurrency,
                             BigDecimal loanEncumbranceAmount) {}

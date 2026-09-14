package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole chart with its balances, and the one assertion a ledger must always be able to make:
 * that debits equal credits.
 *
 * <p><b>The totals sum OWN figures, never rolled ones.</b> Adding up every account's rolled
 * balance would count each posting once for its own account and again for every ancestor above
 * it, so a perfectly sound ledger would report a wild imbalance. This is the single easiest way
 * to get a trial balance wrong, and it is why the totals live here — computed once, on the
 * server — rather than being summed by whatever renders the table. No client-side arithmetic on
 * money is this platform's standing rule, and a total that can be silently wrong is exactly why.
 *
 * <p>{@code balanced} is stated rather than left to be inferred by comparing two decimal strings
 * in a browser.
 *
 * <p>{@code period} is echoed back, null meaning inception-to-date. A balance with no period
 * beside it is a number nobody can reconcile against anything.
 */
public record TrialBalanceView(
    String period,
    List<AccountBalanceView> accounts,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    boolean balanced) {}

package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;

/**
 * One account's place in the chart, and what the ledger says about it.
 *
 * <p><b>Two sets of figures, deliberately.</b> {@code ownDebit}/{@code ownCredit} are the
 * postings made against THIS account code. {@code debit}/{@code credit} roll those up through
 * the hierarchy, so a parent reports itself plus every descendant. A summary account carries
 * no postings of its own, so showing only its own figures would report zero against a branch
 * holding millions — and showing only the rolled figure would hide the fact that nothing was
 * posted there directly. Both, and the reader can tell which is which.
 *
 * <p><b>{@code balance} is signed in the account's OWN normal direction.</b> A DR-normal account
 * balances as debits minus credits; a CR-normal account as credits minus debits. So a positive
 * balance always means "normal", and a negative one is a genuine anomaly worth seeing rather
 * than an artefact of which way the subtraction happened to run.
 */
public record AccountBalanceView(
    String accountCode,
    String name,
    AccountType accountType,
    PostingDirection normalBalance,
    String parentCode,
    short level,
    /** False for a summary account -- the platform refuses direct postings to it. */
    boolean postingAllowed,
    AccountStatus status,
    String currency,
    /** Postings against this account code alone. */
    BigDecimal ownDebit,
    BigDecimal ownCredit,
    /** This account plus every descendant. Equal to the `own` figures for a leaf. */
    BigDecimal debit,
    BigDecimal credit,
    /** Rolled debit/credit netted in this account's normal direction. */
    BigDecimal balance) {}

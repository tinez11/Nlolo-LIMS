package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What closing the account on {@code asOf} would move: the balance plus interest not yet posted.
 * Before any surrender charge -- the charge is the insurer's and is never a ledger entry.
 */
public record ClosingQuote(String policyNumber, LocalDate asOf, BigDecimal balance, BigDecimal interestToDate,
                           BigDecimal value, String currency) {}

package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A term account as the console shows it (2026-10-21): the rate the policy locked at sale, when it matures, and
 * what became of it. The balance and its movements are the account's own -- {@link AccountView} and the ledger.
 */
public record TermAccountView(java.util.UUID termAccountId, String policyNumber, BigDecimal ratePercent, LocalDate maturityDate,
                              TermAccountStatus status, String defaultPayeeRef, LocalDate maturedOn) {}

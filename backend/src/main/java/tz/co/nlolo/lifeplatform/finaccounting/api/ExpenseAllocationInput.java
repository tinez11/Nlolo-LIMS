package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;

/**
 * What finance types for a month's expense allocation (IFRS 17 I5b, P-19): three totals and the study they come from, or
 * -- all three zero -- why there is no allocation this month.
 */
public record ExpenseAllocationInput(BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition,
                                     String studyReference, String note, String nilReason) {}

package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.util.List;

/** An expense allocation's split as it would post now (IFRS 17 I5b): the month's pool beside the total, and the lines. */
public record ExpenseAllocationPreview(String period, BigDecimal pool, BigDecimal total, boolean overPool,
                                       List<ExpenseAllocationView.Line> lines) {}

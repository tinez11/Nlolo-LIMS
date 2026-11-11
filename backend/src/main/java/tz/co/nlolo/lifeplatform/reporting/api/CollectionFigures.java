package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The collection figures (C1, 2026-11-11): of the premiums that fell due from {@code from} to {@code to}, how much was
 * asked for, how much of it is paid, and the rate; and, as of today, what is due today and what is overdue.
 *
 * @param ratePercent paid ÷ due, one decimal ("87.5"); null when nothing fell due -- no rate is not a rate of zero
 */
public record CollectionFigures(LocalDate from, LocalDate to, BigDecimal due, BigDecimal paid, String ratePercent,
                                long dueTodayCount, BigDecimal dueTodayOwed, long overdueCount, BigDecimal overdueOwed,
                                String currency) {}

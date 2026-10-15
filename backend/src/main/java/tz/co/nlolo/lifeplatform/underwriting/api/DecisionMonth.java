package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's decisions of one outcome, and how long they took (2026-10-15).
 *
 * @param averageDays mean days from a case being opened to being decided, one decimal place. Calendar days, not
 *     working days: the platform has no calendar of holidays, and a "4.2 working days" it cannot support would be a
 *     figure nobody could reproduce.
 */
public record DecisionMonth(YearMonth month, DecisionOutcome outcome, long cases, BigDecimal averageDays) {}

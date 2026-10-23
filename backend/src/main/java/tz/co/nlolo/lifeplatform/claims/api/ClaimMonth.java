package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * How many claims something happened to in one civil month (2026-10-23). For settlements {@code totalDays} is the
 * calendar days from registration to settlement added over those claims, unrounded, so a caller can average any span
 * of months exactly; for registrations it is null.
 */
public record ClaimMonth(YearMonth month, long claims, BigDecimal totalDays) {}

package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One version of the solvency factors. Version 0 is the built-in placeholder set every company starts on; a published
 * version is never changed, so a past month can be re-run on what was in force then.
 *
 * @param placeholder the factors are stand-ins, not the regulator's -- every solvency report says so while it is true
 * @param createdBy   who published it; null for the built-in set
 */
public record SolvencyFactorSetView(int version, String name, LocalDate effectiveFrom, boolean placeholder,
                                    String createdBy, Instant createdAt, SolvencyFactors factors) {}

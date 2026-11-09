package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.LocalDate;

/** A new version of the solvency factors, as published from the console. */
public record NewSolvencyFactorSet(String name, LocalDate effectiveFrom, boolean placeholder, SolvencyFactors factors) {}

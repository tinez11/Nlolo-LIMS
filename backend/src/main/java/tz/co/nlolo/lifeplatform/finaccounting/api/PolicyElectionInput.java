package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.LocalDate;

/** A proposed accounting policy election: which key, for which scope ("*" for all), what value, from when, and why. */
public record PolicyElectionInput(String key, String scope, String value, LocalDate effectiveFrom, String rationale) {}

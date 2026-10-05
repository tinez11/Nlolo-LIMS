package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A filed unit statement (U2): ANNUAL (the calendar year, once) or ON_DEMAND; its PDF downloads by statementId. */
public record UnitStatementView(UUID statementId, String policyNumber, LocalDate periodFrom, LocalDate periodTo,
                                String kind, String generatedBy, Instant generatedAt) {}

package tz.co.nlolo.lifeplatform.accumulation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StatementRecordView(UUID statementId, String policyNumber, LocalDate periodFrom, LocalDate periodTo,
                                  int lastSeq, String documentRef, String generatedBy, Instant generatedAt) {}

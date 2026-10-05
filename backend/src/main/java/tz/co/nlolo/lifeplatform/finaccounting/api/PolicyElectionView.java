package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One accounting policy election (IFRS 17 spec §3): PROPOSED until a second person decides it, then APPROVED (with
 * its sign-off and its place in the register, {@code registerVersion}) or REJECTED (with a reason).
 */
public record PolicyElectionView(UUID electionId, String key, String scope, String value, LocalDate effectiveFrom,
                                 String status, String rationale, String signOffRef, String decisionReason,
                                 String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt,
                                 Integer registerVersion) {}

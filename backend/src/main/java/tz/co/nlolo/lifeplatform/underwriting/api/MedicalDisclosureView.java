package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recorded set of disclosures on a case.
 *
 * <p>A set, not a single answer, because a proposal form is answered in one sitting and the
 * meaningful unit is "what was declared, when, by whom". Recording a second set does not amend
 * the first — later evidence supersedes earlier evidence in the reader's judgement, and both
 * stay on the record, which is the only shape a contest can be argued from.
 */
public record MedicalDisclosureView(
    UUID medicalDisclosureId,
    UUID caseId,
    List<DisclosureAnswer> answers,
    String recordedBy,
    Instant recordedAt) {}

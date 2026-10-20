package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A quotation the customer agreed to (2026-10-20). {@code inputs} and {@code quoted} are the JSON the quotation wrote:
 * what was quoted, and the server's figures for it on {@code quotedOn}.
 *
 * @param caseId the proposal opened from it; null while the agent is onboarding the customer
 */
public record AcceptedQuotationView(UUID quotationId, String ownerSubject, UUID applicantPartyId, UUID productId,
                                    UUID productVersionId, String inputs, String quoted, LocalDate quotedOn,
                                    LocalDate validUntil, UUID caseId, Instant submittedAt) {}

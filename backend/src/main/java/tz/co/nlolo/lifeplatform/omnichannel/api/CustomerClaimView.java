package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A claim as its claimant reads it in the portal (2026-10-08, the customer portal design step 4; PRD sections 17-21):
 * where it is, in plain words, what was decided and why in words fit for a customer, the documents sent, and the
 * documents still asked for. Never an assessor's findings or a staff note.
 *
 * @param steps the claim's stages in order, each DONE, CURRENT or PENDING
 * @param decision the decision in a customer's words; null while undecided
 */
public record CustomerClaimView(UUID claimId, String policyNumber, String productName, String claimType, String status,
                                String statusText, LocalDate dateOfEvent, List<Step> steps, String decision,
                                BigDecimal approvedAmount, String currency, List<Document> documents,
                                List<DocumentRequest> requests) {

    public record Step(String label, String state, Instant date) {}

    public record Document(String documentRef, String description, Instant uploadedAt, String uploadedByName) {}

    public record DocumentRequest(UUID requestId, String document, String reason, Instant requestedAt, String status,
                                  Instant receivedAt) {}

    /** One claim in the customer's list. */
    public record Summary(UUID claimId, String policyNumber, String productName, String claimType, String status,
                          String statusText, LocalDate dateOfEvent, int actionsRequired) {}
}

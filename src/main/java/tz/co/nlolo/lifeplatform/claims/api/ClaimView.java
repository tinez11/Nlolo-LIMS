package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Field set mirrors openapi-claims.yaml's ClaimView schema, with claimantPartyId and details
 * added since later tasks (registration, assessment, decision) need both round-tripped through
 * the application layer even though the external schema only commits to the narrower set. */
public record ClaimView(UUID claimId, String policyNumber, UUID claimantPartyId, ClaimType claimType,
                         ClaimStatus status, LocalDate dateOfEvent, ClaimDetails details,
                         BigDecimal approvedAmount, String approvedCurrency) {}

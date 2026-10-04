package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Field set mirrors openapi-claims.yaml's ClaimView schema, with claimantPartyId and details
 * added since later tasks (registration, assessment, decision) need both round-tripped through
 * the application layer even though the external schema only commits to the narrower set.
 *
 * <p>{@code requiresContestabilityReview} is NOT a column on {@code claims.claim} (Task 1's
 * migration is the only one in scope to add one) -- {@code ClaimsApiImpl} derives it fresh on
 * every read by re-checking {@code UnderwritingApi.checkContestability}, per Task 4's brief. */
public record ClaimView(UUID claimId, String policyNumber,
                         /** The insured life, on a group scheme. NULL on individual business, where the
                          * policy names the life itself -- not "unknown". */
                         UUID policyMemberId,
                         UUID claimantPartyId, ClaimType claimType,
                         ClaimStatus status, LocalDate dateOfEvent, ClaimDetails details,
                         BigDecimal approvedAmount, String approvedCurrency,
                         boolean requiresContestabilityReview,
                         /** The covered life who died, on a funeral plan's claim; NULL on every other
                          * claim, and on a list row (a search does not load it). */
                         UUID coveredLifeId,
                         /** Whether the death was accidental; null where coveredLifeId is. */
                         Boolean accidental) {}

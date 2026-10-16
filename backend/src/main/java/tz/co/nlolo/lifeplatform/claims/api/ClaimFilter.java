package tz.co.nlolo.lifeplatform.claims.api;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * What a caller is asking of the claim list. A record rather than more parameters: the search had five and grew four
 * more for the dashboards (2026-10-15), following {@code underwriting.api.CaseFilter} exactly in shape.
 *
 * <p>Every field is optional and means "no filter on that dimension" when null. {@code policyNumbers} keeps its
 * three-way meaning: NULL for staff (no scope), an EMPTY collection for an agent whose book is empty (zero rows), a
 * populated collection for that agent's policies.
 *
 * <p>Both date ranges are half-open -- {@code from} inclusive, {@code to} exclusive -- so a month is [first of
 * month, first of next). A settled-date bound also excludes the unsettled: a null {@code settledAt} is in no range.
 */
public record ClaimFilter(ClaimStatus status, ClaimType claimType, UUID claimantPartyId,
                          Collection<String> policyNumbers, String q,
                          Instant registeredFrom, Instant registeredTo,
                          Instant settledFrom, Instant settledTo) {

    public static ClaimFilter none() {
        return new ClaimFilter(null, null, null, null, null, null, null, null, null);
    }

    public static ClaimFilter status(ClaimStatus status) {
        return none().withStatus(status);
    }

    public ClaimFilter withStatus(ClaimStatus value) {
        return new ClaimFilter(value, claimType, claimantPartyId, policyNumbers, q,
            registeredFrom, registeredTo, settledFrom, settledTo);
    }

    public ClaimFilter withClaimType(ClaimType value) {
        return new ClaimFilter(status, value, claimantPartyId, policyNumbers, q,
            registeredFrom, registeredTo, settledFrom, settledTo);
    }

    public ClaimFilter withClaimant(UUID value) {
        return new ClaimFilter(status, claimType, value, policyNumbers, q,
            registeredFrom, registeredTo, settledFrom, settledTo);
    }

    public ClaimFilter withPolicyNumbers(Collection<String> value) {
        return new ClaimFilter(status, claimType, claimantPartyId, value, q,
            registeredFrom, registeredTo, settledFrom, settledTo);
    }

    public ClaimFilter withQuery(String value) {
        return new ClaimFilter(status, claimType, claimantPartyId, policyNumbers, value,
            registeredFrom, registeredTo, settledFrom, settledTo);
    }

    public ClaimFilter withRegistered(Instant from, Instant to) {
        return new ClaimFilter(status, claimType, claimantPartyId, policyNumbers, q,
            from, to, settledFrom, settledTo);
    }

    public ClaimFilter withSettled(Instant from, Instant to) {
        return new ClaimFilter(status, claimType, claimantPartyId, policyNumbers, q,
            registeredFrom, registeredTo, from, to);
    }
}

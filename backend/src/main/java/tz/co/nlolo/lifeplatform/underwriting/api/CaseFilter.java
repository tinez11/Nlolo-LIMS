package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller is asking of the case list. A record rather than more parameters: the search had four and grew three
 * more for the dashboards (2026-10-15), and a seven-argument method is one whose call sites no longer read.
 *
 * <p>Every field is optional and means "no filter on that dimension" when null. {@code applicantPartyIds} keeps its
 * three-way meaning exactly: NULL for staff (no scope), an EMPTY set for an agent who has registered nobody (scope to
 * nothing, zero rows), a populated set for the clients an agent registered.
 *
 * @param decidedFrom inclusive; {@code decidedTo} exclusive, so a month is [first of month, first of next)
 */
public record CaseFilter(UnderwritingCaseStatus status, DecisionOutcome outcome, ReferralStatus referral,
                         Instant decidedFrom, Instant decidedTo, UUID applicantPartyId, Set<UUID> applicantPartyIds) {

    public static CaseFilter none() {
        return new CaseFilter(null, null, null, null, null, null, null);
    }

    public static CaseFilter status(UnderwritingCaseStatus status) {
        return none().withStatus(status);
    }

    public CaseFilter withStatus(UnderwritingCaseStatus value) {
        return new CaseFilter(value, outcome, referral, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withOutcome(DecisionOutcome value) {
        return new CaseFilter(status, value, referral, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withReferral(ReferralStatus value) {
        return new CaseFilter(status, outcome, value, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withDecided(Instant from, Instant to) {
        return new CaseFilter(status, outcome, referral, from, to, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withApplicant(UUID value) {
        return new CaseFilter(status, outcome, referral, decidedFrom, decidedTo, value, applicantPartyIds);
    }

    public CaseFilter withApplicants(Set<UUID> value) {
        return new CaseFilter(status, outcome, referral, decidedFrom, decidedTo, applicantPartyId, value);
    }
}

package tz.co.nlolo.lifeplatform.underwriting.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A proposal has arrived for the underwriter (2026-10-20): first submitted from an agent's quotation, or resubmitted
 * after a return or a postponement. Published as {@code underwriting.ProposalSubmitted} after the commit, for the
 * underwriting team's alert email -- the queue itself needs no event, it reads the case.
 *
 * <p>Also answers "who introduced this client": the agent who registered the applicant, which is who the policy's
 * commission follows and who the underwriter will be dealing with.
 */
@Component
class ProposalArrivals {

    static final String EVENT = "underwriting.ProposalSubmitted";

    private final ApplicationEventPublisher events;
    private final PartyApi partyApi;

    ProposalArrivals(ApplicationEventPublisher events, PartyApi partyApi) {
        this.events = events;
        this.partyApi = partyApi;
    }

    /** @param round 1 for a first submission, 2 and on for each resubmission */
    void announce(UnderwritingCase c, int round) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", c.getCaseId().toString());
        payload.put("proposalNumber", c.getProposalNumber());
        payload.put("round", round);
        payload.put("applicantName", nameOf(c.getApplicantPartyId()));
        String agent = introducedBy(c.getApplicantPartyId());
        payload.put("introducedBy", agent == null ? "" : agent);
        events.publishEvent(DomainEventEnvelope.of(EVENT, c.getTenantId(), payload));
    }

    /** The agent who registered this client, by name; null for a client staff or the customer registered. */
    String introducedBy(UUID applicantPartyId) {
        PartyDetailView applicant = detail(applicantPartyId);
        return applicant == null || applicant.registeredByPartyId() == null ? null
            : nameOf(applicant.registeredByPartyId());
    }

    private String nameOf(UUID partyId) {
        PartyDetailView party = detail(partyId);
        return party == null ? null : party.displayName();
    }

    private PartyDetailView detail(UUID partyId) {
        if (partyId == null) return null;
        try {
            return partyApi.getPartyDetail(partyId);
        } catch (PartyNotFoundException gone) {
            return null;
        }
    }
}

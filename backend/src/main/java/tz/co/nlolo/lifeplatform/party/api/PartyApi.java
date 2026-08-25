package tz.co.nlolo.lifeplatform.party.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface PartyApi {
    PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber, String email, String registeredBy);
    PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber, String email, String registeredBy);

    /**
     * Not part of openapi-party.yaml (which currently only exposes individual/corporate
     * registration -- SACCO/employer group schemes register via /parties/corporates per
     * that spec's own comment). Added narrowly here so GROUP-type parties -- which
     * addGroupMember/listGroupMembers and the PartyType enum already assume exist -- have
     * ANY creation path, and so the GROUP-type check added to addGroupMember is exercisable
     * by a test rather than permanently dead code. Task 8 decides whether/how to expose it.
     */
    PartyView registerGroup(String displayName, String registeredBy);

    PartyView getParty(UUID partyId);
    void submitKycEvidence(UUID partyId, KycStatus status, String evidenceDocumentRef, String verifiedBy);
    void addGroupMember(UUID groupPartyId, UUID memberPartyId);
    Page<GroupMembershipView> listGroupMembers(UUID groupPartyId, Pageable pageable);

    /**
     * There was no way to list/filter parties at all -- {@code PartyRepository} had exactly one
     * query method before this ({@code findByTenantIdAndRegistrationNumber}) -- which meant a
     * party registered PENDING KYC and not yet referenced by any policy/claim/underwriting
     * case/agent was invisible to staff: nothing could find it to review. {@code kycStatus} and
     * {@code createdBy} are both nullable filters (null = no filter on that dimension); staff use
     * this as a KYC review queue (any {@code kycStatus}, any/no {@code createdBy}), agents get a
     * read-only "parties I registered" view (the controller force-scopes {@code createdBy} to the
     * caller's own JWT subject for an agents-realm token, never client-supplied).
     * {@code q} is a free-text, case-insensitive substring match against displayName, combinable
     * with {@code kycStatus} -- both filters apply together, not either-or. Null means no filter
     * on that dimension.
     */
    Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q, Pageable pageable);
}

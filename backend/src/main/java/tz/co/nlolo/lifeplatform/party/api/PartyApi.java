package tz.co.nlolo.lifeplatform.party.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Set;
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

    /**
     * The full party record, for the client register's detail screen.
     *
     * <p>A second read rather than a widened {@link #getParty}: four modules call {@code getParty}
     * as an existence check and would carry the extra PII for nothing, and {@link PartyView} is
     * also every row of {@link #searchParties}'s page. Callers outside {@code PartyController}
     * should keep using {@code getParty} -- this one exists for the one screen that displays a
     * person, and is the single place the agents-realm scoping rule applies.
     */
    PartyDetailView getPartyDetail(UUID partyId);

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

    /**
     * The ids of every party registered by {@code createdBy}, for another module to scope its own
     * query by.
     *
     * <p>Exists because a domain record can reference a party without carrying any agent
     * reference of its own -- an underwriting case names an applicant, not an agent -- so
     * "cases belonging to this agent's clients" cannot be expressed in that module alone. Rather
     * than have underwriting depend on distribution, or join across schemas, the module that owns
     * the registration fact answers the question and the caller filters on the result. Same idiom
     * as {@code PolicyApi.policyNumbersForAgentTeam}.
     *
     * <p>Returns an EMPTY set, never null, for an agent who has registered nobody. Callers must
     * treat that as "scope to nothing" and not as "no scope" -- collapsing the two is how a
     * scoping filter silently becomes an unscoped read.
     */
    Set<UUID> partyIdsRegisteredBy(String createdBy);

    /**
     * Whether one party was registered by {@code createdBy} — the authorization question, asked
     * directly.
     *
     * <p>Separate from {@link #partyIdsRegisteredBy} on purpose: that one answers "which parties",
     * and is the right shape when a caller needs to filter a query by the whole set. This one
     * answers "is this one mine", and exists so a guard on a single record does not have to
     * materialise several thousand ids to check one of them, nor read the party's PII to look at a
     * single column.
     */
    boolean isRegisteredBy(UUID partyId, String createdBy);
}

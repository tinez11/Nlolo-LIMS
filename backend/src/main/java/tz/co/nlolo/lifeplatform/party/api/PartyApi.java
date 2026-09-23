package tz.co.nlolo.lifeplatform.party.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PartyApi {
    /**
     * Register a person, with whatever of the person record is known.
     *
     * @throws DuplicateIdentityDocumentException if the registration carries an
     *     identity document already registered to another party in this tenant.
     */
    PartyView registerIndividual(IndividualRegistration registration, String registeredBy);

    /**
     * Register a person AND record which agent brought them in.
     *
     * <p>{@code registeredByAgentPartyId} is the registering agent's own PARTY id, taken from
     * the caller's {@code party_id} token claim — null when staff registered them or they
     * registered themselves, which is a real state rather than missing data.
     *
     * <p><b>This is what makes an agent's commission real.</b> Commission accrues off
     * {@code PolicyActivated.agentOfRecordId}, and until this existed the only record of who
     * registered a client was {@code created_by}, holding a Keycloak subject that nothing can
     * resolve to an agent. An agent could sign up a customer and earn nothing on their policies
     * unless somebody separately named them on each case.
     *
     * <p>A party id rather than an agent id because party may not depend on distribution. Policy
     * resolves it at issuance — it is the only module allowed to see both.
     *
     * <p>An overload, not a widened signature: about fifty callers, most of them fixtures that
     * care only that a party exists, have no opinion about who registered it.
     */
    PartyView registerIndividual(IndividualRegistration registration, String registeredBy,
                                  UUID registeredByAgentPartyId);

    /**
     * The pre-Build-1 registration: name, date of birth, contact details.
     *
     * <p>Kept as an overload because roughly fifty callers -- almost all of them test
     * fixtures that care about nothing but "a party exists" -- would otherwise have had
     * to grow twelve arguments they have no opinion about.
     *
     * <p><b>Declared abstract here on purpose, and NOT a {@code default} method.</b> As a
     * default method it carried no {@code @Transactional}, so Spring's proxy passed it
     * straight to the target and the delegating call to the two-argument form became a
     * self-invocation that never re-entered the proxy. Registration then ran outside any
     * transaction: the row still saved (Spring Data opens its own), but
     * {@code @TransactionalEventListener(AFTER_COMMIT)} drops events published with no
     * transaction active, so {@code party.PartyRegistered} silently stopped reaching the
     * audit log for every legacy caller. Caught by
     * {@code registeringAnIndividualPublishesEventThatReachesAuditLog}. The
     * implementation carries the annotation instead, which makes the delegation safe
     * because the transaction is already open by the time it happens.
     */
    PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber,
                                  String email, String registeredBy);

    PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber, String email, String registeredBy);

    /** @see #registerIndividual(IndividualRegistration, String, UUID) — an employer is brought in by an agent too. */
    PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber,
                                 String email, String registeredBy, UUID registeredByAgentPartyId);

    /**
     * Correct what the platform has recorded about a person.
     *
     * <p><b>KYC status is untouched, deliberately.</b> KYC is the passport: a document verifying
     * that this person is who they say they are. Amending the record does not un-verify that
     * document, so a correction does not send a verified client back to PENDING and make them
     * prove themselves again over a misspelled street name.
     *
     * <p>A full replacement, not a patch — every field is set to what is passed, nulls included,
     * because "clear the employer" has to be expressible. Callers send the whole picture.
     *
     * <p>Not amendable: KYC status (its own endpoint, backed by its own evidence), party type,
     * and who registered the client — the last because an editable attribution is an editable
     * commission.
     *
     * @throws PartyNotFoundException if no such party exists in this tenant.
     * @throws DuplicateIdentityDocumentException if the amended identity document already
     *     belongs to a DIFFERENT party in this tenant.
     */
    PartyDetailView amendIndividual(UUID partyId, IndividualRegistration amended, String amendedBy);

    /** @see #amendIndividual — the same rules, for a company or a group. */
    PartyDetailView amendOrganisation(UUID partyId, String displayName, String phoneNumber,
                                       String email, String amendedBy);

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
     * The person this identity document names, if this tenant already holds one.
     *
     * <p>Exists because {@link DuplicateIdentityDocumentException} previously told a caller that
     * a duplicate existed and gave them no way whatever to find it — a dead end for anybody
     * trying to attach to the existing person rather than create a second one. The repository
     * has always had this query; only the module API lacked it.
     *
     * <p>The first caller is credit-life member promotion: a borrower enrolled from a lender's
     * spreadsheet may already bank with that lender, and two party rows for one national ID is
     * exactly the duplicate-person problem {@code ux_party_individual_identity} exists to
     * prevent.
     *
     * <p>Empty for an {@link IdentityDocument#none()}, rather than throwing — "we hold nobody
     * with no document" is the honest answer to a meaningless question.
     */
    Optional<PartyView> findByIdentityDocument(IdentityDocument identityDocument);

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
     *
     * <p>{@code partyTypes} restricts the result to the given party types, and a null or empty
     * collection means no restriction. It exists because the client register is two working
     * areas -- individuals, and corporates/groups -- and that separation has to be a server-side
     * filter to be true: filtering a page client-side would present "the individuals among the
     * newest 20 parties" as the individual register and report a total belonging to neither.
     * A collection rather than a single value, because "corporates and groups" is one area of
     * two enum values and needs to page and total as one list.
     */
    Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q,
                                    Collection<PartyType> partyTypes, Pageable pageable);

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
     * The ids of parties whose display name contains {@code q}, case-insensitively — the same
     * ids-only idiom as {@link #partyIdsRegisteredBy}, for a module that holds party ids and
     * needs to filter them by name.
     *
     * <p>Its caller is the group-scheme member roll. A member row holds a {@code memberPartyId}
     * and no name, because the name is this module's to guard, so "find the member called
     * Juma on this 500-life scheme" cannot be answered in the policy module alone. It resolves
     * names here and filters ids there.
     *
     * <p>Returns an EMPTY set, never null, when nothing matches. A caller must treat that as
     * "match nothing" and not as "no filter" — the two collapse into an unfiltered read, and
     * an empty set also cannot be handed to a SQL {@code IN} clause.
     *
     * <p>The set is bounded only by the tenant's party count, the same as
     * {@code partyIdsRegisteredBy}: a one-letter {@code q} in a large tenant resolves a lot of
     * ids. That is the accepted cost of not joining across module schemas, and callers should
     * pass a meaningful search term rather than a prefix of one.
     */
    Set<UUID> partyIdsMatchingName(String q);

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

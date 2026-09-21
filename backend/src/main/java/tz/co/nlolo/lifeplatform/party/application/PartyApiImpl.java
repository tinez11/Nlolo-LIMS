package tz.co.nlolo.lifeplatform.party.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.DuplicateIdentityDocumentException;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.party.domain.GroupMembership;
import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import tz.co.nlolo.lifeplatform.party.infrastructure.GroupMembershipRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.KycRecordRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phone validation: docs/04-api-contracts.md §5 -- Tanzanian E.164 pattern
 * (+255 + 9 digits), enforced at the API boundary. Enforced here (not only in
 * Task 8's REST layer) so it holds for every caller, internal or external.
 */
@Service
public class PartyApiImpl implements PartyApi {

    private static final Pattern TZ_PHONE_PATTERN = Pattern.compile("^\\+255\\d{9}$");

    private final PartyRepository partyRepository;
    private final KycRecordRepository kycRecordRepository;
    private final GroupMembershipRepository groupMembershipRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PartyApiImpl(PartyRepository partyRepository, KycRecordRepository kycRecordRepository,
                         GroupMembershipRepository groupMembershipRepository, ApplicationEventPublisher eventPublisher) {
        this.partyRepository = partyRepository;
        this.kycRecordRepository = kycRecordRepository;
        this.groupMembershipRepository = groupMembershipRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The legacy five-argument registration.
     *
     * <p>{@code @Transactional} belongs HERE rather than on a {@code default} method on
     * the interface: the annotation is what makes the delegation below run inside a
     * transaction, so the party save and its {@code party.PartyRegistered} event stay
     * atomic. See the note on {@link PartyApi#registerIndividual(String, LocalDate,
     * String, String, String)} for what breaks otherwise.
     */
    @Override
    @Transactional
    public PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber,
                                         String email, String registeredBy) {
        return registerIndividual(
            IndividualRegistration.minimal(fullName, dateOfBirth, phoneNumber, email), registeredBy);
    }

    @Override
    @Transactional
    public PartyView registerIndividual(IndividualRegistration registration, String registeredBy) {
        return registerIndividual(registration, registeredBy, null);
    }

    @Override
    @Transactional
    public PartyView registerIndividual(IndividualRegistration registration, String registeredBy,
                                         UUID registeredByAgentPartyId) {
        validatePhone(registration.phoneNumber());
        UUID tenantId = TenantContext.get();

        IdentityDocument document = registration.identityDocument();
        if (document.recorded()
                && partyRepository.findByTenantIdAndIdTypeAndIdNumber(
                    tenantId, document.type(), document.number()).isPresent()) {
            throw new DuplicateIdentityDocumentException(document.type());
        }

        Party party;
        try {
            // saveAndFlush, and the check above is only a nicer error -- exactly the
            // reasoning registerCorporate already records for registration numbers. The
            // partial unique index ux_party_individual_identity is the guarantee: two
            // concurrent requests can both pass the check and race to insert, and partyId
            // is generated in memory, so a plain save() would not reach the database until
            // the surrounding transaction commits and the violation would surface far from
            // here.
            party = document.recorded()
                ? partyRepository.saveAndFlush(Party.newIndividual(tenantId, registration, registeredBy, registeredByAgentPartyId))
                : partyRepository.save(Party.newIndividual(tenantId, registration, registeredBy, registeredByAgentPartyId));
        } catch (DataIntegrityViolationException ex) {
            if (document.recorded()) {
                throw new DuplicateIdentityDocumentException(document.type());
            }
            throw ex;
        }

        publishRegistered(party);
        return toView(party);
    }

    @Override
    @Transactional
    public PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber, String email, String registeredBy) {
        return registerCorporate(registeredName, registrationNumber, phoneNumber, email, registeredBy, null);
    }

    @Override
    @Transactional
    public PartyView registerCorporate(String registeredName, String registrationNumber, String phoneNumber,
                                        String email, String registeredBy, UUID registeredByAgentPartyId) {
        validatePhone(phoneNumber);
        UUID tenantId = TenantContext.get();
        if (partyRepository.findByTenantIdAndRegistrationNumber(tenantId, registrationNumber).isPresent()) {
            throw new DuplicateRegistrationNumberException(registrationNumber);
        }
        Party party;
        try {
            // The check above is a fast-path UX improvement, not the guarantee -- ux_party_corporate_regno
            // (the partial unique index on (tenant_id, registration_number)) is. Two concurrent requests can
            // both pass the check above and race to insert; the loser's DataIntegrityViolationException is
            // translated here so callers see the same domain exception regardless of timing. This MUST be
            // saveAndFlush, not save: partyId is an in-memory-generated UUID (Hibernate's UuidGenerator, no
            // DB round-trip needed to assign it), so plain save() only queues the INSERT in the flush action
            // queue -- it doesn't hit the DB until the surrounding @Transactional proxy commits, which is
            // after this method (and this catch block) has already returned. saveAndFlush forces the INSERT
            // to execute synchronously, right here, so a real unique-constraint violation is actually caught.
            party = partyRepository.saveAndFlush(Party.newCorporate(tenantId, registeredName, registrationNumber, phoneNumber, email, registeredBy, registeredByAgentPartyId));
        } catch (DataIntegrityViolationException e) {
            // ONLY ux_party_corporate_regno means a duplicate. Reporting every integrity
            // violation as one sends the reader hunting for a corporate party that does
            // not exist -- a value-too-long on the registered name would have been
            // announced as "registration number already in use". Same bug class as M7's
            // onboardAgent and, on this branch, createProduct.
            if (violatesConstraint(e, "ux_party_corporate_regno")) {
                throw new DuplicateRegistrationNumberException(registrationNumber);
            }
            throw e;
        }
        publishRegistered(party);
        return toView(party);
    }

    @Override
    @Transactional
    public PartyView registerGroup(String displayName, String registeredBy) {
        UUID tenantId = TenantContext.get();
        Party party = partyRepository.save(Party.newGroup(tenantId, displayName, registeredBy));
        publishRegistered(party);
        return toView(party);
    }

    @Override
    public PartyView getParty(UUID partyId) {
        return toView(findPartyOrThrow(partyId));
    }

    @Override
    public PartyDetailView getPartyDetail(UUID partyId) {
        Party party = findPartyOrThrow(partyId);
        return new PartyDetailView(party.getPartyId(), party.getPartyType(), party.getKycStatus(),
            party.getDisplayName(), party.getDateOfBirth(), party.getRegistrationNumber(),
            party.getPhoneNumber(), party.getEmail(), party.getKycVerifiedAt(), party.getCreatedAt(),
            party.getCreatedBy(),
            party.getSex(), party.getSmokerStatus(), party.getIdentityDocument(),
            party.getOccupation(), party.getOccupationClass(), party.getEmployerName(),
            party.getNationality(), party.getAddress(), party.getRegisteredByPartyId());
    }

    @Override
    public Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q,
                                            Collection<PartyType> partyTypes, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        // An empty collection is normalised to null -- "no types given" and "no type filter"
        // are the same request, and `IN ()` is not valid SQL.
        Collection<PartyType> types = (partyTypes == null || partyTypes.isEmpty()) ? null : partyTypes;
        // The three no-q branches stay on the original derived-query methods (unchanged
        // shape) for the common (no-text-search, no-type) case -- a present `q` OR a present
        // type filter routes through the null-safe `search` query, same reasoning
        // PolicyApiImpl.searchPolicies/ClaimsApiImpl.searchClaims already use for their
        // own optional extra filter dimension. Keeping the derived branches for the
        // no-type case is what makes this change additive: a caller that passes no types
        // executes exactly the query it executed before.
        Page<Party> page;
        if ((q != null && !q.isBlank()) || types != null) {
            page = partyRepository.search(tenantId, kycStatus, createdBy,
                (q != null && !q.isBlank()) ? q.trim() : null, types, pageable);
        } else if (kycStatus != null && createdBy != null) {
            page = partyRepository.findByTenantIdAndKycStatusAndCreatedBy(tenantId, kycStatus, createdBy, pageable);
        } else if (kycStatus != null) {
            page = partyRepository.findByTenantIdAndKycStatus(tenantId, kycStatus, pageable);
        } else if (createdBy != null) {
            page = partyRepository.findByTenantIdAndCreatedBy(tenantId, createdBy, pageable);
        } else {
            page = partyRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(PartyApiImpl::toView);
    }

    @Override
    @Transactional
    public void submitKycEvidence(UUID partyId, KycStatus status, String evidenceDocumentRef, String verifiedBy) {
        Party party = findPartyOrThrow(partyId);
        KycStatus previousStatus = party.getKycStatus();

        kycRecordRepository.save(new KycRecord(party.getTenantId(), partyId, evidenceDocumentRef, status, verifiedBy));
        party.applyKycDecision(status, verifiedBy);
        partyRepository.save(party);

        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyKycStatusChanged", party.getTenantId(),
            Map.of("partyId", partyId, "previousStatus", previousStatus.name(), "newStatus", status.name(),
                   "evidenceDocumentRef", evidenceDocumentRef)));
    }

    @Override
    @Transactional
    public void addGroupMember(UUID groupPartyId, UUID memberPartyId) {
        Party group = findPartyOrThrow(groupPartyId);
        if (group.getPartyType() != PartyType.GROUP) {
            throw new IllegalArgumentException("Party " + groupPartyId + " is not a GROUP-type party");
        }
        findPartyOrThrow(memberPartyId);
        groupMembershipRepository.save(new GroupMembership(group.getTenantId(), groupPartyId, memberPartyId));
    }

    @Override
    public Page<GroupMembershipView> listGroupMembers(UUID groupPartyId, Pageable pageable) {
        // Tenant-check the group itself before querying memberships -- group_membership rows
        // aren't filtered by tenant_id below otherwise, so a caller under the wrong tenant could
        // list another tenant's group members if they had (or guessed) its groupPartyId.
        findPartyOrThrow(groupPartyId);
        return groupMembershipRepository.findByGroupPartyIdAndStatus(groupPartyId, "ACTIVE", pageable)
            .map(m -> new GroupMembershipView(m.getMemberPartyId(), m.getJoinDate(), m.getStatus()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duplicate check EXCLUDES this party, which is the difference between amending and
     * registering: a correction that leaves the identity document untouched must not be refused
     * for colliding with itself. The partial unique index still guarantees it against a
     * concurrent amendment, and the flush below is what makes that violation surface here rather
     * than at commit — the same reasoning registration records.
     */
    @Override
    @Transactional
    public PartyDetailView amendIndividual(UUID partyId, IndividualRegistration amended, String amendedBy) {
        validatePhone(amended.phoneNumber());
        Party party = findPartyOrThrow(partyId);

        IdentityDocument document = amended.identityDocument();
        if (document.recorded()) {
            partyRepository.findByTenantIdAndIdTypeAndIdNumber(
                    party.getTenantId(), document.type(), document.number())
                .filter(other -> !other.getPartyId().equals(partyId))
                .ifPresent(other -> { throw new DuplicateIdentityDocumentException(document.type()); });
        }

        party.amendIndividualDetails(amended, amendedBy);
        try {
            partyRepository.saveAndFlush(party);
        } catch (DataIntegrityViolationException ex) {
            if (document.recorded()) {
                throw new DuplicateIdentityDocumentException(document.type());
            }
            throw ex;
        }

        publishAmended(party, amendedBy);
        return getPartyDetail(partyId);
    }

    @Override
    @Transactional
    public PartyDetailView amendOrganisation(UUID partyId, String displayName, String phoneNumber,
                                              String email, String amendedBy) {
        validatePhone(phoneNumber);
        Party party = findPartyOrThrow(partyId);
        party.amendCorporateDetails(displayName, phoneNumber, email, amendedBy);
        partyRepository.save(party);
        publishAmended(party, amendedBy);
        return getPartyDetail(partyId);
    }

    /**
     * The audit trail for a correction.
     *
     * <p>Carries who and when rather than a field-by-field diff. A client record holds identity
     * documents, an address and a date of birth; copying those into an event payload would put a
     * second copy of exactly the data the KYC rules exist to protect into the audit log, which is
     * read far more widely than the party table. That the record was amended, by whom, and when
     * is what an auditor needs to go and ask; the answer lives on the record itself.
     */
    private void publishAmended(Party party, String amendedBy) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyDetailsAmended", party.getTenantId(),
            Map.of("partyId", party.getPartyId(), "partyType", party.getPartyType().name(),
                   "amendedBy", amendedBy)));
    }

    /**
     * Whether an integrity violation was caused by the named constraint.
     *
     * <p>Walks the cause chain because the constraint name is on the Postgres-level
     * cause, not on Spring's wrapper.
     */
    private static boolean violatesConstraint(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private void publishRegistered(Party party) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyRegistered", party.getTenantId(),
            Map.of("partyId", party.getPartyId(), "partyType", party.getPartyType().name())));
    }

    private Party findPartyOrThrow(UUID partyId) {
        Party party = partyRepository.findById(partyId).orElseThrow(() -> new PartyNotFoundException(partyId));
        // Fail-loud tenant scoping (plan's Global Constraint): RLS (Task 7) isn't wired up yet, so
        // until then this is the ONLY thing stopping a caller under tenant A's TenantContext from
        // reading or writing tenant B's party via a partyId it doesn't own. A cross-tenant mismatch
        // is reported identically to "doesn't exist" (docs/04-api-contracts.md §2) so callers can't
        // distinguish "not found" from "not yours" and infer another tenant's data exists.
        if (!party.getTenantId().equals(TenantContext.get())) {
            throw new PartyNotFoundException(partyId);
        }
        return party;
    }

    private static void validatePhone(String phoneNumber) {
        if (phoneNumber != null && !TZ_PHONE_PATTERN.matcher(phoneNumber).matches()) {
            throw new IllegalArgumentException("Phone number must match the Tanzanian E.164 pattern +255XXXXXXXXX");
        }
    }

    @Override
    public Set<UUID> partyIdsRegisteredBy(String createdBy) {
        return partyRepository.findPartyIdsByTenantIdAndCreatedBy(TenantContext.get(), createdBy);
    }

    @Override
    public Set<UUID> partyIdsMatchingName(String q) {
        // A blank term is "no search", and it must not become "every party in the tenant"
        // by way of a LIKE '%%' -- the caller reads an empty set as "match nothing", so
        // answering a blank q with every id would invert the meaning of the filter.
        if (q == null || q.isBlank()) {
            return Set.of();
        }
        return partyRepository.findPartyIdsByTenantIdAndDisplayNameLike(TenantContext.get(), q.trim());
    }

    @Override
    public boolean isRegisteredBy(UUID partyId, String createdBy) {
        return partyRepository.existsByPartyIdAndTenantIdAndCreatedBy(partyId, TenantContext.get(), createdBy);
    }

    private static PartyView toView(Party party) {
        return new PartyView(party.getPartyId(), party.getPartyType(), party.getKycStatus(), party.getDisplayName());
    }
}

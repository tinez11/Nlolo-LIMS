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
                ? partyRepository.saveAndFlush(Party.newIndividual(tenantId, registration, registeredBy))
                : partyRepository.save(Party.newIndividual(tenantId, registration, registeredBy));
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
            party = partyRepository.saveAndFlush(Party.newCorporate(tenantId, registeredName, registrationNumber, phoneNumber, email, registeredBy));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateRegistrationNumberException(registrationNumber);
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
            party.getNationality(), party.getAddress());
    }

    @Override
    public Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        // The three no-q branches stay on the original derived-query methods (unchanged
        // shape) for the common (no-text-search) case -- only a present `q` routes
        // through the new three-way `search` query, same reasoning
        // PolicyApiImpl.searchPolicies/ClaimsApiImpl.searchClaims already use for their
        // own optional extra filter dimension.
        Page<Party> page;
        if (q != null && !q.isBlank()) {
            page = partyRepository.search(tenantId, kycStatus, createdBy, q.trim(), pageable);
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
    public boolean isRegisteredBy(UUID partyId, String createdBy) {
        return partyRepository.existsByPartyIdAndTenantIdAndCreatedBy(partyId, TenantContext.get(), createdBy);
    }

    private static PartyView toView(Party party) {
        return new PartyView(party.getPartyId(), party.getPartyType(), party.getKycStatus(), party.getDisplayName());
    }
}

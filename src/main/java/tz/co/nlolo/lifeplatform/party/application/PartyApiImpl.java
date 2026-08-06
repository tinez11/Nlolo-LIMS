package tz.co.nlolo.lifeplatform.party.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
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

    @Override
    @Transactional
    public PartyView registerIndividual(String fullName, LocalDate dateOfBirth, String phoneNumber, String email, String registeredBy) {
        validatePhone(phoneNumber);
        UUID tenantId = TenantContext.get();
        Party party = partyRepository.save(Party.newIndividual(tenantId, fullName, dateOfBirth, phoneNumber, email, registeredBy));
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

    private static PartyView toView(Party party) {
        return new PartyView(party.getPartyId(), party.getPartyType(), party.getKycStatus(), party.getDisplayName());
    }
}

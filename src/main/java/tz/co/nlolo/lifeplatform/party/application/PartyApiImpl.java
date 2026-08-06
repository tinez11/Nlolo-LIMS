package tz.co.nlolo.lifeplatform.party.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.party.domain.GroupMembership;
import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import tz.co.nlolo.lifeplatform.party.infrastructure.GroupMembershipRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.KycRecordRepository;
import tz.co.nlolo.lifeplatform.party.infrastructure.PartyRepository;
import org.springframework.context.ApplicationEventPublisher;
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
        Party party = partyRepository.save(Party.newCorporate(tenantId, registeredName, registrationNumber, phoneNumber, email, registeredBy));
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
        findPartyOrThrow(memberPartyId);
        groupMembershipRepository.save(new GroupMembership(group.getTenantId(), groupPartyId, memberPartyId));
    }

    @Override
    public Page<GroupMembershipView> listGroupMembers(UUID groupPartyId, Pageable pageable) {
        return groupMembershipRepository.findByGroupPartyIdAndStatus(groupPartyId, "ACTIVE", pageable)
            .map(m -> new GroupMembershipView(m.getMemberPartyId(), m.getJoinDate(), m.getStatus()));
    }

    private void publishRegistered(Party party) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("party.PartyRegistered", party.getTenantId(),
            Map.of("partyId", party.getPartyId(), "partyType", party.getPartyType().name())));
    }

    private Party findPartyOrThrow(UUID partyId) {
        return partyRepository.findById(partyId).orElseThrow(() -> new PartyNotFoundException(partyId));
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

package tz.co.nlolo.lifeplatform.party.api;

import java.util.UUID;

public record PartyView(UUID partyId, PartyType partyType, KycStatus kycStatus, String displayName) {}

package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;

public record KycUpdateRequest(KycStatus status, String evidenceDocumentRef) {}

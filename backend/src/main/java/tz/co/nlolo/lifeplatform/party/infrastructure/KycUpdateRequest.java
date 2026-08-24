package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Mirrors api/openapi/openapi-party.yaml's /parties/{partyId}/kyc request body: required [status, evidenceDocumentRef].
public record KycUpdateRequest(
    @NotNull KycStatus status,
    @NotBlank String evidenceDocumentRef) {}

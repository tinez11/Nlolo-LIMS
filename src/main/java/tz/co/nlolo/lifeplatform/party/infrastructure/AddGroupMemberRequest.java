package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Mirrors api/openapi/openapi-party.yaml's group-members POST request body: required [memberPartyId].
public record AddGroupMemberRequest(@NotNull UUID memberPartyId) {}

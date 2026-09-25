package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * @param agentOfRecordId the agent who earns commission from now on; null makes the scheme direct
 * @param reason why -- recorded on the event audit keeps, since this moves where money goes
 */
public record ChangeAgentOfRecordRequestDto(UUID agentOfRecordId, @NotBlank String reason) {}

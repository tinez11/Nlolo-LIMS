package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.SalesChannel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code PUT /agents/{agentId}/placement} (IFRS 17 I2): both required. */
public record AgentPlacementRequestDto(@NotNull SalesChannel salesChannel, @NotBlank String homeBranch) {}

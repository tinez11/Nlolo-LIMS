package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;

public record SuspendPolicyRequestDto(@NotBlank String reason) {}

package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;

/** Body of a surrender request: where the payout goes. */
public record SurrenderRequestDto(@NotBlank String payeeRef) {}

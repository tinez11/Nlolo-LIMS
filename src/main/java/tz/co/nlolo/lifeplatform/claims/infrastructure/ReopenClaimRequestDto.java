package tz.co.nlolo.lifeplatform.claims.infrastructure;

import jakarta.validation.constraints.NotBlank;

public record ReopenClaimRequestDto(@NotBlank String reason) {}

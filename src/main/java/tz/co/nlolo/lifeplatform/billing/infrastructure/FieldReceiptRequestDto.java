package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record FieldReceiptRequestDto(
    @NotBlank String policyNumber,
    @NotNull @Valid MoneyDto amount,
    @NotBlank String clientIdempotencyKey,
    @NotNull Instant capturedAt) {}

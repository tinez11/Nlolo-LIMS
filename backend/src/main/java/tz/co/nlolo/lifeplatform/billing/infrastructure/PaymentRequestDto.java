package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.constraints.NotBlank;

public record PaymentRequestDto(@NotBlank String payerRef) {}

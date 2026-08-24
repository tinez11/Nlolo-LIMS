package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RepaymentRequestDto(@NotNull @Valid MoneyDto amount, @NotBlank String paymentReference) {}

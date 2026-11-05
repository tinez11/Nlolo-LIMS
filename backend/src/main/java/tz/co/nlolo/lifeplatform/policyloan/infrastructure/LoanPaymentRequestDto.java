package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Ask the payer's phone for a loan repayment (money movement step 1, 2026-11-04): the number, and how much. */
public record LoanPaymentRequestDto(@NotBlank String payerRef, @NotNull @Valid MoneyDto amount) {}

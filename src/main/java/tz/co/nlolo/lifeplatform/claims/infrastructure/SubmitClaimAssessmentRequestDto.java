package tz.co.nlolo.lifeplatform.claims.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitClaimAssessmentRequestDto(
    @NotBlank String findings,
    @NotNull @Valid MoneyDto recommendedAmount,
    boolean fraudIndicator) {}

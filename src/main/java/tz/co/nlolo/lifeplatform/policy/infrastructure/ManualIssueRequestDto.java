package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ManualIssueRequestDto(
    @NotNull UUID underwritingCaseId,
    @NotNull UUID policyholderPartyId,
    @NotNull UUID productVersionId,
    @NotNull @Valid MoneyDto sumAssured,
    @NotNull UUID agentOfRecordId,
    String premiumFrequency,
    List<@Valid BeneficiaryInputDto> beneficiaries,
    @NotBlank String reasonForManualIssue) {}

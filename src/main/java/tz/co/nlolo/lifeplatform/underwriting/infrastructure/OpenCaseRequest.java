package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

// Mirrors api/openapi/openapi-underwriting.yaml's OpenCaseRequest: required
// [applicantPartyId, productId, productVersionId, sumAssured]. sumAssured (a Money
// object in the spec) is flattened into sumAssuredAmount/sumAssuredCurrency here.
public record OpenCaseRequest(
    @NotNull UUID applicantPartyId,
    @NotNull UUID productId,
    @NotNull UUID productVersionId,
    @NotNull BigDecimal sumAssuredAmount,
    @NotBlank String sumAssuredCurrency) {}

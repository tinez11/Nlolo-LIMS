package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

// Mirrors api/openapi/openapi-underwriting.yaml's OpenCaseRequest: required
// [applicantPartyId, productId, productVersionId, sumAssured]. sumAssured is a nested
// Money-shaped object matching openapi-common.yaml's shared Money schema exactly --
// a decimal STRING amount (never a bare JSON number) plus a currencyCode, to avoid the
// binary-float precision loss risk Money's own schema description exists to rule out at
// any double-parsing client boundary. (An earlier task flattened this to bare
// sumAssuredAmount/sumAssuredCurrency fields to match the code as it existed then; the
// final review found that divergence from the platform's own Money convention was real
// technical debt with zero external consumers today to break, so it's reversed here --
// the code now matches the spec's Money shape, not the other way round.) Mapped to the
// UnderwritingApi.openCase's BigDecimal sumAssuredAmount + String sumAssuredCurrency
// parameters at the controller boundary; UnderwritingApi's own signature and the DB
// columns are unchanged -- only this wire-format DTO changed.
public record OpenCaseRequest(
    @NotNull UUID applicantPartyId,
    @NotNull UUID productId,
    @NotNull UUID productVersionId,
    @NotNull @Valid Money sumAssured,
    /**
     * Who sold it, carried through to automatic issuance so commission can accrue on the
     * normal path. Optional, because a self-service application is a genuine direct sale --
     * and because it was hardcoded absent until now, so every existing caller omits it.
     */
    UUID agentOfRecordId,

    /**
     * Whose life is insured, when that is not the applicant.
     *
     * <p>Optional, and omitting it means self-insured — the service resolves it to the
     * applicant rather than storing a null, so a newly opened case always answers the
     * question. Most life business is not self-insured, and both group business and
     * credit life are structurally impossible to express without this.
     */
    UUID lifeAssuredPartyId,

    @Size(max = 100) String branch,

    /** Free text on purpose: the platform does not own this vocabulary yet. */
    @Size(max = 60) String sourceOfBusiness,

    LocalDate proposedCommencementDate) {

    // Field names/constraints mirror openapi-common.yaml#/components/schemas/Money exactly.
    public record Money(
        @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
}

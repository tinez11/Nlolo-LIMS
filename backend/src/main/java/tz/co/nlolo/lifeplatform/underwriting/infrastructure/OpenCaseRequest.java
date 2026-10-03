package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.underwriting.api.BeneficiaryNomination;
import tz.co.nlolo.lifeplatform.underwriting.api.NominationType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    LocalDate proposedCommencementDate,

    /**
     * What the applicant asks for about the CONTRACT, as distinct from the risk above.
     *
     * <p>All optional. A product that does not term genuinely has none; a premium-paying term
     * shorter than the cover term is a limited-payment policy and equal-or-absent is ordinary;
     * a proposal arriving with the nomination blank is routine.
     *
     * <p>{@code requestedTermMonths} is checked against the product version's eligibility
     * bounds in the service, not here — Bean Validation cannot see the product.
     */
    @Positive Integer requestedTermMonths,
    @Positive Integer premiumPayingTermMonths,

    /**
     * Not an enum on this record, unlike {@code AssessmentType} above, because the value is
     * carried to {@code policy.policy.premium_frequency} as a string and its authority is that
     * column's CHECK. Minting a second enum here would create two places to add a frequency.
     *
     * <p>And this regex is the third, which is the lesson: the comment above says the CHECK is
     * the authority, yet SINGLE was admitted by that CHECK, by the enum, by the OpenAPI schema
     * and by the console, and still failed here with a bare "Request validation failed" and no
     * field named. Any frequency added to {@code policy_premium_frequency_check} must be added
     * to this pattern in the same change.
     */
    @Pattern(regexp = "MONTHLY|QUARTERLY|ANNUALLY|SINGLE") String premiumFrequency,

    List<@Valid BeneficiaryNominationDto> beneficiaries,

    /**
     * On an ANNUITY product only (product step 5): the chosen form, frequency and joint life,
     * recorded with the case. Optional here -- it may also be recorded afterwards, and the decision
     * refuses an annuity case that has none.
     */
    @Valid AnnuityChoiceDto annuityChoice) {

    /** What an annuity applicant chose. Unannotated: the service refuses in the console's words. */
    public record AnnuityChoiceDto(String formCode, String frequency, UUID jointLifePartyId) {
        public tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice toApi() {
            return tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice.of(formCode, frequency, jointLifePartyId);
        }
    }

    // Field names/constraints mirror openapi-common.yaml#/components/schemas/Money exactly.
    public record Money(
        @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}

    /**
     * One nomination on the wire.
     *
     * <p>{@code partyId} and {@code freeformDesignee} carry no annotations: exactly one must be
     * set and which one depends on {@code type}, a pairing Bean Validation cannot express on a
     * single field. The service enforces it, mirroring
     * {@code chk_proposal_beneficiary_exactly_one_designation}.
     */
    public record BeneficiaryNominationDto(
        @NotNull NominationType type,
        UUID partyId,
        @Size(max = 255) String freeformDesignee,
        @NotNull BigDecimal sharePercent,
        Boolean revocable) {

        /** Absent means revocable, which is the ordinary nomination and the column's default. */
        public boolean resolveRevocable() {
            return revocable == null || revocable;
        }

        public BeneficiaryNomination toApiNomination() {
            return new BeneficiaryNomination(type, partyId, freeformDesignee, sharePercent, resolveRevocable());
        }
    }
}

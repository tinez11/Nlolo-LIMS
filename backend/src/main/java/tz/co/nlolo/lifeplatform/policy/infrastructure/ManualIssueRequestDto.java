package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManualIssueRequestDto(
    @NotNull UUID underwritingCaseId,
    @NotNull UUID policyholderPartyId,
    @NotNull UUID productVersionId,
    @NotNull @Valid MoneyDto sumAssured,
    @NotNull @Valid MoneyDto premiumAmount,
    // openapi-policy.yaml's ManualIssueRequest marks agentOfRecordId both `nullable: true` and
    // present in `required` -- that combination means the JSON key must be present but its value
    // may be null (e.g. a direct/online issuance channel with no agent), not "must be non-null."
    // A bare UUID field can't express "must be present, may be null" any more precisely at the
    // Bean Validation layer, so no @NotNull here; a null value is spec-valid and must reach
    // PolicyApi.IssueRequest unchanged.
    UUID agentOfRecordId,
    String premiumFrequency,
    List<@Valid BeneficiaryInputDto> beneficiaries,
    @NotBlank String reasonForManualIssue,

    // The policy term (V6). All optional: a product that does not term -- whole life, an
    // annuity, an annually renewable group scheme -- genuinely has none, and a policy
    // issued before the migration has none either.
    //
    // There is deliberately no maturityDate. The aggregate derives it in
    // Policy.applyTerm, so it cannot be supplied wrong from outside; a caller-supplied
    // value would be rejected by policy_maturity_matches_term for reasons the caller
    // could not see.
    LocalDate commencementDate,
    @Positive Integer policyTermMonths,
    @Positive Integer premiumPayingTermMonths,

    /**
     * Whose life is insured, when that is not the policyholder. Optional: omitting it
     * means self-insured, and the aggregate resolves it rather than storing a null.
     */
    UUID lifeAssuredPartyId,

    /**
     * Why this is being issued by hand, and — through
     * {@link tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis#startsCoverImmediately()} —
     * whether cover starts now or waits for the first premium like any other new business.
     *
     * <p>Required, which is the point. Manual issue is the exception path, and an exception path
     * with no recorded reason is how a bypass becomes the normal route: this endpoint was already
     * the one that could duplicate a policy and skip the underwriter, and now it is also the only
     * one that can put a contract on risk before anybody has paid for it. An auditor asking "on
     * what basis did cover start before the premium?" gets a field to group by rather than a
     * paragraph of free text to read.
     *
     * <p>{@code reasonForManualIssue} stays alongside it and stays required too: the enum is what
     * a report groups by, the sentence is what a person reads.
     */
    @NotNull tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis issuanceBasis) {}

package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;

import java.math.BigDecimal;

/**
 * Mirrors api/openapi/openapi-underwriting.yaml's DecideRequest: required [outcome, reason].
 *
 * <p>{@code loadingPercent} carries no annotation deliberately. It is required for
 * {@code LOADED} and forbidden for every other outcome -- a pairing Bean Validation cannot
 * express on a single field -- so {@code UnderwritingApiImpl.decide} enforces it, mirroring the
 * {@code chk_loading_only_when_loaded} CHECK. Annotating it {@code @NotNull} here would refuse
 * the three outcomes that must not carry one.
 */
public record DecideRequest(
    @NotNull DecisionOutcome outcome,
    BigDecimal loadingPercent,
    @NotBlank String reason,
    // An annuity's light path (product step 5): proof of age was seen. Ignored on every other case.
    Boolean ageEvidenceConfirmed) {}

package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/**
 * {@code rate} arrives as a decimal STRING, matching how it is rendered -- see
 * {@link CommissionRuleDto}'s javadoc on why a commission rate must never be a JSON float.
 *
 * <p>The rate-XOR-flat invariant is deliberately NOT expressed as a bean-validation constraint
 * here. {@code DistributionApiImpl.createCommissionPlan} already enforces it (and must, since it
 * is a published API reachable by a non-HTTP caller), and duplicating it as a 400 would give the
 * same malformed request two different status codes depending on which layer noticed first. A
 * violation therefore surfaces as this module's documented 422
 * {@code DISTRIBUTION_VALIDATION_FAILED}, not a 400.
 */
public record CreateCommissionPlanRequestDto(
    @NotNull UUID productId,
    @NotEmpty @Valid List<RuleInput> rules) {

    public record RuleInput(
        @NotNull TierType tierType,
        @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$") String rate,
        @Valid MoneyDto flatAmount) {}
}

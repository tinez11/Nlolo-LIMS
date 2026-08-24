package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Commission-plan authoring. Staff/finance only -- agents never author a plan, they only read the
 * one that applies to them ({@code AgentController.getApplicablePlan}). Same FINANCE_OFFICER/ADMIN
 * decision recorded in {@link AgentController}'s javadoc, for the same reason.
 *
 * <p>Separate from {@link AgentController} because a plan is its own aggregate root (Di1 promoted
 * it) hanging off a PRODUCT, not off an agent -- {@code POST /commission-plans} has no agentId in
 * its path and no agent in its body.
 */
@RestController
public class CommissionPlanController {

    private final DistributionApi distributionApi;

    public CommissionPlanController(DistributionApi distributionApi) {
        this.distributionApi = distributionApi;
    }

    @PostMapping("/commission-plans")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<CommissionPlanResponseDto> createCommissionPlan(
            @Valid @RequestBody CreateCommissionPlanRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        List<DistributionApi.CommissionRuleInput> rules = request.rules().stream()
            .map(CommissionPlanController::toRuleInput)
            .toList();
        CommissionPlanView view = distributionApi.createCommissionPlan(request.productId(), rules, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(CommissionPlanResponseDto.from(view));
    }

    /** Both money-ish values arrive as decimal strings and are parsed with {@code new BigDecimal(String)},
     * never via {@code double} -- the whole reason the wire format is a string (docs/06:31). A rule
     * carrying neither, or both, is rejected downstream by {@code DistributionApiImpl} as a 422; see
     * {@link CreateCommissionPlanRequestDto}'s javadoc on why that is not duplicated here as a 400. */
    private static DistributionApi.CommissionRuleInput toRuleInput(CreateCommissionPlanRequestDto.RuleInput input) {
        return new DistributionApi.CommissionRuleInput(
            input.tierType(),
            input.rate() == null ? null : new BigDecimal(input.rate()),
            input.flatAmount() == null ? null : new BigDecimal(input.flatAmount().amount()),
            input.flatAmount() == null ? null : input.flatAmount().currencyCode());
    }
}

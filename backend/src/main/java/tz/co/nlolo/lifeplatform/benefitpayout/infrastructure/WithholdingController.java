package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.benefitpayout.application.WithholdingRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tax-withholding rules (product step 5). Finance's: the same roles that approve payouts. Proposing
 * needs an Idempotency-Key -- a 400 without one, as every keyed create since step 3 -- and approving
 * needs a second person, which the rule itself enforces.
 */
@RestController
public class WithholdingController {

    private final WithholdingRules rules;

    public WithholdingController(WithholdingRules rules) {
        this.rules = rules;
    }

    public record ProposeRequest(List<String> payoutKinds, BigDecimal ratePercent, LocalDate effectiveFrom,
                                 LocalDate effectiveTo, String legalReference) {}

    @GetMapping("/withholding-rules")
    @PreAuthorize(BenefitPayoutController.FINANCE)
    public List<WithholdingRuleResponse> list() {
        return rules.list().stream().map(WithholdingRuleResponse::from).toList();
    }

    @PostMapping("/withholding-rules")
    @PreAuthorize(BenefitPayoutController.FINANCE)
    @ResponseStatus(HttpStatus.CREATED)
    public WithholdingRuleResponse propose(@RequestBody ProposeRequest request,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @AuthenticationPrincipal Jwt jwt) {
        return WithholdingRuleResponse.from(rules.propose(request.payoutKinds(), request.ratePercent(), request.effectiveFrom(),
            request.effectiveTo(), request.legalReference(), jwt.getSubject(), idempotencyKey));
    }

    @PostMapping("/withholding-rules/{ruleId}/approve")
    @PreAuthorize(BenefitPayoutController.FINANCE)
    public WithholdingRuleResponse approve(@PathVariable UUID ruleId, @AuthenticationPrincipal Jwt jwt) {
        return WithholdingRuleResponse.from(rules.approve(ruleId, jwt.getSubject()));
    }

    public record EndRequest(LocalDate effectiveTo) {}

    /** Ends an approved rule on its last day. One finance officer may do it alone (the user's decision). */
    @PostMapping("/withholding-rules/{ruleId}/end")
    @PreAuthorize(BenefitPayoutController.FINANCE)
    public WithholdingRuleResponse end(@PathVariable UUID ruleId, @RequestBody EndRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return WithholdingRuleResponse.from(rules.end(ruleId, request.effectiveTo(), jwt.getSubject()));
    }

    @PostMapping("/withholding-rules/{ruleId}/withdraw")
    @PreAuthorize(BenefitPayoutController.FINANCE)
    public WithholdingRuleResponse withdraw(@PathVariable UUID ruleId, @AuthenticationPrincipal Jwt jwt) {
        return WithholdingRuleResponse.from(rules.withdraw(ruleId, jwt.getSubject()));
    }
}

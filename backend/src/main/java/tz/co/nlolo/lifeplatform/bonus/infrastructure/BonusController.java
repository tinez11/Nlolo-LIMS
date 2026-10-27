package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import tz.co.nlolo.lifeplatform.bonus.api.BonusApi;
import tz.co.nlolo.lifeplatform.bonus.api.PolicyBonusView;

import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class BonusController {


    private final BonusApi api;

    public BonusController(BonusApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize("@access.can('products.view')")
    public List<DeclarationResponse> listDeclarations(@PathVariable UUID productId) {
        return api.listDeclarations(productId).stream().map(DeclarationResponse::from).toList();
    }

    /** Once per Idempotency-Key (plan §12): the console sends one, and a retry must not propose twice. */
    @PostMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize("@access.can('products.declare')")
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationResponse propose(@PathVariable UUID productId, @Valid @RequestBody DeclarationRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.proposeDeclaration(productId, request.valuationDate(),
            request.reversionaryRatePercent(), request.terminalRatePercent(), jwt.getSubject(), idempotencyKey));
    }

    @PostMapping("/bonus-declarations/{declarationId}/approve")
    @PreAuthorize("@access.can('products.declare-approve')")
    public DeclarationResponse approve(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.approveDeclaration(declarationId, jwt.getSubject()));
    }

    @PostMapping("/bonus-declarations/{declarationId}/withdraw")
    @PreAuthorize("@access.can('products.declare')")
    public DeclarationResponse withdraw(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.withdrawDeclaration(declarationId, jwt.getSubject()));
    }

    // ---- A policy's bonuses (task 7) -------------------------------------------------------------

    /** Any staff member may read, as with an account: it is the policyholder's own record. */
    @GetMapping("/policies/{policyNumber}/bonuses")
    @PreAuthorize("@access.can('accounts.view')")
    public PolicyBonusResponse policyBonuses(@PathVariable String policyNumber) {
        return PolicyBonusResponse.from(api.policyBonuses(policyNumber)
            .orElseThrow(() -> new NotWithProfitsException(policyNumber)));
    }

    /** What a death or maturity on {@code asOf} would add. Reads only; nothing is recorded. */
    @GetMapping("/policies/{policyNumber}/bonuses/value")
    @PreAuthorize("@access.can('accounts.view')")
    public BonusValuationResponse value(@PathVariable String policyNumber,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        String currency = api.policyBonuses(policyNumber).map(PolicyBonusView::currency)
            .orElseThrow(() -> new NotWithProfitsException(policyNumber));
        return BonusValuationResponse.from(api.valueAt(policyNumber, asOf != null ? asOf : LocalDate.now(Eligibility.CIVIL_ZONE)), currency);
    }
}

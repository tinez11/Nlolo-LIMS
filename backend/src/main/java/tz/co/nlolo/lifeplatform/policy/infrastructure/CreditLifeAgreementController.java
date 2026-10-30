package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi.AgreementInput;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi.AgreementView;

import java.util.List;

/**
 * A credit-life scheme's yearly agreements with its lender (2026-10-30). Reading them is part of reading the scheme;
 * adding one decides what every borrower joining that year is charged and what the lender earns, so it takes
 * schemes.manage, as setting a scheme's terms does.
 */
@RestController
public class CreditLifeAgreementController {

    private final CreditLifeAgreementApi agreements;

    public CreditLifeAgreementController(CreditLifeAgreementApi agreements) {
        this.agreements = agreements;
    }

    @GetMapping("/group-schemes/{policyNumber}/agreements")
    @PreAuthorize("@access.can('schemes.view')")
    public ResponseEntity<List<AgreementView>> list(@PathVariable String policyNumber) {
        return ResponseEntity.ok(agreements.agreements(policyNumber));
    }

    @PostMapping("/group-schemes/{policyNumber}/agreements")
    @PreAuthorize("@access.can('schemes.manage')")
    public ResponseEntity<AgreementView> add(@PathVariable String policyNumber, @RequestBody AgreementInput input,
                                             @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agreements.add(policyNumber, input, jwt.getSubject()));
    }
}

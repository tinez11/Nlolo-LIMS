package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;

import java.util.List;
import java.util.UUID;

@RestController
public class AccumulationController {

    /** Setting a price is ADMIN's, as publishing a product version is. */
    static final String PRICING = "hasRole('REALM_STAFF') and hasRole('ADMIN')";
    /** The second signature on money: another ADMIN, or finance. */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final AccumulationApi api;

    public AccumulationController(AccumulationApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(FINANCE)
    public List<RateDeclarationResponse> listRates(@PathVariable UUID productId) {
        return api.listRates(productId).stream().map(RateDeclarationResponse::from).toList();
    }

    @PostMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(PRICING)
    @ResponseStatus(HttpStatus.CREATED)
    public RateDeclarationResponse proposeRate(@PathVariable UUID productId, @Valid @RequestBody RateDeclarationRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.proposeRate(productId, request.ratePercent(), request.effectiveFrom(),
            jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/approve")
    @PreAuthorize(FINANCE)
    public RateDeclarationResponse approveRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.approveRate(declarationId, jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/withdraw")
    @PreAuthorize(PRICING)
    public RateDeclarationResponse withdrawRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.withdrawRate(declarationId, jwt.getSubject()));
    }
}

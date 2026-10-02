package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.bonus.api.BonusApi;

import java.util.List;
import java.util.UUID;

@RestController
public class BonusController {

    /** Declaring a bonus is a price: ADMIN's, as publishing a version is. */
    static final String PRICING = "hasRole('REALM_STAFF') and hasRole('ADMIN')";
    /** The second signature: another ADMIN, or finance. */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final BonusApi api;

    public BonusController(BonusApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize(FINANCE)
    public List<DeclarationResponse> listDeclarations(@PathVariable UUID productId) {
        return api.listDeclarations(productId).stream().map(DeclarationResponse::from).toList();
    }

    /** Once per Idempotency-Key (plan §12): the console sends one, and a retry must not propose twice. */
    @PostMapping("/products/{productId}/bonus-declarations")
    @PreAuthorize(PRICING)
    @ResponseStatus(HttpStatus.CREATED)
    public DeclarationResponse propose(@PathVariable UUID productId, @Valid @RequestBody DeclarationRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.proposeDeclaration(productId, request.valuationDate(),
            request.reversionaryRatePercent(), request.terminalRatePercent(), jwt.getSubject(), idempotencyKey));
    }

    @PostMapping("/bonus-declarations/{declarationId}/approve")
    @PreAuthorize(FINANCE)
    public DeclarationResponse approve(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.approveDeclaration(declarationId, jwt.getSubject()));
    }

    @PostMapping("/bonus-declarations/{declarationId}/withdraw")
    @PreAuthorize(PRICING)
    public DeclarationResponse withdraw(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return DeclarationResponse.from(api.withdrawDeclaration(declarationId, jwt.getSubject()));
    }
}

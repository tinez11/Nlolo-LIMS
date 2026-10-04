package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immediate annuities over HTTP (product step 5): a live quote for the case form -- agents open cases
 * too -- and a policy's contract for its Annuity tab.
 */
@RestController
public class AnnuityController {

    private final AnnuityApi api;
    private final PartyApi partyApi;

    public AnnuityController(AnnuityApi api, PartyApi partyApi) {
        this.api = api;
        this.partyApi = partyApi;
    }

    public record QuoteRequest(UUID productVersionId, String formCode, String frequency, BigDecimal purchasePrice,
                               UUID annuitantPartyId, UUID jointLifePartyId) {}

    /** Prices without storing anything. Refusals are 422 ANNUITY_NOT_PRICEABLE, in the pricer's words. */
    @PostMapping("/annuity-quotes")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public AnnuityQuoteResponse quote(@RequestBody QuoteRequest r, @AuthenticationPrincipal Jwt jwt,
                                      Authentication authentication) {
        if (r.productVersionId() == null || r.annuitantPartyId() == null) {
            throw new tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException(
                "A quote needs the product version and the annuitant");
        }
        // A quote answers with the annuitant's age and rated sex, so an agent may quote only the
        // lives of clients they registered -- the scope GET /parties and the case list apply.
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        if (isAgent) {
            Set<UUID> own = partyApi.partyIdsRegisteredBy(jwt.getSubject());
            if (!own.contains(r.annuitantPartyId())
                    || (r.jointLifePartyId() != null && !own.contains(r.jointLifePartyId()))) {
                throw new AccessDeniedException("An agent may quote only the clients they registered");
            }
        }
        AnnuityPrice price = api.quote(r.productVersionId(), r.formCode(), r.frequency(), r.purchasePrice(),
            r.annuitantPartyId(), r.jointLifePartyId());
        return AnnuityQuoteResponse.from(price, "TZS");
    }

    /** Any staff member may read, as with an account or a bonus history. 404 NOT_AN_ANNUITY otherwise. */
    @GetMapping("/policies/{policyNumber}/annuity")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public AnnuityContractResponse contract(@PathVariable String policyNumber) {
        AnnuityContractView view = api.contract(policyNumber).orElseThrow(() -> new NotAnAnnuityPolicyException(policyNumber));
        return AnnuityContractResponse.from(view);
    }

    static Map<String, String> money(BigDecimal amount, String currency) {
        return amount == null ? null : Map.of("amount", amount.toPlainString(), "currencyCode", currency);
    }

    static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}

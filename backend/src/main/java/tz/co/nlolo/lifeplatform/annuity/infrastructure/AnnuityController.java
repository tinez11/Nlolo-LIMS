package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Immediate annuities over HTTP (product step 5): a live quote for the case form -- agents open cases
 * too -- and a policy's contract for its Annuity tab.
 */
@RestController
public class AnnuityController {

    private final AnnuityApi api;

    public AnnuityController(AnnuityApi api) {
        this.api = api;
    }

    public record QuoteRequest(UUID productVersionId, String formCode, String frequency, BigDecimal purchasePrice,
                               UUID annuitantPartyId, UUID jointLifePartyId) {}

    /** Prices without storing anything. Refusals are 422 ANNUITY_NOT_PRICEABLE, in the pricer's words. */
    @PostMapping("/annuity-quotes")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public AnnuityQuoteResponse quote(@RequestBody QuoteRequest r) {
        if (r.productVersionId() == null || r.annuitantPartyId() == null) {
            throw new tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException(
                "A quote needs the product version and the annuitant");
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

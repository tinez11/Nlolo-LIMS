package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The account charges staff keep and choose from on a savings case or policy (2026-10-09, product V32). Everyone on staff
 * reads them -- the case and issue forms need the list; finance officers and admins keep them and the organisation's rule.
 */
@RestController
public class AccountChargeController {

    /**
     * Body of a new charge. {@code when}: DEPOSIT, PREMIUM, TRANSFER_IN, WITHDRAWAL, MONTHLY, YEARLY, OPENING or MATURITY.
     * No {@code fromPolicyYear} is year 1; no {@code toPolicyYear} is every year after.
     */
    public record CreateCharge(String name, String description, String when, String amountType, BigDecimal amount,
                               String currency, Integer fromPolicyYear, Integer toPolicyYear) {}

    /** The organisation's rule on charges and the minimum balance. */
    public record ChargeSetting(boolean mayGoBelowMinimum) {}

    private final AccountChargeApi accountCharges;
    private final ProductApi productApi;

    public AccountChargeController(AccountChargeApi accountCharges, ProductApi productApi) {
        this.accountCharges = accountCharges;
        this.productApi = productApi;
    }

    /**
     * The Account charges a version takes (V34), in the order chosen: what its policies are charged by unless one is
     * issued on a choice of its own. Empty for a version charged by rows of its own, or by nothing.
     */
    @GetMapping("/products/{productId}/versions/{versionId}/account-charges")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('products.view')")
    public ResponseEntity<List<AccountChargeView>> versionCharges(@PathVariable UUID productId,
                                                                  @PathVariable UUID versionId) {
        List<UUID> ids = productApi.resolveAccumulationPlan(versionId).accountChargeIds();
        List<AccountChargeView> found = new java.util.ArrayList<>(accountCharges.resolve(ids));
        found.sort(java.util.Comparator.comparingInt(c -> ids.indexOf(c.chargeId())));
        return ResponseEntity.ok(found);
    }

    @GetMapping("/account-charges")
    @PreAuthorize("@access.can('products.view')")
    public ResponseEntity<List<AccountChargeView>> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(accountCharges.list(activeOnly));
    }

    @PostMapping("/account-charges")
    @PreAuthorize("@access.can('products.charges')")
    public ResponseEntity<AccountChargeView> create(@RequestBody CreateCharge body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountCharges.create(body.name(), body.description(),
            body.when(), body.amountType(), body.amount(), body.currency(),
            body.fromPolicyYear() == null ? 1 : body.fromPolicyYear(), body.toPolicyYear(), jwt.getSubject()));
    }

    /** No longer offered on new cases and policies; the policies already on it keep it. */
    @PostMapping("/account-charges/{chargeId}/withdrawal")
    @PreAuthorize("@access.can('products.charges')")
    public ResponseEntity<AccountChargeView> withdraw(@PathVariable UUID chargeId) {
        return ResponseEntity.ok(accountCharges.setActive(chargeId, false));
    }

    @PostMapping("/account-charges/{chargeId}/reinstatement")
    @PreAuthorize("@access.can('products.charges')")
    public ResponseEntity<AccountChargeView> reinstate(@PathVariable UUID chargeId) {
        return ResponseEntity.ok(accountCharges.setActive(chargeId, true));
    }

    @GetMapping("/account-charges/setting")
    @PreAuthorize("@access.can('products.view')")
    public ResponseEntity<ChargeSetting> setting() {
        return ResponseEntity.ok(new ChargeSetting(accountCharges.mayGoBelowMinimum()));
    }

    @PutMapping("/account-charges/setting")
    @PreAuthorize("@access.can('products.charges')")
    public ResponseEntity<ChargeSetting> changeSetting(@RequestBody ChargeSetting body, @AuthenticationPrincipal Jwt jwt) {
        accountCharges.setMayGoBelowMinimum(body.mayGoBelowMinimum(), jwt.getSubject());
        return ResponseEntity.ok(new ChargeSetting(accountCharges.mayGoBelowMinimum()));
    }
}

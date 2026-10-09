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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The account charges staff keep and choose from on a savings case or policy (2026-10-09, product V32). Everyone on staff
 * reads them -- the case and issue forms need the list; finance officers and admins keep them and the organisation's rule.
 */
@RestController
public class AccountChargeController {

    /** Body of a new charge. {@code when}: DEPOSIT, WITHDRAWAL, MONTHLY, YEARLY, OPENING or MATURITY. */
    public record CreateCharge(String name, String description, String when, String amountType, BigDecimal amount,
                               String currency) {}

    /** The organisation's rule on charges and the minimum balance. */
    public record ChargeSetting(boolean mayGoBelowMinimum) {}

    private final AccountChargeApi accountCharges;

    public AccountChargeController(AccountChargeApi accountCharges) {
        this.accountCharges = accountCharges;
    }

    @GetMapping("/account-charges")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<List<AccountChargeView>> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(accountCharges.list(activeOnly));
    }

    @PostMapping("/account-charges")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AccountChargeView> create(@RequestBody CreateCharge body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountCharges.create(body.name(), body.description(),
            body.when(), body.amountType(), body.amount(), body.currency(), jwt.getSubject()));
    }

    /** No longer offered on new cases and policies; the policies already on it keep it. */
    @PostMapping("/account-charges/{chargeId}/withdrawal")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AccountChargeView> withdraw(@PathVariable UUID chargeId) {
        return ResponseEntity.ok(accountCharges.setActive(chargeId, false));
    }

    @PostMapping("/account-charges/{chargeId}/reinstatement")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AccountChargeView> reinstate(@PathVariable UUID chargeId) {
        return ResponseEntity.ok(accountCharges.setActive(chargeId, true));
    }

    @GetMapping("/account-charges/setting")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<ChargeSetting> setting() {
        return ResponseEntity.ok(new ChargeSetting(accountCharges.mayGoBelowMinimum()));
    }

    @PutMapping("/account-charges/setting")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChargeSetting> changeSetting(@RequestBody ChargeSetting body, @AuthenticationPrincipal Jwt jwt) {
        accountCharges.setMayGoBelowMinimum(body.mayGoBelowMinimum(), jwt.getSubject());
        return ResponseEntity.ok(new ChargeSetting(accountCharges.mayGoBelowMinimum()));
    }
}

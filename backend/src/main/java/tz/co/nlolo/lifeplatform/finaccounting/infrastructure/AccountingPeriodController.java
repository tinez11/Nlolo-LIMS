package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountingPeriodView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;

import java.util.List;

/**
 * Accounting periods (IFRS 17 spec §5.4): finance or an admin starts closing a period, locks it, and asks for a
 * locked one to be reopened -- which a second person approves.
 */
@RestController
public class AccountingPeriodController {


    /** Why a locked period is reopened. */
    public record ReopenRequest(String reason) {}

    private final FinaccountingApi api;

    public AccountingPeriodController(FinaccountingApi api) {
        this.api = api;
    }

    @GetMapping("/finance/periods")
    @PreAuthorize("@access.can('ledger.view')")
    public List<AccountingPeriodView> periods() {
        return api.periods();
    }

    @GetMapping("/finance/periods/{period}")
    @PreAuthorize("@access.can('ledger.view')")
    public AccountingPeriodView period(@PathVariable String period) {
        return api.period(period);
    }

    @PostMapping("/finance/periods/{period}/closing")
    @PreAuthorize("@access.can('ledger.manage')")
    public AccountingPeriodView startClosing(@PathVariable String period, @AuthenticationPrincipal Jwt jwt) {
        return api.startClosing(period, jwt.getSubject());
    }

    @PostMapping("/finance/periods/{period}/lock")
    @PreAuthorize("@access.can('ledger.manage')")
    public AccountingPeriodView lock(@PathVariable String period, @AuthenticationPrincipal Jwt jwt) {
        return api.lockPeriod(period, jwt.getSubject());
    }

    @PostMapping("/finance/periods/{period}/reopen-request")
    @PreAuthorize("@access.can('ledger.manage')")
    public AccountingPeriodView requestReopen(@PathVariable String period, @RequestBody ReopenRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return api.requestReopen(period, body == null ? null : body.reason(), jwt.getSubject());
    }

    @PostMapping("/finance/periods/{period}/reopen-approval")
    @PreAuthorize("@access.can('ledger.manage')")
    public AccountingPeriodView approveReopen(@PathVariable String period, @AuthenticationPrincipal Jwt jwt) {
        return api.approveReopen(period, jwt.getSubject());
    }
}

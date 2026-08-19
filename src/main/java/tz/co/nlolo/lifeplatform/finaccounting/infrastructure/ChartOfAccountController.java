package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The {@code /chart-of-accounts} surface -- read-only account listing.
 *
 * <p><b>There is no write endpoint here, and there never will be one on this surface.</b> Every
 * posting is derived from a domain event (Task 6's listeners); nothing hand-enters a journal
 * entry against a hand-entered account. Chart-of-account rows are seeded by
 * {@code ChartOfAccountSeeder}, not authored through this API -- that absence is deliberate,
 * matching {@code GlPostingController}'s own javadoc for the same reasoning.
 *
 * <p><b>FINANCE_OFFICER/ADMIN gate this endpoint, which is a decision, not a spec quote,</b> for
 * the same reason recorded in {@code GlPostingController}'s javadoc.
 */
@RestController
public class ChartOfAccountController {

    private final FinaccountingApi finaccountingApi;

    public ChartOfAccountController(FinaccountingApi finaccountingApi) {
        this.finaccountingApi = finaccountingApi;
    }

    @GetMapping("/chart-of-accounts")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<ChartOfAccountResponseDto>> listChartOfAccounts() {
        return ResponseEntity.ok(finaccountingApi.listChartOfAccounts().stream()
            .map(ChartOfAccountResponseDto::from).toList());
    }
}

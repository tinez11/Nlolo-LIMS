package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The {@code /chart-of-accounts} surface.
 *
 * <p><b>Journal entries stay read-only, permanently -- the chart of accounts does not.</b> Every
 * posting is still derived from a domain event and nothing here changes that (Task 6's listeners
 * remain the only path onto {@code gl_posting}/{@code journal_entry}). But {@code chart_of_account}
 * itself was never database-blocked from being writable -- finaccounting/V2 granted app_role full
 * SELECT/INSERT/UPDATE/DELETE on it from the start, unlike the ledger tables' deliberate REVOKE --
 * only the API/application layer was missing, added here on explicit request. {@code accountType}/
 * {@code normalBalance} stay derived from the account code's own leading digit and are never
 * independently settable (see {@link FinaccountingApi}'s javadoc), and delete is blocked once any
 * real posting references the account (a real {@code fk_gl_posting_account_code} foreign key,
 * finaccounting/V3) -- retiring an in-use account is a distinct, deferred concern, not built here.
 *
 * <p><b>FINANCE_OFFICER/ADMIN gate every endpoint here, which is a decision, not a spec quote,</b>
 * for the same reason recorded in {@code GlPostingController}'s javadoc.
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

    @PostMapping("/chart-of-accounts")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> createAccount(
            @Valid @RequestBody CreateAccountRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ChartOfAccountView view = finaccountingApi.createAccount(request.accountCode(), request.name(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChartOfAccountResponseDto.from(view));
    }

    @PutMapping("/chart-of-accounts/{accountCode}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> renameAccount(@PathVariable String accountCode,
            @Valid @RequestBody RenameAccountRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ChartOfAccountView view = finaccountingApi.renameAccount(accountCode, request.name(), jwt.getSubject());
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(view));
    }

    @DeleteMapping("/chart-of-accounts/{accountCode}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<Void> deleteAccount(@PathVariable String accountCode) {
        finaccountingApi.deleteAccount(accountCode);
        return ResponseEntity.noContent().build();
    }
}

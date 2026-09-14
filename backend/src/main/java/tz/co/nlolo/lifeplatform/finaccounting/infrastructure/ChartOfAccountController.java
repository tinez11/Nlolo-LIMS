package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * The chart with its balances, and whether the ledger balances.
     *
     * <p>A separate path from {@code GET /chart-of-accounts} rather than a flag on it. That one
     * is bounded reference data a caller reads to render a tree or populate a picker; this one
     * aggregates the whole posting table behind it. Folding them together would make every
     * structural read pay for an aggregation it did not ask for, and would leave a caller unable
     * to say which it wanted.
     *
     * <p>Balances ROLL UP: a parent reports itself plus every descendant. The totals do NOT --
     * they sum each account's own postings, because adding rolled figures would count every
     * posting once per ancestor and report a sound ledger as wildly out. See
     * {@code TrialBalanceView}.
     */
    @GetMapping("/chart-of-accounts/balances")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<TrialBalanceResponseDto> trialBalance(
            /* YYYY-MM, or omitted for inception-to-date. Echoed back on the response, because a
               balance with no period beside it cannot be reconciled against anything. */
            @RequestParam(required = false) String period) {
        return ResponseEntity.ok(TrialBalanceResponseDto.from(finaccountingApi.trialBalance(period)));
    }

    @PostMapping("/chart-of-accounts")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> createAccount(
            @Valid @RequestBody CreateAccountRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ChartOfAccountView view = finaccountingApi.createAccount(request.accountCode(),
            request.parentCode(), request.name(), request.description(), request.currency(),
            request.postingAllowed(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ChartOfAccountResponseDto.from(view));
    }

    @PutMapping("/chart-of-accounts/{accountCode}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> updateAccount(@PathVariable String accountCode,
            @Valid @RequestBody UpdateAccountRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ChartOfAccountView view = finaccountingApi.updateAccount(accountCode, request.name(),
            request.description(), jwt.getSubject());
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(view));
    }

    /**
     * Retiring an account, and the reason {@code DELETE} below stays narrow.
     *
     * <p>An account that has ever been posted against can never be deleted -- its history must
     * stay mappable to the account it was booked to. Deactivating is what takes such an account
     * out of service: the ledger refuses every new leg naming it, and nothing already written
     * moves.
     */
    @PostMapping("/chart-of-accounts/{accountCode}/deactivate")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> deactivateAccount(@PathVariable String accountCode,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(
            finaccountingApi.setAccountStatus(accountCode, AccountStatus.INACTIVE, jwt.getSubject())));
    }

    @PostMapping("/chart-of-accounts/{accountCode}/activate")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> activateAccount(@PathVariable String accountCode,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(
            finaccountingApi.setAccountStatus(accountCode, AccountStatus.ACTIVE, jwt.getSubject())));
    }

    @DeleteMapping("/chart-of-accounts/{accountCode}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<Void> deleteAccount(@PathVariable String accountCode) {
        finaccountingApi.deleteAccount(accountCode);
        return ResponseEntity.noContent().build();
    }
}

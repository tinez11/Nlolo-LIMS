package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The {@code /gl-postings*} surface -- read-only journal entries and their legs.
 *
 * <p><b>There is no write endpoint here, and there never will be one on this surface.</b> Every
 * posting is derived from a domain event by this module's own listeners (Task 6): nothing hand-
 * enters a journal entry. That absence is what makes the ledger trustworthy -- a correction is a
 * future reversal entry (deferred, see the design spec's §9), never an edit to an existing one.
 *
 * <p><b>FINANCE_OFFICER/ADMIN gate every endpoint here, which is a decision, not a spec quote.</b>
 * There is no finance-specific staff role beyond {@code FINANCE_OFFICER} on this platform, and
 * finaccounting is itself a finance/back-office concern -- the same decision and reasoning M7
 * recorded for {@code distribution} and M8/M9 recorded for {@code reinsurance} (see
 * {@code ReinsuranceExceptionHandler}'s and {@code TreatyController}'s javadocs).
 */
@RestController
public class GlPostingController {

    private final FinaccountingApi finaccountingApi;

    public GlPostingController(FinaccountingApi finaccountingApi) {
        this.finaccountingApi = finaccountingApi;
    }

    /**
     * Paged since M9's final review (finding I3): with no filters this used to return every journal
     * entry the tenant had ever posted, as a bare array, on the one table this platform guarantees
     * grows without bound. {@code page}/{@code pageSize} defaults and the {@code Math.min} hard cap
     * are copied deliberately from {@code ClaimController.listClaims} and {@code
     * PolicyController.searchPolicies} rather than invented -- {@code pageSize} is a client hint, and
     * the cap is what stops it being a denial-of-service knob.
     */
    @GetMapping("/gl-postings")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<JournalEntrySearchResponseDto> listGlPostings(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String policyNumber,
            // The filter that lets a balance be opened up. Every posting already carried an
            // account code, so "what made up account 1210" was in the data and unanswerable
            // through this endpoint -- which is why the chart of accounts and this screen sat
            // side by side in the Finance nav with no way to reach each other.
            @RequestParam(required = false) String accountCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<JournalEntryView> result = finaccountingApi.listJournalEntries(period, policyNumber, accountCode,
            PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(JournalEntrySearchResponseDto.from(result));
    }

    @GetMapping("/gl-postings/{journalEntryId}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<JournalEntryResponseDto> getGlPosting(@PathVariable UUID journalEntryId) {
        JournalEntryView view = finaccountingApi.getJournalEntry(journalEntryId);
        return ResponseEntity.ok(JournalEntryResponseDto.from(view));
    }
}

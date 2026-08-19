package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping("/gl-postings")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<JournalEntryResponseDto>> listGlPostings(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String policyNumber) {
        List<JournalEntryView> views = finaccountingApi.listJournalEntries(period, policyNumber);
        return ResponseEntity.ok(views.stream().map(JournalEntryResponseDto::from).toList());
    }

    @GetMapping("/gl-postings/{journalEntryId}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<JournalEntryResponseDto> getGlPosting(@PathVariable UUID journalEntryId) {
        JournalEntryView view = finaccountingApi.getJournalEntry(journalEntryId);
        return ResponseEntity.ok(JournalEntryResponseDto.from(view));
    }
}

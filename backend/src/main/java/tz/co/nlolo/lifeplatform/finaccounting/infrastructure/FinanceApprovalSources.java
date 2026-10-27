package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountingPeriodView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.application.EngineRuns;
import tz.co.nlolo.lifeplatform.finaccounting.application.ExpenseAllocations;
import tz.co.nlolo.lifeplatform.finaccounting.application.YearEndCloses;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * What the books wait on (2026-10-27): a manual journal, an expense allocation, an engine run and a year-end close for
 * the finance approver; an accounting-policy election and a period's reopening for the second finance signature.
 */
@Configuration
class FinanceApprovalSources {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    @Bean
    ApprovalSource manualJournalApprovals(ManualJournalApi journals) {
        return ApprovalSource.of("manual-journal", "Manual journals", "Approve and post", "ledger.approve",
            limit -> ApprovalSource.Pending.of(journals.list("SUBMITTED", null, null).stream()
                .sorted(Comparator.comparing(j -> j.submittedAt() == null ? j.preparedAt() : j.submittedAt()))
                .map(j -> new ApprovalSource.Item(j.id().toString(), null, j.title() + " · " + j.period(),
                    j.totalDebit(), j.currency(), j.preparer(), j.submittedAt() == null ? j.preparedAt() : j.submittedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource accountingPolicyApprovals(FinaccountingApi finance) {
        return ApprovalSource.of("accounting-policy", "Accounting policy elections", "Decide", "ledger.manage",
            limit -> ApprovalSource.Pending.of(finance.policyElections(LocalDate.now(CIVIL)).stream()
                .filter(e -> "PROPOSED".equals(e.status()))
                .sorted(Comparator.comparing(e -> e.proposedAt()))
                .map(e -> new ApprovalSource.Item(e.electionId().toString(), null,
                    e.key() + (e.scope() == null || "*".equals(e.scope()) ? "" : " for " + e.scope()) + " = " + e.value()
                        + " from " + ApprovalSource.day(e.effectiveFrom()), null, null, e.proposedBy(), e.proposedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource periodReopenings(FinaccountingApi finance) {
        return ApprovalSource.of("period-reopen", "Locked months asked to reopen", "Approve reopening", "ledger.manage",
            limit -> ApprovalSource.Pending.of(finance.periods().stream()
                .filter(FinanceApprovalSources::reopenAsked)
                .sorted(Comparator.comparing(AccountingPeriodView::reopenRequestedAt))
                .map(p -> new ApprovalSource.Item(p.period(), null, "Reopen " + p.period() + ": " + p.reopenReason(),
                    null, null, p.reopenRequestedBy(), p.reopenRequestedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource expenseAllocationApprovals(ExpenseAllocations allocations) {
        return ApprovalSource.of("expense-allocation", "Expense allocations", "Approve and post", "ifrs17.approve",
            limit -> ApprovalSource.Pending.of(allocations.awaitingApproval().stream()
                .map(a -> new ApprovalSource.Item(a.allocationId().toString(), null,
                    a.nilReason() != null ? "No allocation in " + a.period() : "Expenses of " + a.period(),
                    a.total(), a.currency(), a.preparedBy(), a.preparedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource engineRunApprovals(EngineRuns runs) {
        return ApprovalSource.of("engine-run", "IFRS 17 engine results", "Approve and post", "ifrs17.approve",
            limit -> ApprovalSource.Pending.of(runs.list(null, "VALIDATED").stream()
                .sorted(Comparator.comparing(r -> r.uploadedAt()))
                .map(r -> new ApprovalSource.Item(r.runId().toString(), null,
                    r.period() + " results, " + r.engineReference(), null, null, r.uploadedBy(), r.uploadedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource yearEndCloseApprovals(YearEndCloses closes) {
        return ApprovalSource.of("year-end-close", "Year-end closes", "Approve and post", "ifrs17.approve",
            limit -> ApprovalSource.Pending.of(closes.awaitingApproval().stream()
                .map(c -> new ApprovalSource.Item(c.closeId().toString(), null, "Close the year " + c.year(),
                    c.profit(), null, c.preparedBy(), c.preparedAt()))
                .toList(), limit));
    }

    /** Locked, a reopening asked for, and not yet reopened since it was asked. */
    private static boolean reopenAsked(AccountingPeriodView p) {
        return p.status() == PeriodStatus.LOCKED && p.reopenRequestedBy() != null && p.reopenRequestedAt() != null
            && (p.reopenedAt() == null || p.reopenedAt().isBefore(p.reopenRequestedAt()));
    }
}

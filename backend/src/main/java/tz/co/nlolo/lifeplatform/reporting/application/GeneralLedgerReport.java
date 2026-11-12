package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * One account's movements across a span of periods, opening and closing balances included.
 *
 * <p>The opening balance is the cumulative position through the month BEFORE the range -- the figure that
 * makes a running balance mean anything. Without it a general ledger is a list of postings, not a ledger.
 *
 * <p>The running balance moves in the ACCOUNT's own normal direction, so a positive figure means "normal" on
 * a receivable and on a payable alike. It is built from {@link PostingDirection}, never by subtracting
 * rendered strings: {@code gl_posting.amount} is always a positive magnitude and the sign lives in the
 * direction, so a reversal is a new entry with the legs swapped rather than a negative amount.
 */
@Component
public class GeneralLedgerReport implements Report {

    /**
     * One page of an append-only table. The ledger grows without limit, so the page is a real bound; a span of
     * periods on a single account comfortably fits, and the export carries whatever the page holds.
     */
    private static final int PAGE_SIZE = 2000;

    /** requiresAccount: this report is ABOUT one account, so the console asks for it rather than submitting
     *  without one and showing the server's refusal where the report should be. */
    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("general-ledger", "General ledger",
        "Every posting to one account across a span of periods, with its opening and closing balance.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH_RANGE, List.of(), false, true).from("LEDGER");

    private final FinaccountingApi finaccounting;

    public GeneralLedgerReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        if (f.accountCode() == null) {
            throw new ReportParameterException("accountCode is required for general-ledger; choose one account");
        }
        String account = f.accountCode();
        String openingThrough = f.monthFrom().minusMonths(1).toString();
        String from = f.monthFrom().toString();
        String to = f.monthTo().toString();

        BigDecimal balance = finaccounting.trialBalanceThrough(openingThrough).accounts().stream()
            .filter(a -> a.accountCode().equals(account))
            .map(AccountBalanceView::balance)
            .findFirst().orElse(BigDecimal.ZERO);

        List<ReportColumn> columns = List.of(col("period", "Period", TEXT), col("entry", "Entry", TEXT),
            col("description", "Description", TEXT), col("reference", "Reference", TEXT),
            Sections.link("policyNumber", "Policy", "POLICY"),
            col("currency", "Currency", TEXT), col("debit", "Debit", MONEY), col("credit", "Credit", MONEY),
            col("balance", "Balance", MONEY));

        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(openingRow(openingThrough, balance));

        // The account's own postings across the range, read a page at a time by the ledger's query (2026-11-09) -- not
        // the first 2,000 entries of all time filtered here, which silently dropped a range past that page.
        String currency = "";
        int page = 0;
        int read = 0;
        while (read < PAGE_SIZE) {
            AccountPostingsView postings = finaccounting.accountPostings(account, from, to, page, 500);
            for (AccountPostingsView.Posting posting : postings.postings()) {
                if (!posting.accountCode().equals(account)) {
                    continue;
                }
                balance = posting.direction() == PostingDirection.DR
                    ? balance.add(posting.amount()) : balance.subtract(posting.amount());
                currency = posting.currency();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("period", posting.period());
                row.put("entry", posting.journalEntryId().toString());
                row.put("description", StatementReports.readable(posting.sourceEvent()));
                row.put("reference", posting.sourceRef() == null ? "" : posting.sourceRef());
                row.put("policyNumber", posting.policyNumber() == null ? "" : posting.policyNumber());
                row.put("currency", posting.currency());
                row.put("debit", posting.direction() == PostingDirection.DR ? Figures.money(posting.amount()) : "0.00");
                row.put("credit", posting.direction() == PostingDirection.CR ? Figures.money(posting.amount()) : "0.00");
                row.put("balance", Figures.money(balance));
                row.put("_source", posting.sourceEvent() + ":" + posting.sourceRef());
                rows.add(row);
            }
            read += postings.postings().size();
            if (postings.postings().size() < postings.size() || (long) (page + 1) * postings.size() >= postings.count()) {
                break;
            }
            page++;
        }

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("period", "Closing balance");
        totals.put("entry", "");
        totals.put("description", "");
        totals.put("reference", "");
        totals.put("policyNumber", "");
        totals.put("currency", currency);
        totals.put("debit", "");
        totals.put("credit", "");
        totals.put("balance", Figures.money(balance));

        return List.of(new ReportSection("Account " + account + ", " + from + " to " + to, columns, rows, totals));
    }

    private static Map<String, String> openingRow(String through, BigDecimal balance) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("period", through);
        row.put("entry", "");
        row.put("description", "Opening balance");
        row.put("policyNumber", "");
        row.put("currency", "");
        row.put("debit", "");
        row.put("credit", "");
        row.put("balance", Figures.money(balance));
        return row;
    }
}

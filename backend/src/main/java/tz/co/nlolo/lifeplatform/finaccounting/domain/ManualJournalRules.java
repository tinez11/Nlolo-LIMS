package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a manual journal must satisfy before it is submitted, and again before it is posted (IFRS 17 I4, guide 2.3
 * and 5.4). Every problem is reported, not the first: a preparer fixes a journal in one pass. Pure -- the chart,
 * the period's state and the reason codes are passed in.
 * <ul>
 *   <li>balanced, at least one Dr and one Cr line, every amount above zero, one currency;</li>
 *   <li>every account a posting account of the chart, ACTIVE, MAN or BOTH -- an AUTO account is the system's alone
 *       (the database refuses it too);</li>
 *   <li>a BOTH account needs a reason code (guide 2.3), and a reason code must be one of the list;</li>
 *   <li>a reason, and at least one supporting document (guide Part 4: "a reason and an attached document");</li>
 *   <li>a period that is not LOCKED; an auto-reverse date after the period.</li>
 * </ul>
 */
public final class ManualJournalRules {

    /** What the rules need to know of an account. */
    public record Account(String code, String name, PostingMode mode, boolean postingAllowed, boolean active,
                          String currency) {}

    private ManualJournalRules() {}

    public static List<String> problems(ManualJournalInput journal, String period, Map<String, Account> chart,
                                        Set<String> reasonCodes, PeriodStatus periodStatus, int documents) {
        List<String> problems = new ArrayList<>();
        if (journal.title() == null || journal.title().isBlank()) {
            problems.add("A journal has a title");
        }
        if (journal.reason() == null || journal.reason().isBlank()) {
            problems.add("Say why this journal is needed (the reason)");
        }
        if (documents == 0) {
            problems.add("Attach the document this journal rests on (a resolution, a statement, a report)");
        }
        if (periodStatus == PeriodStatus.LOCKED) {
            problems.add("Period " + period + " is locked; a manual journal posts to an open or closing period");
        }
        if (journal.autoReverseOn() != null
                && !journal.autoReverseOn().isAfter(java.time.YearMonth.parse(period).atEndOfMonth())) {
            problems.add("An auto-reversal falls after the journal's period, " + period);
        }
        if (journal.reasonCode() != null && !journal.reasonCode().isBlank() && !reasonCodes.contains(journal.reasonCode())) {
            problems.add("Reason code " + journal.reasonCode() + " is not one of " + reasonCodes);
        }

        List<ManualJournalInput.Line> lines = journal.lines();
        if (lines.isEmpty()) {
            problems.add("A journal has lines");
            return problems;
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        boolean needsReasonCode = false;
        String currency = journal.currency() == null ? "TZS" : journal.currency();
        for (int i = 0; i < lines.size(); i++) {
            ManualJournalInput.Line line = lines.get(i);
            String where = "Line " + (i + 1) + (line.accountCode() == null ? "" : " (" + line.accountCode() + ")");
            if (line.side() == null) {
                problems.add(where + ": Dr or Cr?");
            }
            if (line.amount() == null || line.amount().signum() <= 0) {
                problems.add(where + ": an amount above zero");
            } else if (line.amount().scale() > 2) {
                problems.add(where + ": at most two decimal places");
            } else if (line.side() == PostingDirection.DR) {
                debit = debit.add(line.amount());
            } else if (line.side() == PostingDirection.CR) {
                credit = credit.add(line.amount());
            }
            Account account = line.accountCode() == null ? null : chart.get(line.accountCode());
            if (account == null) {
                problems.add(where + ": no such account");
                continue;
            }
            if (!account.postingAllowed()) {
                problems.add(where + ": " + account.name() + " is a heading and takes no postings");
            } else if (!account.active()) {
                problems.add(where + ": " + account.name() + " is retired");
            } else if (account.mode() == PostingMode.AUTO) {
                problems.add(where + ": " + account.name() + " is posted only by the system (AUTO)");
            }
            needsReasonCode |= account.mode() == PostingMode.BOTH;
            if (!currency.equals(account.currency())) {
                problems.add(where + ": " + account.name() + " is in " + account.currency() + ", the journal in " + currency);
            }
        }
        if (debit.signum() == 0 || credit.signum() == 0) {
            problems.add("A journal has at least one Dr and one Cr line");
        } else if (debit.compareTo(credit) != 0) {
            problems.add("Debits " + debit.toPlainString() + " and credits " + credit.toPlainString() + " differ by "
                + debit.subtract(credit).abs().toPlainString());
        }
        if (needsReasonCode && (journal.reasonCode() == null || journal.reasonCode().isBlank())) {
            problems.add("A line on a BOTH account (mostly automatic) needs a reason code");
        }
        return problems;
    }
}

package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A manual journal as its preparer states it (IFRS 17 I4). {@code period} YYYY-MM, the current month when null;
 * {@code autoReverseOn} the first day of a later period for an accrual reversed by the platform, else null.
 */
public record ManualJournalInput(String period, String currency, String title, String reason, String reasonCode,
                                 String templateId, LocalDate autoReverseOn, List<Line> lines) {

    public ManualJournalInput {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One line: account, side, a positive amount, and the dimensions that apply (fund, branch, a reference). */
    public record Line(String accountCode, PostingDirection side, BigDecimal amount, String description, String branch,
                       String fund, String referenceType, String reference) {}
}

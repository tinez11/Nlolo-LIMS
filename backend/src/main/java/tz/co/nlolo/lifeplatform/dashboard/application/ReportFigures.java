package tz.co.nlolo.lifeplatform.dashboard.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Reads a report's OWN number out of its result -- the dashboard's only way to show a ledger figure, so it can never
 * disagree with the report a finance officer opens from the card.
 *
 * <p>Coupled to two titles the reports emit: the balance sheet's class sections ("Assets", "Liabilities", from
 * {@code LedgerAccounts.CLASS_NAMES}) and the income statement's "Result" section. Both reports carry a comment naming
 * this class, so a rename is made knowingly. A title or column that is not there is null, which the card shows as
 * "Not available" -- never zero.
 */
final class ReportFigures {

    private ReportFigures() {}

    static BigDecimal sectionTotal(ReportResult result, String sectionTitle, String column) {
        if (result == null) {
            return null;
        }
        for (ReportSection section : result.sections()) {
            if (sectionTitle.equals(section.title())) {
                String value = section.totals().get(column);
                return value == null || value.isBlank() ? null : new BigDecimal(value);
            }
        }
        return null;
    }

    /** The income statement's result, signed: the report states a loss as an unsigned amount beside its outcome. */
    static BigDecimal netResult(ReportResult result) {
        if (result == null) {
            return null;
        }
        for (ReportSection section : result.sections()) {
            if ("Result".equals(section.title()) && !section.rows().isEmpty()) {
                Map<String, String> row = section.rows().get(0);
                String amount = row.get("amount");
                if (amount == null || amount.isBlank()) {
                    return null;
                }
                BigDecimal value = new BigDecimal(amount);
                return row.getOrDefault("outcome", "").startsWith("Loss") ? value.negate() : value;
            }
        }
        return null;
    }
}

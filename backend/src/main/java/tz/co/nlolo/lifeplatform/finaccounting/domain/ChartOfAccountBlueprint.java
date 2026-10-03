package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.util.List;
import java.util.Map;

/**
 * THE canonical chart of accounts, defined once.
 *
 * <p><b>EVERY ACCOUNT BELOW IS A PLACEHOLDER pending FINANCE sign-off</b> -- the same treatment
 * M9 gave the original nine, and M2/M7/M8 gave the underwriting decision engine, commission and
 * cession. No document on this platform specifies account codes or their debit/credit treatment.
 *
 * <p>finaccounting/V5 duplicates this list in SQL, because a migration cannot call Java.
 * {@code ChartOfAccountMigrationV5Test} asserts the two agree exactly, so they cannot drift.
 *
 * <p><b>The 4xxx income accounts are seeded and will stay at zero.</b> Premium is EARNED through
 * LRC release, which is C1-blocked (Actuarial), so no {@link PostingRule} entry targets them.
 * They exist for chart completeness; seeding them is not an un-deferral of IFRS 17 measurement.
 *
 * <p><b>Cash posts to 1120 Mobile Money, not 1110 Main Bank Account.</b> Every money-movement
 * path on this platform is mobile money today, so 1120 is the honest target; 1110 and 1130 seed
 * as real but empty accounts. A bank-collection path would revisit that choice.
 */
public final class ChartOfAccountBlueprint {

    private ChartOfAccountBlueprint() {}

    /** One seeded account. {@code parentCode} is null only for the five block roots. */
    public record Seed(String code, String name, String parentCode,
                        boolean postingAllowed, String controlOf) {}

    private static Seed header(String code, String name, String parentCode) {
        return new Seed(code, name, parentCode, false, null);
    }

    private static Seed post(String code, String name, String parentCode) {
        return new Seed(code, name, parentCode, true, null);
    }

    private static Seed post(String code, String name, String parentCode, String controlOf) {
        return new Seed(code, name, parentCode, true, controlOf);
    }

    private static final List<Seed> ACCOUNTS = List.of(
        header("1000", "Assets", null),
        header("1100", "Cash and Cash Equivalents", "1000"),
        post("1110", "Main Bank Account", "1100"),
        post("1120", "Mobile Money", "1100"),
        post("1130", "Petty Cash", "1100"),
        header("1200", "Receivables", "1000"),
        post("1210", "Premium Receivables", "1200", "BILLING"),
        post("1220", "Agent Receivables", "1200", "DISTRIBUTION"),
        post("1230", "Other Receivables", "1200"),
        post("1240", "Reinsurance Recoverable", "1200", "REINSURANCE"),
        post("1250", "Policy Loan Receivables", "1200", "POLICYLOAN"),
        post("1300", "Investments", "1000"),
        header("2000", "Liabilities", null),
        header("2100", "Insurance Liabilities", "2000"),
        post("2110", "Claims Payable", "2100", "CLAIMS"),
        post("2120", "Premiums Received in Advance", "2100"),
        post("2130", "Policyholder Benefits Payable", "2100"),
        post("2140", "Unearned Premium", "2100"),
        header("2200", "Payables", "2000"),
        post("2210", "Agent Commissions Payable", "2200", "DISTRIBUTION"),
        post("2220", "Reinsurance Payable", "2200", "REINSURANCE"),
        // Product step 5: tax withheld from payouts, owed to the authority until remitted (finaccounting V8).
        post("2230", "Withholding Tax Payable", "2200"),
        post("2300", "Other Liabilities", "2000"),
        header("3000", "Equity", null),
        post("3100", "Share Capital", "3000"),
        post("3200", "Retained Earnings", "3000"),
        post("3300", "Current Year Profit/Loss", "3000"),
        header("4000", "Income", null),
        post("4100", "Premium Income", "4000"),
        post("4200", "Investment Income", "4000"),
        post("4300", "Other Income", "4000"),
        header("5000", "Expenses", null),
        post("5100", "Claims Expense", "5000"),
        post("5200", "Commission Expense", "5000"),
        post("5300", "Operating Expenses", "5000"),
        post("5400", "Other Expenses", "5000"),
        post("5500", "Reinsurance Ceded Premium", "5000"));

    /** In insertion order: every parent appears before its children, so a caller may insert
     *  straight down the list without violating the self-referencing foreign key. */
    public static List<Seed> accounts() {
        return ACCOUNTS;
    }

    /**
     * The nine M9 codes, mapped to where their postings belong in this chart.
     *
     * <p><b>Three of these ROTATE</b> -- 5000 to 5100, 5100 to 5200, 5200 to 5500 -- which is why
     * finaccounting/V5 must drop {@code fk_gl_posting_account_code} for the duration of the remap
     * rather than updating rows in place. 1400 is the one legacy code with no counterpart in the
     * new chart: its postings move to 1250 and the code itself disappears.
     */
    public static Map<String, String> legacyRemap() {
        return Map.of(
            "1000", "1120",
            "1200", "1210",
            "1300", "1240",
            "1400", "1250",
            "2200", "2140",
            "2300", "2220",
            "5000", "5100",
            "5100", "5200",
            "5200", "5500");
    }

    /** The tenant functional currency every account is seeded with. */
    public static final String SEED_CURRENCY = "TZS";
}

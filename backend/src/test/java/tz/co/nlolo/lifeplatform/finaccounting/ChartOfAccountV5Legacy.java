package tz.co.nlolo.lifeplatform.finaccounting;

import java.util.List;
import java.util.Map;

/**
 * The placeholder chart V5 to V9 shipped (40 accounts) and V5's legacy remap, frozen here when IFRS 17 I1
 * replaced the chart with the posting guide's. V5's test still proves what V5 did; V10 then clears the development
 * ledger.
 */
final class ChartOfAccountV5Legacy {

    private ChartOfAccountV5Legacy() {}

    record Seed(String code, String name, String parentCode, boolean postingAllowed, String controlOf) {}

    static final List<Seed> ACCOUNTS = List.of(
        new Seed("1000", "Assets", null, false, null),
        new Seed("1100", "Cash and Cash Equivalents", "1000", false, null),
        new Seed("1110", "Main Bank Account", "1100", true, null),
        new Seed("1120", "Mobile Money", "1100", true, null),
        new Seed("1130", "Petty Cash", "1100", true, null),
        new Seed("1200", "Receivables", "1000", false, null),
        new Seed("1210", "Premium Receivables", "1200", true, "BILLING"),
        new Seed("1220", "Agent Receivables", "1200", true, "DISTRIBUTION"),
        new Seed("1230", "Other Receivables", "1200", true, null),
        new Seed("1240", "Reinsurance Recoverable", "1200", true, "REINSURANCE"),
        new Seed("1250", "Policy Loan Receivables", "1200", true, "POLICYLOAN"),
        new Seed("1300", "Investments", "1000", true, null),
        new Seed("2000", "Liabilities", null, false, null),
        new Seed("2100", "Insurance Liabilities", "2000", false, null),
        new Seed("2110", "Claims Payable", "2100", true, "CLAIMS"),
        new Seed("2120", "Premiums Received in Advance", "2100", true, null),
        new Seed("2130", "Policyholder Benefits Payable", "2100", true, null),
        new Seed("2140", "Unearned Premium", "2100", true, null),
        new Seed("2150", "Unit-Linked Policyholder Liability", "2100", true, null),
        new Seed("2200", "Payables", "2000", false, null),
        new Seed("2210", "Agent Commissions Payable", "2200", true, "DISTRIBUTION"),
        new Seed("2220", "Reinsurance Payable", "2200", true, "REINSURANCE"),
        new Seed("2230", "Withholding Tax Payable", "2200", true, null),
        new Seed("2300", "Other Liabilities", "2000", true, null),
        new Seed("3000", "Equity", null, false, null),
        new Seed("3100", "Share Capital", "3000", true, null),
        new Seed("3200", "Retained Earnings", "3000", true, null),
        new Seed("3300", "Current Year Profit/Loss", "3000", true, null),
        new Seed("4000", "Income", null, false, null),
        new Seed("4100", "Premium Income", "4000", true, null),
        new Seed("4200", "Investment Income", "4000", true, null),
        new Seed("4300", "Other Income", "4000", true, null),
        new Seed("4310", "Unit-Linked Charges Income", "4000", true, null),
        new Seed("5000", "Expenses", null, false, null),
        new Seed("5100", "Claims Expense", "5000", true, null),
        new Seed("5200", "Commission Expense", "5000", true, null),
        new Seed("5300", "Operating Expenses", "5000", true, null),
        new Seed("5400", "Other Expenses", "5000", true, null),
        new Seed("5500", "Reinsurance Ceded Premium", "5000", true, null),
        new Seed("5600", "Change in Unit-Linked Liability", "5000", true, null));

    static Map<String, String> remap() {
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
}

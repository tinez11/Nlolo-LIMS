package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The accounting policy register's baseline (IFRS 17 spec §3), seeded APPROVED for each tenant beside its chart and
 * effective from 2020-01-01. Pending the appointed actuary's and the external auditors' sign-off -- the guide itself
 * says these are judgements they must confirm before go-live -- which is what each row's sign-off says.
 */
public final class PolicyRegisterBaseline {

    private PolicyRegisterBaseline() {}

    public static final LocalDate EFFECTIVE_FROM = LocalDate.of(2020, 1, 1);
    public static final String SIGN_OFF = "Baseline per IFRS 17 spec 2026-10-05 §3 -- pending actuary and auditor sign-off";

    public record Row(String key, String scope, String value, String rationale) {}

    private static final String SAVINGS_FLAG = "IFRS 9 only while the death benefit is the balance alone; a version"
        + " paying more on death is insurance (GMM) -- confirm with the actuary and auditors";
    private static final String PENSION_FLAG = "IFRS 9 in deferral only while annuity rates are not guaranteed; a"
        + " guaranteed-rate version is GMM from inception -- confirm with the actuary and auditors";

    public static final List<Row> ROWS = List.of(
        new Row("MEASUREMENT_MODEL", "TERM", "GMM", null),
        new Row("MEASUREMENT_MODEL", "CRL", "GMM", "A single credit-life policy; a lender's scheme overrides to PAA"),
        new Row("MEASUREMENT_MODEL", "GRPL", "PAA", "Cover of one year or less"),
        new Row("MEASUREMENT_MODEL", "FUN", "PAA", "Family funeral: renewable cover of one year or less"),
        new Row("MEASUREMENT_MODEL", "WL", "GMM", null),
        new Row("MEASUREMENT_MODEL", "END", "GMM", null),
        new Row("MEASUREMENT_MODEL", "MB", "GMM", null),
        new Row("MEASUREMENT_MODEL", "PAR", "VFA", "Participating, where B101 is met"),
        new Row("MEASUREMENT_MODEL", "ULIP", "VFA", "Unit-linked with significant insurance risk"),
        new Row("MEASUREMENT_MODEL", "SAV", "IFRS9", SAVINGS_FLAG),
        new Row("MEASUREMENT_MODEL", "DEP", "IFRS9", SAVINGS_FLAG),
        new Row("MEASUREMENT_MODEL", "IANN", "GMM", null),
        new Row("MEASUREMENT_MODEL", "DANN", "IFRS9", PENSION_FLAG),
        new Row("MEASUREMENT_MODEL", "PEN", "IFRS9", PENSION_FLAG),
        new Row("INVESTMENT_COMPONENT_RULE", "TERM", "PREMIUMS_RETURNED", "Only a return-of-premium benefit has one"),
        new Row("INVESTMENT_COMPONENT_RULE", "CRL", "NONE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "GRPL", "NONE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "FUN", "NONE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "IANN", "NONE", "Unless a guaranteed period"),
        new Row("INVESTMENT_COMPONENT_RULE", "WL", "SURRENDER_VALUE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "END", "SURRENDER_VALUE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "MB", "SURRENDER_VALUE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "PAR", "SURRENDER_VALUE_WITH_BONUSES", null),
        new Row("INVESTMENT_COMPONENT_RULE", "ULIP", "FUND_VALUE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "SAV", "WHOLE_BALANCE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "DEP", "WHOLE_BALANCE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "DANN", "WHOLE_BALANCE", null),
        new Row("INVESTMENT_COMPONENT_RULE", "PEN", "WHOLE_BALANCE", null),
        new Row("MODEL_OVERRIDE_ALLOWED", "SAV", "GMM", SAVINGS_FLAG),
        new Row("MODEL_OVERRIDE_ALLOWED", "DEP", "GMM", SAVINGS_FLAG),
        new Row("MODEL_OVERRIDE_ALLOWED", "DANN", "GMM", PENSION_FLAG),
        new Row("MODEL_OVERRIDE_ALLOWED", "PEN", "GMM", PENSION_FLAG),
        new Row("MODEL_OVERRIDE_ALLOWED", "CRL", "PAA", "A lender's credit-life scheme"),
        new Row("MODEL_OVERRIDE_ALLOWED", "*", "NONE", "Every other portfolio takes its model as elected"),
        new Row("POLICY_LOANS", "*", "INSIDE_CONTRACT", "Policy loans sit inside the insurance contract liability;"
            + " interest is a contract inflow (LN_INT) -- confirm with the auditors"),
        new Row("ACQUISITION_CASH_FLOWS", "GMM", "SPREAD", null),
        new Row("ACQUISITION_CASH_FLOWS", "VFA", "SPREAD", null),
        new Row("ACQUISITION_CASH_FLOWS", "PAA", "EXPENSE_WHEN_INCURRED", "IFRS 17 para 59(a)"),
        new Row("OCI_OPTION", "*", "OFF", "Discount-rate effects to profit or loss (7120)"),
        new Row("RIDERS", "*", "HOST_GROUP", null),
        new Row("PREMIUM_BILLING", "*", "ACCRUAL_AT_INVOICE", "Invoice: DR 2122 / CR 2121 (PAA: 2142 / 2141)"),
        new Row("CONTRACT_RECOGNITION", "*", "ISSUE_DATE", "Money before issue sits in 2410"),
        new Row("COHORT", "*", "ANNUAL", "Calendar year of issue"));
}

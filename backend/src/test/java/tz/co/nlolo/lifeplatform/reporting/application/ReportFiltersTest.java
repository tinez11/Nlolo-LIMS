package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The month filters the accounting reports take, and the rule the whole file exists for: a report gets
 * exactly the date fields its kind names, and a stray one is refused rather than silently ignored -- so a
 * caller never believes a filter was applied when it was not.
 */
class ReportFiltersTest {

    private static ReportDefinitionView accounting(DateFilterKind kind) {
        return new ReportDefinitionView("trial-balance", "Trial balance", "", ReportCategory.ACCOUNTING,
            kind, List.of(), false);
    }

    private static ReportDefinitionView management(DateFilterKind kind) {
        return new ReportDefinitionView("policies-in-force", "Policies in force", "", ReportCategory.MANAGEMENT,
            kind, List.of(), false);
    }

    @Test
    void aMonthReportNeedsItsMonth() {
        ReportParameterException e = assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(accounting(DateFilterKind.MONTH), ReportFilter.none()));
        assertTrue(e.getMessage().contains("monthFrom"));
    }

    /** The ledger carries no posting date, so a day would promise a precision it cannot deliver. */
    @Test
    void aDayDateIsRefusedOnAMonthReport() {
        ReportFilter f = ReportFilter.month(YearMonth.of(2026, 9)).withAsOf(LocalDate.of(2026, 9, 30));
        assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(accounting(DateFilterKind.MONTH), f));
    }

    @Test
    void oneMonthIsEnoughForAMonthReport() {
        assertDoesNotThrow(() -> ReportFilters.validate(accounting(DateFilterKind.MONTH),
            ReportFilter.month(YearMonth.of(2026, 9))));
    }

    @Test
    void aSecondMonthDoesNotApplyToASingleMonthReport() {
        assertThrows(ReportParameterException.class, () -> ReportFilters.validate(
            accounting(DateFilterKind.MONTH),
            ReportFilter.months(YearMonth.of(2026, 4), YearMonth.of(2026, 9))));
    }

    @Test
    void aRangeNeedsBothEndsInOrder() {
        assertThrows(ReportParameterException.class, () -> ReportFilters.validate(
            accounting(DateFilterKind.MONTH_RANGE), ReportFilter.month(YearMonth.of(2026, 9))));
        assertThrows(ReportParameterException.class, () -> ReportFilters.validate(
            accounting(DateFilterKind.MONTH_RANGE),
            ReportFilter.months(YearMonth.of(2026, 9), YearMonth.of(2026, 4))));
        assertDoesNotThrow(() -> ReportFilters.validate(accounting(DateFilterKind.MONTH_RANGE),
            ReportFilter.months(YearMonth.of(2026, 4), YearMonth.of(2026, 9))));
    }

    @Test
    void anAccountCodeMustLookLikeTheChartsCodes() {
        ReportFilter bad = ReportFilter.month(YearMonth.of(2026, 9)).withAccountCode("11OO");
        assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(accounting(DateFilterKind.MONTH), bad));
        assertDoesNotThrow(() -> ReportFilters.validate(accounting(DateFilterKind.MONTH),
            ReportFilter.month(YearMonth.of(2026, 9)).withAccountCode("1100")));
    }

    /** The rule runs both ways: a month handed to a management report is refused, not quietly dropped. */
    @Test
    void aMonthIsRefusedOnAReportThatReadsDates() {
        ReportFilter f = ReportFilter.asOf(LocalDate.of(2026, 9, 30)).withMonths(YearMonth.of(2026, 9), null);
        ReportParameterException e = assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(management(DateFilterKind.AS_OF), f));
        assertTrue(e.getMessage().contains("monthFrom"));
    }
}

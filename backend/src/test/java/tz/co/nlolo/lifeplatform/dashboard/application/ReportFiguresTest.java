package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFiguresTest {

    private static ReportResult result(ReportSection... sections) {
        return new ReportResult("k", "t", "", Instant.now(), List.of(sections));
    }

    private static ReportSection classSection(String title, String total) {
        return new ReportSection(title, List.of(), List.of(), Map.of("accountCode", "Total", "balance", total));
    }

    private static ReportSection resultSection(String outcome, String amount) {
        return new ReportSection("Result", List.of(), List.of(Map.of("outcome", outcome, "amount", amount)), Map.of());
    }

    @Test
    void readsASectionsTotalByTitle() {
        ReportResult sheet = result(classSection("Assets", "742580120.00"), classSection("Liabilities", "512340210.00"));

        assertThat(ReportFigures.sectionTotal(sheet, "Assets", "balance")).isEqualByComparingTo("742580120.00");
        assertThat(ReportFigures.sectionTotal(sheet, "Liabilities", "balance")).isEqualByComparingTo("512340210.00");
    }

    /** Absent is null: a missing section is a card reading "Not available", never a quiet zero. */
    @Test
    void aMissingSectionOrColumnIsNull() {
        ReportResult sheet = result(classSection("Assets", "1.00"));

        assertThat(ReportFigures.sectionTotal(sheet, "Equity", "balance")).isNull();
        assertThat(ReportFigures.sectionTotal(sheet, "Assets", "comparison")).isNull();
    }

    @Test
    void aLossIsNegativeAndAProfitPositive() {
        assertThat(ReportFigures.netResult(result(resultSection("Profit for the period", "31204880.00"))))
            .isEqualByComparingTo("31204880.00");
        assertThat(ReportFigures.netResult(result(resultSection("Loss for the period", "1200.00"))))
            .isEqualByComparingTo("-1200.00");
        assertThat(ReportFigures.netResult(result())).isNull();
    }
}

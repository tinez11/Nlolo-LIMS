package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.unitlinked.api.StatementData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF says what the data says -- and a fund with no price yet says exactly that, never 0.00. */
class StatementPdfTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Test
    void itCarriesThePolicyEveryFundAndSaysWhenAFundHasNoPriceYet() throws Exception {
        Map<String, BigDecimal> charges = new LinkedHashMap<>();
        charges.put("ALLOCATION_CHARGE", new BigDecimal("10000.00"));
        charges.put("POLICY_FEE", new BigDecimal("2000.00"));
        StatementData data = new StatementData("UL-2026-000123", FROM, TO,
            List.of(new StatementData.Position("BD1", BigDecimal.ZERO, null, null, null),
                new StatementData.Position("EQ1", BigDecimal.ZERO, null, null, null)),
            List.of(new StatementData.Position("BD1", new BigDecimal("36000.000000"), new BigDecimal("1.010000"),
                    LocalDate.of(2026, 12, 31), new BigDecimal("36360.00")),
                new StatementData.Position("EQ1", new BigDecimal("54000.000000"), new BigDecimal("1.250000"),
                    LocalDate.of(2026, 12, 30), new BigDecimal("67500.00"))),
            List.of(new StatementData.Line(LocalDate.of(2026, 3, 2), "ALLOCATION", "EQ1", new BigDecimal("54000.000000"),
                    new BigDecimal("1.000000"), new BigDecimal("54000.00")),
                new StatementData.Line(LocalDate.of(2026, 3, 2), "ALLOCATION", "BD1", new BigDecimal("36000.000000"),
                    new BigDecimal("1.000000"), new BigDecimal("36000.00")),
                new StatementData.Line(LocalDate.of(2026, 3, 2), "ALLOCATION_CHARGE", null, null, null,
                    new BigDecimal("-10000.00")),
                new StatementData.Line(LocalDate.of(2026, 4, 2), "POLICY_FEE", "EQ1", new BigDecimal("-1200.000000"),
                    new BigDecimal("1.000000"), new BigDecimal("-1200.00")),
                new StatementData.Line(LocalDate.of(2026, 4, 2), "POLICY_FEE", "BD1", new BigDecimal("-800.000000"),
                    new BigDecimal("1.000000"), new BigDecimal("-800.00"))),
            new BigDecimal("90000.00"), charges, new BigDecimal("0.00"), "TZS");

        byte[] pdf = StatementPdf.render(data, "Nlolo Life");

        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("UL-2026-000123", "EQ1", "BD1", "No price yet", "TZS 103,860.00",
                "Allocation charge:  TZS 10,000.00", "at 1.250000 (30/12/2026)");
            assertThat(text).doesNotContain("TZS 0.00 (");
        }
    }
}

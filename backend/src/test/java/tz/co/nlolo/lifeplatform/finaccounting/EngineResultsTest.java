package tz.co.nlolo.lifeplatform.finaccounting;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineResults;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineResultsTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I5a: the engine's results file -- read, then checked all at once against the extract it answers. */
class EngineResultsTest {

    private static final String GROUP = "TERM-GMM-2026-REM";
    private static final String RI = "RI-AB12CD34-2026";
    private static final Set<String> CHART = ChartOfAccountBlueprint.accounts().stream()
        .filter(ChartOfAccountBlueprint.Seed::postingAllowed).map(ChartOfAccountBlueprint.Seed::code)
        .collect(Collectors.toSet());

    /** A workbook as an actuary would fill the template: header, journal rows, closing rows. */
    private static byte[] workbook(List<Object[]> header, List<Object[]> journal, List<Object[]> closing) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(wb.createSheet("Header"), header);
            List<Object[]> j = new ArrayList<>();
            j.add(new Object[] {"group", "entry", "account", "side", "amount", "movement", "note"});
            j.addAll(journal);
            fill(wb.createSheet("Journal"), j);
            List<Object[]> c = new ArrayList<>();
            c.add(new Object[] {"group", "lrc", "lic", "csm", "arc", "aic", "ri_csm"});
            c.addAll(closing);
            fill(wb.createSheet("Closing"), c);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet, List<Object[]> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            Object[] values = rows.get(r);
            for (int c = 0; c < values.length; c++) {
                if (values[c] instanceof Number n) {
                    row.createCell(c).setCellValue(n.doubleValue());
                } else if (values[c] != null) {
                    row.createCell(c).setCellValue(values[c].toString());
                }
            }
        }
    }

    private static List<Object[]> header(String reference) {
        return List.of(new Object[] {"Period", "2026-08"}, new Object[] {"Extract", 1},
            new Object[] {"Engine reference", reference}, new Object[] {"Engine", "Prophet 9"},
            new Object[] {"Measurement date", "2026-08-31"});
    }

    /** The guide's P-01 and P-08 for a policy group, P-16 for a reinsurance group, and both groups' closing figures. */
    private static byte[] valid() throws Exception {
        return workbook(header("RUN-2026-08-A"),
            List.of(new Object[] {GROUP, "P-01", "2110", "DR", 150000000, null, "new business"},
                new Object[] {GROUP, "P-01", "2111", "CR", 30000000, null, null},
                new Object[] {GROUP, "P-01", "2112", "CR", 120000000, null, null},
                new Object[] {GROUP, "P-08", "2112", "DR", "14000000.00", "CSM_REL", null},
                new Object[] {GROUP, "P-08", "4130", "CR", "14000000.00", "CSM_REL", null},
                new Object[] {RI, "P-16", "1410", "DR", 480000, null, null},
                new Object[] {RI, "P-16", "1436", "CR", 480000, null, null}),
            List.of(new Object[] {GROUP, "-150000000.00", "0", "-106000000.00", null, null, null},
                new Object[] {RI, null, null, null, "480000", "0", "0"}));
    }

    private static List<String> validate(byte[] file, PeriodStatus status, List<String> extractGroups, Set<String> known) {
        EngineResults.Parsed parsed = EngineResults.parse(file);
        if (!parsed.errors().isEmpty()) {
            return parsed.errors();
        }
        return parsed.results().problems(new EngineResults.Context(status, extractGroups, known, CHART));
    }

    @Test
    void aFilledTemplateIsReadAndPassesAgainstItsExtract() throws Exception {
        EngineResults.Parsed parsed = EngineResults.parse(valid());
        assertThat(parsed.errors()).isEmpty();
        EngineResults r = parsed.results();
        assertThat(r.header().period()).isEqualTo("2026-08");
        assertThat(r.header().extractNumber()).isEqualTo(1);
        assertThat(r.header().engineReference()).isEqualTo("RUN-2026-08-A");
        assertThat(r.lines()).hasSize(7);
        assertThat(r.lines().get(0).amount()).isEqualByComparingTo("150000000.00");
        assertThat(r.closings()).extracting(EngineResults.Closing::group).containsExactly(GROUP, RI);
        assertThat(r.problems(new EngineResults.Context(PeriodStatus.CLOSING, List.of(GROUP, RI), Set.of(), CHART)))
            .isEmpty();
    }

    @Test
    void everyProblemIsListedAtOnceWithItsRow() throws Exception {
        byte[] bad = workbook(header("RUN-OLD"),
            List.of(new Object[] {GROUP, "P-08", "2112", "DR", 100, null, null},
                new Object[] {GROUP, "P-08", "4110", "CR", 80, null, null},           // Dr 105 against Cr 95: unbalanced
                new Object[] {GROUP, "P-99", "2112", "CR", 10, null, null},           // no such entry
                new Object[] {GROUP, "P-16", "1410", "DR", 5, null, null},            // a reinsurance account on a policy group
                new Object[] {GROUP, "P-16", "1436", "CR", 5, null, null},
                new Object[] {"NOT-EXTRACTED", "P-05", "2111", "DR", 1, null, null},
                new Object[] {"NOT-EXTRACTED", "P-05", "4120", "CR", 1, null, null},
                new Object[] {RI, "P-17", "2112", "DR", 3, null, null},               // a policy account on an RI group
                new Object[] {RI, "P-17", "6130", "CR", 3, null, null},
                new Object[] {GROUP, "P-10", "4150", "XX", "1.001", null, null}),     // side and decimals
            List.<Object[]>of(new Object[] {RI, null, null, null, "1", "0", "0"}));             // the policy group has no closing row
        List<String> problems = validate(bad, PeriodStatus.OPEN, List.of(GROUP, RI), Set.of("RUN-OLD"));
        assertThat(problems)
            .contains("Header: period 2026-08 is OPEN; results are loaded into a closing period")
            .contains("Header: engine reference RUN-OLD was loaded before; a new run needs a new reference")
            .contains("Journal row 4: P-99 is not one of P-01 to P-17")
            .contains("Journal row 5: " + GROUP + " P-16 account 1410 is not one the engine posts for a policy group")
            .contains("Journal row 7: group NOT-EXTRACTED is not in extract 2026-08 #1")
            .contains("Journal row 9: " + RI + " P-17 account 2112 is not one the engine posts for a reinsurance group")
            .contains("Journal row 11: side must be DR or CR, got 'XX'")
            .contains("Journal row 11: amount 1.001 has more than two decimals")
            .contains("Closing: group " + GROUP + " of the extract has no closing row")
            .anyMatch(p -> p.startsWith("Journal: " + GROUP + " does not balance"));
    }

    @Test
    void aWorkbookWithoutTheTemplatesSheetsSaysWhichAreMissing() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Sheet1");
            wb.write(out);
            assertThat(EngineResults.parse(out.toByteArray()).errors())
                .containsExactly("The workbook has no 'Header' sheet", "The workbook has no 'Journal' sheet",
                    "The workbook has no 'Closing' sheet");
        }
        assertThat(EngineResults.parse("not a workbook".getBytes()).errors())
            .singleElement().asString().contains("not an Excel workbook");
    }

    @Test
    void theBlankTemplateReadsBackAsEmptyButWellFormed() {
        EngineResults.Parsed parsed = EngineResults.parse(EngineResultsTemplate.blank());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.results().lines()).isEmpty();
    }

    @Test
    void aClosingRowMustMatchItsKindOfGroup() throws Exception {
        byte[] file = workbook(header("RUN-B"),
            List.of(new Object[] {GROUP, "P-05", "2111", "DR", 1, null, null},
                new Object[] {GROUP, "P-05", "4120", "CR", 1, null, null}),
            List.<Object[]>of(new Object[] {GROUP, "1", null, "1", "1", null, null}));
        assertThat(validate(file, PeriodStatus.CLOSING, List.of(GROUP), Set.of()))
            .containsExactly("Closing row 2: " + GROUP + " is a policy group: give lrc, lic and csm, not arc, aic or ri_csm");
        assertThat(new BigDecimal("1")).isNotNull();
    }
}

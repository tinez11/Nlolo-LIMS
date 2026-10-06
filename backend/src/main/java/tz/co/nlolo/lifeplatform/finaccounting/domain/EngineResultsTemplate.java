package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The blank results template an actuary fills from any engine (IFRS 17 I5a, user answer Q1): Header, Journal and
 * Closing sheets with their columns, and an Example sheet -- the guide's P-01, P-08 and P-16 -- that is never read.
 */
public final class EngineResultsTemplate {

    private EngineResultsTemplate() {}

    public static byte[] blank() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rows(wb.createSheet("Header"), List.of(
                List.of("Period", ""), List.of("Extract", ""), List.of("Engine reference", ""), List.of("Engine", ""),
                List.of("Measurement date", "")));
            rows(wb.createSheet("Journal"), List.of(EngineResults.JOURNAL_COLUMNS));
            rows(wb.createSheet("Closing"), List.of(EngineResults.CLOSING_COLUMNS));
            rows(wb.createSheet("Example"), List.of(
                List.of("Not read. Journal rows for a policy group and a reinsurance group, from the posting guide:"),
                EngineResults.JOURNAL_COLUMNS,
                List.of("TERM-GMM-2026-REM", "P-01", "2110", "DR", "150000000.00", "", "New profitable group"),
                List.of("TERM-GMM-2026-REM", "P-01", "2111", "CR", "30000000.00", "", ""),
                List.of("TERM-GMM-2026-REM", "P-01", "2112", "CR", "120000000.00", "", ""),
                List.of("TERM-GMM-2026-REM", "P-08", "2112", "DR", "14000000.00", "", "CSM released"),
                List.of("TERM-GMM-2026-REM", "P-08", "4130", "CR", "14000000.00", "", ""),
                List.of("RI-AB12CD34-2026", "P-16", "1410", "DR", "480000.00", "", "Ceded premium into the asset"),
                List.of("RI-AB12CD34-2026", "P-16", "1436", "CR", "480000.00", "", ""),
                List.of(""),
                List.of("Closing figures: lrc, lic and csm for a policy group; arc, aic and ri_csm for a reinsurance"
                    + " group (RI-...). Signs as the ledger books them, Dr - Cr: a liability is negative.")));
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rows(Sheet sheet, List<List<String>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).size(); c++) {
                row.createCell(c).setCellValue(rows.get(r).get(c));
            }
        }
    }
}

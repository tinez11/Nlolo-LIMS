package tz.co.nlolo.lifeplatform;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a lender's workbook to CSV text, once, at the edge.
 *
 * <p>Everything downstream -- validation, judging, the rejection report, every test --
 * then deals with exactly one shape. This class exists because both real client
 * schedules are .xlsx and rejecting a counterparty's file on format is a weekly argument
 * we would lose.
 *
 * <p><b>The whole hazard of accepting XLSX lives here</b>, and none of it is theoretical:
 *
 * <ul>
 *   <li>Every value is read as TEXT through {@link DataFormatter}, never
 *       {@code getNumericCellValue()}. XLSX stores numbers as doubles, and
 *       {@code 8500000.00} round-trips as {@code 8499999.999999999}.</li>
 *   <li>Dates become ISO here, because a raw serial such as {@code 46203} means
 *       2026-06-30 to Excel and nothing at all downstream.</li>
 *   <li>Numbers are emitted in plain form, so a numeric-looking account number does not
 *       arrive as {@code 4.17E+11} with its leading zeros gone.</li>
 *   <li>Rows that are entirely blank are dropped: the real LOLC sheet carries ~200 rows
 *       of formula residue below its data.</li>
 * </ul>
 */
public final class XlsxToCsv {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private XlsxToCsv() {}

    /** The first sheet of the workbook, as CSV text. */
    public static String convert(InputStream xlsx) {
        StringWriter out = new StringWriter();
        try (Workbook workbook = new XSSFWorkbook(xlsx);
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                boolean anyValue = false;
                short lastCell = row.getLastCellNum();
                for (int column = 0; column < lastCell; column++) {
                    String value = read(row.getCell(column), formatter);
                    if (!value.isBlank()) anyValue = true;
                    values.add(value);
                }
                if (!anyValue) continue; // formula residue below the data
                printer.printRecord(values);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the workbook", e);
        }
        return out.toString();
    }

    private static String read(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();

        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // A serial reaching the parser would be refused as malformed, which
                    // is safe but useless to the lender -- their column IS a date.
                    yield cell.getLocalDateTimeCellValue().toLocalDate().format(ISO);
                }
                // stripTrailingZeros THEN toPlainString, and both halves matter.
                //
                // Without the strip, a term of 48 arrives as "48.0" and is refused as not
                // a whole number of months -- found by the test, not by inspection.
                // Without toPlainString the strip alone yields 8.5E+6, and the parser
                // refuses scientific notation outright. Together they give "48" and
                // "8500000", which is what the lender typed.
                yield BigDecimal.valueOf(cell.getNumericCellValue())
                    .stripTrailingZeros().toPlainString();
            }
            case STRING -> cell.getStringCellValue().strip();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK, _NONE, ERROR -> "";
            default -> formatter.formatCellValue(cell).strip();
        };
    }
}

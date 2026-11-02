package tz.co.nlolo.lifeplatform;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
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
import java.util.function.Predicate;

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

    /** The column {@link #convertSchedule} adds, carrying each row's own number as Excel shows it. */
    public static final String SHEET_ROW = "sheet_row";

    /**
     * The first table in the workbook whose header row {@code isHeader} recognises, as CSV text (2026-10-30).
     *
     * <p>A lender's real file does not start at A1: the insurer's own template has a title, the rate and the totals
     * above the header, and the lender's has a Summary sheet first. So every sheet is searched, the first
     * {@value #HEADER_SEARCH_ROWS} rows of each, and whatever is above the header is left out.
     *
     * <p>Formula cells are worked out here when they can be -- the premium column of a template nobody has opened in
     * Excel has no saved value -- and fall back to what Excel last saved when they cannot. Nothing in the file is ever
     * run: a workbook with macros is read as cells.
     *
     * <p>Each row carries its own number in {@link #SHEET_ROW}, so a refusal names the row the lender sees.
     *
     * @return null when no sheet has a recognisable header
     */
    public static String convertSchedule(InputStream xlsx, Predicate<List<String>> isHeader) {
        StringWriter out = new StringWriter();
        try (Workbook workbook = new XSSFWorkbook(xlsx);
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                int headerRow = -1;
                for (Row row : sheet) {
                    if (row.getRowNum() >= HEADER_SEARCH_ROWS) break;
                    if (isHeader.test(values(row, formatter, evaluator))) {
                        headerRow = row.getRowNum();
                        break;
                    }
                }
                if (headerRow < 0) continue;
                for (Row row : sheet) {
                    if (row.getRowNum() < headerRow) continue;
                    List<String> values = values(row, formatter, evaluator);
                    if (values.stream().allMatch(String::isBlank)) continue;
                    List<String> record = new ArrayList<>();
                    record.add(row.getRowNum() == headerRow ? SHEET_ROW : String.valueOf(row.getRowNum() + 1));
                    record.addAll(values);
                    printer.printRecord(record);
                }
                printer.flush();
                return out.toString();
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the workbook", e);
        }
    }

    private static final int HEADER_SEARCH_ROWS = 30;

    private static List<String> values(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>();
        for (int column = 0; column < Math.max(row.getLastCellNum(), 0); column++) {
            values.add(evaluated(row.getCell(column), formatter, evaluator));
        }
        return values;
    }

    private static String evaluated(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() != CellType.FORMULA) return read(cell, formatter);
        try {
            CellValue value = evaluator.evaluate(cell);
            return switch (value.getCellType()) {
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? DateUtil.getLocalDateTime(value.getNumberValue()).toLocalDate().format(ISO)
                    : BigDecimal.valueOf(value.getNumberValue()).stripTrailingZeros().toPlainString();
                case STRING -> value.getStringValue().strip();
                case BOOLEAN -> String.valueOf(value.getBooleanValue());
                default -> "";
            };
        } catch (RuntimeException e) {
            // A function POI cannot work out: what Excel last saved is the answer.
            return read(cell, formatter);
        }
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

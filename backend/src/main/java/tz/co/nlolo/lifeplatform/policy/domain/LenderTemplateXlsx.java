package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The file a lender fills in, built to explain itself.
 *
 * <p><b>Why the file has to teach.</b> It is the only artefact that reaches the lender. There is
 * no portal and no automated delivery: a staff member downloads this and sends it by whatever
 * means they use, so everything the console knows about the format — which columns are
 * compulsory, what a date must look like, that extra columns are ignored — reaches the lender
 * only if somebody retypes it into an email. Two real files came back refused entire, and the
 * second was sent after being told the format, because the telling happened in prose and the
 * correction happened in Excel.
 *
 * <p>So the workbook carries three things a bare header row cannot:
 *
 * <ul>
 *   <li><b>An instructions sheet</b> that travels with the file and survives forwarding.</li>
 *   <li><b>Data validation on the cells themselves</b> — a real date rule on the date columns, a
 *       whole number on the term, a list on sex. Excel refuses the value as it is typed, which is
 *       the only place a lender can fix it cheaply. Every one of these rules is also enforced
 *       server-side; this is the early half, never the only half.</li>
 *   <li><b>A frozen, bold header row</b>, because a lender scrolling four hundred rows otherwise
 *       loses the columns.</li>
 * </ul>
 *
 * <p><b>Dates are typed cells, and that is the whole reason this format exists.</b> Excel rewrites
 * a date when it opens a CSV and again when it saves one; in a workbook it round-trips whatever it
 * displays, and {@link tz.co.nlolo.lifeplatform.XlsxToCsv} reads it back as ISO.
 */
public final class LenderTemplateXlsx {

    /**
     * What a borrower looks like, as the CSV template's example row needs them. The enrolment workbook is
     * {@link CreditLifeTemplateXlsx} since 2026-10-30, built from the scheme's agreement.
     */
    public record EnrolmentExample(String memberReference, String borrowerFullName,
                                    LocalDate borrowerDateOfBirth, BigDecimal loanPrincipalAmount,
                                    int loanTermMonths, LocalDate disbursementDate,
                                    String loanAccountNumber) {}

    private LenderTemplateXlsx() {}

    /**
     * The monthly exits file.
     *
     * @param exampleReference a reference that CANNOT exist -- the scheme's prefix with sequence
     *     zero. The asymmetry with the enrolment example is deliberate: an enrolment example
     *     defends itself by duplicating a covered loan, while a real reference here with a real
     *     date would take a living borrower off cover, refund their premium and claw back the
     *     commission, and every one of those looks correct downstream.
     */
    public static byte[] exits(String exampleReference, LocalDate exampleDate) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Loans ending");
            Styles styles = new Styles(workbook);
            String[] headers = ExitCsvParser.templateCsv().strip().split(",");
            writeHeader(sheet, headers, styles);

            Row row = sheet.createRow(1);
            text(row, 0, exampleReference);
            date(row, 1, exampleDate, styles);
            text(row, 2, "SETTLED_EARLY");

            int lastRow = 5000;
            DataValidationHelper helper = sheet.getDataValidationHelper();
            dateRule(sheet, helper, 1, lastRow, "Exit date",
                "The day the loan ended, typed as 2026-09-24.");
            listRule(sheet, helper, 2, lastRow,
                ExitCsvParser.lenderStateableReasons().stream().map(Enum::name).toArray(String[]::new),
                "Reason",
                "Why the loan ended. A death is not one of these -- a claim takes the borrower off cover.");
            decimalRule(sheet, helper, 3, lastRow, "Outstanding balance",
                "What was still owed, if the lender tracks it. Optional.");

            instructions(workbook, EXITS_GUIDANCE, styles);
            autoSize(sheet, headers.length);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the exits template", e);
        }
    }

    /* ------------------------------------------------------------------ guidance */

    /**
     * What the console's own column guide says, in the file.
     *
     * <p>Deliberately a copy of what a staff member reads on screen rather than a summary of it.
     * The lender and the staff member are answering the same questions about the same columns, and
     * two descriptions that drift apart is how a lender ends up told one thing and judged by
     * another.
     */
    private static final List<String[]> EXITS_GUIDANCE = List.of(
        new String[] { "", "Fill in the Loans ending sheet. One row per loan that ENDED this"
            + " month." },
        new String[] { "", "" },
        new String[] { "Required", "member_reference, exit_date, exit_reason" },
        new String[] { "Optional", "outstanding_balance_at_exit" },
        new String[] { "", "" },
        new String[] { "member_reference", "REQUIRED here, unlike the enrolment file. An exit is"
            + " always about a loan already on cover, so quote the reference from the enrolment"
            + " report." },
        new String[] { "exit_reason", "One of: SETTLED_EARLY, REFINANCED, WRITTEN_OFF, CANCELLED."
            + " A death is NOT one of these -- a claim takes the borrower off cover." },
        new String[] { "Dates", "Typed as 2026-09-24. The column is formatted for you; leave it as"
            + " a date." },
        new String[] { "", "" },
        new String[] { "A restructure", "A refinance or top-up is an exit AND a new enrolment, never"
            + " an amendment: the new loan is a different risk over a different term and earns its"
            + " own reference." },
        new String[] { "The example row", "The filled row quotes a reference ending 000000, which"
            + " belongs to nobody. It is there to show the shape; leaving it in is refused rather"
            + " than taking anybody off cover." });

    private static void instructions(Workbook workbook, List<String[]> guidance, Styles styles) {
        Sheet sheet = workbook.createSheet("How to fill this in");
        int r = 0;
        Row title = sheet.createRow(r++);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("How to fill this in");
        titleCell.setCellStyle(styles.title);
        r++;
        for (String[] line : guidance) {
            Row row = sheet.createRow(r++);
            Cell label = row.createCell(0);
            label.setCellValue(line[0]);
            label.setCellStyle(styles.header);
            Cell body = row.createCell(1);
            body.setCellValue(line[1]);
            body.setCellStyle(styles.wrapped);
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 90 * 256);
    }

    /* --------------------------------------------------------------------- sheet */

    private static void writeHeader(Sheet sheet, String[] headers, Styles styles) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }
        // Frozen, so the columns stay visible down a file of several hundred borrowers.
        sheet.createFreezePane(0, 1);
    }

    private static void autoSize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private static void text(Row row, int column, String value) {
        if (value != null && !value.isBlank()) {
            row.createCell(column).setCellValue(value);
        }
    }

    private static void date(Row row, int column, LocalDate value, Styles styles) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.date);
    }

    /* ---------------------------------------------------------------- validation */

    private static void apply(Sheet sheet, DataValidationConstraint constraint,
                               CellRangeAddressList range, String title, String message) {
        DataValidation validation = sheet.getDataValidationHelper()
            .createValidation(constraint, range);
        validation.setShowPromptBox(true);
        validation.createPromptBox(title, message);
        validation.setShowErrorBox(true);
        // The message a lender sees INSIDE Excel, at the moment they type. Worth as much care as
        // the server's refusal, because it is the one that arrives in time to be cheap to fix.
        validation.createErrorBox(title, message);
        // A WARNING rather than a hard stop: a lender pasting a whole column from their loan
        // system must not be blocked by a rule that is, in the end, advice. The server still
        // refuses the row, with a reason, so nothing unsound gets through -- this only decides
        // whether they find out now or later.
        validation.setSuppressDropDownArrow(false);
        sheet.addValidationData(validation);
    }

    private static void dateRule(Sheet sheet, DataValidationHelper helper, int column, int lastRow,
                                  String title, String message) {
        apply(sheet, helper.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                "DATE(1900,1,1)", "DATE(2100,12,31)", "yyyy-mm-dd"),
            new CellRangeAddressList(1, lastRow, column, column), title, message);
    }

    private static void listRule(Sheet sheet, DataValidationHelper helper, int column, int lastRow,
                                  String[] allowed, String title, String message) {
        apply(sheet, helper.createExplicitListConstraint(allowed),
            new CellRangeAddressList(1, lastRow, column, column), title, message);
    }

    private static void decimalRule(Sheet sheet, DataValidationHelper helper, int column,
                                     int lastRow, String title, String message) {
        apply(sheet, helper.createDecimalConstraint(
                DataValidationConstraint.OperatorType.GREATER_OR_EQUAL, "0", null),
            new CellRangeAddressList(1, lastRow, column, column), title, message);
    }

    /** The three styles the whole workbook uses, created once per workbook as POI requires. */
    private static final class Styles {
        private final CellStyle header;
        private final CellStyle date;
        private final CellStyle title;
        private final CellStyle wrapped;

        private Styles(Workbook workbook) {
            CreationHelper helper = workbook.getCreationHelper();

            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.LEFT);

            date = workbook.createCellStyle();
            // ISO in the DISPLAY as well as in the cell. The cell is a date either way; this only
            // decides what Excel shows, and showing the shape the platform stores is what a lender
            // copying values by hand needs to see.
            date.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            Font big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(big);

            wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
        }
    }
}

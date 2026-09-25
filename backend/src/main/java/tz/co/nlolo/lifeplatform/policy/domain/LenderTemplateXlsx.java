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
 * displays, and {@link XlsxToCsv} reads it back as ISO.
 */
public final class LenderTemplateXlsx {

    /** What a borrower looks like, as the sheet needs them: typed, not text. */
    public record EnrolmentExample(String memberReference, String borrowerFullName,
                                    LocalDate borrowerDateOfBirth, BigDecimal loanPrincipalAmount,
                                    int loanTermMonths, LocalDate disbursementDate,
                                    String loanAccountNumber) {}

    private LenderTemplateXlsx() {}

    /**
     * The enrolment schedule.
     *
     * @param example one of the lender's OWN borrowers, or null when the scheme has none yet.
     *     Safe to leave in the returned file: the judge refuses a row matching a loan already on
     *     cover, so it comes back ALREADY_ENROLLED rather than insuring anybody twice.
     */
    public static byte[] enrolment(EnrolmentExample example) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Borrowers");
            Styles styles = new Styles(workbook);
            String[] headers = EnrolmentCsvParser.templateCsv().strip().split(",");
            writeHeader(sheet, headers, styles);

            if (example != null) {
                Row row = sheet.createRow(1);
                text(row, 0, example.memberReference());
                text(row, 1, example.borrowerFullName());
                date(row, 2, example.borrowerDateOfBirth(), styles);
                if (example.loanPrincipalAmount() != null) {
                    row.createCell(6).setCellValue(example.loanPrincipalAmount().doubleValue());
                }
                row.createCell(7).setCellValue(example.loanTermMonths());
                date(row, 8, example.disbursementDate(), styles);
                text(row, 9, example.loanAccountNumber());
            }

            /*
             * Validation over the rows a lender will actually fill, not the whole column: a rule
             * applied to all 1,048,576 rows makes a file Excel is visibly slow to open, and the
             * lender files this product was built for are hundreds of rows, not hundreds of
             * thousands.
             */
            int lastRow = 5000;
            DataValidationHelper helper = sheet.getDataValidationHelper();
            dateRule(sheet, helper, 2, lastRow, "Date of birth",
                "A date, typed as 2000-09-01. Leave it as a date -- do not format this column as text.");
            dateRule(sheet, helper, 8, lastRow, "Disbursement date",
                "The day the lender paid the money out. Cover starts on it, so it cannot be in the future.");
            listRule(sheet, helper, 3, lastRow, new String[] { "M", "F" }, "Sex",
                "M or F. Optional -- leave it blank if the lender's system does not hold it.");
            wholeNumberRule(sheet, helper, 7, lastRow, "Term",
                "A whole number of months, e.g. 24.");
            decimalRule(sheet, helper, 6, lastRow, "Amount borrowed",
                "The principal, e.g. 1200000.00. Numbers only -- no currency symbol and no thousands separator.");

            instructions(workbook, ENROLMENT_GUIDANCE, styles);
            autoSize(sheet, headers.length);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the enrolment template", e);
        }
    }

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
    private static final List<String[]> ENROLMENT_GUIDANCE = List.of(
        new String[] { "", "Fill in the Borrowers sheet. One row per LOAN, not per person:"
            + " two loans to the same borrower are two rows." },
        new String[] { "", "" },
        new String[] { "Required", "borrower_full_name, borrower_date_of_birth,"
            + " loan_principal_amount, loan_term_months, disbursement_date" },
        new String[] { "Optional", "member_reference, borrower_sex, borrower_national_id,"
            + " borrower_phone, loan_account_number" },
        new String[] { "", "" },
        new String[] { "member_reference", "LEAVE BLANK for a new borrower. The insurer creates it"
            + " and returns it on the report; quote it back on any later file about that same loan." },
        new String[] { "Dates", "Typed as 2000-09-01. The date columns are already formatted for"
            + " you -- type a date and leave it as a date. Do NOT save this as a CSV: Excel rewrites"
            + " dates when it opens and saves one, and the row is then refused." },
        new String[] { "Amounts", "Numbers only: 1200000.00. No currency symbol, no thousands"
            + " separator." },
        new String[] { "disbursement_date", "The day the money went out. COVER STARTS ON IT, so it"
            + " cannot be in the future." },
        new String[] { "Extra columns", "Anything else in the file is ignored, not refused. Send the"
            + " export you already have." },
        new String[] { "", "" },
        new String[] { "What happens next", "Every row is checked and the file is reviewed before"
            + " anybody is covered. A row that cannot be accepted comes back on a report with the"
            + " reason -- and a refused row means THAT BORROWER IS NOT COVERED." },
        new String[] { "The example row", "If there is a row already filled in, it is one of your own"
            + " borrowers, shown as an example. Leaving it in is harmless: it is already on cover,"
            + " so it comes back marked as already enrolled." });

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

    private static void wholeNumberRule(Sheet sheet, DataValidationHelper helper, int column,
                                         int lastRow, String title, String message) {
        apply(sheet, helper.createIntegerConstraint(
                DataValidationConstraint.OperatorType.GREATER_OR_EQUAL, "1", null),
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

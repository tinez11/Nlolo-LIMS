package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The credit-life file a lender fills in for loans that ended early (2026-11-01): repaid, refinanced, written off or
 * cancelled. The sibling of {@link CreditLifeTemplateXlsx}, and laid out like it -- a navy title band, a line saying
 * what happens to each loan, a row of totals, then the loans, typed in white columns with a grey Check column that
 * names what is wrong before the file is sent.
 *
 * <p>A loan is named by the lender's own account number, the number on the file that insured it. The member reference
 * our acceptance report gives back is still taken, for a loan that had no account number.
 *
 * <p>Read back by {@link tz.co.nlolo.lifeplatform.XlsxToCsv#convertSchedule}, which finds the header row wherever it
 * is, and by {@link ExitCsvParser}, which knows these column names.
 */
public final class CreditLifeExitsTemplateXlsx {

    public static final String SHEET = "Loans ending";
    public static final String GUIDE = "How to fill this in";

    public static final String ACCOUNT = "Loan account no.";
    public static final String NAME = "Borrower's full name";
    public static final String ENDED = "Date the loan ended";
    public static final String WHY = "Why it ended";
    public static final String BALANCE = "Balance still owed";
    public static final String REFERENCE = "Member reference";
    public static final String CHECK = "Check";

    private static final int A = 0, B = 1, C = 2, D = 3, E = 4, F = 5, G = 6;
    private static final int LAST_COLUMN = G;

    /** Row 7 in Excel, as on the joining file: room above for the title and the totals. */
    public static final int HEADER_ROW = 6;
    private static final int FIRST_DATA = HEADER_ROW + 1;
    static final int DATA_ROWS = 500;
    private static final int LAST_DATA = FIRST_DATA + DATA_ROWS - 1;

    private CreditLifeExitsTemplateXlsx() {}

    /** The reasons a lender may state, as the list in the file offers them: "Settled early", never CLAIM_SETTLED. */
    static List<String> reasons() {
        List<String> words = new ArrayList<>();
        for (ExitReason reason : ExitCsvParser.lenderStateableReasons()) {
            String plain = reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            words.add(Character.toUpperCase(plain.charAt(0)) + plain.substring(1));
        }
        return words;
    }

    public static byte[] build(String lenderName, String policyNumber, String currency) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CreditLifeTemplateXlsx.Styles styles = new CreditLifeTemplateXlsx.Styles(workbook);
            XSSFSheet sheet = workbook.createSheet(SHEET);

            top(sheet, lenderName, policyNumber, styles);
            header(sheet, styles);
            body(sheet, styles);
            validation(sheet, currency);
            checkColours(sheet, styles);
            int[] chars = { 18, 30, 16, 16, 18, 24, 40 };
            for (int c = 0; c < chars.length; c++) {
                sheet.setColumnWidth(c, chars[c] * 256);
            }

            sheet.createFreezePane(0, FIRST_DATA);
            sheet.setDisplayGridlines(false);
            PrintSetup print = sheet.getPrintSetup();
            print.setLandscape(true);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));

            // Not protected, as on the joining file: a pasted block carries locked formatting and could not be
            // corrected after (2026-11-02).

            guide(workbook, lenderName, policyNumber, currency, styles);
            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the loans-ending template", e);
        }
    }

    /* ---------------------------------------------------------------- the top */

    private static void top(XSSFSheet sheet, String lenderName, String policyNumber,
                            CreditLifeTemplateXlsx.Styles styles) {
        CreditLifeTemplateXlsx.band(sheet, 0, "Credit life: loans that ended early", styles.title, 30, LAST_COLUMN);
        CreditLifeTemplateXlsx.band(sheet, 1, CreditLifeTemplateXlsx.nonBlank(lenderName, "Lender")
            + "  ·  Scheme " + policyNumber, styles.subtitle, 20, LAST_COLUMN);
        CreditLifeTemplateXlsx.band(sheet, 2, "Each loan comes off cover on the day it ended, and the part of its"
            + " premium not yet used comes back to you.", styles.rule, 34, LAST_COLUMN);

        Row labels = sheet.createRow(3);
        Row values = sheet.createRow(4);
        labels.setHeightInPoints(18);
        values.setHeightInPoints(26);
        String check = "G" + (FIRST_DATA + 1) + ":G" + (LAST_DATA + 1);
        CreditLifeTemplateXlsx.card(sheet, labels, values, A, "Loans ending (Check OK)",
            "COUNTIF(" + check + ",\"OK\")", styles.cardCount, styles);
        // Any text but OK: the Check column is blank on a row nobody typed in.
        CreditLifeTemplateXlsx.card(sheet, labels, values, C, "Rows to fix",
            "COUNTIF(" + check + ",\"?*\")-COUNTIF(" + check + ",\"OK\")", styles.cardCount, styles);

        CreditLifeTemplateXlsx.band(sheet, 5, "Type in the white columns, one row for each loan that ended. Every"
            + " row's Check should say OK before you send the file. A borrower who died is not sent here: tell us,"
            + " and it is handled as a claim.", styles.hint, 30, LAST_COLUMN);
    }

    /* -------------------------------------------------------------- the table */

    private static void header(XSSFSheet sheet, CreditLifeTemplateXlsx.Styles styles) {
        Row row = sheet.createRow(HEADER_ROW);
        row.setHeightInPoints(32);
        String[] typed = { ACCOUNT, NAME, ENDED, WHY, BALANCE, REFERENCE };
        for (int c = 0; c < typed.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(typed[c]);
            cell.setCellStyle(styles.header);
        }
        Cell check = row.createCell(G);
        check.setCellValue(CHECK);
        check.setCellStyle(styles.headerFilled);
    }

    private static void body(XSSFSheet sheet, CreditLifeTemplateXlsx.Styles styles) {
        String check = checkFormula();
        for (int r = FIRST_DATA; r <= LAST_DATA; r++) {
            Row row = sheet.createRow(r);
            row.createCell(A).setCellStyle(styles.inputText);
            row.createCell(B).setCellStyle(styles.inputText);
            row.createCell(C).setCellStyle(styles.inputDate);
            row.createCell(D).setCellStyle(styles.inputText);
            row.createCell(E).setCellStyle(styles.inputMoney);
            row.createCell(F).setCellStyle(styles.inputText);
            Cell verdict = row.createCell(G);
            verdict.setCellFormula(check.replace("#", String.valueOf(r + 1)));
            verdict.setCellStyle(styles.filledText);
        }
    }

    /** What is wrong with row {@code #}, first thing first, or OK -- the same order the platform reads a row in. */
    static String checkFormula() {
        String[][] tests = {
            { "AND(A#=\"\",F#=\"\")", "Give the loan account no." },
            { "C#=\"\"", "Fill in the date the loan ended" },
            { "C#>TODAY()", "That date has not come yet" },
            { "D#=\"\"", "Choose why it ended" },
        };
        StringBuilder formula = new StringBuilder("IF(COUNTA(A#:F#)=0,\"\",");
        for (String[] test : tests) {
            formula.append("IF(").append(test[0]).append(",\"").append(test[1]).append("\",");
        }
        formula.append("\"OK\"").append(")".repeat(tests.length)).append(")");
        return formula.toString();
    }

    /* ------------------------------------------------------------ validation */

    private static void validation(XSSFSheet sheet, String currency) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        rule(sheet, helper.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                "DATE(2000,1,1)", "TODAY()", null), C, ENDED,
            "The day it was repaid, refinanced, written off or cancelled. Type it the way your Excel takes dates,"
                + " e.g. 30/09/2026.", false);
        rule(sheet, helper.createExplicitListConstraint(reasons().toArray(String[]::new)), D, WHY,
            "Choose from the list: " + String.join(", ", reasons()) + ".", true);
        rule(sheet, helper.createDecimalConstraint(DataValidationConstraint.OperatorType.GREATER_OR_EQUAL, "0", null),
            E, BALANCE, "Optional: what was still owed when it ended, in " + currency + ". Numbers only.", false);
    }

    private static void rule(XSSFSheet sheet, DataValidationConstraint constraint, int column, String title,
                             String message, boolean list) {
        DataValidation validation = sheet.getDataValidationHelper()
            .createValidation(constraint, new CellRangeAddressList(FIRST_DATA, LAST_DATA, column, column));
        validation.setShowPromptBox(true);
        validation.createPromptBox(title, message);
        validation.setShowErrorBox(true);
        validation.createErrorBox(title, message);
        // A warning, not a stop, as on the joining file: a pasted block must not be blocked by advice.
        validation.setErrorStyle(DataValidation.ErrorStyle.WARNING);
        validation.setSuppressDropDownArrow(list);
        sheet.addValidationData(validation);
    }

    private static void checkColours(XSSFSheet sheet, CreditLifeTemplateXlsx.Styles styles) {
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule ok = formatting.createConditionalFormattingRule(ComparisonOperator.EQUAL, "\"OK\"");
        ok.createFontFormatting().setFontColor(styles.okInk);
        ok.createPatternFormatting().setFillBackgroundColor(styles.okGround);
        String first = "G" + (FIRST_DATA + 1);
        ConditionalFormattingRule wrong = formatting.createConditionalFormattingRule(
            "AND(" + first + "<>\"\"," + first + "<>\"OK\")");
        wrong.createFontFormatting().setFontColor(styles.badInk);
        wrong.getFontFormatting().setFontStyle(false, true);
        wrong.createPatternFormatting().setFillBackgroundColor(styles.badGround);
        formatting.addConditionalFormatting(
            new CellRangeAddress[] { new CellRangeAddress(FIRST_DATA, LAST_DATA, G, G) }, ok, wrong);
    }

    /* ------------------------------------------------------------- the guide */

    private static void guide(XSSFWorkbook workbook, String lenderName, String policyNumber, String currency,
                              CreditLifeTemplateXlsx.Styles styles) {
        XSSFSheet sheet = workbook.createSheet(GUIDE);
        sheet.setDisplayGridlines(false);
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 92 * 256);
        int r = 0;
        Row title = sheet.createRow(r++);
        title.setHeightInPoints(30);
        title.createCell(0).setCellValue(GUIDE);
        title.getCell(0).setCellStyle(styles.title);
        title.createCell(1).setCellStyle(styles.title);
        r++;

        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] { "This file", "The loans " + CreditLifeTemplateXlsx.nonBlank(lenderName, "you")
            + " made under scheme " + policyNumber + " that ended before their term: repaid early, refinanced,"
            + " written off or cancelled. One row for each loan." });
        lines.add(null);
        lines.add(new String[] { ACCOUNT, "Your number for the loan, as it was on the file that insured it. This is"
            + " how we find the loan." });
        lines.add(new String[] { NAME, "Optional, for your own reading. We find the loan by its account number." });
        lines.add(new String[] { ENDED, "The day the loan was repaid, refinanced, written off or cancelled. Cover"
            + " ends that day." });
        lines.add(new String[] { WHY, "Choose from the list: " + String.join(", ", reasons()) + "." });
        lines.add(new String[] { BALANCE, "Optional: what was still owed when it ended, in " + currency + ", if you"
            + " keep it." });
        lines.add(new String[] { REFERENCE, "Only for a loan that had no account number: the reference on our"
            + " acceptance report, for example CL-1234ABCD-000123." });
        lines.add(null);
        lines.add(new String[] { "A borrower who died", "Is not sent here. Tell us, and the loan is handled as a"
            + " claim: it pays what was still owed." });
        lines.add(new String[] { CHECK, "Says OK when the row is right, or what is wrong with it. A row that is not"
            + " OK will be refused, and THAT LOAN STAYS ON COVER." });
        lines.add(null);
        lines.add(new String[] { "Sending it", "Save it as an Excel file and send it as it is. Extra columns are"
            + " ignored." });
        lines.add(new String[] { "What happens next", "We find every loan and take it off cover from the day it"
            + " ended. The part of its premium not yet used comes back to you: taken off the file's premium if you"
            + " have not paid it yet, or paid back to you if you have." });

        for (String[] line : lines) {
            Row row = sheet.createRow(r++);
            if (line == null) {
                continue;
            }
            Cell label = row.createCell(0);
            label.setCellValue(line[0]);
            label.setCellStyle(styles.guideLabel);
            Cell body = row.createCell(1);
            body.setCellValue(line[1]);
            body.setCellStyle(styles.guideBody);
        }
    }
}

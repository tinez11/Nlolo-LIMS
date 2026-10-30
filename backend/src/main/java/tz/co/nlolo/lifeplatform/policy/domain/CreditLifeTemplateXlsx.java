package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ComparisonOperator;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The credit-life file a lender fills in, built from the scheme's agreement (2026-10-30).
 *
 * <p>The lender's own template carried the premium formula so the person typing knew each borrower's premium as they
 * typed; this one does the same from the agreement itself, so the rate, the minimum and the limits in the file are the
 * ones the platform will judge it by. Each row checks itself, in a Check column that names what is wrong, before the
 * file is ever sent.
 *
 * <p>Laid out the way the console looks: a navy title band, the price in words, a row of totals, then the borrowers.
 * The columns the lender types are white and come first, so a block pasted from their loan system lands in one go; the
 * columns that fill themselves are grey and locked.
 *
 * <p>Read back by {@link tz.co.nlolo.lifeplatform.XlsxToCsv#convertSchedule}, which finds the header row wherever it
 * is, and by {@link EnrolmentCsvParser}, which knows these column names.
 */
public final class CreditLifeTemplateXlsx {

    /** What the file is built from: the agreement in force, or the scheme's own rate when it has none. */
    public record Terms(String lenderName, String policyNumber, String currency,
                        LocalDate validFrom, LocalDate validTo,
                        BigDecimal ratePercent, CreditLifePremiumBasis basis, BigDecimal minimumPremium,
                        Integer maxAge, Integer minTermMonths, Integer maxTermMonths,
                        BigDecimal commissionPercent) {}

    public static final String SHEET = "Borrowers";
    public static final String GUIDE = "How to fill this in";

    // The columns. Typed first and together, so a block pasted from a loan system lands in one go.
    public static final String NAME = "Borrower's full name";
    public static final String BORN = "Date of birth";
    public static final String SEX = "Sex";
    public static final String AMOUNT = "Loan amount";
    public static final String TERM = "Loan term (months)";
    public static final String START = "Loan start date";
    public static final String ACCOUNT = "Loan account no.";
    public static final String NATIONAL_ID = "National ID";
    public static final String PHONE = "Phone";
    public static final String END = "Loan end date";
    public static final String PREMIUM = "Premium";
    public static final String CHECK = "Check";

    private static final int A = 0, B = 1, C = 2, D = 3, E = 4, F = 5, G = 6, H = 7, I = 8, J = 9, K = 10, L = 11;
    private static final int LAST_COLUMN = L;

    /** Row 7 in Excel: room above for the title, the price and the totals. */
    public static final int HEADER_ROW = 6;
    private static final int FIRST_DATA = HEADER_ROW + 1;
    /** Enough for a busy month; validation on a whole column makes Excel slow to open. */
    static final int DATA_ROWS = 1000;
    private static final int LAST_DATA = FIRST_DATA + DATA_ROWS - 1;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private CreditLifeTemplateXlsx() {}

    public static byte[] build(Terms terms) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            XSSFSheet sheet = workbook.createSheet(SHEET);

            top(sheet, terms, styles);
            header(sheet, styles);
            body(sheet, terms, styles);
            validation(sheet, terms);
            checkColours(sheet, styles);
            widths(sheet);

            sheet.createFreezePane(0, FIRST_DATA);
            sheet.setDisplayGridlines(false);
            PrintSetup print = sheet.getPrintSetup();
            print.setLandscape(true);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            sheet.setFitToPage(true);
            sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));

            // Locks the grey columns against an accidental overwrite. No password: this protects a formula from a
            // slip, not a secret from anybody, and the server prices every row again whatever the file says.
            sheet.protectSheet("");
            sheet.lockFormatColumns(false);
            sheet.lockFormatRows(false);
            sheet.lockFormatCells(false);
            sheet.lockSelectLockedCells(false);
            sheet.lockSelectUnlockedCells(false);

            guide(workbook, terms, styles);
            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the credit-life template", e);
        }
    }

    /* ---------------------------------------------------------------- the top */

    private static void top(XSSFSheet sheet, Terms terms, Styles styles) {
        band(sheet, 0, "Credit life: new loans to insure", styles.title, 30);
        String period = terms.validFrom() == null ? ""
            : "  ·  Agreement " + DAY.format(terms.validFrom())
                + (terms.validTo() == null ? "" : " to " + DAY.format(terms.validTo()));
        band(sheet, 1, nonBlank(terms.lenderName(), "Lender") + "  ·  Scheme " + terms.policyNumber() + period,
            styles.subtitle, 20);
        band(sheet, 2, priceInWords(terms), styles.rule, 34);

        Row labels = sheet.createRow(3);
        Row values = sheet.createRow(4);
        labels.setHeightInPoints(18);
        values.setHeightInPoints(26);
        // Only the rows that say OK: a row that is not will be refused, so counting it would quote a premium nobody
        // will be charged.
        String data = "%s" + (FIRST_DATA + 1) + ":%s" + (LAST_DATA + 1);
        String ok = data.formatted("L", "L") + ",\"OK\"";
        card(sheet, labels, values, A, "Borrowers (Check OK)", "COUNTIF(" + ok + ")", styles.cardCount, styles);
        card(sheet, labels, values, C, "Total loan amount", "SUMIF(" + ok + "," + data.formatted("D", "D") + ")",
            styles.cardMoney, styles);
        card(sheet, labels, values, E, "Total premium", "SUMIF(" + ok + "," + data.formatted("K", "K") + ")",
            styles.cardMoney, styles);
        if (terms.commissionPercent() != null && terms.commissionPercent().signum() > 0) {
            card(sheet, labels, values, G, "Your commission (" + percent(terms.commissionPercent()) + ")",
                "ROUND(E5*" + terms.commissionPercent().toPlainString() + "/100,2)", styles.cardMoney, styles);
        } else {
            card(sheet, labels, values, G, "Your commission", null, styles.cardMoney, styles);
        }
        card(sheet, labels, values, I, "Amount to pay", "E5", styles.cardMoney, styles);
        // The rate the premium column multiplies by, shown rather than buried in a formula.
        card(sheet, labels, values, K, "Rate per year", null, styles.cardRate, styles);
        values.getCell(K).setCellValue(terms.ratePercent().movePointLeft(2).doubleValue());

        band(sheet, 5, "Type in the white columns, one row for each loan. The grey columns fill themselves."
            + " Every row's Check should say OK before you send the file; the totals count only those rows. You pay"
            + " the full premium;"
            + " your commission is paid to you after it is received.", styles.hint, 30);
    }

    private static void band(XSSFSheet sheet, int rowIndex, String text, CellStyle style, float height) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(height);
        for (int c = A; c <= LAST_COLUMN; c++) {
            row.createCell(c).setCellStyle(style);
        }
        row.getCell(A).setCellValue(text);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, A, LAST_COLUMN));
    }

    private static void card(XSSFSheet sheet, Row labels, Row values, int column, String label, String formula,
                             CellStyle valueStyle, Styles styles) {
        for (int c = column; c <= column + 1; c++) {
            labels.createCell(c).setCellStyle(styles.cardLabel);
            values.createCell(c).setCellStyle(valueStyle);
        }
        labels.getCell(column).setCellValue(label);
        if (formula != null) {
            values.getCell(column).setCellFormula(formula);
        }
        sheet.addMergedRegion(new CellRangeAddress(labels.getRowNum(), labels.getRowNum(), column, column + 1));
        sheet.addMergedRegion(new CellRangeAddress(values.getRowNum(), values.getRowNum(), column, column + 1));
    }

    /* -------------------------------------------------------------- the table */

    private static void header(XSSFSheet sheet, Styles styles) {
        Row row = sheet.createRow(HEADER_ROW);
        row.setHeightInPoints(32);
        String[] typed = { NAME, BORN, SEX, AMOUNT, TERM, START, ACCOUNT, NATIONAL_ID, PHONE };
        for (int c = 0; c < typed.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(typed[c]);
            cell.setCellStyle(styles.header);
        }
        String[] filled = { END, PREMIUM, CHECK };
        for (int c = 0; c < filled.length; c++) {
            Cell cell = row.createCell(J + c);
            cell.setCellValue(filled[c]);
            cell.setCellStyle(styles.headerFilled);
        }
    }

    private static void body(XSSFSheet sheet, Terms terms, Styles styles) {
        String premium = premiumFormula(terms);
        String check = checkFormula(terms);
        for (int r = FIRST_DATA; r <= LAST_DATA; r++) {
            Row row = sheet.createRow(r);
            int n = r + 1;
            row.createCell(A).setCellStyle(styles.inputText);
            row.createCell(B).setCellStyle(styles.inputDate);
            row.createCell(C).setCellStyle(styles.inputCentre);
            row.createCell(D).setCellStyle(styles.inputMoney);
            row.createCell(E).setCellStyle(styles.inputWhole);
            row.createCell(F).setCellStyle(styles.inputDate);
            row.createCell(G).setCellStyle(styles.inputText);
            row.createCell(H).setCellStyle(styles.inputText);
            row.createCell(I).setCellStyle(styles.inputText);

            Cell end = row.createCell(J);
            end.setCellFormula(("IF(OR(F#=\"\",E#=\"\"),\"\",EDATE(F#,E#)-1)").replace("#", String.valueOf(n)));
            end.setCellStyle(styles.filledDate);
            Cell price = row.createCell(K);
            price.setCellFormula(premium.replace("#", String.valueOf(n)));
            price.setCellStyle(styles.filledMoney);
            Cell verdict = row.createCell(L);
            verdict.setCellFormula(check.replace("#", String.valueOf(n)));
            verdict.setCellStyle(styles.filledText);
        }
    }

    /**
     * The agreement's pricing rule as an Excel formula over row {@code #}, mirroring {@link CreditLifePremium}: the
     * same rule, rounded once to the cent, never below the minimum. Blank until the amount and the term are typed.
     */
    static String premiumFormula(Terms terms) {
        String rate = "$K$5";
        String years = "ROUNDUP(E#/12,0)";
        String raw = switch (terms.basis()) {
            case FLAT_ON_PRINCIPAL -> "D#*" + rate;
            case PER_ANNUM_ON_PRINCIPAL -> "D#*" + rate + "*E#/12";
            case WHOLE_YEARS_ROUNDED_UP -> "D#*" + rate + "*" + years;
            // One charge per started year on the balance outstanding at its start, straight-line: the years' shares
            // of the term sum to n*T - 6*n*(n-1) months, n being the started years.
            case ANNUAL_ON_DECLINING_BALANCE -> "D#*" + rate + "*(" + years + "*E#-6*" + years + "*(" + years
                + "-1))/E#";
        };
        String rounded = "ROUND(" + raw + ",2)";
        if (terms.minimumPremium() != null && terms.minimumPremium().signum() > 0) {
            rounded = "MAX(" + terms.minimumPremium().toPlainString() + "," + rounded + ")";
        }
        return "IF(OR(D#=\"\",E#=\"\"),\"\"," + rounded + ")";
    }

    /** What is wrong with row {@code #}, first thing first, or OK -- the same order the platform judges in. */
    static String checkFormula(Terms terms) {
        List<String[]> tests = new ArrayList<>();
        tests.add(new String[] { "OR(A#=\"\",B#=\"\",D#=\"\",E#=\"\",F#=\"\")",
            "Fill in name, date of birth, amount, term and start date" });
        tests.add(new String[] { "F#>TODAY()", "The loan has not started yet" });
        if (terms.validFrom() != null) {
            tests.add(new String[] { "F#<" + excelDate(terms.validFrom()),
                "Started before the agreement (" + DAY.format(terms.validFrom()) + ")" });
        }
        if (terms.validTo() != null) {
            tests.add(new String[] { "F#>" + excelDate(terms.validTo()),
                "Started after the agreement ended (" + DAY.format(terms.validTo()) + ")" });
        }
        if (terms.maxAge() != null) {
            // Whole years at the loan's start, as the platform counts them: a birthday not yet reached that year takes
            // one off. Not DATEDIF, which the upload's reader cannot work out.
            tests.add(new String[] { "YEAR(F#)-YEAR(B#)-IF(DATE(YEAR(F#),MONTH(B#),DAY(B#))>F#,1,0)>" + terms.maxAge(),
                "Older than " + terms.maxAge() + " when the loan started" });
        }
        if (terms.minTermMonths() != null) {
            tests.add(new String[] { "E#<" + terms.minTermMonths(),
                "Shorter than " + terms.minTermMonths() + " months" });
        }
        if (terms.maxTermMonths() != null) {
            tests.add(new String[] { "E#>" + terms.maxTermMonths(),
                "Longer than " + terms.maxTermMonths() + " months" });
        }
        StringBuilder formula = new StringBuilder("IF(COUNTA(A#:F#)=0,\"\",");
        for (String[] test : tests) {
            formula.append("IF(").append(test[0]).append(",\"").append(test[1]).append("\",");
        }
        formula.append("\"OK\"").append(")".repeat(tests.size())).append(")");
        return formula.toString();
    }

    private static String excelDate(LocalDate day) {
        return "DATE(" + day.getYear() + "," + day.getMonthValue() + "," + day.getDayOfMonth() + ")";
    }

    /* ------------------------------------------------------------ validation */

    private static void validation(XSSFSheet sheet, Terms terms) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        rule(sheet, helper.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                "DATE(1900,1,1)", "TODAY()", null), B, BORN,
            "Type it the way your Excel takes dates, e.g. 14/03/1988. It shows as 14 Mar 1988: check the month.");
        rule(sheet, helper.createExplicitListConstraint(new String[] { "M", "F" }), C, SEX,
            "M or F. You may leave it blank.");
        rule(sheet, helper.createDecimalConstraint(DataValidationConstraint.OperatorType.GREATER_THAN, "0", null), D,
            AMOUNT, "What was lent, in " + terms.currency() + ". Numbers only, e.g. 1500000.");
        int min = terms.minTermMonths() == null ? 1 : terms.minTermMonths();
        int max = terms.maxTermMonths() == null ? 480 : terms.maxTermMonths();
        rule(sheet, helper.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN,
                String.valueOf(min), String.valueOf(max)), E, TERM,
            "Whole months, from " + min + " to " + max + ". A one-year loan is 12.");
        String from = terms.validFrom() == null ? "DATE(1900,1,1)" : excelDate(terms.validFrom());
        String to = "TODAY()";
        rule(sheet, helper.createDateConstraint(DataValidationConstraint.OperatorType.BETWEEN, from, to, null), F,
            START, "The day the money was paid out. Cover starts that day, so it cannot be in the future"
                + (terms.validFrom() == null ? "." : ", or before " + DAY.format(terms.validFrom()) + "."));
    }

    private static void rule(XSSFSheet sheet, DataValidationConstraint constraint, int column, String title,
                             String message) {
        DataValidation validation = sheet.getDataValidationHelper()
            .createValidation(constraint, new CellRangeAddressList(FIRST_DATA, LAST_DATA, column, column));
        validation.setShowPromptBox(true);
        validation.createPromptBox(title, message);
        validation.setShowErrorBox(true);
        validation.createErrorBox(title, message);
        // A warning, not a stop: a block pasted from a loan system must not be blocked by advice. The Check column and
        // the platform still say what is wrong.
        validation.setErrorStyle(DataValidation.ErrorStyle.WARNING);
        validation.setSuppressDropDownArrow(column == C);
        sheet.addValidationData(validation);
    }

    private static void checkColours(XSSFSheet sheet, Styles styles) {
        SheetConditionalFormatting formatting = sheet.getSheetConditionalFormatting();
        ConditionalFormattingRule ok = formatting.createConditionalFormattingRule(ComparisonOperator.EQUAL, "\"OK\"");
        ok.createFontFormatting().setFontColor(styles.okInk);
        ok.createPatternFormatting().setFillBackgroundColor(styles.okGround);
        String first = "L" + (FIRST_DATA + 1);
        ConditionalFormattingRule wrong = formatting.createConditionalFormattingRule(
            "AND(" + first + "<>\"\"," + first + "<>\"OK\")");
        wrong.createFontFormatting().setFontColor(styles.badInk);
        wrong.getFontFormatting().setFontStyle(false, true);
        wrong.createPatternFormatting().setFillBackgroundColor(styles.badGround);
        formatting.addConditionalFormatting(
            new CellRangeAddress[] { new CellRangeAddress(FIRST_DATA, LAST_DATA, L, L) }, ok, wrong);
    }

    private static void widths(XSSFSheet sheet) {
        int[] chars = { 30, 14, 7, 16, 11, 14, 16, 16, 15, 14, 14, 40 };
        for (int c = 0; c < chars.length; c++) {
            sheet.setColumnWidth(c, chars[c] * 256);
        }
    }

    /* ------------------------------------------------------------- the guide */

    private static void guide(XSSFWorkbook workbook, Terms terms, Styles styles) {
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

        BigDecimal example = new BigDecimal("10000000");
        int exampleTerm = 18;
        BigDecimal examplePremium = CreditLifePremium.forLoan(example, exampleTerm, terms.ratePercent(), terms.basis(),
            terms.minimumPremium());

        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] { "This file", "The new loans " + nonBlank(terms.lenderName(), "you")
            + " made that should be insured under scheme " + terms.policyNumber() + ". One row for each loan:"
            + " two loans to one person are two rows." });
        lines.add(new String[] { "The price", priceInWords(terms) });
        lines.add(new String[] { "For example", "A loan of " + terms.currency() + " " + money(example) + " for "
            + exampleTerm + " months pays " + terms.currency() + " " + money(examplePremium) + "." });
        lines.add(null);
        lines.add(new String[] { NAME, "As on the loan agreement." });
        lines.add(new String[] { BORN, "Type it the way your Excel normally takes a date, for example 14/03/1988."
            + " It shows as 14 Mar 1988, so you can see the month was read right." });
        lines.add(new String[] { SEX, "M or F. You may leave it blank." });
        lines.add(new String[] { AMOUNT, "What was lent, in " + terms.currency() + ". Numbers only, no currency"
            + " sign. This is the amount insured." });
        lines.add(new String[] { TERM, "How long the loan runs, in whole months"
            + (terms.minTermMonths() == null ? "" : ", at least " + terms.minTermMonths())
            + (terms.maxTermMonths() == null ? "" : " and at most " + terms.maxTermMonths()) + ". A one-year loan"
            + " is 12." });
        lines.add(new String[] { START, "The day the money was paid out. Cover starts that day." });
        lines.add(new String[] { ACCOUNT + ", " + NATIONAL_ID + ", " + PHONE, "Optional, but they help us find"
            + " the right borrower if there is ever a claim." });
        lines.add(null);
        lines.add(new String[] { "Grey columns", END + ", " + PREMIUM + " and " + CHECK + " fill themselves and"
            + " cannot be typed over." });
        lines.add(new String[] { CHECK, "Says OK when the row is right, or what is wrong with it. A row that is not"
            + " OK will be refused, and THAT BORROWER IS NOT COVERED." });
        lines.add(new String[] { "The totals", "At the top of the Borrowers sheet: how many borrowers, the total"
            + " loan amount, the premium, your commission and the amount to pay -- counting only the rows whose"
            + " Check says OK." });
        lines.add(null);
        lines.add(new String[] { "Sending it", "Save it as an Excel file and send it as it is. Do not save it as"
            + " CSV: dates are lost that way. Extra columns are ignored." });
        lines.add(new String[] { "What happens next", "We check every row against the agreement and send a"
            + " quotation: the borrowers, the premium and your commission. You pay the full premium; your"
            + " commission is paid to you after it is received." });

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

    /* --------------------------------------------------------------- wording */

    /** The agreement's price and limits as one sentence a lender can check against what they signed. */
    static String priceInWords(Terms terms) {
        String rate = percent(terms.ratePercent());
        String rule = switch (terms.basis()) {
            case WHOLE_YEARS_ROUNDED_UP -> "Premium = loan amount × " + rate + " for each year the loan runs;"
                + " part of a year counts as a full year";
            case PER_ANNUM_ON_PRINCIPAL -> "Premium = loan amount × " + rate + " a year, for the loan's months";
            case FLAT_ON_PRINCIPAL -> "Premium = loan amount × " + rate + ", once, whatever the term";
            case ANNUAL_ON_DECLINING_BALANCE -> "Premium = " + rate + " a year on what is still owed at the start of"
                + " each year";
        };
        StringBuilder words = new StringBuilder(rule);
        if (terms.minimumPremium() != null && terms.minimumPremium().signum() > 0) {
            words.append(", and at least ").append(terms.currency()).append(" ").append(money(terms.minimumPremium()));
        }
        words.append(".");
        if (terms.minTermMonths() != null && terms.maxTermMonths() != null) {
            words.append(" Loans of ").append(terms.minTermMonths()).append(" to ").append(terms.maxTermMonths())
                .append(" months.");
        }
        if (terms.maxAge() != null) {
            words.append(" Borrowers up to ").append(terms.maxAge()).append(" years old when the loan starts.");
        }
        return words.toString();
    }

    private static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.##").format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /* ---------------------------------------------------------------- styles */

    /** The console's own colours (index.css), so the file looks like the system it came from. */
    private static final class Styles {
        final XSSFColor navy, teal, ink, muted, ground, tint, line, okInk, okGround, badInk, badGround;
        final CellStyle title, subtitle, rule, hint, cardLabel, cardCount, cardMoney, cardRate, header, headerFilled;
        final CellStyle inputText, inputCentre, inputDate, inputMoney, inputWhole;
        final CellStyle filledDate, filledMoney, filledText, guideLabel, guideBody;

        Styles(XSSFWorkbook workbook) {
            navy = colour("16323e");
            teal = colour("007c77");
            ink = colour("0a1d26");
            muted = colour("55666f");
            ground = colour("eff5f5");
            tint = colour("e2f7f5");
            line = colour("c5d0d2");
            okInk = colour("07602f");
            okGround = colour("dbf7e0");
            badInk = colour("a92227");
            badGround = colour("ffe6e4");
            short dates = workbook.getCreationHelper().createDataFormat().getFormat("d mmm yyyy");
            short amounts = workbook.getCreationHelper().createDataFormat().getFormat("#,##0.00");
            short whole = workbook.getCreationHelper().createDataFormat().getFormat("#,##0");
            short rate = workbook.getCreationHelper().createDataFormat().getFormat("0.0###%");

            title = fill(workbook, navy, font(workbook, 16, true, colour("ffffff")));
            subtitle = fill(workbook, navy, font(workbook, 11, false, colour("bfe9e4")));
            rule = fill(workbook, tint, font(workbook, 11, true, ink));
            rule.setWrapText(true);
            hint = plain(workbook, font(workbook, 10, false, muted));
            hint.setWrapText(true);

            cardLabel = fill(workbook, ground, font(workbook, 9, false, muted));
            cardCount = fill(workbook, ground, font(workbook, 15, true, ink));
            cardCount.setDataFormat(whole);
            cardMoney = fill(workbook, ground, font(workbook, 15, true, ink));
            cardMoney.setDataFormat(amounts);
            cardRate = fill(workbook, ground, font(workbook, 15, true, teal));
            cardRate.setDataFormat(rate);
            for (CellStyle s : List.of(cardLabel, cardCount, cardMoney, cardRate)) {
                s.setAlignment(HorizontalAlignment.LEFT);
                s.setIndention((short) 1);
            }

            header = fill(workbook, navy, font(workbook, 10, true, colour("ffffff")));
            headerFilled = fill(workbook, teal, font(workbook, 10, true, colour("ffffff")));
            for (CellStyle s : List.of(header, headerFilled)) {
                s.setWrapText(true);
                s.setVerticalAlignment(VerticalAlignment.CENTER);
            }

            XSSFFont body = font(workbook, 11, false, ink);
            inputText = input(workbook, body);
            inputText.setIndention((short) 1);
            inputCentre = input(workbook, body);
            inputCentre.setAlignment(HorizontalAlignment.CENTER);
            inputDate = input(workbook, body);
            inputDate.setDataFormat(dates);
            inputMoney = input(workbook, body);
            inputMoney.setDataFormat(amounts);
            inputWhole = input(workbook, body);
            inputWhole.setDataFormat(whole);

            XSSFFont quiet = font(workbook, 11, false, muted);
            filledDate = filled(workbook, quiet);
            filledDate.setDataFormat(dates);
            filledMoney = filled(workbook, font(workbook, 11, true, ink));
            filledMoney.setDataFormat(amounts);
            filledText = filled(workbook, quiet);

            guideLabel = plain(workbook, font(workbook, 11, true, navy));
            guideLabel.setVerticalAlignment(VerticalAlignment.TOP);
            guideLabel.setWrapText(true);
            guideBody = plain(workbook, font(workbook, 11, false, ink));
            guideBody.setVerticalAlignment(VerticalAlignment.TOP);
            guideBody.setWrapText(true);
        }

        private static XSSFColor colour(String hex) {
            return new XSSFColor(java.util.HexFormat.of().parseHex(hex), null);
        }

        private static XSSFFont font(XSSFWorkbook workbook, int points, boolean bold, XSSFColor colour) {
            XSSFFont font = workbook.createFont();
            font.setFontName("Calibri");
            font.setFontHeightInPoints((short) points);
            font.setBold(bold);
            font.setColor(colour);
            return font;
        }

        private static XSSFCellStyle plain(XSSFWorkbook workbook, XSSFFont font) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            return style;
        }

        private static XSSFCellStyle fill(XSSFWorkbook workbook, XSSFColor colour, XSSFFont font) {
            XSSFCellStyle style = plain(workbook, font);
            style.setFillForegroundColor(colour);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setIndention((short) 1);
            return style;
        }

        private XSSFCellStyle input(XSSFWorkbook workbook, XSSFFont font) {
            XSSFCellStyle style = plain(workbook, font);
            style.setLocked(false);
            edges(style);
            return style;
        }

        private XSSFCellStyle filled(XSSFWorkbook workbook, XSSFFont font) {
            XSSFCellStyle style = fill(workbook, ground, font);
            style.setIndention((short) 0);
            style.setLocked(true);
            edges(style);
            return style;
        }

        private void edges(XSSFCellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(line);
            style.setBottomBorderColor(line);
            style.setLeftBorderColor(line);
            style.setRightBorderColor(line);
        }
    }
}

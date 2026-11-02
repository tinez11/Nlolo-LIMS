package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tz.co.nlolo.lifeplatform.XlsxToCsv;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRow;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a lender's schedule into rows, trusting nothing in it.
 *
 * <p>A pure function over a {@link Reader}: no Spring, no database, no clock. The same
 * reasoning as {@link AmortisationCalculator} and {@link GroupBenefitCalculator} -- this
 * is the part with the most edge cases and the least need for infrastructure, and every
 * edge case here is something a real client file actually contains.
 *
 * <p><b>A row problem and a FILE problem are different things.</b> A bad row is recorded
 * and the other 399 proceed; a bad header aborts the whole file, because every row would
 * fail identically and 400 identical rejections tell a lender less than one sentence does.
 */
public final class EnrolmentCsvParser {

    /** A problem with the FILE, not with a row. */
    public static class MalformedScheduleException extends IllegalArgumentException {
        public MalformedScheduleException(String message) { super(message); }
    }

    /** One row that could not be read, and why, in terms the lender can act on. */
    public record RowError(int lineNumber, String loanAccountNumber, String borrowerFullName,
                            EnrolmentRejection reason, String detail) {}

    public record ParsedSchedule(List<EnrolmentRow> rows, List<RowError> errors) {}

    public static final String LOAN_ACCOUNT_NUMBER = "loan_account_number";
    public static final String BORROWER_FULL_NAME = "borrower_full_name";
    public static final String BORROWER_DATE_OF_BIRTH = "borrower_date_of_birth";
    public static final String BORROWER_SEX = "borrower_sex";
    public static final String BORROWER_NATIONAL_ID = "borrower_national_id";
    public static final String BORROWER_PHONE = "borrower_phone";
    public static final String LOAN_PRINCIPAL_AMOUNT = "loan_principal_amount";
    public static final String LOAN_TERM_MONTHS = "loan_term_months";
    public static final String DISBURSEMENT_DATE = "disbursement_date";

    /**
     * THEIRS, not ours: what the lender says this loan costs to insure.
     *
     * <p>Optional, and never used to price anything -- the insurer prices the cover from the
     * scheme's own rate and basis. It is read so the two can be COMPARED, because a file
     * whose premium column is silently discarded reconciles against nothing.
     */
    public static final String LENDER_PREMIUM_AMOUNT = "lender_premium_amount";

    /** Ours, not theirs: blank on a new borrower, quoted back for an existing one. */
    public static final String MEMBER_REFERENCE = "member_reference";

    /**
     * The five a lender must send.
     *
     * <p>Sex, national ID, phone, the lender's own loan account number and our member
     * reference are all optional; anything else in the file is IGNORED rather than
     * refused. Both real exports carry columns we do not use -- S/N, AGE, premium per
     * policy year -- and refusing a file for carrying more than we asked for would reject
     * every real export on day one.
     *
     * <p>The loan account number was required until 2026-09-22, when the client confirmed
     * neither lender holds one. Requiring it would have rejected every real file.
     */
    private static final List<String> REQUIRED_COLUMNS = List.of(
        BORROWER_FULL_NAME, BORROWER_DATE_OF_BIRTH,
        LOAN_PRINCIPAL_AMOUNT, LOAN_TERM_MONTHS, DISBURSEMENT_DATE);

    /**
     * The blank file a lender is given, header row only.
     *
     * <p><b>Generated from the constants above rather than written out</b>, because the
     * alternative is a copy that drifts. The columns changed twice already — thirteen cut to
     * nine when the real client files arrived, then re-keyed when the client confirmed neither
     * lender holds a loan account number — and a hand-maintained template would have gone stale
     * on the first of those and been quietly wrong on the second.
     *
     * <p><b>The header alone.</b> The worked example is added by {@code EnrolmentApi.renderTemplate},
     * which has a scheme and can therefore show one of that lender's OWN borrowers — a row that is
     * safe to leave in, because it duplicates a loan already on cover and comes back refused. An
     * invented borrower here could not be: left in the file, it would enrol somebody who does not
     * exist, which is why this method stops at the columns.
     *
     * <p>Required and optional columns are all present, in the order the lender's own exports
     * tend to read. A file may also carry columns we do not use — both real exports do — and
     * those are ignored rather than refused.
     */
    public static String templateCsv() {
        return String.join(",",
            MEMBER_REFERENCE, BORROWER_FULL_NAME, BORROWER_DATE_OF_BIRTH, BORROWER_SEX,
            BORROWER_NATIONAL_ID, BORROWER_PHONE, LOAN_PRINCIPAL_AMOUNT, LOAN_TERM_MONTHS,
            DISBURSEMENT_DATE, LOAN_ACCOUNT_NUMBER, LENDER_PREMIUM_AMOUNT) + "\n";
    }

    /**
     * The other names a column goes by (2026-10-30): the insurer's own template's ("Borrower's full name") and the
     * lenders' ("CLIENT NAME", "SUM ASSURED (TZS)", "POLICY TERM (MONTHS)", "START DATE"). Compared after
     * {@link #simplified}, so case, brackets, apostrophes and the currency don't matter.
     */
    private static final Map<String, String> ALIASES = aliases(
        BORROWER_FULL_NAME, "borrower s full name", "borrower full name", "borrower name", "client name",
            "full name", "name", "member name", "customer name",
        BORROWER_DATE_OF_BIRTH, "date of birth", "dob", "birth date", "birthdate",
        BORROWER_SEX, "sex", "gender",
        BORROWER_NATIONAL_ID, "national id", "nida", "nida number", "id number",
        BORROWER_PHONE, "phone", "phone number", "mobile", "mobile number",
        LOAN_PRINCIPAL_AMOUNT, "loan amount", "sum assured", "amount borrowed", "principal", "loan principal",
            "disbursed amount",
        LOAN_TERM_MONTHS, "loan term months", "policy term months", "term months", "loan term", "term",
            "tenure months", "tenor months",
        DISBURSEMENT_DATE, "loan start date", "start date", "disbursement date", "date disbursed",
        LOAN_ACCOUNT_NUMBER, "loan account no", "loan account number", "account number", "loan number",
        LENDER_PREMIUM_AMOUNT, "premium", "premium amount", "lender premium",
        MEMBER_REFERENCE, "member reference", "our reference",
        XlsxToCsv.SHEET_ROW);

    private static Map<String, String> aliases(String... columnsThenNames) {
        Map<String, String> aliases = new HashMap<>();
        String column = null;
        for (String entry : columnsThenNames) {
            if (entry.contains("_")) {
                column = entry;
                aliases.put(simplified(entry), entry);
            } else {
                aliases.put(entry, column);
            }
        }
        return aliases;
    }

    /** Lower case, words only: "SUM ASSURED (TZS)" and "sum_assured" both read "sum assured". */
    static String simplified(String header) {
        String words = header.toLowerCase(Locale.ROOT).replaceAll("\\(\\s*(tzs|tsh|tshs|usd)\\s*\\)", " ")
            .replaceAll("[^a-z0-9]+", " ").strip();
        return words.replaceAll("\\b(tzs|tshs|tsh)$", "").strip();
    }

    /** The column a header names, or the header itself simplified when it names none of ours (and is ignored). */
    static String columnFor(String header) {
        String simple = simplified(header);
        return ALIASES.getOrDefault(simple, simple);
    }

    /** Whether a spreadsheet row is the header: it names at least three of the five a lender must send. */
    public static boolean looksLikeHeader(List<String> cells) {
        return cells.stream().map(EnrolmentCsvParser::columnFor).filter(REQUIRED_COLUMNS::contains).distinct()
            .count() >= 3;
    }

    /** The five a lender must fill, for a console that has to say which they are. */
    public static List<String> requiredColumns() {
        return REQUIRED_COLUMNS;
    }

    private EnrolmentCsvParser() {}

    public static ParsedSchedule parse(Reader reader) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

        List<EnrolmentRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        // Account number to the line that first carried it, so a duplicate can name the
        // earlier line rather than merely asserting there is one.
        Map<String, Integer> firstSeenAt = new HashMap<>();

        try (CSVParser parser = CSVParser.parse(stripByteOrderMark(reader), format)) {
            Map<String, Integer> headers = normalisedHeaders(parser);
            requireEveryColumn(headers);

            int lineNumber = 1;
            for (CSVRecord record : parser) {
                lineNumber++;
                if (isEntirelyBlank(record) || isTotalsRow(record, headers)) {
                    // The real LOLC sheet carries ~200 rows of formula residue below its
                    // data. Rejecting those would bury the real rejections under noise.
                    continue;
                }
                // A spreadsheet's own row number when the reader supplied it, so a refusal names the row the lender
                // can see rather than a count of the rows that were not blank.
                String sheetRow = cell(record, headers, XlsxToCsv.SHEET_ROW);
                int line = sheetRow == null ? lineNumber : Integer.parseInt(sheetRow);
                readRow(record, line, headers, firstSeenAt)
                    .ifLeft(rows::add)
                    .ifRight(errors::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the enrolment schedule", e);
        }

        return new ParsedSchedule(List.copyOf(rows), List.copyOf(errors));
    }

    /**
     * A row is either read or refused, never both.
     *
     * <p>A tiny either rather than a nullable pair, so the caller cannot forget one arm:
     * silently dropping a row that was neither enrolled nor reported is the one outcome
     * this whole feature must not produce.
     */
    private record RowResult(EnrolmentRow row, RowError error) {
        static RowResult read(EnrolmentRow row) { return new RowResult(row, null); }
        static RowResult refused(RowError error) { return new RowResult(null, error); }

        RowResult ifLeft(java.util.function.Consumer<EnrolmentRow> consumer) {
            if (row != null) consumer.accept(row);
            return this;
        }

        void ifRight(java.util.function.Consumer<RowError> consumer) {
            if (error != null) consumer.accept(error);
        }
    }

    private static RowResult readRow(CSVRecord record, int lineNumber,
                                      Map<String, Integer> headers, Map<String, Integer> firstSeenAt) {
        String accountNumber = cell(record, headers, LOAN_ACCOUNT_NUMBER);
        String fullName = cell(record, headers, BORROWER_FULL_NAME);

        // One error per row, the first one found. A lender fixing a row fixes it whole,
        // and five findings on one line is noise to work through.
        for (String column : REQUIRED_COLUMNS) {
            if (cell(record, headers, column) == null) {
                return RowResult.refused(new RowError(lineNumber, accountNumber, fullName,
                    EnrolmentRejection.MISSING_REQUIRED_FIELD,
                    column + " is blank."));
            }
        }

        LocalDate dateOfBirth;
        BigDecimal principal;
        int termMonths;
        LocalDate disbursedOn;
        try {
            dateOfBirth = date(cell(record, headers, BORROWER_DATE_OF_BIRTH), BORROWER_DATE_OF_BIRTH);
            principal = amount(cell(record, headers, LOAN_PRINCIPAL_AMOUNT), LOAN_PRINCIPAL_AMOUNT);
            termMonths = wholeNumber(cell(record, headers, LOAN_TERM_MONTHS), LOAN_TERM_MONTHS);
            disbursedOn = date(cell(record, headers, DISBURSEMENT_DATE), DISBURSEMENT_DATE);
        } catch (UnreadableValue e) {
            return RowResult.refused(new RowError(lineNumber, accountNumber, fullName,
                EnrolmentRejection.MALFORMED_VALUE, e.getMessage()));
        }

        // The lender's own premium, if they sent one. Read separately and AFTER the five that
        // matter, because a row must not be refused over it: this figure prices nothing, and a
        // file that cannot be read because the counterparty's own column is malformed would be
        // a rejection nobody benefits from. Unreadable means absent, and absent means the row
        // reconciles against nothing -- which is what it did for every file until now.
        BigDecimal statedPremium;
        try {
            String stated = cell(record, headers, LENDER_PREMIUM_AMOUNT);
            statedPremium = stated == null || stated.isBlank()
                ? null : amount(stated, LENDER_PREMIUM_AMOUNT);
        } catch (UnreadableValue e) {
            statedPremium = null;
        }

        // Two identical rows in ONE file, caught by the loan itself because the lender has
        // no identifier to give: who, born when, borrowed how much, on what day. The same
        // composite the scheme is checked against, applied within the file first so a
        // lender who pasted a block twice hears about it against their own line numbers.
        String loanKey = fullName.toLowerCase(Locale.ROOT) + "|" + dateOfBirth + "|"
            + disbursedOn + "|" + principal.stripTrailingZeros().toPlainString();
        Integer earlier = firstSeenAt.get(loanKey);
        if (earlier != null) {
            return RowResult.refused(new RowError(lineNumber, accountNumber, fullName,
                EnrolmentRejection.DUPLICATE_LOAN,
                fullName + " with the same date of birth, disbursement date and amount"
                    + " already appears at line " + earlier + " of this file."));
        }

        firstSeenAt.put(loanKey, lineNumber);
        return RowResult.read(new EnrolmentRow(lineNumber,
            cell(record, headers, MEMBER_REFERENCE), accountNumber, fullName, dateOfBirth,
            cell(record, headers, BORROWER_SEX),
            cell(record, headers, BORROWER_NATIONAL_ID),
            cell(record, headers, BORROWER_PHONE),
            principal, termMonths, disbursedOn, statedPremium));
    }

    /** Carries the column name and the offending value, which is what a lender needs. */
    private static class UnreadableValue extends RuntimeException {
        UnreadableValue(String message) { super(message); }
    }

    /**
     * Amounts through {@link BigDecimal}, never {@code Double.parseDouble}.
     *
     * <p>Scientific notation is refused explicitly. Excel exports a large number as
     * {@code 8.5E+06}, and {@code BigDecimal} would ACCEPT that string silently -- so the
     * only way it becomes visible is to look for it.
     */
    private static BigDecimal amount(String value, String column) {
        if (value.indexOf('E') >= 0 || value.indexOf('e') >= 0) {
            throw new UnreadableValue(column + " \"" + value
                + "\" is in scientific notation. Export the column as text or as a plain number.");
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new UnreadableValue(column + " \"" + value + "\" is not an amount.");
        }
    }

    private static int wholeNumber(String value, String column) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UnreadableValue(column + " \"" + value + "\" is not a whole number of months.");
        }
    }

    /**
     * ISO dates only.
     *
     * <p>An Excel serial such as {@code 46203} is 2026-06-30 to Excel and nothing at all
     * here. Refused by name rather than guessed at: reading it as a year would insure
     * the wrong person for the wrong term, and the message is what tells the lender their
     * export is wrong.
     */
    private static LocalDate date(String value, String column) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            LocalDate dayFirst = dayFirst(value);
            if (dayFirst != null) return dayFirst;
            throw new UnreadableValue(column + " \"" + value + "\" is not a date in YYYY-MM-DD"
                + " form. " + dateHint(value));
        }
    }

    /**
     * A date a lender's sheet kept as text, day first as dates are written here: 18/05/2026, 18-05-2026 or 18.05.2026
     * (2026-11-02). A block pasted from a bank's own sheet arrives this way, and the template reads it the same way.
     * The year must have four digits -- a two-digit one is a century apart on a date of birth and is not guessed -- and
     * a day or month that cannot be one is refused rather than swapped.
     */
    static LocalDate dayFirst(String value) {
        java.util.regex.Matcher m = DAY_FIRST.matcher(value.strip());
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern DAY_FIRST =
        java.util.regex.Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})");

    /**
     * What actually went wrong with this value, rather than one guess for every shape.
     *
     * <p>The message used to blame an Excel date serial whatever arrived, which was right for
     * {@code 46203} and actively misleading for {@code 1-Sep-00}: a lender told to "format the
     * column as a date" would do exactly that and get the same refusal, because formatting as a
     * DATE is what produced it. The first real file sent through this platform bounced entirely
     * on that value, and the advice it came back with pointed the wrong way.
     *
     * <p>The two-digit year is refused rather than guessed, and that is a deliberate choice on
     * this column specifically. {@code 00} is 1900 or 2000, the two readings are a century apart,
     * and this is a DATE OF BIRTH: one of them is a plausible borrower and the other is refused by
     * the entry-age gate or, worse, quietly accepted at the wrong age. Nothing in the file breaks
     * the tie, so the lender does.
     */
    private static String dateHint(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return "A number here is an Excel date serial; format the column as a date before"
                + " exporting.";
        }
        if (value.matches(".*\\b\\d{2}$")) {
            return "This looks like a date Excel rewrote on save, with a two-digit year that could"
                + " be 19xx or 20xx -- a century apart on a date of birth, so it is not guessed."
                + " Set the column format to Text and type the date as 2000-09-01.";
        }
        return "Excel rewrites dates when it saves a CSV. Set the column format to Text and type"
            + " the date as 2000-09-01.";
    }

    /**
     * Excel writes UTF-8 with a BOM. Left in place, the first header reads
     * {@code ﻿loan_account_number} and the file fails as though the column were
     * absent -- which sends the lender looking for a column that is right there.
     */
    private static Reader stripByteOrderMark(Reader reader) throws IOException {
        java.io.PushbackReader pushback = new java.io.PushbackReader(reader, 1);
        int first = pushback.read();
        if (first != -1 && first != '﻿') {
            pushback.unread(first);
        }
        return pushback;
    }

    private static Map<String, Integer> normalisedHeaders(CSVParser parser) {
        Map<String, Integer> raw = parser.getHeaderMap();
        if (raw == null || raw.isEmpty()) {
            throw new MalformedScheduleException("This file has no header row.");
        }
        Map<String, Integer> normalised = new HashMap<>();
        raw.forEach((name, index) -> {
            if (name != null && !name.isBlank()) {
                // The first column to claim a name keeps it: a file with both "Name" and "Client name" is read from
                // whichever comes first, not the last one the map happened to see.
                normalised.merge(columnFor(name), index, Math::min);
            }
        });
        return normalised;
    }

    /** A totals row at the foot of a lender's sheet: it says TOTAL somewhere and is nobody's date of birth. */
    private static boolean isTotalsRow(CSVRecord record, Map<String, Integer> headers) {
        if (cell(record, headers, BORROWER_DATE_OF_BIRTH) != null) return false;
        for (String value : record) {
            String word = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if (word.equals("total") || word.equals("totals") || word.equals("grand total")) return true;
        }
        return false;
    }

    private static void requireEveryColumn(Map<String, Integer> headers) {
        List<String> missing = REQUIRED_COLUMNS.stream()
            .filter(column -> !headers.containsKey(column))
            .toList();
        if (!missing.isEmpty()) {
            throw new MalformedScheduleException(
                "This file is missing " + (missing.size() == 1 ? "a required column" : "required columns")
                    + ": " + String.join(", ", missing)
                    + ". Use the template at credit-life-enrolment-sample.csv.");
        }
    }

    private static boolean isEntirelyBlank(CSVRecord record) {
        for (String value : record) {
            if (value != null && !value.isBlank()) return false;
        }
        return true;
    }

    private static String cell(CSVRecord record, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        if (index == null || index >= record.size()) return null;
        String value = record.get(index);
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}

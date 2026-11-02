package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tz.co.nlolo.lifeplatform.XlsxToCsv;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.ExitRejection;
import tz.co.nlolo.lifeplatform.policy.api.ExitRow;

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
 * Reads a lender's exits file: which loans ended, when, and why.
 *
 * <p>Pure, like {@link EnrolmentCsvParser}, and deliberately its mirror image — a reader who
 * understands one understands the other. Every awkward case handled here is one the real LOLC
 * and BUMACO exports contain, because the exits file comes out of the same spreadsheets: BOMs,
 * Excel serial dates, scientific notation, hundreds of trailing blank rows, extra columns.
 *
 * <p>A loan is named by the lender's own account number or by the member reference our acceptance report gave back
 * (V44): the lender knows its loans by account, and asking only for our reference made the file one they could fill
 * from our report alone.
 *
 * <p>This parser decides only what a row SAYS. Whether the loan is on this scheme, whether it is still on cover, and
 * whether the date falls inside it are questions about the roll, and belong to the service.
 */
public final class ExitCsvParser {

    /** A problem with the FILE, not with a row. */
    public static class MalformedExitsFileException extends IllegalArgumentException {
        public MalformedExitsFileException(String message) { super(message); }
    }

    /** One row that could not be read, and why, in terms the lender can act on. */
    public record RowError(int lineNumber, String memberReference, String loanAccountNumber, ExitRejection reason,
                            String detail) {}

    public record ParsedExits(List<ExitRow> rows, List<RowError> errors) {}

    public static final String LOAN_ACCOUNT_NUMBER = "loan_account_number";
    public static final String MEMBER_REFERENCE = "member_reference";
    public static final String EXIT_DATE = "exit_date";
    public static final String EXIT_REASON = "exit_reason";
    public static final String OUTSTANDING_BALANCE_AT_EXIT = "outstanding_balance_at_exit";

    /**
     * The date and the reason, always; the loan itself by either of {@link #IDENTIFIERS}. The balance is optional: a
     * lender may legitimately not track it.
     */
    private static final List<String> REQUIRED_COLUMNS = List.of(EXIT_DATE, EXIT_REASON);

    /** The two ways a row may name its loan; a file carries at least one of them. */
    private static final List<String> IDENTIFIERS = List.of(LOAN_ACCOUNT_NUMBER, MEMBER_REFERENCE);

    /** The other names a column goes by: the insurer's own template's, and the plain words a lender uses. */
    private static final Map<String, String> ALIASES = aliases(
        LOAN_ACCOUNT_NUMBER, "loan account no", "loan account number", "account number", "loan number",
        MEMBER_REFERENCE, "member reference", "our reference", "member ref",
        EXIT_DATE, "date the loan ended", "date ended", "exit date", "end date", "closed on", "date closed",
        EXIT_REASON, "why it ended", "reason", "exit reason", "reason for exit",
        OUTSTANDING_BALANCE_AT_EXIT, "balance still owed", "balance outstanding", "outstanding balance", "balance",
        XlsxToCsv.SHEET_ROW);

    private static Map<String, String> aliases(String... columnsThenNames) {
        Map<String, String> aliases = new HashMap<>();
        String column = null;
        for (String entry : columnsThenNames) {
            if (entry.contains("_")) {
                column = entry;
                aliases.put(EnrolmentCsvParser.simplified(entry), entry);
            } else {
                aliases.put(entry, column);
            }
        }
        return aliases;
    }

    /** The column a header names, or the header itself simplified when it names none of ours (and is ignored). */
    static String columnFor(String header) {
        String simple = EnrolmentCsvParser.simplified(header);
        return ALIASES.getOrDefault(simple, simple);
    }

    /** Whether a spreadsheet row is the header: it names the date, the reason and a way to find the loan. */
    public static boolean looksLikeHeader(List<String> cells) {
        List<String> named = cells.stream().map(ExitCsvParser::columnFor).distinct().toList();
        return named.containsAll(REQUIRED_COLUMNS) && IDENTIFIERS.stream().anyMatch(named::contains);
    }

    /**
     * The four reasons a LENDER may state.
     *
     * <p>{@link ExitReason#CLAIM_SETTLED} is deliberately absent although it is a valid enum
     * value. Only {@code dischargeForSettledClaim} may write it: a lender asserting a claim was
     * paid would suppress both the refund and the commission clawback on a loan that was merely
     * repaid — the insurer keeps premium it did not earn, and the bank keeps commission on it.
     */
    private static final List<ExitReason> LENDER_STATEABLE = List.of(
        ExitReason.SETTLED_EARLY, ExitReason.REFINANCED,
        ExitReason.WRITTEN_OFF, ExitReason.CANCELLED);

    /**
     * The blank exits file a lender is given, header row only.
     *
     * <p>Generated from the constants above for the same reason the enrolment template is: a
     * hand-written copy drifts from the parser that reads it, and the first person to notice is
     * a lender whose file was refused for a column nobody told them about.
     */
    public static String templateCsv() {
        return String.join(",",
            LOAN_ACCOUNT_NUMBER, MEMBER_REFERENCE, EXIT_DATE, EXIT_REASON, OUTSTANDING_BALANCE_AT_EXIT) + "\n";
    }

    /** The two a lender must always fill; the loan is named by account number or member reference besides. */
    public static List<String> requiredColumns() {
        return REQUIRED_COLUMNS;
    }

    /**
     * The reasons a lender may state, for a console that has to tell them.
     *
     * <p>Not every {@link ExitReason}: CLAIM_SETTLED is written only by the claim path, because a
     * lender asserting a claim was paid would suppress both the refund and the clawback on a loan
     * that was merely repaid.
     */
    public static List<ExitReason> lenderStateableReasons() {
        return LENDER_STATEABLE;
    }

    private ExitCsvParser() {}

    public static ParsedExits parse(Reader reader) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build();

        List<ExitRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        // The loan to the line that first named it, so a duplicate can name the earlier line rather than merely
        // asserting there is one.
        Map<String, Integer> firstSeenAt = new HashMap<>();

        try (CSVParser parser = CSVParser.parse(stripByteOrderMark(reader), format)) {
            Map<String, Integer> headers = normalisedHeaders(parser);
            requireEveryColumn(headers);

            int lineNumber = 1;
            for (CSVRecord record : parser) {
                lineNumber++;
                if (isEntirelyBlank(record, headers)) {
                    continue;
                }
                // A spreadsheet's own row number when the reader supplied it, so a refusal names the row the lender
                // can see rather than a count of the rows that were not blank.
                String sheetRow = cell(record, headers, XlsxToCsv.SHEET_ROW);
                int line = sheetRow == null ? lineNumber : Integer.parseInt(sheetRow);
                RowResult result = readRow(record, line, headers, firstSeenAt);
                if (result.row() != null) rows.add(result.row());
                if (result.error() != null) errors.add(result.error());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the exits file", e);
        }

        return new ParsedExits(List.copyOf(rows), List.copyOf(errors));
    }

    /** A row is either read or refused, never both and never neither. */
    private record RowResult(ExitRow row, RowError error) {
        static RowResult read(ExitRow row) { return new RowResult(row, null); }
        static RowResult refused(RowError error) { return new RowResult(null, error); }
    }

    private static RowResult readRow(CSVRecord record, int lineNumber,
                                      Map<String, Integer> headers, Map<String, Integer> firstSeenAt) {
        String reference = cell(record, headers, MEMBER_REFERENCE);
        String account = cell(record, headers, LOAN_ACCOUNT_NUMBER);

        // One error per row, the first one found. A lender fixing a row fixes it whole, and
        // five findings on one line is noise to work through.
        if (reference == null && account == null) {
            return RowResult.refused(new RowError(lineNumber, null, null, ExitRejection.MISSING_REQUIRED_FIELD,
                "Give the loan account number, or the member reference from our acceptance report."));
        }
        for (String column : REQUIRED_COLUMNS) {
            if (cell(record, headers, column) == null) {
                return RowResult.refused(new RowError(lineNumber, reference, account,
                    ExitRejection.MISSING_REQUIRED_FIELD, words(column) + " is blank."));
            }
        }

        LocalDate exitDate;
        BigDecimal balance;
        try {
            exitDate = date(cell(record, headers, EXIT_DATE), EXIT_DATE);
            balance = optionalAmount(cell(record, headers, OUTSTANDING_BALANCE_AT_EXIT));
        } catch (UnreadableValue e) {
            return RowResult.refused(new RowError(lineNumber, reference, account,
                ExitRejection.MALFORMED_VALUE, e.getMessage()));
        }

        String given = cell(record, headers, EXIT_REASON);
        ExitReason reason;
        try {
            // "Settled early", as the template's list offers it, and SETTLED_EARLY, as a file built by hand says it.
            reason = ExitReason.valueOf(given.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_")
                .replaceAll("^_|_$", ""));
        } catch (IllegalArgumentException e) {
            return RowResult.refused(unknownReason(lineNumber, reference, account, given));
        }
        if (!LENDER_STATEABLE.contains(reason)) {
            // Parses as an ExitReason, but is not one a lender may state. CLAIM_SETTLED is the
            // only value that reaches here, and the message says why rather than pretending
            // the word was unreadable.
            return RowResult.refused(new RowError(lineNumber, reference, account,
                ExitRejection.UNKNOWN_EXIT_REASON,
                "\"" + given + "\" says a claim was paid, which only the insurer records. If this borrower"
                    + " died, report it as a claim; if the loan simply ended, say how: " + stateableReasons() + "."));
        }

        String loan = reference != null ? reference : "loan account " + account;
        Integer earlier = firstSeenAt.get(loan);
        if (earlier != null) {
            return RowResult.refused(new RowError(lineNumber, reference, account,
                ExitRejection.DUPLICATE_REFERENCE,
                capitalised(loan) + " already appears at row " + earlier + " of this file."));
        }

        firstSeenAt.put(loan, lineNumber);
        return RowResult.read(new ExitRow(lineNumber, reference, account, exitDate, reason, balance));
    }

    private static RowError unknownReason(int lineNumber, String reference, String account, String given) {
        return new RowError(lineNumber, reference, account, ExitRejection.UNKNOWN_EXIT_REASON,
            "\"" + given + "\" is not a reason we recognise. Use one of: " + stateableReasons() + ".");
    }

    /** As the template's list offers them: Settled early, Refinanced, Written off, Cancelled. */
    private static String stateableReasons() {
        return String.join(", ", LENDER_STATEABLE.stream().map(r -> capitalised(words(r.name()))).toList());
    }

    /** A column or reason in the words a lender reads: exit_date is "exit date". */
    private static String words(String code) {
        return switch (code) {
            case EXIT_DATE -> "The date the loan ended";
            case EXIT_REASON -> "Why it ended";
            default -> code.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Carries the column name and the offending value, which is what a lender needs. */
    private static class UnreadableValue extends RuntimeException {
        UnreadableValue(String message) { super(message); }
    }

    /**
     * The balance, where the lender tracks one.
     *
     * <p>Scientific notation refused explicitly: Excel exports a large number as
     * {@code 4.18E+06} and {@link BigDecimal} would accept that string silently. Negative
     * refused because a debt owed cannot be less than nothing, and
     * {@code chk_exit_row...} would refuse the row later and less legibly.
     */
    private static BigDecimal optionalAmount(String value) {
        if (value == null) return null;
        if (value.indexOf('E') >= 0 || value.indexOf('e') >= 0) {
            throw new UnreadableValue("The balance \"" + value
                + "\" is in scientific notation. Export the column as text or as a plain number.");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new UnreadableValue("The balance \"" + value + "\" is not an amount.");
        }
        if (parsed.signum() < 0) {
            throw new UnreadableValue("The balance \"" + value
                + "\" is negative; a balance still owed cannot be less than nothing.");
        }
        return parsed;
    }

    /**
     * ISO dates only; the workbook reader has already turned a date cell into one.
     *
     * <p>An Excel serial such as {@code 46203} is 2026-06-30 to Excel and nothing at all here.
     * Refused by name rather than guessed at: reading it as a year would date a refund to the
     * wrong century.
     */
    private static LocalDate date(String value, String column) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new UnreadableValue(words(column) + " \"" + value
                + "\" is not a date. In a CSV write it as YYYY-MM-DD; a number here is an Excel date serial, so"
                + " format the column as a date, or send the Excel file itself.");
        }
    }

    /**
     * Excel writes UTF-8 with a BOM. Left in place, the first header reads
     * {@code ﻿member_reference} and the file fails as though the column were absent — which
     * sends the lender looking for a column that is right there.
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
            throw new MalformedExitsFileException("This file has no header row.");
        }
        Map<String, Integer> normalised = new HashMap<>();
        raw.forEach((name, index) -> {
            if (name != null && !name.isBlank()) {
                normalised.putIfAbsent(columnFor(name), index);
            }
        });
        return normalised;
    }

    private static void requireEveryColumn(Map<String, Integer> headers) {
        List<String> missing = new ArrayList<>(REQUIRED_COLUMNS.stream()
            .filter(column -> !headers.containsKey(column))
            .map(ExitCsvParser::words)
            .toList());
        if (IDENTIFIERS.stream().noneMatch(headers::containsKey)) {
            missing.add(0, "the loan account number (or our member reference)");
        }
        if (!missing.isEmpty()) {
            throw new MalformedExitsFileException(
                "This file is missing " + (missing.size() == 1 ? "a column" : "columns") + ": "
                    + String.join(", ", missing) + ". Use the scheme's loans-ending template.");
        }
    }

    /** Blank in every column a lender types: a template row nobody filled, whose Check column says nothing. */
    private static boolean isEntirelyBlank(CSVRecord record, Map<String, Integer> headers) {
        for (String column : List.of(LOAN_ACCOUNT_NUMBER, MEMBER_REFERENCE, EXIT_DATE, EXIT_REASON,
                OUTSTANDING_BALANCE_AT_EXIT)) {
            if (cell(record, headers, column) != null) return false;
        }
        return true;
    }

    /** Null rather than empty for an absent or blank cell, so one check covers both. */
    private static String cell(CSVRecord record, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        if (index == null || index >= record.size()) return null;
        String value = record.get(index);
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}

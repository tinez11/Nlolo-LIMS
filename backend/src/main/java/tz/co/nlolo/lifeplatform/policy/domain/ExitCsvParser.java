package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
 * <p>This parser decides only what a row SAYS. Whether the reference names a member of this
 * scheme, whether they are still on cover, and whether the date falls inside it are questions
 * about the roll, and belong to the service.
 */
public final class ExitCsvParser {

    /** A problem with the FILE, not with a row. */
    public static class MalformedExitsFileException extends IllegalArgumentException {
        public MalformedExitsFileException(String message) { super(message); }
    }

    /** One row that could not be read, and why, in terms the lender can act on. */
    public record RowError(int lineNumber, String memberReference, ExitRejection reason,
                            String detail) {}

    public record ParsedExits(List<ExitRow> rows, List<RowError> errors) {}

    public static final String MEMBER_REFERENCE = "member_reference";
    public static final String EXIT_DATE = "exit_date";
    public static final String EXIT_REASON = "exit_reason";
    public static final String OUTSTANDING_BALANCE_AT_EXIT = "outstanding_balance_at_exit";

    /**
     * All three except the balance, which a lender may legitimately not track.
     *
     * <p>The reference is required here where it is optional on an enrolment file, and that
     * asymmetry is the point: enrolment can name a NEW borrower, an exit cannot.
     */
    private static final List<String> REQUIRED_COLUMNS =
        List.of(MEMBER_REFERENCE, EXIT_DATE, EXIT_REASON);

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
        // Reference to the line that first carried it, so a duplicate can name the earlier
        // line rather than merely asserting there is one.
        Map<String, Integer> firstSeenAt = new HashMap<>();

        try (CSVParser parser = CSVParser.parse(stripByteOrderMark(reader), format)) {
            Map<String, Integer> headers = normalisedHeaders(parser);
            requireEveryColumn(headers);

            int lineNumber = 1;
            for (CSVRecord record : parser) {
                lineNumber++;
                if (isEntirelyBlank(record)) {
                    continue;
                }
                RowResult result = readRow(record, lineNumber, headers, firstSeenAt);
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

        // One error per row, the first one found. A lender fixing a row fixes it whole, and
        // five findings on one line is noise to work through.
        for (String column : REQUIRED_COLUMNS) {
            if (cell(record, headers, column) == null) {
                return RowResult.refused(new RowError(lineNumber, reference,
                    ExitRejection.MISSING_REQUIRED_FIELD, column + " is blank."));
            }
        }

        LocalDate exitDate;
        BigDecimal balance;
        try {
            exitDate = date(cell(record, headers, EXIT_DATE), EXIT_DATE);
            balance = optionalAmount(cell(record, headers, OUTSTANDING_BALANCE_AT_EXIT));
        } catch (UnreadableValue e) {
            return RowResult.refused(new RowError(lineNumber, reference,
                ExitRejection.MALFORMED_VALUE, e.getMessage()));
        }

        ExitReason reason;
        try {
            reason = ExitReason.valueOf(
                cell(record, headers, EXIT_REASON).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RowResult.refused(unknownReason(lineNumber, reference,
                cell(record, headers, EXIT_REASON)));
        }
        if (!LENDER_STATEABLE.contains(reason)) {
            // Parses as an ExitReason, but is not one a lender may state. CLAIM_SETTLED is the
            // only value that reaches here, and the message says why rather than pretending
            // the word was unreadable.
            return RowResult.refused(new RowError(lineNumber, reference,
                ExitRejection.UNKNOWN_EXIT_REASON,
                "exit_reason \"" + reason + "\" states that a claim was paid, which only the "
                    + "insurer records. If this borrower died, report it as a claim; if the "
                    + "loan simply ended, state how: " + stateableReasons() + "."));
        }

        Integer earlier = firstSeenAt.get(reference);
        if (earlier != null) {
            return RowResult.refused(new RowError(lineNumber, reference,
                ExitRejection.DUPLICATE_REFERENCE,
                reference + " already appears at line " + earlier + " of this file."));
        }

        firstSeenAt.put(reference, lineNumber);
        return RowResult.read(new ExitRow(lineNumber, reference, exitDate, reason, balance));
    }

    private static RowError unknownReason(int lineNumber, String reference, String given) {
        return new RowError(lineNumber, reference, ExitRejection.UNKNOWN_EXIT_REASON,
            "exit_reason \"" + given + "\" is not one we recognise. Use one of: "
                + stateableReasons() + ".");
    }

    private static String stateableReasons() {
        return String.join(", ", LENDER_STATEABLE.stream().map(Enum::name).toList());
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
            throw new UnreadableValue(OUTSTANDING_BALANCE_AT_EXIT + " \"" + value
                + "\" is in scientific notation. Export the column as text or as a plain number.");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new UnreadableValue(OUTSTANDING_BALANCE_AT_EXIT + " \"" + value
                + "\" is not an amount.");
        }
        if (parsed.signum() < 0) {
            throw new UnreadableValue(OUTSTANDING_BALANCE_AT_EXIT + " \"" + value
                + "\" is negative; a balance still owed cannot be less than nothing.");
        }
        return parsed;
    }

    /**
     * ISO dates only.
     *
     * <p>An Excel serial such as {@code 46203} is 2026-06-30 to Excel and nothing at all here.
     * Refused by name rather than guessed at: reading it as a year would date a refund to the
     * wrong century.
     */
    private static LocalDate date(String value, String column) {
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new UnreadableValue(column + " \"" + value
                + "\" is not a date in YYYY-MM-DD form. A number here is an Excel date"
                + " serial; format the column as a date before exporting.");
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
                normalised.put(name.strip().toLowerCase(Locale.ROOT), index);
            }
        });
        return normalised;
    }

    private static void requireEveryColumn(Map<String, Integer> headers) {
        List<String> missing = REQUIRED_COLUMNS.stream()
            .filter(column -> !headers.containsKey(column))
            .toList();
        if (!missing.isEmpty()) {
            throw new MalformedExitsFileException(
                "This file is missing " + (missing.size() == 1 ? "a required column" : "required columns")
                    + ": " + String.join(", ", missing)
                    + ". Use the template at credit-life-exits-sample.csv.");
        }
    }

    private static boolean isEntirelyBlank(CSVRecord record) {
        for (String value : record) {
            if (value != null && !value.isBlank()) return false;
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

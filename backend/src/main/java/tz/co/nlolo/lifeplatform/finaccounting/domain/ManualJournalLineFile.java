package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A manual journal's lines from a file (IFRS 17 I4, user answer Q4) -- an opening-balances migration (O-11) or a
 * payroll journal of many lines. CSV text (an Excel workbook is converted to it first, at the edge) with a header row
 * naming the columns, in any order:
 * <pre>account, side, amount, description, branch, fund, reference</pre>
 * {@code side} is DR or CR (Debit/Credit accepted); {@code amount} a positive number with at most two decimals and
 * no thousands separators other than commas inside quotes. Every row is checked and every error reported, by row
 * number; nothing is returned when any row is wrong. Pure.
 */
public final class ManualJournalLineFile {

    public record Result(List<ManualJournalInput.Line> lines, List<String> errors) {}

    private static final List<String> REQUIRED = List.of("account", "side", "amount");

    private ManualJournalLineFile() {}

    public static Result parse(String csv) {
        List<String> errors = new ArrayList<>();
        List<ManualJournalInput.Line> lines = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true).build().parse(new StringReader(csv))) {
            Map<String, Integer> header = parser.getHeaderMap();
            for (String column : REQUIRED) {
                if (header.keySet().stream().noneMatch(h -> h.equalsIgnoreCase(column))) {
                    errors.add("The file has no '" + column + "' column (columns: account, side, amount, description,"
                        + " branch, fund, reference)");
                }
            }
            if (!errors.isEmpty()) {
                return new Result(List.of(), errors);
            }
            for (CSVRecord row : parser) {
                int rowNo = (int) row.getRecordNumber() + 1;   // the header is row 1
                String account = get(row, "account");
                String side = get(row, "side");
                String amount = get(row, "amount");
                if (account == null && side == null && amount == null) {
                    continue;
                }
                PostingDirection direction = side == null ? null : switch (side.toUpperCase(Locale.ROOT)) {
                    case "DR", "DEBIT" -> PostingDirection.DR;
                    case "CR", "CREDIT" -> PostingDirection.CR;
                    default -> null;
                };
                if (account == null || !account.matches("\\d{4}")) {
                    errors.add("Row " + rowNo + ": account must be a four-digit code, got '" + (account == null ? "" : account) + "'");
                }
                if (direction == null) {
                    errors.add("Row " + rowNo + ": side must be DR or CR, got '" + (side == null ? "" : side) + "'");
                }
                BigDecimal value = null;
                try {
                    value = amount == null ? null : new BigDecimal(amount.replace(",", ""));
                } catch (NumberFormatException e) {
                    // reported below
                }
                if (value == null || value.signum() <= 0 || value.scale() > 2) {
                    errors.add("Row " + rowNo + ": amount must be a positive number with at most two decimals, got '"
                        + (amount == null ? "" : amount) + "'");
                }
                lines.add(new ManualJournalInput.Line(account, direction, value, get(row, "description"),
                    get(row, "branch"), get(row, "fund"), get(row, "reference") == null ? null : "DOCUMENT",
                    get(row, "reference")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            errors.add("The file could not be read as CSV: " + e.getMessage());
        }
        if (lines.isEmpty() && errors.isEmpty()) {
            errors.add("The file has no lines");
        }
        return errors.isEmpty() ? new Result(lines, List.of()) : new Result(List.of(), errors);
    }

    private static String get(CSVRecord row, String column) {
        for (String name : row.getParser().getHeaderNames()) {
            if (name.equalsIgnoreCase(column) && row.isSet(name)) {
                String v = row.get(name);
                return v == null || v.isBlank() ? null : v.trim();
            }
        }
        return null;
    }
}

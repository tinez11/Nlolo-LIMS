package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An IFRS 17 engine's results for a period (IFRS 17 I5a, month-end step 7), as the platform's template states them: a
 * Header (period, the extract it answers, the engine's run reference), Journal rows -- group x guide entry (P-01 to
 * P-17) x account x side x amount -- and each group's Closing figures. {@link #parse} reads the workbook and
 * {@link #problems} checks it against the extract it answers; both report every fault at once, by sheet and row. Pure.
 */
public record EngineResults(Header header, List<Line> lines, List<Closing> closings) {

    public record Header(String period, Integer extractNumber, String engineReference, String engineName,
                         String measurementDate) {}

    /** One engine line; {@code row} is the spreadsheet row (the header is row 1). */
    public record Line(int row, String group, String entry, String account, String side, BigDecimal amount,
                       String movement, String note) {}

    /** A group's closing figures: lrc/lic/csm for a policy group, arc/aic/riCsm for a reinsurance-held group. */
    public record Closing(int row, String group, BigDecimal lrc, BigDecimal lic, BigDecimal csm, BigDecimal arc,
                          BigDecimal aic, BigDecimal riCsm) {}

    public record Parsed(EngineResults results, List<String> errors) {}

    /** What the results are checked against. {@code extractGroups} is null when the extract named does not exist. */
    public record Context(PeriodStatus periodStatus, List<String> extractGroups, Set<String> knownReferences,
                          Set<String> postingAccounts) {}

    /** The accounts the guide's P-01 to P-17 post for a policy group, besides LIC 22xx. */
    public static final Set<String> POLICY_ACCOUNTS = Set.of("2110", "2111", "2112", "2113", "2121", "2123", "2124", "2132",
        "2143", "4110", "4115", "4120", "4130", "4140", "4150", "5130", "5300", "5400", "5410", "5500", "7110", "7120");

    /** The accounts P-16 and P-17 post for a reinsurance-held group. */
    public static final Set<String> REINSURANCE_ACCOUNTS = Set.of("1410", "1411", "1412", "1413", "1436", "6110", "6120",
        "6130");

    static final List<String> JOURNAL_COLUMNS = List.of("group", "entry", "account", "side", "amount", "movement", "note");
    static final List<String> CLOSING_COLUMNS = List.of("group", "lrc", "lic", "csm", "arc", "aic", "ri_csm");

    public EngineResults {
        lines = List.copyOf(lines);
        closings = List.copyOf(closings);
    }

    public static boolean isReinsurance(String group) {
        return group != null && group.startsWith("RI-");
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    public static Parsed parse(byte[] xlsx) {
        List<String> errors = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet header = wb.getSheet("Header");
            Sheet journal = wb.getSheet("Journal");
            Sheet closing = wb.getSheet("Closing");
            for (String name : List.of("Header", "Journal", "Closing")) {
                if (wb.getSheet(name) == null) {
                    errors.add("The workbook has no '" + name + "' sheet");
                }
            }
            if (!errors.isEmpty()) {
                return new Parsed(null, errors);
            }
            Header h = header(header, errors);
            List<Line> lines = new ArrayList<>();
            Map<String, Integer> jc = columns(journal, JOURNAL_COLUMNS, "Journal", errors);
            Map<String, Integer> cc = columns(closing, CLOSING_COLUMNS, "Closing", errors);
            if (!errors.isEmpty()) {
                return new Parsed(null, errors);
            }
            for (int r = 1; r <= journal.getLastRowNum(); r++) {
                Row row = journal.getRow(r);
                if (blank(row)) {
                    continue;
                }
                String where = "Journal row " + (r + 1);
                lines.add(new Line(r + 1, text(row, jc.get("group")), text(row, jc.get("entry")), text(row, jc.get("account")),
                    upper(text(row, jc.get("side"))), amount(row, jc.get("amount"), where, errors),
                    text(row, jc.get("movement")), text(row, jc.get("note"))));
            }
            List<Closing> closings = new ArrayList<>();
            for (int r = 1; r <= closing.getLastRowNum(); r++) {
                Row row = closing.getRow(r);
                if (blank(row)) {
                    continue;
                }
                String where = "Closing row " + (r + 1);
                closings.add(new Closing(r + 1, text(row, cc.get("group")), signed(row, cc.get("lrc"), where, errors),
                    signed(row, cc.get("lic"), where, errors), signed(row, cc.get("csm"), where, errors),
                    signed(row, cc.get("arc"), where, errors), signed(row, cc.get("aic"), where, errors),
                    signed(row, cc.get("ri_csm"), where, errors)));
            }
            return new Parsed(errors.isEmpty() ? new EngineResults(h, lines, closings) : null, errors);
        } catch (Exception e) {
            return new Parsed(null, List.of("The file is not an Excel workbook (.xlsx) the template describes: "
                + e.getClass().getSimpleName()));
        }
    }

    private static Header header(Sheet sheet, List<String> errors) {
        Map<String, String> values = new HashMap<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String label = text(row, 0);
            if (label != null) {
                values.put(label.toLowerCase(), text(row, 1));
            }
        }
        Integer extract = null;
        String raw = values.get("extract");
        if (raw != null) {
            try {
                extract = Integer.valueOf(raw.replaceFirst("^.*#", "").trim());
            } catch (NumberFormatException e) {
                errors.add("Header: extract must be the extract's number, got '" + raw + "'");
            }
        }
        return new Header(values.get("period"), extract, values.get("engine reference"), values.get("engine"),
            values.get("measurement date"));
    }

    private static Map<String, Integer> columns(Sheet sheet, List<String> wanted, String name, List<String> errors) {
        Map<String, Integer> columns = new HashMap<>();
        Row head = sheet.getRow(0);
        if (head != null) {
            for (int c = 0; c < head.getLastCellNum(); c++) {
                String label = text(head, c);
                if (label != null) {
                    columns.put(label.toLowerCase(), c);
                }
            }
        }
        for (String column : wanted) {
            if (!columns.containsKey(column) && !column.equals("movement") && !column.equals("note")) {
                errors.add(name + ": no '" + column + "' column (columns: " + String.join(", ", wanted) + ")");
            }
        }
        return columns;
    }

    private static final DataFormatter FORMAT = new DataFormatter();

    private static boolean blank(Row row) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (!FORMAT.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String text(Row row, Integer column) {
        if (row == null || column == null) {
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value;
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            value = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        } else if (type == CellType.NUMERIC) {
            value = cell.getLocalDateTimeCellValue().toLocalDate().toString();
        } else {
            value = FORMAT.formatCellValue(cell);
        }
        value = value == null ? null : value.strip();
        return value == null || value.isEmpty() ? null : value;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static BigDecimal amount(Row row, Integer column, String where, List<String> errors) {
        String raw = text(row, column);
        if (raw == null) {
            errors.add(where + ": no amount");
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            errors.add(where + ": amount '" + raw + "' is not a number");
            return null;
        }
    }

    private static BigDecimal signed(Row row, Integer column, String where, List<String> errors) {
        String raw = text(row, column);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            errors.add(where + ": '" + raw + "' is not a number");
            return null;
        }
    }

    // ---- checking ---------------------------------------------------------------------------------------------------

    /** Every fault against the extract the results answer; empty when they may be approved. */
    public List<String> problems(Context ctx) {
        List<String> problems = new ArrayList<>();
        String extractName = "extract " + header.period() + " #" + header.extractNumber();
        if (header.period() == null || !header.period().matches("\\d{4}-(0[1-9]|1[0-2])")) {
            problems.add("Header: period must be YYYY-MM, got '" + header.period() + "'");
        } else if (ctx.periodStatus() != PeriodStatus.CLOSING) {
            problems.add("Header: period " + header.period() + " is " + ctx.periodStatus()
                + "; results are loaded into a closing period");
        }
        if (header.extractNumber() == null) {
            problems.add("Header: name the extract these results answer (its number)");
        } else if (ctx.extractGroups() == null) {
            problems.add("Header: there is no " + extractName);
        }
        if (header.engineReference() == null) {
            problems.add("Header: give the engine's own run reference");
        } else if (ctx.knownReferences().contains(header.engineReference())) {
            problems.add("Header: engine reference " + header.engineReference()
                + " was loaded before; a new run needs a new reference");
        }
        Set<String> extracted = ctx.extractGroups() == null ? Set.of() : new LinkedHashSet<>(ctx.extractGroups());
        Map<String, BigDecimal[]> balance = new LinkedHashMap<>();
        for (Line l : lines) {
            String where = "Journal row " + l.row();
            if (l.group() == null) {
                problems.add(where + ": no group");
                continue;
            }
            if (ctx.extractGroups() != null && !extracted.contains(l.group())) {
                problems.add(where + ": group " + l.group() + " is not in " + extractName);
            }
            if (l.entry() == null || !l.entry().matches("P-(0[1-9]|1[0-7])")) {
                problems.add(where + ": " + l.entry() + " is not one of P-01 to P-17");
            }
            boolean reinsurance = isReinsurance(l.group());
            boolean allowed = l.account() != null && ctx.postingAccounts().contains(l.account())
                && (reinsurance ? REINSURANCE_ACCOUNTS.contains(l.account())
                    : POLICY_ACCOUNTS.contains(l.account()) || l.account().startsWith("22"));
            if (!allowed) {
                problems.add(where + ": " + l.group() + " " + l.entry() + " account " + l.account()
                    + " is not one the engine posts for a " + (reinsurance ? "reinsurance" : "policy") + " group");
            }
            boolean sideOk = "DR".equals(l.side()) || "CR".equals(l.side());
            if (!sideOk) {
                problems.add(where + ": side must be DR or CR, got '" + l.side() + "'");
            }
            if (l.amount() != null) {
                if (l.amount().signum() <= 0) {
                    problems.add(where + ": amount must be above zero");
                } else if (l.amount().stripTrailingZeros().scale() > 2) {
                    problems.add(where + ": amount " + l.amount().toPlainString() + " has more than two decimals");
                } else if (sideOk) {
                    BigDecimal[] drCr = balance.computeIfAbsent(l.group(), g -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    int i = "DR".equals(l.side()) ? 0 : 1;
                    drCr[i] = drCr[i].add(l.amount());
                }
            }
        }
        balance.forEach((group, drCr) -> {
            if (drCr[0].compareTo(drCr[1]) != 0) {
                problems.add("Journal: " + group + " does not balance: Dr " + drCr[0].toPlainString() + ", Cr "
                    + drCr[1].toPlainString());
            }
        });
        Set<String> closed = new LinkedHashSet<>();
        for (Closing c : closings) {
            String where = "Closing row " + c.row();
            if (c.group() == null) {
                problems.add(where + ": no group");
                continue;
            }
            closed.add(c.group());
            if (ctx.extractGroups() != null && !extracted.contains(c.group())) {
                problems.add(where + ": group " + c.group() + " is not in " + extractName);
            }
            if (isReinsurance(c.group())) {
                if (c.arc() == null || c.aic() == null || c.riCsm() == null || c.lrc() != null || c.lic() != null
                        || c.csm() != null) {
                    problems.add(where + ": " + c.group() + " is a reinsurance group: give arc, aic and ri_csm, not lrc,"
                        + " lic or csm");
                }
            } else if (c.lrc() == null || c.lic() == null || c.csm() == null || c.arc() != null || c.aic() != null
                    || c.riCsm() != null) {
                problems.add(where + ": " + c.group() + " is a policy group: give lrc, lic and csm, not arc, aic or ri_csm");
            }
        }
        for (String group : extracted) {
            if (!isReinsurance(group) && !closed.contains(group)) {
                problems.add("Closing: group " + group + " of the extract has no closing row");
            }
        }
        return problems;
    }
}

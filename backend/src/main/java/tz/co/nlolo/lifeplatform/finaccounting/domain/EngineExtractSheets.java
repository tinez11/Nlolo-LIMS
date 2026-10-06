package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The extract the IFRS 17 engine is sent for a period (IFRS 17 I5a, month-end step 6; guide 5.3, no double counting):
 * the period's actual cash flows by group, every measurement account's opening and closing balance by group, and the
 * in-force policies with their group. Balances are net Dr - Cr, as booked: the engine applies its own signs. Pure.
 */
public record EngineExtractSheets(List<CashFlowRow> cashFlows, List<BalanceRow> balances, List<PolicyRow> policies) {

    /** One group x movement x account x side total of the period's postings. */
    public record CashFlowRow(String group, String movement, String account, String side, BigDecimal amount,
                              String currency) {}

    /** One group's account, net Dr - Cr before the period (opening) and at its end (closing). */
    public record BalanceRow(String group, String account, BigDecimal opening, BigDecimal closing, String currency) {}

    /** One in-force policy and what the engine needs of it. */
    public record PolicyRow(String policyNumber, String group, String model, LocalDate issueDate, BigDecimal sumAssured,
                            BigDecimal premium, String frequency, String status, BigDecimal fundValue,
                            BigDecimal investmentComponent, String currency) {}

    public static final List<String> SHEET_NAMES = List.of("cash-flows", "balances", "policies");

    private static final List<String> CASH_FLOW_HEADER = List.of("group", "movement", "account", "side", "amount", "currency");
    private static final List<String> BALANCE_HEADER = List.of("group", "account", "opening_net_dr_minus_cr",
        "closing_net_dr_minus_cr", "currency");
    private static final List<String> POLICY_HEADER = List.of("policy", "group", "model", "issue_date", "sum_assured",
        "premium", "frequency", "status", "fund_value", "investment_component", "currency");

    public EngineExtractSheets {
        cashFlows = List.copyOf(cashFlows);
        balances = List.copyOf(balances);
        policies = List.copyOf(policies);
    }

    /** Every group any sheet names, sorted: what an engine run for this period may report on. */
    public List<String> groups() {
        TreeSet<String> groups = new TreeSet<>();   // sorted; and a TreeSet takes no null, so none is added
        cashFlows.stream().map(CashFlowRow::group).filter(java.util.Objects::nonNull).forEach(groups::add);
        balances.stream().map(BalanceRow::group).filter(java.util.Objects::nonNull).forEach(groups::add);
        policies.stream().map(PolicyRow::group).filter(java.util.Objects::nonNull).forEach(groups::add);
        return List.copyOf(groups);
    }

    /** One sheet as CSV: {@code cash-flows}, {@code balances} or {@code policies}. */
    public byte[] csv(String sheet) {
        List<List<Object>> rows = rows(sheet);
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\n").build())) {
            for (List<Object> row : rows) {
                printer.printRecord(row.stream().map(EngineExtractSheets::text).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The three sheets in one workbook, after an About sheet naming the period and the extract's number. */
    public byte[] xlsx(String period, int number) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            Sheet about = wb.createSheet("About");
            about.createRow(0).createCell(0).setCellValue("IFRS 17 engine extract");
            Row id = about.createRow(1);
            id.createCell(0).setCellValue("Extract");
            id.createCell(1).setCellValue(period + " #" + number);
            Row groups = about.createRow(2);
            groups.createCell(0).setCellValue("Groups");
            groups.createCell(1).setCellValue(String.join(", ", groups()));
            write(wb.createSheet("Cash flows"), rows("cash-flows"), money);
            write(wb.createSheet("Balances"), rows("balances"), money);
            write(wb.createSheet("Policies"), rows("policies"), money);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<List<Object>> rows(String sheet) {
        List<List<Object>> rows = new ArrayList<>();
        switch (sheet) {
            case "cash-flows" -> {
                rows.add(List.copyOf(CASH_FLOW_HEADER));
                cashFlows.forEach(r -> rows.add(cells(r.group(), r.movement(), r.account(), r.side(), r.amount(), r.currency())));
            }
            case "balances" -> {
                rows.add(List.copyOf(BALANCE_HEADER));
                balances.forEach(r -> rows.add(cells(r.group(), r.account(), r.opening(), r.closing(), r.currency())));
            }
            case "policies" -> {
                rows.add(List.copyOf(POLICY_HEADER));
                policies.forEach(r -> rows.add(cells(r.policyNumber(), r.group(), r.model(), r.issueDate(), r.sumAssured(),
                    r.premium(), r.frequency(), r.status(), r.fundValue(), r.investmentComponent(), r.currency())));
            }
            default -> throw new IllegalArgumentException("A sheet is cash-flows, balances or policies, not '" + sheet + "'");
        }
        return rows;
    }

    private static List<Object> cells(Object... values) {
        List<Object> row = new ArrayList<>();
        for (Object v : values) {
            row.add(v);
        }
        return row;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof BigDecimal d ? d.toPlainString() : value.toString();
    }

    private static void write(Sheet sheet, List<List<Object>> rows, CellStyle money) {
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                Object v = values.get(c);
                if (v instanceof BigDecimal d) {
                    var cell = row.createCell(c);
                    cell.setCellValue(d.doubleValue());
                    cell.setCellStyle(money);
                } else {
                    row.createCell(c).setCellValue(text(v));
                }
            }
        }
    }
}

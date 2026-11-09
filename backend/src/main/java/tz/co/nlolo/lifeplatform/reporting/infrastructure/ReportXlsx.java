package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedXlsx;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A report as an Excel workbook (2026-11-09, report formatting): a Summary sheet under the company's letterhead -- the
 * title, what it covers, who prepared it and when, the notices, the headline figures with their comparison -- then one
 * sheet per section. Figures are real numbers so a reader can sum them (money as #,##0.00 with a loss in brackets,
 * counts whole, percentages as percentages, days as dates); headings in the company's colour, frozen, with filters;
 * subtotals and totals bold; set up to print across the page with the headings on every page.
 */
final class ReportXlsx {
    private ReportXlsx() {}

    static byte[] render(ReportResult result, Letterhead letterhead, String preparedBy) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb, letterhead);
            summary(wb, st, result, letterhead, preparedBy);

            Set<String> names = new HashSet<>(Set.of("Summary"));
            for (ReportSection section : result.sections()) {
                List<ReportColumn> columns = section.columns().stream().filter(c -> !c.key().startsWith("_")).toList();
                Sheet sheet = wb.createSheet(uniqueName(section.title(), names));
                Row title = sheet.createRow(0);
                title.createCell(0).setCellValue(section.title());
                title.getCell(0).setCellStyle(st.sectionTitle);
                int heading = 2;
                Row header = sheet.createRow(heading);
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(columns.get(c).label());
                    cell.setCellStyle(st.heading);
                }
                int r = heading + 1;
                for (Map<String, String> values : section.rows()) {
                    String kind = values.getOrDefault("_kind", "LINE");
                    int level = parseLevel(values.get("_level"));
                    write(sheet.createRow(r++), columns, values, st, !"LINE".equals(kind), level);
                }
                if (!section.totals().isEmpty()) write(sheet.createRow(r++), columns, section.totals(), st, true, 0);
                for (int c = 0; c < columns.size(); c++) {
                    sheet.autoSizeColumn(c);
                    sheet.setColumnWidth(c, Math.min(Math.max(sheet.getColumnWidth(c), 11 * 256), 60 * 256));
                }
                BrandedXlsx.finish(sheet, letterhead, heading, columns.size(), r - 1);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The cover: letterhead, title, coverage, preparer, notices, and the headline figures. */
    private static void summary(XSSFWorkbook wb, Styles st, ReportResult result, Letterhead letterhead,
                                String preparedBy) {
        Sheet sheet = wb.createSheet("Summary");
        int r = BrandedXlsx.letterhead(wb, sheet, letterhead);
        cell(sheet.createRow(r++), 0, result.title(), st.title);
        if (present(result.filterSummary())) cell(sheet.createRow(r++), 0, result.filterSummary(), null);
        if (present(result.comparisonLabel())) cell(sheet.createRow(r++), 0, result.comparisonLabel(), st.muted);
        cell(sheet.createRow(r++), 0, "Prepared" + (present(preparedBy) ? " by " + preparedBy : "") + " on "
            + ReportFormat.stamp(result.generatedAt()), st.muted);
        r++;
        for (String notice : result.notices()) cell(sheet.createRow(r++), 0, notice, st.notice);
        if (!result.notices().isEmpty()) r++;
        if (!result.figures().isEmpty()) {
            Row head = sheet.createRow(r++);
            cell(head, 0, "Figure", st.heading);
            cell(head, 1, "Value", st.heading);
            cell(head, 2, "Compared with", st.heading);
            for (ReportFigure f : result.figures()) {
                Row row = sheet.createRow(r++);
                cell(row, 0, f.label(), f.lead() ? st.bold : null);
                cell(row, 1, ReportFormat.figure(f), f.lead() ? st.boldRight : st.right);
                String caption = ReportFormat.caption(f);
                cell(row, 2, caption == null ? "" : caption, st.muted);
            }
        }
        sheet.setColumnWidth(0, 44 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 34 * 256);
        BrandedXlsx.finish(sheet, letterhead, -1, 0, 0);
    }

    private static void write(Row row, List<ReportColumn> columns, Map<String, String> values, Styles st, boolean bold,
                              int level) {
        for (int c = 0; c < columns.size(); c++) {
            ReportColumn column = columns.get(c);
            String value = values.getOrDefault(column.key(), "");
            Cell cell = row.createCell(c);
            if (ReportFormat.numeric(column.type()) && value.matches("-?\\d+(\\.\\d+)?")) {
                BigDecimal n = new BigDecimal(value);
                if (column.type() == ColumnType.PERCENT) {
                    cell.setCellValue(n.movePointLeft(2).doubleValue());
                    cell.setCellStyle(bold ? st.percentBold : st.percent);
                } else {
                    cell.setCellValue(n.doubleValue());
                    boolean count = column.type() == ColumnType.COUNT;
                    cell.setCellStyle(count ? (bold ? st.countBold : st.count) : (bold ? st.moneyBold : st.money));
                }
            } else if (column.type() == ColumnType.DATE && value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                cell.setCellValue(LocalDate.parse(value));
                cell.setCellStyle(st.date);
            } else {
                cell.setCellValue(value);
                if (c == 0 && level > 0) cell.setCellStyle(st.indent(level, bold));
                else if (bold) cell.setCellStyle(st.bold);
            }
        }
    }

    private static int parseLevel(String v) {
        try {
            return v == null ? 0 : Math.max(0, Math.min(10, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void cell(Row row, int column, String text, CellStyle style) {
        Cell c = row.createCell(column);
        c.setCellValue(text == null ? "" : text);
        if (style != null) c.setCellStyle(style);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String uniqueName(String title, Set<String> taken) {
        String base = WorkbookUtil.createSafeSheetName(title.length() > 28 ? title.substring(0, 28) : title);
        String name = base;
        for (int i = 2; taken.contains(name); i++) name = base + " " + i;
        taken.add(name);
        return name;
    }

    /** One workbook's styles, made once: Excel caps how many a file may hold. */
    private static final class Styles {
        final XSSFWorkbook wb;
        final CellStyle bold;
        final CellStyle right;
        final CellStyle boldRight;
        final CellStyle muted;
        final CellStyle title;
        final CellStyle sectionTitle;
        final CellStyle notice;
        final XSSFCellStyle heading;
        final CellStyle money;
        final CellStyle moneyBold;
        final CellStyle count;
        final CellStyle countBold;
        final CellStyle percent;
        final CellStyle percentBold;
        final CellStyle date;
        private final Map<String, CellStyle> indents = new java.util.HashMap<>();

        Styles(XSSFWorkbook wb, Letterhead l) {
            this.wb = wb;
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            right = wb.createCellStyle();
            right.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            boldRight = wb.createCellStyle();
            boldRight.cloneStyleFrom(bold);
            boldRight.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            Font mutedFont = wb.createFont();
            mutedFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            muted = wb.createCellStyle();
            muted.setFont(mutedFont);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            title = wb.createCellStyle();
            title.setFont(titleFont);
            Font sectionFont = wb.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 12);
            sectionTitle = wb.createCellStyle();
            sectionTitle.setFont(sectionFont);
            Font noticeFont = wb.createFont();
            noticeFont.setBold(true);
            noticeFont.setColor(IndexedColors.BROWN.getIndex());
            notice = wb.createCellStyle();
            notice.setFont(noticeFont);
            notice.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            notice.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            heading = BrandedXlsx.heading(wb, l);
            String moneyFormat = "#,##0.00;(#,##0.00)";
            money = numberStyle(moneyFormat, false);
            moneyBold = numberStyle(moneyFormat, true);
            count = numberStyle("#,##0", false);
            countBold = numberStyle("#,##0", true);
            percent = numberStyle("0.0%", false);
            percentBold = numberStyle("0.0%", true);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
        }

        private CellStyle numberStyle(String format, boolean isBold) {
            CellStyle s = wb.createCellStyle();
            if (isBold) s.cloneStyleFrom(bold);
            s.setDataFormat(wb.createDataFormat().getFormat(format));
            return s;
        }

        CellStyle indent(int level, boolean isBold) {
            return indents.computeIfAbsent(level + ":" + isBold, k -> {
                CellStyle s = wb.createCellStyle();
                if (isBold) s.cloneStyleFrom(bold);
                s.setIndention((short) level);
                return s;
            });
        }
    }
}

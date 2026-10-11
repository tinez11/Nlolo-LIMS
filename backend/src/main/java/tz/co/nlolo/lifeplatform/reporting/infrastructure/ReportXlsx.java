package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An About sheet, then one sheet per section. Counts, money and percentages are written as numbers so a
 * reader can sum them; a blank figure (a rate with no denominator) stays blank. */
final class ReportXlsx {
    private ReportXlsx() {}

    static byte[] render(ReportResult result) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle count = wb.createCellStyle();
            count.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            Sheet about = wb.createSheet("About");
            about.createRow(0).createCell(0).setCellValue(result.title());
            Row summary = about.createRow(1);
            summary.createCell(0).setCellValue("Filters");
            summary.createCell(1).setCellValue(result.filterSummary());
            Row generated = about.createRow(2);
            generated.createCell(0).setCellValue("Generated");
            generated.createCell(1).setCellValue(result.generatedAt().toString());

            Set<String> names = new HashSet<>(Set.of("About"));
            for (ReportSection section : result.sections()) {
                Sheet sheet = wb.createSheet(uniqueName(section.title(), names));
                Row header = sheet.createRow(0);
                for (int c = 0; c < section.columns().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(section.columns().get(c).label());
                    cell.setCellStyle(bold);
                }
                int r = 1;
                for (Map<String, String> values : section.rows()) {
                    write(sheet.createRow(r++), section.columns(), values, money, count, null);
                }
                if (!section.totals().isEmpty()) {
                    write(sheet.createRow(r), section.columns(), section.totals(), money, count, bold);
                }
                for (int c = 0; c < section.columns().size(); c++) sheet.autoSizeColumn(c);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Row row, List<ReportColumn> columns, Map<String, String> values, CellStyle money,
                              CellStyle count, CellStyle bold) {
        for (int c = 0; c < columns.size(); c++) {
            ReportColumn column = columns.get(c);
            String value = values.getOrDefault(column.key(), "");
            Cell cell = row.createCell(c);
            boolean numeric = column.type() == ColumnType.COUNT || column.type() == ColumnType.MONEY
                || column.type() == ColumnType.PERCENT;
            if (numeric && !value.isBlank() && value.matches("-?\\d+(\\.\\d+)?")) {
                cell.setCellValue(new BigDecimal(value).doubleValue());
                cell.setCellStyle(column.type() == ColumnType.COUNT ? count : money);
            } else {
                cell.setCellValue(value);
                if (bold != null) cell.setCellStyle(bold);
            }
        }
    }

    private static String uniqueName(String title, Set<String> taken) {
        String base = WorkbookUtil.createSafeSheetName(title.length() > 28 ? title.substring(0, 28) : title);
        String name = base;
        for (int i = 2; taken.contains(name); i++) name = base + " " + i;
        taken.add(name);
        return name;
    }
}

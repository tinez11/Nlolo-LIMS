package tz.co.nlolo.lifeplatform.omnichannel.domain;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A {@link CustomerDocument} as an Excel workbook: the title and header facts above the table, money as real
 * numbers (so a customer's sums work) formatted #,##0.00, dates as real dates, the header row frozen, and the
 * totals beneath.
 */
public final class DocumentXlsx {
    private DocumentXlsx() {}

    public static byte[] render(CustomerDocument d) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet(sheetName(d.title()));
            Font boldFont = book.createFont();
            boldFont.setBold(true);
            Font titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            CellStyle title = book.createCellStyle();
            title.setFont(titleFont);
            CellStyle label = book.createCellStyle();
            CellStyle value = book.createCellStyle();
            value.setFont(boldFont);
            CellStyle head = book.createCellStyle();
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            CellStyle money = book.createCellStyle();
            money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            money.setAlignment(HorizontalAlignment.RIGHT);
            CellStyle date = book.createCellStyle();
            date.setDataFormat(book.createDataFormat().getFormat("dd/mm/yyyy"));
            date.setAlignment(HorizontalAlignment.LEFT);
            CellStyle totalValue = book.createCellStyle();
            totalValue.setFont(boldFont);
            totalValue.setAlignment(HorizontalAlignment.RIGHT);

            int r = 0;
            set(sheet.createRow(r++), 0, d.issuer(), label);
            set(sheet.createRow(r++), 0, d.title(), title);
            r++;
            for (CustomerDocument.Field f : d.header()) {
                Row row = sheet.createRow(r++);
                set(row, 0, f.label(), label);
                set(row, 1, f.value(), value);
            }
            r++;
            int headerRow = r;
            Row header = sheet.createRow(r++);
            for (int i = 0; i < d.columns().size(); i++) set(header, i, d.columns().get(i).label(), head);
            for (var values : d.rows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < d.columns().size(); i++) {
                    Object v = i < values.size() ? values.get(i) : null;
                    if (v == null) continue;
                    Cell cell = row.createCell(i);
                    switch (d.columns().get(i).kind()) {
                        case MONEY -> {
                            if (v instanceof BigDecimal b) { cell.setCellValue(b.doubleValue()); cell.setCellStyle(money); }
                            else cell.setCellValue(v.toString());
                        }
                        case DATE -> {
                            if (v instanceof LocalDate ld) { cell.setCellValue(ld); cell.setCellStyle(date); }
                            else cell.setCellValue(v.toString());
                        }
                        case TEXT -> cell.setCellValue(v.toString());
                    }
                }
            }
            r++;
            int labelColumn = Math.max(0, d.columns().size() - 2);
            for (CustomerDocument.Field t : d.totals()) {
                Row row = sheet.createRow(r++);
                set(row, labelColumn, t.label(), label);
                set(row, labelColumn + 1, t.value(), totalValue);
            }
            r++;
            for (String note : d.notes()) set(sheet.createRow(r++), 0, note, label);

            sheet.createFreezePane(0, headerRow + 1);
            for (int i = 0; i < d.columns().size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(Math.max(sheet.getColumnWidth(i), 12 * 256), 45 * 256));
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + d.title(), e);
        }
    }

    private static void set(Row row, int column, String text, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(text == null ? "" : text);
        cell.setCellStyle(style);
    }

    /** Excel refuses sheet names over 31 characters or with [ ] : * ? / \. */
    private static String sheetName(String title) {
        String t = title.replaceAll("[\\[\\]:*?/\\\\]", " ");
        return t.length() > 31 ? t.substring(0, 31) : t;
    }
}

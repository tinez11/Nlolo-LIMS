package tz.co.nlolo.lifeplatform.organisation.api;

import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The company's branding on an Excel workbook (2026-11-09, organisation settings): its logo and name above the content,
 * its colour on table headings, and a page set up to print -- across the page, fitted to its width, headings repeated
 * on every page, the company and the page number in the header and footer.
 */
public final class BrandedXlsx {

    private BrandedXlsx() {}

    /**
     * Writes the letterhead into the first rows of {@code sheet}: the logo (if any) at the left, the company's name and
     * its contacts beside it. Returns the first free row.
     */
    public static int letterhead(XSSFWorkbook book, Sheet sheet, Letterhead l) {
        int textColumn = 0;
        if (l.logo() != null && l.logo().length > 0) {
            try {
                boolean png = l.logo().length > 3 && (l.logo()[0] & 0xFF) == 0x89;
                int picture = book.addPicture(l.logo(), png ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG);
                ClientAnchor anchor = book.getCreationHelper().createClientAnchor();
                anchor.setCol1(0);
                anchor.setRow1(0);
                anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                // Fitted to about 56 pixels high, whatever size it was uploaded at.
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(l.logo()));
                double scale = image == null ? 1.0 : Math.min(1.0, 56.0 / image.getHeight());
                sheet.createDrawingPatriarch().createPicture(anchor, picture).resize(scale);
                textColumn = 2;
            } catch (RuntimeException | java.io.IOException e) {
                textColumn = 0; // An unreadable logo costs the logo, never the export.
            }
        }
        XSSFCellStyle name = book.createCellStyle();
        XSSFFont nameFont = book.createFont();
        nameFont.setBold(true);
        nameFont.setFontHeightInPoints((short) 14);
        nameFont.setColor(new XSSFColor(colour(l.primaryColour(), Letterhead.DEFAULT_PRIMARY), null));
        name.setFont(nameFont);
        XSSFCellStyle muted = book.createCellStyle();
        XSSFFont mutedFont = book.createFont();
        mutedFont.setColor(new XSSFColor(new Color(104, 112, 122), null));
        mutedFont.setFontHeightInPoints((short) 9);
        muted.setFont(mutedFont);

        Row first = row(sheet, 0);
        first.createCell(textColumn).setCellValue(l.displayName());
        first.getCell(textColumn).setCellStyle(name);
        int r = 1;
        for (String line : contacts(l)) {
            Row row = row(sheet, r++);
            row.createCell(textColumn).setCellValue(line);
            row.getCell(textColumn).setCellStyle(muted);
        }
        return Math.max(r, 4) + 1;
    }

    /** A table heading cell in the company's colour, white bold text. */
    public static XSSFCellStyle heading(XSSFWorkbook book, Letterhead l) {
        XSSFCellStyle head = book.createCellStyle();
        head.setFillForegroundColor(new XSSFColor(colour(l.primaryColour(), Letterhead.DEFAULT_PRIMARY), null));
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = book.createFont();
        font.setBold(true);
        font.setColor(new XSSFColor(Color.WHITE, null));
        head.setFont(font);
        head.setWrapText(true);
        return head;
    }

    /**
     * The sheet ready to print and to work in: headings frozen at {@code headingRow}, filters on its columns, landscape,
     * fitted to the page width, the heading row repeated on every page, the company in the header, the page in the
     * footer.
     */
    public static void finish(Sheet sheet, Letterhead l, int headingRow, int columns, int lastRow) {
        if (columns > 0 && headingRow >= 0) {
            sheet.createFreezePane(0, headingRow + 1);
            if (lastRow > headingRow) {
                sheet.setAutoFilter(new CellRangeAddress(headingRow, lastRow, 0, columns - 1));
            }
            sheet.setRepeatingRows(new CellRangeAddress(headingRow, headingRow, 0, columns - 1));
        }
        PrintSetup print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setPaperSize("LETTER".equals(l.paper()) ? PrintSetup.LETTER_PAPERSIZE : PrintSetup.A4_PAPERSIZE);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        sheet.setFitToPage(true);
        sheet.getHeader().setLeft(l.displayName());
        sheet.getFooter().setLeft(l.footerText() == null ? "" : l.footerText());
        sheet.getFooter().setRight("Page &P of &N");
    }

    private static List<String> contacts(Letterhead l) {
        List<String> lines = new ArrayList<>();
        if (present(l.address())) lines.add(l.address());
        List<String> reach = new ArrayList<>();
        if (present(l.phone())) reach.add(l.phone());
        if (present(l.email())) reach.add(l.email());
        if (present(l.website())) reach.add(l.website());
        if (!reach.isEmpty()) lines.add(String.join(" · ", reach));
        if (present(l.registrationNumber())) lines.add("TIRA reg. " + l.registrationNumber());
        return lines;
    }

    private static Row row(Sheet sheet, int r) {
        Row row = sheet.getRow(r);
        return row == null ? sheet.createRow(r) : row;
    }

    private static Color colour(String hex, String fallback) {
        return BrandedPdf.colour(hex, fallback);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}

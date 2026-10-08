package tz.co.nlolo.lifeplatform.omnichannel.domain;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@link CustomerDocument} as a clean, printable PDF: the issuer and title, the header facts in two columns,
 * the table with ruled header and banded rows (money right-aligned), the totals, the notes, and "Page X of Y"
 * on every page. The table's header repeats on each page it continues onto.
 *
 * <p>Standard-14 Helvetica: no font file to ship. It encodes WinAnsi only, so any other character a person
 * typed (a name, a reason) is replaced rather than allowed to fail the whole document.
 */
public final class DocumentPdf {
    private DocumentPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGIN = 40f;
    private static final float ROW = 16f;
    private static final float PAD = 4f;
    private static final Color INK = new Color(30, 30, 30);
    private static final Color MUTED = new Color(110, 110, 110);
    private static final Color RULE = new Color(200, 200, 200);
    private static final Color BAND = new Color(244, 245, 247);
    private static final Color HEAD = new Color(225, 231, 236);

    public static byte[] render(CustomerDocument d) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new Layout(doc, d).write();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render " + d.title(), e);
        }
    }

    static String cell(Object value, CustomerDocument.Kind kind) {
        if (value == null) return "";
        return switch (kind) {
            case MONEY -> value instanceof BigDecimal b ? money(b) : value.toString();
            case DATE -> value instanceof LocalDate date ? DMY.format(date) : value.toString();
            case TEXT -> value.toString();
        };
    }

    public static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static final class Layout {
        private final PDDocument doc;
        private final CustomerDocument d;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDRectangle size;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream s;
        private float y;
        private float[] widths;

        Layout(PDDocument doc, CustomerDocument d) {
            this.doc = doc;
            this.d = d;
            this.size = d.landscape() ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
        }

        void write() throws IOException {
            float tableWidth = size.getWidth() - 2 * MARGIN;
            float total = 0;
            for (CustomerDocument.Column c : d.columns()) total += c.weight();
            widths = new float[d.columns().size()];
            for (int i = 0; i < widths.length; i++) widths[i] = tableWidth * d.columns().get(i).weight() / total;

            newPage();
            text(bold, 9, MARGIN, y, d.issuer().toUpperCase(Locale.ROOT), MUTED);
            y -= 18;
            text(bold, 16, MARGIN, y, d.title(), INK);
            y -= 22;
            writeHeader();
            y -= 8;
            tableHeader();
            for (int r = 0; r < d.rows().size(); r++) {
                if (y - ROW < MARGIN + 30) {
                    newPage();
                    tableHeader();
                }
                if (r % 2 == 1) fill(MARGIN, y - ROW, tableWidth, ROW, BAND);
                writeRow(d.rows().get(r), regular, INK);
                y -= ROW;
            }
            rule(MARGIN, y, MARGIN + tableWidth, y);
            if (d.rows().isEmpty()) {
                y -= ROW;
                text(regular, 9, MARGIN + PAD, y + 5, "Nothing to show for this period.", MUTED);
            }
            y -= 12;
            writeTotals(tableWidth);
            for (String note : d.notes()) {
                if (y < MARGIN + 30) newPage();
                text(regular, 8, MARGIN, y, note, MUTED);
                y -= 11;
            }
            s.close();
            footers();
        }

        /**
         * The header facts in two columns. A value too long for its column wraps onto the lines below and pushes the
         * rest of its column down (2026-10-08) -- an address ran on into the second column's "Ends".
         */
        private void writeHeader() throws IOException {
            float half = (size.getWidth() - 2 * MARGIN) / 2;
            float valueWidth = half - 95 - 12;
            List<CustomerDocument.Field> fields = d.header();
            int perColumn = (fields.size() + 1) / 2;
            float[] cursor = { y, y };
            for (int i = 0; i < fields.size(); i++) {
                int column = i < perColumn ? 0 : 1;
                float x = MARGIN + column * half;
                text(regular, 8, x, cursor[column], fields.get(i).label(), MUTED);
                List<String> lines = wrap(bold, 9, safe(fields.get(i).value()), valueWidth);
                for (int k = 0; k < lines.size(); k++) {
                    text(bold, 9, x + 95, cursor[column] - k * 11, lines.get(k), INK);
                }
                cursor[column] -= 13 + (lines.size() - 1) * 11;
            }
            y = Math.min(cursor[0], cursor[1]) + 13 - 12;
        }

        /** Words onto lines no wider than {@code max}; a single word longer than a line is shortened to fit. */
        private static List<String> wrap(PDType1Font font, float sz, String v, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : v.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * sz <= max) {
                    line = new StringBuilder(candidate);
                } else {
                    if (!line.isEmpty()) lines.add(line.toString());
                    line = new StringBuilder(fit(font, sz, word, max));
                }
            }
            if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
            return lines;
        }

        private void tableHeader() throws IOException {
            float width = size.getWidth() - 2 * MARGIN;
            fill(MARGIN, y - ROW, width, ROW, HEAD);
            List<Object> labels = new ArrayList<>();
            for (CustomerDocument.Column c : d.columns()) labels.add(c.label());
            writeRow(labels, bold, INK, true);
            y -= ROW;
        }

        private void writeRow(List<Object> values, PDType1Font font, Color color) throws IOException {
            writeRow(values, font, color, false);
        }

        private void writeRow(List<Object> values, PDType1Font font, Color color, boolean labels) throws IOException {
            float x = MARGIN;
            for (int i = 0; i < widths.length; i++) {
                CustomerDocument.Column c = d.columns().get(i);
                String v = labels ? String.valueOf(values.get(i))
                    : cell(i < values.size() ? values.get(i) : null, c.kind());
                v = fit(font, 8.5f, safe(v), widths[i] - 2 * PAD);
                float textWidth = font.getStringWidth(v) / 1000 * 8.5f;
                // Text after a right-aligned money column gets extra room, or the two read as one.
                boolean afterMoney = i > 0 && d.columns().get(i - 1).kind() == CustomerDocument.Kind.MONEY;
                float tx = c.kind() == CustomerDocument.Kind.MONEY ? x + widths[i] - PAD - textWidth
                    : x + PAD + (afterMoney ? 8 : 0);
                text(font, 8.5f, tx, y - ROW + 5, v, color);
                x += widths[i];
            }
        }

        private void writeTotals(float tableWidth) throws IOException {
            for (CustomerDocument.Field t : d.totals()) {
                if (y < MARGIN + 30) newPage();
                String value = safe(t.value());
                float valueWidth = bold.getStringWidth(value) / 1000 * 10;
                text(regular, 9, MARGIN + tableWidth - 260, y, t.label(), MUTED);
                text(bold, 10, MARGIN + tableWidth - valueWidth - PAD, y, value, INK);
                y -= 15;
            }
            y -= 6;
        }

        /** Shortens text with ".." until it fits the column, rather than running into the next one. */
        private static String fit(PDType1Font font, float sz, String v, float max) throws IOException {
            if (font.getStringWidth(v) / 1000 * sz <= max) return v;
            String t = v;
            while (t.length() > 1 && font.getStringWidth(t + "..") / 1000 * sz > max) t = t.substring(0, t.length() - 1);
            return t + "..";
        }

        private void newPage() throws IOException {
            if (s != null) s.close();
            PDPage page = new PDPage(size);
            doc.addPage(page);
            pages.add(page);
            s = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGIN;
        }

        private void footers() throws IOException {
            for (int i = 0; i < pages.size(); i++) {
                try (PDPageContentStream f = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    String left = safe(d.issuer() + " -- " + d.title());
                    String right = "Page " + (i + 1) + " of " + pages.size();
                    write(f, regular, 7.5f, MARGIN, MARGIN - 18, left, MUTED);
                    float w = regular.getStringWidth(right) / 1000 * 7.5f;
                    write(f, regular, 7.5f, size.getWidth() - MARGIN - w, MARGIN - 18, right, MUTED);
                }
            }
        }

        private void text(PDType1Font font, float sz, float x, float atY, String v, Color color) throws IOException {
            write(s, font, sz, x, atY, safe(v), color);
        }

        private static void write(PDPageContentStream to, PDType1Font font, float sz, float x, float atY, String v, Color color)
                throws IOException {
            to.beginText();
            to.setFont(font, sz);
            to.setNonStrokingColor(color);
            to.newLineAtOffset(x, atY);
            to.showText(v);
            to.endText();
        }

        private void fill(float x, float atY, float w, float h, Color color) throws IOException {
            s.setNonStrokingColor(color);
            s.addRect(x, atY, w, h);
            s.fill();
        }

        private void rule(float x1, float atY, float x2, float y2) throws IOException {
            s.setStrokingColor(RULE);
            s.setLineWidth(0.6f);
            s.moveTo(x1, atY);
            s.lineTo(x2, y2);
            s.stroke();
        }
    }
}

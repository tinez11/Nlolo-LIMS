package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tz.co.nlolo.lifeplatform.unitlinked.api.StatementData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A unit statement as a PDF, laid out from {@link StatementData} alone -- accumulation's StatementPdf's shape:
 * Standard-14 Helvetica, a new page when one fills. A position with no price yet says "No price yet", never 0.00.
 */
final class StatementPdf {
    private StatementPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGIN = 50f;
    private static final float LINE = 14f;

    static byte[] render(StatementData s, String insurerName) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            Writer w = new Writer(doc);
            w.line(bold, 14, insurerName + " -- unit-linked statement");
            w.text("Policy " + s.policyNumber() + "    Period " + DMY.format(s.from()) + " to " + DMY.format(s.to()));
            w.gap();
            positions(w, bold, "Your units on " + DMY.format(s.from().minusDays(1)), s.opening(), s.currency());
            w.line(bold, 11, "Movements");
            if (s.lines().isEmpty()) {
                w.text("   None in this period.");
            }
            for (StatementData.Line l : s.lines()) {
                w.text("   " + DMY.format(l.date()) + "   " + label(l.type())
                    + (l.fundCode() != null ? "   " + l.fundCode() : "")
                    + (l.units() != null ? "   " + units(l.units()) + " units" : "")
                    + (l.price() != null ? " at " + l.price().toPlainString() : "")
                    + "   " + money(l.amount(), s.currency()));
            }
            w.gap();
            positions(w, bold, "Your units on " + DMY.format(s.to()), s.closing(), s.currency());
            w.line(bold, 11, "In this period");
            w.text("   Invested in units:  " + money(s.paidIn(), s.currency()));
            for (Map.Entry<String, BigDecimal> c : s.chargesByType().entrySet()) {
                w.text("   " + label(c.getKey()) + ":  " + money(c.getValue(), s.currency()));
            }
            w.text("   Paid out:  " + money(s.paidOut(), s.currency()));
            w.gap();
            w.text("Unit prices go down as well as up. Each value is at the latest approved price on or before its day,");
            w.text("shown with that price's date.");
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the unit statement for " + s.policyNumber(), e);
        }
    }

    private static void positions(Writer w, PDType1Font bold, String title, List<StatementData.Position> positions,
                                  String currency) throws IOException {
        w.line(bold, 11, title);
        for (StatementData.Position p : positions) {
            w.text("   " + p.fundCode() + "   " + units(p.units()) + " units   "
                + (p.price() == null ? "No price yet"
                    : "at " + p.price().toPlainString() + " (" + DMY.format(p.priceDate()) + ")   " + money(p.value(), currency)));
        }
        LocalDate priced = StatementData.priceDateOf(positions);
        w.line(bold, 10, "   Total value:  " + (priced == null ? "No price yet" : money(StatementData.valueOf(positions), currency)));
        w.gap();
    }

    private static String label(String type) {
        String words = type.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String units(BigDecimal units) {
        return new DecimalFormat("#,##0.000000;-#,##0.000000", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(units);
    }

    private static String money(BigDecimal amount, String currency) {
        return currency + " " + new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }

    /** Writes top to bottom, starting a new page when one fills. */
    private static final class Writer {
        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void text(String s) throws IOException {
            line(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10, s);
        }

        void line(PDType1Font font, float size, String s) throws IOException {
            if (y < MARGIN) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(s.replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
            y -= LINE;
        }

        void gap() { y -= LINE / 2; }

        void close() throws IOException { stream.close(); }
    }
}

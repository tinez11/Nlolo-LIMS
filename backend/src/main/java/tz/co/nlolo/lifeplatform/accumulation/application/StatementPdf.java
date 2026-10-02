package tz.co.nlolo.lifeplatform.accumulation.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The statement as a PDF, laid out from the SAME {@link StatementView} the console shows (rule 5).
 *
 * <p>Standard-14 Helvetica only: no font file to ship, and every glyph a statement needs (digits,
 * Latin letters, the comma and full stop) is in it. A long statement continues onto further pages.
 */
final class StatementPdf {
    private StatementPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float MARGIN = 50f;
    private static final float LINE = 14f;

    static byte[] render(StatementView s, String insurerName) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14, insurerName + " -- savings account statement");
            w.text("Policy " + s.policyNumber() + "    Period " + DMY.format(s.periodFrom()) + " to " + DMY.format(s.periodTo()));
            w.gap();
            w.text("Opening balance " + DMY.format(s.periodFrom()) + ":  " + money(s.openingBalance(), s.currency()));
            w.gap();
            for (StatementView.Group g : s.groups()) {
                w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 11,
                    StatementLabels.of(g.type()) + "  (" + money(g.total(), s.currency()) + ")");
                for (LedgerEntryView e : g.entries()) {
                    w.text("   " + DMY.format(e.effectiveDate()) + "   " + money(e.amount(), s.currency())
                        + (e.reason() != null ? "   " + e.reason() : ""));
                }
                w.gap();
            }
            w.line(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12,
                "Closing balance " + DMY.format(s.periodTo()) + ":  " + money(s.closingBalance(), s.currency()));
            w.text("Includes every entry up to number " + s.lastSeq() + ". A later correction appears on the next statement.");
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the statement for " + s.policyNumber(), e);
        }
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
            // Standard-14 fonts encode WinAnsi only; anything else (a reason a person typed) is
            // replaced rather than allowed to fail the whole statement.
            stream.showText(s.replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
            y -= LINE;
        }

        void gap() { y -= LINE / 2; }

        void close() throws IOException { stream.close(); }
    }
}

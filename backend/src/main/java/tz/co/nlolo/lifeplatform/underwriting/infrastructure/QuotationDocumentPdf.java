package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A quotation an applicant can be handed, signed and kept (2026-10-16).
 *
 * <p>Portrait A4 and Standard-14 Helvetica, as every other PDF here does -- no font file to ship. The shape is the
 * one a life quotation has always had: who it is for and on what product, the figures in a labelled block, the
 * benefits beside them, then the two signatures and the date it was printed.
 *
 * <p><b>It prints what it is given and computes nothing.</b> Every figure arrives already quoted by the module that
 * owns the rate -- the product's rate table, a funeral plan's grid, a deposit's band -- because a document an
 * applicant signs must carry the same number the policy will charge.
 */
final class QuotationDocumentPdf {

    private QuotationDocumentPdf() {}

    private static final float MARGIN = 56f;
    private static final float LINE = 15f;
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font ITALIC = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    /** One labelled figure in the quotation block. {@code strong} is a total: it carries a rule above it. */
    record Fact(String label, String value, boolean strong) {
        static Fact of(String label, String value) { return new Fact(label, value, false); }
        static Fact total(String label, String value) { return new Fact(label, value, true); }
    }

    /** One priced life on a family quotation. */
    record Line(String name, String role, String age, String benefit, String premium) {}

    /**
     * @param title what kind of quotation this is, in the words a customer reads
     * @param reference the quotation's own reference, so a customer ringing back can be found
     * @param facts the labelled figures, in the order they should be read
     * @param lines a priced life per row, or empty where the product prices one life
     * @param notes anything true that the figures do not say by themselves
     */
    record Content(String title, String reference, String proposer, String product, String printedOn,
                   List<Fact> facts, List<Line> lines, List<String> notes) {}

    static byte[] render(Content content) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);

            w.rule();
            w.centred(BOLD, 13, content.title().toUpperCase());
            w.rule();
            w.gap();

            w.pair("Name of proposer", content.proposer());
            w.pair("Product", content.product());
            w.pair("Quotation reference", content.reference());
            w.gap();

            for (Fact fact : content.facts()) {
                if (fact.strong()) {
                    w.ruleShort();
                    w.pair(BOLD, fact.label(), fact.value());
                } else {
                    w.pair(fact.label(), fact.value());
                }
            }

            if (!content.lines().isEmpty()) {
                w.gap();
                w.text(BOLD, 10, MARGIN, "The lives covered");
                w.newLine();
                float[] cols = {0f, 150f, 215f, 265f, 375f};
                w.row(BOLD, cols, "Life", "Role", "Age", "Benefit", "Yearly premium");
                for (Line line : content.lines()) {
                    w.row(REGULAR, cols, line.name(), line.role(), line.age(), line.benefit(), line.premium());
                }
            }

            if (!content.notes().isEmpty()) {
                w.gap();
                for (String note : content.notes()) {
                    w.text(ITALIC, 8.5f, MARGIN, note);
                    w.newLine();
                }
            }

            w.gap();
            w.rule();
            w.gap();
            w.text(REGULAR, 9, MARGIN, "I confirm that I have understood the information provided in this quotation");
            w.newLine();
            w.text(REGULAR, 9, MARGIN, "and have received a copy of it.");
            w.newLine();
            w.gap();
            w.gap();
            w.text(REGULAR, 9, MARGIN, "Signature of applicant .................................");
            w.text(REGULAR, 9, MARGIN + 300, "Date .....................");
            w.newLine();
            w.gap();
            w.text(REGULAR, 9, MARGIN, "(" + content.proposer() + ")");
            w.newLine();
            w.gap();
            w.gap();
            w.text(REGULAR, 9, MARGIN, "Signature (authorised officer) ....................");
            w.text(REGULAR, 9, MARGIN + 300, "Printed " + content.printedOn());
            w.newLine();

            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Writer {
        private final PDDocument doc;
        private PDPageContentStream stream;
        private float y;
        private float width;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            width = PDRectangle.A4.getWidth();
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void text(PDType1Font font, float size, float x, String s) throws IOException {
            if (y < MARGIN) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            // Standard-14 encodes WinAnsi only, so anything outside it is replaced rather than failing a download.
            stream.showText(s.replace('’', '\'').replace('–', '-').replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
        }

        void centred(PDType1Font font, float size, String s) throws IOException {
            float textWidth = font.getStringWidth(s.replaceAll("[^\\x20-\\x7E]", "?")) / 1000 * size;
            text(font, size, (width - textWidth) / 2, s);
            newLine();
        }

        /** A label on the left and its figure at the RIGHT MARGIN, so every number lines up in one column. */
        void pair(String label, String value) throws IOException {
            pair(REGULAR, label, value);
        }

        void pair(PDType1Font font, String label, String value) throws IOException {
            text(font, 10, MARGIN, label);
            float textWidth = font.getStringWidth(value.replaceAll("[^\\x20-\\x7E]", "?")) / 1000 * 10;
            text(font, 10, width - MARGIN - textWidth, value);
            newLine();
        }

        void row(PDType1Font font, float[] cols, String... values) throws IOException {
            if (y < MARGIN + LINE) newPage();
            for (int i = 0; i < values.length && i < cols.length; i++) {
                text(font, 9, MARGIN + cols[i], values[i]);
            }
            newLine();
        }

        void rule() throws IOException {
            if (y < MARGIN) newPage();
            stream.moveTo(MARGIN, y + 4);
            stream.lineTo(width - MARGIN, y + 4);
            stream.stroke();
            y -= 6;
        }

        /** Above a total: the width of the figures, not the page. */
        void ruleShort() throws IOException {
            if (y < MARGIN) newPage();
            stream.moveTo(width - MARGIN - 160, y + 4);
            stream.lineTo(width - MARGIN, y + 4);
            stream.stroke();
            y -= 4;
        }

        void newLine() {
            y -= LINE;
        }

        void gap() {
            y -= LINE / 2;
        }

        void close() throws IOException {
            stream.close();
        }
    }

    /** Collects facts without the caller writing null checks around every optional one. */
    static final class Facts {
        private final List<Fact> facts = new ArrayList<>();

        Facts add(String label, String value) {
            if (value != null && !value.isBlank()) facts.add(Fact.of(label, value));
            return this;
        }

        /** Both have to be there: an account has no maturity value, so it has no total row either. */
        Facts total(String label, String value) {
            if (label != null && !label.isBlank() && value != null && !value.isBlank()) {
                facts.add(Fact.total(label, value));
            }
            return this;
        }

        List<Fact> list() {
            return List.copyOf(facts);
        }
    }
}

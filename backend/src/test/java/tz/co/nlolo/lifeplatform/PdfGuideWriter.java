package tz.co.nlolo.lifeplatform;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a plain A4 guide top to bottom, starting a new page when one fills.
 *
 * <p>The {@code StatementPdf} idiom, lifted into test code because two guides now need it and a
 * second copy is a second thing to fix. Standard-14 Helvetica only: no font file to ship.
 */
public final class PdfGuideWriter implements AutoCloseable {

    private static final float MARGIN = 50f;
    private static final float LINE = 14f;
    private static final PDType1Font BODY = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final PDDocument doc;
    private PDPageContentStream stream;
    private float y;

    public PdfGuideWriter(PDDocument doc) throws IOException {
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

    public void heading(float size, String s) throws IOException { at(BOLD, size, MARGIN, s); }

    public void small(String s) throws IOException { at(BODY, 8.5f, MARGIN, s); }

    public void wrap(String s) throws IOException {
        for (String l : split(s, 100)) at(BODY, 9.5f, MARGIN, l);
    }

    public void bullet(String s) throws IOException {
        List<String> lines = split(s, 95);
        at(BODY, 9.5f, MARGIN, "- " + lines.get(0));
        for (String rest : lines.subList(1, lines.size())) at(BODY, 9.5f, MARGIN + 10, rest);
    }

    public void numbered(int n, String s) throws IOException {
        List<String> lines = split(s, 93);
        at(BOLD, 9.5f, MARGIN, n + ". " + lines.get(0));
        for (String rest : lines.subList(1, lines.size())) at(BODY, 9.5f, MARGIN + 15, rest);
    }

    /** A field name and what it means, the name in bold and the meaning wrapped beside it. */
    public void field(String name, String meaning) throws IOException {
        if (y < MARGIN + LINE) newPage();
        put(BOLD, 9.5f, MARGIN, name);
        List<String> lines = split(meaning, 68);
        put(BODY, 9.5f, MARGIN + 165, lines.get(0));
        y -= LINE;
        for (String rest : lines.subList(1, lines.size())) at(BODY, 9.5f, MARGIN + 165, rest);
    }

    /** Three columns, for a plain table. */
    public void row(String a, String b, String c) throws IOException {
        if (y < MARGIN) newPage();
        put(BODY, 9.5f, MARGIN, a);
        put(BODY, 9.5f, MARGIN + 160, b);
        put(BODY, 9.5f, MARGIN + 250, c);
        y -= LINE;
    }

    /** A label and a right-hand figure. */
    public void money(String label, String amount) throws IOException {
        if (y < MARGIN) newPage();
        put(BODY, 9.5f, MARGIN, label);
        put(BOLD, 9.5f, MARGIN + 330, amount);
        y -= LINE;
    }

    public void rule() throws IOException {
        if (y < MARGIN) newPage();
        stream.moveTo(MARGIN, y + 9);
        stream.lineTo(MARGIN + 460, y + 9);
        stream.stroke();
        y -= 4;
    }

    public void gap() { y -= LINE / 2; }

    private void at(PDType1Font font, float size, float x, String s) throws IOException {
        if (y < MARGIN) newPage();
        put(font, size, x, s);
        y -= LINE;
    }

    private void put(PDType1Font font, float size, float x, String s) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        // Standard-14 fonts encode WinAnsi only.
        stream.showText(s.replaceAll("[^\\x20-\\x7E]", "?"));
        stream.endText();
    }

    private static List<String> split(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() + word.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    @Override
    public void close() throws IOException { stream.close(); }
}

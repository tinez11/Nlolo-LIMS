package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Landscape A4, Standard-14 Helvetica (accumulation's StatementPdf does the same: no font file to ship).
 * Columns share the width equally; a value too long for its column is cut with "~". */
final class ReportPdf {
    private ReportPdf() {}

    private static final float MARGIN = 36f;
    private static final float LINE = 13f;
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    static byte[] render(ReportResult result) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            w.text(BOLD, 14, MARGIN, result.title());
            w.newLine();
            w.text(REGULAR, 9, MARGIN, result.filterSummary() + "    Generated " + result.generatedAt());
            w.newLine();
            for (ReportSection section : result.sections()) {
                w.gap();
                w.text(BOLD, 11, MARGIN, section.title());
                w.newLine();
                float width = (w.pageWidth() - 2 * MARGIN) / Math.max(1, section.columns().size());
                w.cells(BOLD, section.columns().stream().map(ReportColumn::label).toList(), width);
                for (Map<String, String> row : section.rows()) {
                    w.cells(REGULAR, section.columns().stream().map(c -> row.getOrDefault(c.key(), "")).toList(), width);
                }
                if (!section.totals().isEmpty()) {
                    w.cells(BOLD, section.columns().stream().map(c -> section.totals().getOrDefault(c.key(), "")).toList(), width);
                }
                if (section.rows().isEmpty()) {
                    w.text(REGULAR, 9, MARGIN, "No rows.");
                    w.newLine();
                }
            }
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
        private float pageWidth;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        float pageWidth() {
            return pageWidth;
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            PDPage page = new PDPage(landscape);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            pageWidth = landscape.getWidth();
            y = landscape.getHeight() - MARGIN;
        }

        void text(PDType1Font font, float size, float x, String s) throws IOException {
            if (y < MARGIN) newPage();
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            // Standard-14 fonts encode WinAnsi only; anything else is replaced rather than failing the export.
            stream.showText(s.replace('·', '-').replaceAll("[^\\x20-\\x7E]", "?"));
            stream.endText();
        }

        void cells(PDType1Font font, List<String> values, float width) throws IOException {
            if (y < MARGIN + LINE) newPage();
            int maxChars = Math.max(3, (int) (width / 4.6f));
            for (int i = 0; i < values.size(); i++) {
                String v = values.get(i);
                text(font, 8, MARGIN + i * width, v.length() > maxChars ? v.substring(0, maxChars - 1) + "~" : v);
            }
            newLine();
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
}

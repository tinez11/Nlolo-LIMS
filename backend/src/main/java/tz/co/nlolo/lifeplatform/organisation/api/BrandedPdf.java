package tz.co.nlolo.lifeplatform.organisation.api;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a {@link BrandedDocument} under the company's letterhead (2026-11-09, organisation settings): the one PDF
 * renderer for customer documents, statements and report exports, so a company's branding reaches all of them at once.
 *
 * <ul>
 *   <li><b>Three layouts.</b> CLASSIC: logo and name left, contacts right, a rule in the brand colour. BANDED: a band of
 *   the brand colour across the top. MINIMAL: a quiet name line, the contacts in the footer.</li>
 *   <li><b>Tables</b> wrap long text onto further lines (never cut it), right-align figures, band alternate rows, set
 *   subtotals bold and totals ruled, and repeat their heading on every page they continue onto.</li>
 *   <li><b>The footer</b> carries the company's footer text (or, on a report, its confidentiality line) and "Page X of
 *   Y".</li>
 *   <li><b>Typefaces</b> are the PDF standard faces -- Helvetica or Times -- so no font file is shipped or licensed.
 *   They encode WinAnsi only: other characters are mapped to the nearest one they have.</li>
 * </ul>
 */
public final class BrandedPdf {

    private BrandedPdf() {}

    private static final float MARGIN = 40f;
    private static final float PAD = 4f;
    private static final float LINE = 10.5f;
    private static final Color INK = new Color(28, 32, 38);
    private static final Color MUTED = new Color(104, 112, 122);
    private static final Color RULE = new Color(206, 211, 217);
    private static final Color WHITE = Color.WHITE;
    private static final Color NOTICE_BG = new Color(255, 244, 214);
    private static final Color NOTICE_FG = new Color(122, 83, 0);
    private static final Color DOWN = new Color(204, 78, 60);

    public static byte[] render(BrandedDocument d, Letterhead l) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new Layout(doc, d, l == null ? Letterhead.fallback() : l).write();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render " + d.title(), e);
        }
    }

    /** "#17324D" as a colour; a malformed value is the platform's own. */
    static Color colour(String hex, String fallback) {
        String h = hex != null && hex.matches("#[0-9A-Fa-f]{6}") ? hex : fallback;
        return new Color(Integer.parseInt(h.substring(1), 16));
    }

    /** {@code c} mixed toward white: 0 is the colour, 1 is white. */
    static Color tint(Color c, float towardWhite) {
        return new Color(mix(c.getRed(), towardWhite), mix(c.getGreen(), towardWhite), mix(c.getBlue(), towardWhite));
    }

    private static int mix(int v, float t) {
        return Math.round(v + (255 - v) * t);
    }

    /**
     * Text the standard faces can encode: the WinAnsi punctuation a person types is kept (— – ’ “ ” · …), the few
     * signs the platform writes are spelt out (✓ √ ² ÷ ≥), and anything else becomes "?" rather than failing the page.
     */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp <= 0x7E) out.appendCodePoint(cp);
            else switch (cp) {
                case '—', '–', '’', '‘', '“', '”', '·', '…', '€', '£', '©', '®', '°', '±', '×', 'µ' -> out.appendCodePoint(cp);
                case 0xA0 -> out.append(' ');
                case '✓' -> out.append("OK");
                case '√' -> out.append("sqrt");
                case '²' -> out.append("2");
                case '≥' -> out.append(">=");
                case '≤' -> out.append("<=");
                case '÷' -> out.append("/");
                case '−' -> out.append('-');
                default -> out.append('?');
            }
        });
        return out.toString();
    }

    private static final class Layout {
        private final PDDocument doc;
        private final BrandedDocument d;
        private final Letterhead l;
        private final PDType1Font regular;
        private final PDType1Font bold;
        private final Color primary;
        private final Color secondary;
        private final PDRectangle size;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream s;
        private float y;
        private PDImageXObject logo;

        Layout(PDDocument doc, BrandedDocument d, Letterhead l) {
            this.doc = doc;
            this.d = d;
            this.l = l;
            boolean serif = "SERIF".equals(l.typeface());
            this.regular = new PDType1Font(serif ? Standard14Fonts.FontName.TIMES_ROMAN : Standard14Fonts.FontName.HELVETICA);
            this.bold = new PDType1Font(serif ? Standard14Fonts.FontName.TIMES_BOLD : Standard14Fonts.FontName.HELVETICA_BOLD);
            this.primary = colour(l.primaryColour(), Letterhead.DEFAULT_PRIMARY);
            this.secondary = colour(l.secondaryColour(), Letterhead.DEFAULT_SECONDARY);
            PDRectangle paper = "LETTER".equals(l.paper()) ? PDRectangle.LETTER : PDRectangle.A4;
            this.size = d.landscape() ? new PDRectangle(paper.getHeight(), paper.getWidth()) : paper;
        }

        float width() {
            return size.getWidth() - 2 * MARGIN;
        }

        /** Room left above the footer. */
        float floor() {
            return MARGIN + ("MINIMAL".equals(l.layout()) ? 30 : 22);
        }

        void write() throws IOException {
            if (l.logo() != null && l.logo().length > 0) {
                try {
                    logo = PDImageXObject.createFromByteArray(doc, l.logo(), "logo");
                } catch (IOException | IllegalArgumentException e) {
                    logo = null; // An unreadable logo costs the logo, never the document.
                }
            }
            newPage(true);
            title();
            notices();
            facts();
            figures();
            chart();
            for (BrandedDocument.Table t : d.tables()) table(t);
            totals();
            notes();
            signature();
            s.close();
            footers();
        }

        // ---- the letterhead -----------------------------------------------------------------------------------

        private void newPage(boolean first) throws IOException {
            if (s != null) s.close();
            PDPage page = new PDPage(size);
            doc.addPage(page);
            pages.add(page);
            s = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGIN;
            if (first) {
                letterhead();
            } else {
                // A continuation page: the company and the title on one quiet line, so a loose page is still placed.
                text(bold, 8, MARGIN, y, safe(l.displayName()), primary);
                String t = safe(d.title());
                text(regular, 8, MARGIN + width() - textWidth(regular, 8, t), y, t, MUTED);
                y -= 8;
                rule(MARGIN, y, MARGIN + width(), y, RULE, 0.5f);
                y -= 14;
            }
        }

        private void letterhead() throws IOException {
            switch (l.layout() == null ? "CLASSIC" : l.layout()) {
                case "BANDED" -> banded();
                case "MINIMAL" -> minimal();
                default -> classic();
            }
        }

        private List<String> contactLines(float max) throws IOException {
            List<String> lines = new ArrayList<>();
            if (present(l.address())) lines.addAll(wrap(regular, 7.5f, l.address(), max));
            String reach = join(" · ", l.phone(), l.email());
            if (!reach.isEmpty()) lines.add(reach);
            if (present(l.website())) lines.add(l.website());
            String ids = join(" · ", present(l.registrationNumber()) ? "TIRA reg. " + l.registrationNumber() : null,
                present(l.tin()) ? "TIN " + l.tin() : null);
            if (!ids.isEmpty()) lines.add(ids);
            return lines;
        }

        private void classic() throws IOException {
            float top = y;
            float x = MARGIN;
            if (logo != null) {
                float[] wh = fit(logo, 130, 46);
                s.drawImage(logo, MARGIN, top - wh[1] + 6, wh[0], wh[1]);
                x = MARGIN + wh[0] + 12;
            }
            text(bold, 14, x, top - 8, l.displayName(), primary);
            if (present(l.tagline())) text(regular, 8, x, top - 21, l.tagline(), MUTED);
            float cy = top;
            for (String line : contactLines(200)) {
                text(regular, 7.5f, MARGIN + width() - textWidth(regular, 7.5f, line), cy, line, MUTED);
                cy -= 9.5f;
            }
            y = Math.min(top - 46, cy) - 6;
            rule(MARGIN, y, MARGIN + width(), y, primary, 1.4f);
            y -= 24;
        }

        private void banded() throws IOException {
            float band = 70;
            fill(0, size.getHeight() - band, size.getWidth(), band, primary);
            float top = size.getHeight() - 22;
            float x = MARGIN;
            if (logo != null) {
                float[] wh = fit(logo, 110, 38);
                fill(MARGIN - 4, top - wh[1] - 2, wh[0] + 8, wh[1] + 8, WHITE);
                s.drawImage(logo, MARGIN, top - wh[1] + 2, wh[0], wh[1]);
                x = MARGIN + wh[0] + 16;
            }
            text(bold, 15, x, top - 10, l.displayName(), WHITE);
            if (present(l.tagline())) text(regular, 8, x, top - 23, l.tagline(), tint(primary, 0.75f));
            float cy = size.getHeight() - 18;
            for (String line : contactLines(220)) {
                text(regular, 7.5f, MARGIN + width() - textWidth(regular, 7.5f, line), cy, line, WHITE);
                cy -= 9.5f;
            }
            fill(0, size.getHeight() - band - 3, size.getWidth(), 3, secondary);
            y = size.getHeight() - band - 30;
        }

        private void minimal() throws IOException {
            float top = y;
            text(bold, 10, MARGIN, top - 6, safe(l.displayName()), primary);
            if (present(l.tagline())) text(regular, 8, MARGIN, top - 18, l.tagline(), MUTED);
            if (logo != null) {
                float[] wh = fit(logo, 90, 32);
                s.drawImage(logo, MARGIN + width() - wh[0], top - wh[1] + 4, wh[0], wh[1]);
            }
            y = top - 40;
        }

        private void title() throws IOException {
            for (String line : wrap(bold, 17, d.title(), width())) {
                text(bold, 17, MARGIN, y, line, "MINIMAL".equals(l.layout()) ? primary : INK);
                y -= 20;
            }
            if (present(d.subtitle())) {
                for (String line : wrap(regular, 9, d.subtitle(), width())) {
                    text(regular, 9, MARGIN, y + 4, line, MUTED);
                    y -= 12;
                }
            }
            y -= 6;
        }

        // ---- the body -----------------------------------------------------------------------------------------

        private void notices() throws IOException {
            for (String notice : d.notices()) {
                List<String> lines = wrap(bold, 8.5f, notice, width() - 16);
                float h = lines.size() * LINE + 10;
                room(h + 6);
                fill(MARGIN, y - h + 8, width(), h, NOTICE_BG);
                float ty = y - 2;
                for (String line : lines) {
                    text(bold, 8.5f, MARGIN + 8, ty, line, NOTICE_FG);
                    ty -= LINE;
                }
                y -= h + 6;
            }
        }

        /** The width of a fact's label: room for "Coverage term", leaving the value the rest. */
        private static final float LABEL = 86;

        /** The facts in two columns; a long value wraps under itself and pushes its column down. */
        private void facts() throws IOException {
            if (d.facts().isEmpty()) return;
            float half = width() / 2;
            float valueWidth = half - LABEL - 10;
            int perColumn = (d.facts().size() + 1) / 2;
            float[] cursor = { y, y };
            for (int i = 0; i < d.facts().size(); i++) {
                int column = i < perColumn ? 0 : 1;
                float x = MARGIN + column * half;
                BrandedDocument.Field f = d.facts().get(i);
                text(regular, 8, x, cursor[column], f.label(), MUTED);
                List<String> lines = wrap(bold, 9, f.value(), valueWidth);
                for (int k = 0; k < lines.size(); k++) text(bold, 9, x + LABEL, cursor[column] - k * 11, lines.get(k), INK);
                cursor[column] -= 13 + (lines.size() - 1) * 11;
            }
            y = Math.min(cursor[0], cursor[1]) - 6;
        }

        /** The headline figures in a tinted panel, up to four across, wrapping to a second row. */
        private void figures() throws IOException {
            if (d.figures().isEmpty()) return;
            int across = Math.min(4, d.figures().size());
            float cell = width() / across;
            int rowsNeeded = (d.figures().size() + across - 1) / across;
            float h = 50;
            room(rowsNeeded * h + 10);
            for (int r = 0; r < rowsNeeded; r++) {
                fill(MARGIN, y - h + 8, width(), h, tint(primary, 0.9f));
                for (int c = 0; c < across; c++) {
                    int i = r * across + c;
                    if (i >= d.figures().size()) break;
                    BrandedDocument.Figure f = d.figures().get(i);
                    float x = MARGIN + c * cell + 10;
                    text(regular, 7.5f, x, y - 4, clip(regular, 7.5f, f.label(), cell - 16), MUTED);
                    text(bold, 14, x, y - 21, clip(bold, 14, f.value(), cell - 16), primary);
                    if (present(f.caption())) text(regular, 7, x, y - 33, clip(regular, 7, f.caption(), cell - 16), MUTED);
                }
                y -= h + 2;
            }
            y -= 8;
        }

        private void chart() throws IOException {
            BrandedDocument.Chart c = d.chart();
            if (c == null) return;
            float h = 150;
            room(h + 42);
            if (present(c.description())) {
                text(bold, 10, MARGIN, y, c.description(), primary);
                y -= 12;
            }
            float bottom = y - h + 18;
            if ("TREND".equals(c.kind())) trend(c, bottom, h - 18);
            else bridge(c, bottom, h - 18);
            y = bottom - 34;
        }

        private void bridge(BrandedDocument.Chart c, float bottom, float h) throws IOException {
            List<BrandedDocument.Bar> bars = c.bars();
            if (bars.isEmpty()) return;
            double level = 0;
            double[][] spans = new double[bars.size()][2];
            for (int i = 0; i < bars.size(); i++) {
                BrandedDocument.Bar b = bars.get(i);
                double v = Math.abs(b.value());
                if ("START".equals(b.kind()) || "END".equals(b.kind())) {
                    if ("START".equals(b.kind())) level = b.value();
                    spans[i] = new double[] { 0, b.value() };
                } else {
                    double from = level;
                    level = "UP".equals(b.kind()) ? level + v : level - v;
                    spans[i] = new double[] { from, level };
                }
            }
            double hi = 0;
            double lo = 0;
            for (double[] sp : spans) {
                hi = Math.max(hi, Math.max(sp[0], sp[1]));
                lo = Math.min(lo, Math.min(sp[0], sp[1]));
            }
            double range = hi - lo == 0 ? 1 : (hi - lo) * 1.12;
            float plot = h - 14;
            float band = width() / bars.size();
            float barW = Math.min(band * 0.6f, 56);
            float zero = (float) (bottom + plot * (-lo / range));
            rule(MARGIN, zero, MARGIN + width(), zero, RULE, 0.6f);
            for (int i = 0; i < bars.size(); i++) {
                BrandedDocument.Bar b = bars.get(i);
                float y1 = (float) (bottom + plot * ((spans[i][0] - lo) / range));
                float y2 = (float) (bottom + plot * ((spans[i][1] - lo) / range));
                Color colour = switch (b.kind()) {
                    case "UP" -> secondary;
                    case "DOWN" -> DOWN;
                    default -> primary;
                };
                float x = MARGIN + i * band + (band - barW) / 2;
                fill(x, Math.min(y1, y2), barW, Math.max(1.2f, Math.abs(y2 - y1)), colour);
                String value = number(b.kind().equals("DOWN") ? -Math.abs(b.value()) : b.value(), c.unit());
                text(bold, 7.5f, x + barW / 2 - textWidth(bold, 7.5f, value) / 2, Math.max(y1, y2) + 3, value, INK);
                String label = clip(regular, 7, b.label(), band - 4);
                text(regular, 7, MARGIN + i * band + band / 2 - textWidth(regular, 7, label) / 2, bottom - 10, label, MUTED);
            }
        }

        private void trend(BrandedDocument.Chart c, float bottom, float h) throws IOException {
            int n = c.periods().size();
            if (n == 0 || c.series().isEmpty()) return;
            double hi = 0;
            double lo = 0;
            for (BrandedDocument.Series sr : c.series()) {
                for (Double v : sr.values()) {
                    if (v != null) {
                        hi = Math.max(hi, v);
                        lo = Math.min(lo, v);
                    }
                }
            }
            double range = hi - lo == 0 ? 1 : (hi - lo) * 1.1;
            float plot = h - 14;
            float left = MARGIN + 46;
            float span = width() - 56;
            rule(left, bottom, left + span, bottom, RULE, 0.6f);
            String top = number(lo + range, c.unit());
            text(regular, 7, MARGIN, bottom + plot - 3, top, MUTED);
            text(regular, 7, MARGIN, bottom - 3, number(lo, c.unit()), MUTED);
            Color[] palette = { primary, secondary, new Color(191, 123, 26), new Color(128, 88, 196) };
            int every = Math.max(1, (int) Math.ceil(n / 8.0));
            for (int i = 0; i < n; i += every) {
                float x = left + (n == 1 ? span / 2 : span * i / (n - 1));
                String p = clip(regular, 6.5f, c.periods().get(i), 50);
                text(regular, 6.5f, x - textWidth(regular, 6.5f, p) / 2, bottom - 10, p, MUTED);
            }
            int colourIndex = 0;
            Color last = primary;
            for (int k = 0; k < c.series().size(); k++) {
                BrandedDocument.Series sr = c.series().get(k);
                Color colour = sr.dashed() && k > 0 ? last : palette[colourIndex++ % palette.length];
                last = colour;
                s.setStrokingColor(colour);
                s.setLineWidth(1.4f);
                s.setLineDashPattern(sr.dashed() ? new float[] { 4, 3 } : new float[] {}, 0);
                boolean drawing = false;
                for (int i = 0; i < Math.min(n, sr.values().size()); i++) {
                    Double v = sr.values().get(i);
                    if (v == null) {
                        if (drawing) s.stroke();
                        drawing = false;
                        continue;
                    }
                    float x = left + (n == 1 ? span / 2 : span * i / (n - 1));
                    float yy = (float) (bottom + plot * ((v - lo) / range));
                    if (drawing) s.lineTo(x, yy);
                    else s.moveTo(x, yy);
                    drawing = true;
                }
                if (drawing) s.stroke();
                s.setLineDashPattern(new float[] {}, 0);
                // The legend, under the axis.
                float lx = left + k * 120;
                fill(lx, bottom - 22, 10, 2.5f, colour);
                text(regular, 7, lx + 14, bottom - 24, clip(regular, 7, sr.label(), 100), MUTED);
            }
        }

        private void table(BrandedDocument.Table t) throws IOException {
            float[] widths = widths(t);
            room(60);
            if (present(t.title())) {
                text(bold, 10.5f, MARGIN, y, t.title(), primary);
                y -= 8;
            }
            heading(t, widths);
            if (t.rows().isEmpty()) {
                text(regular, 8.5f, MARGIN + PAD, y - 11, "Nothing to show for this period.", MUTED);
                y -= 22;
                return;
            }
            Color band = tint(secondary, 0.93f);
            for (int r = 0; r < t.rows().size(); r++) {
                BrandedDocument.Row row = t.rows().get(r);
                PDType1Font font = row.style() == BrandedDocument.Style.LINE ? regular : bold;
                List<List<String>> cells = new ArrayList<>();
                int lines = 1;
                for (int i = 0; i < widths.length; i++) {
                    String v = i < row.cells().size() ? row.cells().get(i) : "";
                    float indent = i == 0 ? row.level() * 9 : 0;
                    List<String> wrapped = t.columns().get(i).right()
                        ? List.of(clip(font, 8.5f, v, widths[i] - 2 * PAD))
                        : wrap(font, 8.5f, v, widths[i] - 2 * PAD - indent);
                    if (wrapped.size() > 4) {
                        wrapped = new ArrayList<>(wrapped.subList(0, 4));
                        wrapped.set(3, clip(font, 8.5f, wrapped.get(3) + "...", widths[i] - 2 * PAD - indent));
                    }
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                float h = 6 + lines * LINE;
                if (y - h < floor()) {
                    newPage(false);
                    if (present(t.title())) {
                        text(bold, 9, MARGIN, y, t.title() + " (continued)", primary);
                        y -= 8;
                    }
                    heading(t, widths);
                }
                if (row.style() == BrandedDocument.Style.TOTAL) {
                    fill(MARGIN, y - h, width(), h, tint(primary, 0.88f));
                    rule(MARGIN, y, MARGIN + width(), y, primary, 0.9f);
                } else if (r % 2 == 1) {
                    fill(MARGIN, y - h, width(), h, band);
                }
                float x = MARGIN;
                for (int i = 0; i < widths.length; i++) {
                    float indent = i == 0 ? row.level() * 9 : 0;
                    List<String> wrapped = cells.get(i);
                    for (int k = 0; k < wrapped.size(); k++) {
                        String v = wrapped.get(k);
                        float tx = t.columns().get(i).right()
                            ? x + widths[i] - PAD - textWidth(font, 8.5f, v)
                            : x + PAD + indent;
                        text(font, 8.5f, tx, y - 11 - k * LINE, v, INK);
                    }
                    x += widths[i];
                }
                y -= h;
            }
            rule(MARGIN, y, MARGIN + width(), y, RULE, 0.6f);
            y -= 16;
        }

        private void heading(BrandedDocument.Table t, float[] widths) throws IOException {
            List<List<String>> labels = new ArrayList<>();
            int lines = 1;
            for (int i = 0; i < widths.length; i++) {
                List<String> w = wrap(bold, 8, t.columns().get(i).label(), widths[i] - 2 * PAD);
                labels.add(w);
                lines = Math.max(lines, w.size());
            }
            float h = 6 + lines * 9.5f;
            fill(MARGIN, y - h, width(), h, tint(primary, 0.82f));
            float x = MARGIN;
            for (int i = 0; i < widths.length; i++) {
                for (int k = 0; k < labels.get(i).size(); k++) {
                    String v = labels.get(i).get(k);
                    float tx = t.columns().get(i).right() ? x + widths[i] - PAD - textWidth(bold, 8, v) : x + PAD;
                    text(bold, 8, tx, y - 10 - k * 9.5f, v, INK);
                }
                x += widths[i];
            }
            y -= h;
        }

        private float[] widths(BrandedDocument.Table t) {
            float total = 0;
            for (BrandedDocument.Column c : t.columns()) total += c.weight() <= 0 ? 1 : c.weight();
            float[] w = new float[t.columns().size()];
            for (int i = 0; i < w.length; i++) {
                float weight = t.columns().get(i).weight() <= 0 ? 1 : t.columns().get(i).weight();
                w[i] = width() * weight / total;
            }
            return w;
        }

        private void totals() throws IOException {
            for (BrandedDocument.Field f : d.totals()) {
                room(18);
                String value = safe(f.value());
                text(regular, 9, MARGIN + width() - 280, y, f.label(), MUTED);
                text(bold, 10.5f, MARGIN + width() - textWidth(bold, 10.5f, value) - PAD, y, value, primary);
                y -= 16;
            }
            if (!d.totals().isEmpty()) y -= 6;
        }

        private void notes() throws IOException {
            for (String note : d.notes()) {
                for (String line : wrap(regular, 8, note, width())) {
                    room(12);
                    text(regular, 8, MARGIN, y, line, MUTED);
                    y -= 10.5f;
                }
            }
        }

        /** Who signs it, on the right: the signature, a line, the name and title. */
        private void signature() throws IOException {
            SignatureStamp sig = d.signature();
            if (sig == null) return;
            room(92);
            y -= 10;
            float x = MARGIN + width() - 190;
            if (sig.image() != null && sig.image().length > 0) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, sig.image(), "signature");
                    float[] wh = fit(img, 150, 46);
                    s.drawImage(img, x, y - wh[1], wh[0], wh[1]);
                } catch (IOException | IllegalArgumentException e) {
                    // An unreadable signature image leaves the line to sign on, never a failed document.
                }
            }
            y -= 50;
            rule(x, y, x + 190, y, INK, 0.6f);
            text(bold, 9, x, y - 11, sig.name(), INK);
            text(regular, 8, x, y - 22, sig.title(), MUTED);
            text(regular, 7, x, y - 32, "for " + l.legalName(), MUTED);
            y -= 40;
        }

        private void footers() throws IOException {
            for (int i = 0; i < pages.size(); i++) {
                try (PDPageContentStream f = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    float fy = MARGIN - 18;
                    if ("MINIMAL".equals(l.layout())) {
                        String contacts = join(" · ", l.address(), l.phone(), l.email(), l.website(),
                            present(l.registrationNumber()) ? "TIRA reg. " + l.registrationNumber() : null);
                        if (!contacts.isEmpty()) {
                            write(f, regular, 7, MARGIN, fy + 10, clip(regular, 7, contacts, width()), MUTED);
                        }
                    }
                    String left = d.report() && present(l.reportConfidentiality()) ? l.reportConfidentiality()
                        : present(l.footerText()) ? l.footerText() : l.displayName() + " -- " + d.title();
                    String right = "Page " + (i + 1) + " of " + pages.size();
                    write(f, regular, 7.5f, MARGIN, fy, clip(regular, 7.5f, left, width() - 80), MUTED);
                    write(f, regular, 7.5f, size.getWidth() - MARGIN - textWidth(regular, 7.5f, right), fy, right, MUTED);
                    f.setStrokingColor(tint(primary, 0.5f));
                    f.setLineWidth(0.5f);
                    f.moveTo(MARGIN, fy + 8 + ("MINIMAL".equals(l.layout()) ? 10 : 0));
                    f.lineTo(MARGIN + width(), fy + 8 + ("MINIMAL".equals(l.layout()) ? 10 : 0));
                    f.stroke();
                }
            }
        }

        // ---- drawing ------------------------------------------------------------------------------------------

        /** A new page when {@code h} points do not fit above the footer. */
        private void room(float h) throws IOException {
            if (y - h < floor()) newPage(false);
        }

        private float textWidth(PDType1Font font, float sz, String v) throws IOException {
            return font.getStringWidth(safe(v)) / 1000 * sz;
        }

        private List<String> wrap(PDType1Font font, float sz, String value, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : safe(value).split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (font.getStringWidth(candidate) / 1000 * sz <= max) {
                        line = new StringBuilder(candidate);
                    } else {
                        if (!line.isEmpty()) lines.add(line.toString());
                        line = new StringBuilder(clip(font, sz, word, max));
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }

        /** Shortened with ".." to fit -- only for a figure or a label that cannot wrap. */
        private String clip(PDType1Font font, float sz, String value, float max) throws IOException {
            String v = safe(value);
            if (font.getStringWidth(v) / 1000 * sz <= max) return v;
            String t = v;
            while (t.length() > 1 && font.getStringWidth(t + "..") / 1000 * sz > max) t = t.substring(0, t.length() - 1);
            return t + "..";
        }

        private static float[] fit(PDImageXObject img, float maxW, float maxH) {
            float scale = Math.min(maxW / img.getWidth(), maxH / img.getHeight());
            return new float[] { img.getWidth() * scale, img.getHeight() * scale };
        }

        private void text(PDType1Font font, float sz, float x, float atY, String v, Color colour) throws IOException {
            write(s, font, sz, x, atY, safe(v), colour);
        }

        private static void write(PDPageContentStream to, PDType1Font font, float sz, float x, float atY, String v,
                                  Color colour) throws IOException {
            to.beginText();
            to.setFont(font, sz);
            to.setNonStrokingColor(colour);
            to.newLineAtOffset(x, atY);
            to.showText(safe(v));
            to.endText();
        }

        private void fill(float x, float atY, float w, float h, Color colour) throws IOException {
            s.setNonStrokingColor(colour);
            s.addRect(x, atY, w, h);
            s.fill();
        }

        private void rule(float x1, float atY, float x2, float y2, Color colour, float width) throws IOException {
            s.setStrokingColor(colour);
            s.setLineWidth(width);
            s.moveTo(x1, atY);
            s.lineTo(x2, y2);
            s.stroke();
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (present(p)) out.add(p.trim());
        return String.join(sep, out);
    }

    /** A chart value in its unit: 1,204.00 / 1,204 / 12.5%. */
    static String number(double v, String unit) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.ROOT);
        return switch (unit == null ? "MONEY" : unit) {
            case "COUNT" -> new DecimalFormat("#,##0", sym).format(v);
            case "PERCENT" -> new DecimalFormat("#,##0.0", sym).format(v) + "%";
            default -> new DecimalFormat("#,##0.00;(#,##0.00)", sym).format(v);
        };
    }
}

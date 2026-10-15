package tz.co.nlolo.lifeplatform.accumulation;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes the Malengo product guide to {@code docs/malengo-product-guide.pdf}.
 *
 * <p>A test only because that is the cheapest way to run project code with the project's classpath;
 * it asserts nothing about the platform and starts no Spring context, so it costs a second. The
 * figures in the "what a year looks like" section are the real output of
 * {@link MalengoSimulationTest}, pasted here rather than recomputed -- a guide quoting numbers no
 * run produced would be the worst kind of document.
 */
class MalengoGuidePdfTest {

    private static final float MARGIN = 50f;
    private static final float LINE = 14f;
    private static final PDType1Font BODY = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @Test
    void writesTheProductGuide() throws IOException {
        Path out = Path.of("docs", "malengo-product-guide.pdf");
        Files.createDirectories(out.getParent());

        try (PDDocument doc = new PDDocument()) {
            Writer w = new Writer(doc);

            w.heading(16, "Malengo Savings Plan");
            w.small("Product guide and configuration reference. Prepared 15 October 2026.");
            w.gap();

            w.heading(12, "1. What the plan is");
            w.wrap("A savings contract. The client pays in whenever they like and takes money out whenever they "
                + "like, and the balance earns 4% a year, credited at each month end. There is no fixed sum "
                + "assured: what the client is owed is whatever the account holds. Taking the whole balance out "
                + "is how the contract ends.");
            w.gap();

            w.heading(12, "2. The configurable part");
            w.small("Set on the product version when it is published. Changing any of these means publishing a new");
            w.small("version; a policy already sold keeps the version it was sold on.");
            w.gap();
            w.row("Setting", "Malengo", "What it does");
            w.rule();
            w.row("guaranteedRatePercent", "4", "Annual rate floor, credited monthly");
            w.row("minimumBalance", "0", "A withdrawal may empty the account");
            w.row("contributionAllocation%", "0", "Held back from each deposit");
            w.row("transferAllocation%", "0", "Held back from a transfer in");
            w.row("monthlyPolicyFee", "1,000", "Taken at each month end");
            w.row("minEntryAge / maxEntryAge", "18 / 60", "Enforced when a case is opened");
            w.row("payout amountBasis", "ACCOUNT_VALUE", "Maturity pays the account, not a face amount");
            w.row("portfolioCode", "SAV", "An account must be a savings contract");
            w.gap();
            w.small("The minimum balance is the one setting that separates Malengo from the Akiba plan beside it:");
            w.small("Akiba keeps a 50,000 floor, so its account can never be emptied. Malengo's is zero.");
            w.gap();

            w.heading(12, "3. Setting it up");
            w.numbered(1, "Author the product: category ENDOWMENT, portfolio SAV, currency TZS.");
            w.numbered(2, "Publish a version carrying the settings in section 2, with a rating table covering "
                + "ages 18 to 60 and a MATURITY payout row on ACCOUNT_VALUE.");
            w.numbered(3, "Open an underwriting case for the client and decide it. Entry age is checked here.");
            w.numbered(4, "Issue the policy. The sum assured carries the opening deposit -- see section 5.");
            w.numbered(5, "Collect the first premium. That is the opening deposit, and it opens the account.");
            w.gap();
            w.small("Steps 1 and 2 are scripted in backend/scripts/malengo-demo.sh.");
            w.gap();

            w.heading(12, "4. Running it");
            w.wrap("A deposit is a premium collection or an ad-hoc top-up; both credit the account in full, since "
                + "nothing is held back. A withdrawal is asked for by one person and approved by a second, then "
                + "paid through the payment rail -- and a second withdrawal is refused while one is still in "
                + "flight. Every movement lands on an append-only ledger: a correction is posted as a reversal, "
                + "never an edit. At each month end the engine credits interest, then takes the policy fee.");
            w.gap();

            w.heading(12, "5. Two things to know");
            w.bullet("No fixed sum assured, but not a zero one. The wire layer refuses a sum assured of zero, so "
                + "the contract carries the opening deposit as its face amount. It is a placeholder, not a "
                + "promise: the maturity payout is ACCOUNT_VALUE. This is the convention the fixed-term "
                + "deposit already uses.");
            w.bullet("Emptying the account does NOT yet end the policy. A withdrawal that takes the balance to "
                + "zero leaves the policy ACTIVE and the account OPEN, because only the month-end charge run "
                + "closes an account by exhaustion. Until that is built, a Malengo contract is ended by "
                + "SURRENDER, which pays the balance and closes the policy properly.");
            w.gap();

            w.heading(12, "6. What a year looks like");
            w.small("Simulated against a real database through the real engine (MalengoSimulationTest), with");
            w.small("every date backdated. A 35-year-old client, 1,000,000 paid in across the year, 150,000 out.");
            w.gap();
            w.row("Step", "", "Balance (TZS)");
            w.rule();
            w.money("Opening deposit 500,000, then 2 month-ends", "501,284.88");
            w.money("Top-up 200,000, then 3 more month-ends", "704,418.86");
            w.money("Withdrew 150,000, then 3 more month-ends", "558,407.20");
            w.money("Deposited 300,000, then the rest of the year", "866,715.83");
            w.money("Withdrew the whole balance", "0.00");
            w.gap();
            w.wrap("850,000 of net cash in became 866,715.83: 4% compounded monthly, less twelve 1,000 fees. "
                + "After the final withdrawal the policy was still ACTIVE on a zero balance, which is the gap "
                + "section 5 describes.");

            w.close();
            doc.save(out.toFile());
        }

        // Read it back: a PDF that writes without throwing can still have lost its last section off
        // the bottom of a page, which only extracting the text shows.
        try (PDDocument check = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(check);
            assertThat(text).contains("Malengo Savings Plan");
            assertThat(text).contains("minimumBalance");
            assertThat(text).contains("18 / 60");
            assertThat(text).contains("866,715.83");
            assertThat(text).contains("SURRENDER");
            System.out.println("Wrote " + out.toAbsolutePath() + " (" + Files.size(out) + " bytes, "
                + check.getNumberOfPages() + " pages)");
        }
        assertThat(Files.size(out)).isGreaterThan(2000L);
    }

    /** Writes top to bottom, starting a new page when one fills -- the StatementPdf idiom. */
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

        void heading(float size, String s) throws IOException { at(BOLD, size, MARGIN, s); }

        void small(String s) throws IOException { at(BODY, 8.5f, MARGIN, s); }

        void bullet(String s) throws IOException {
            List<String> lines = split(s, 95);
            at(BODY, 9.5f, MARGIN, "- " + lines.get(0));
            for (String rest : lines.subList(1, lines.size())) at(BODY, 9.5f, MARGIN + 10, rest);
        }

        void numbered(int n, String s) throws IOException {
            List<String> lines = split(s, 95);
            at(BODY, 9.5f, MARGIN, n + ". " + lines.get(0));
            for (String rest : lines.subList(1, lines.size())) at(BODY, 9.5f, MARGIN + 13, rest);
        }

        void wrap(String s) throws IOException {
            for (String l : split(s, 100)) at(BODY, 9.5f, MARGIN, l);
        }

        /** Three columns: label, value, note. */
        void row(String a, String b, String c) throws IOException {
            if (y < MARGIN) newPage();
            put(BODY, 9.5f, MARGIN, a);
            put(BODY, 9.5f, MARGIN + 160, b);
            put(BODY, 9.5f, MARGIN + 250, c);
            y -= LINE;
        }

        /** A label and a right-hand figure. */
        void money(String label, String amount) throws IOException {
            if (y < MARGIN) newPage();
            put(BODY, 9.5f, MARGIN, label);
            put(BOLD, 9.5f, MARGIN + 330, amount);
            y -= LINE;
        }

        void rule() throws IOException {
            if (y < MARGIN) newPage();
            stream.moveTo(MARGIN, y + 9);
            stream.lineTo(MARGIN + 430, y + 9);
            stream.stroke();
            y -= 4;
        }

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
            List<String> out = new java.util.ArrayList<>();
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

        void gap() { y -= LINE / 2; }

        void close() throws IOException { stream.close(); }
    }
}

package tz.co.nlolo.lifeplatform.product;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.PdfGuideWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes {@code docs/savings-product-setup-guide.pdf} -- how to set up a savings product, and what
 * every field on the form means, for the people who configure products rather than build them.
 *
 * <p>A test only because that is the cheapest way to run project code with the project's classpath:
 * it starts no Spring context and asserts nothing about the platform. Every field name and value
 * below was read off the API records and enums, not recalled.
 */
class SavingsProductGuidePdfTest {

    @Test
    void writesTheSavingsProductGuide() throws IOException {
        Path out = Path.of("docs", "savings-product-setup-guide.pdf");
        Files.createDirectories(out.getParent());

        // The writer is closed BEFORE the document is saved, not alongside it in the same
        // try-with-resources: PDFBox refuses to serialise a document whose content stream is
        // still open ("Cannot read while there is an open stream writer").
        try (PDDocument doc = new PDDocument()) {
            PdfGuideWriter w = new PdfGuideWriter(doc);
            w.heading(16, "Setting up a savings product");
            w.small("What to decide, in what order, and what every field means. Prepared 15 October 2026.");
            w.gap();

            // ---- 1 -------------------------------------------------------------------------
            w.heading(12, "1. First decide which of the two shapes you are selling");
            w.wrap("Every savings product on this platform is one of two shapes, and the shape decides which "
                + "fields you fill in. Choose it before you touch the form.");
            w.gap();
            w.field("A flexible account", "The client pays in and takes money out whenever they like. One "
                + "interest rate applies to whatever the balance happens to be. Malengo is one.");
            w.field("A fixed-term deposit", "The client puts in one amount and leaves it for an agreed term. "
                + "The rate comes from a grid of amount bands and terms. Ndoto is one.");
            w.gap();
            w.small("They are not variations of each other. A product is one or the other, and the platform");
            w.small("refuses a version that tries to be both.");
            w.gap();

            // ---- 2 -------------------------------------------------------------------------
            w.heading(12, "2. The process");
            w.numbered(1, "Agree the terms with the business first, on paper. The interest rate or rate "
                + "grid, the charges, the entry ages, and -- for a flexible account -- whether a client may "
                + "empty it. Changing your mind later means a new version, so it is worth settling here.");
            w.numbered(2, "Create the product. This is only its name and where it sits: a code, a name, a "
                + "category, a portfolio and a currency. No terms yet, and nothing can be sold.");
            w.numbered(3, "Publish a version. This is where the terms go. A product with no published "
                + "version cannot be sold; publishing is what puts it on the shelf.");
            w.numbered(4, "Check the version page before anyone sells it. The rate, the ages and the "
                + "charges are all shown. A figure that is wrong here is wrong on every policy sold after.");
            w.numbered(5, "Sell one and look at it. Open an underwriting case, decide it, let the policy "
                + "issue, then collect the first premium -- that payment is what opens the account. Read the "
                + "account page and check the balance is what you expect.");
            w.gap();
            w.wrap("To change terms later, publish a NEW version. Policies already sold keep the version they "
                + "were sold on and are not affected -- which is the point. Never edit a live version hoping "
                + "existing policies will follow.");
            w.gap();

            // ---- 3 -------------------------------------------------------------------------
            w.heading(12, "3. The product fields (filled once, when you create it)");
            w.gap();
            w.field("Product code", "A short unique code you will see in reports, e.g. MALENGO-01. It cannot "
                + "be reused by another product.");
            w.field("Product name", "What people call it: Malengo Savings Plan.");
            w.field("Category", "The kind of contract. Both savings shapes use ENDOWMENT.");
            w.field("Portfolio code", "Which book it is accounted in. SAV for a flexible account, DEP for a "
                + "fixed-term deposit. Get this wrong and publishing is refused -- the platform will not let "
                + "an account product sit in the wrong book.");
            w.field("Default currency", "TZS.");
            w.gap();

            // ---- 4 -------------------------------------------------------------------------
            w.heading(12, "4. The version fields that every product has");
            w.gap();
            w.field("Effective date", "The day this version may start being sold from.");
            w.field("IFRS measurement model", "How finance measures the contract. GMM for long contracts, "
                + "PAA for short ones. Ask finance; it is an accounting choice, not a product one.");
            w.field("TIRA filing", "The regulator's reference and approval date. Required before publishing: "
                + "the platform will not put an unfiled product on sale.");
            w.field("Entry ages", "The youngest and oldest a client may be when they take it out, e.g. 18 "
                + "and 60. Checked when the case is opened, not later.");
            w.field("Rating table", "The age bands the product can price. A client outside every band cannot "
                + "be quoted, so the bands must cover the entry ages you just set.");
            w.field("Benefit schedule", "What the contract pays on death. On a savings product this is "
                + "usually the DEATH benefit on the sum assured.");
            w.field("Free-look days", "How long a client has to change their mind and get their money back, "
                + "normally 15.");
            w.field("Payout schedule", "What is paid at the end. On a savings product put one MATURITY row "
                + "with the basis ACCOUNT_VALUE and the value 100: it means the client gets the whole "
                + "account. The alternatives -- a percentage of the sum assured, or a fixed amount -- are "
                + "for traditional products and are wrong here.");
            w.gap();

            // ---- 5 -------------------------------------------------------------------------
            w.heading(12, "5. Extra fields for a FLEXIBLE ACCOUNT (Malengo)");
            w.gap();
            w.field("Guaranteed rate %", "The yearly rate the client is promised, credited at the end of "
                + "each month. It is a floor, not a fixed figure: you may declare a better rate later, never "
                + "a worse one.");
            w.field("Minimum balance", "The least that must stay in the account after a withdrawal. Set a "
                + "figure and the account can never be emptied; set ZERO and the client may take every "
                + "shilling. This single field is the main difference between one savings plan and another.");
            w.field("Contribution allocation %", "The share HELD BACK from each deposit before it reaches "
                + "the account. 0 means the whole deposit lands. 5 means 95 of every 100 lands.");
            w.field("Transfer allocation %", "The same, for money transferred in from another scheme. "
                + "Usually 0, because no commission is paid on money that was already somebody's savings.");
            w.field("Monthly policy fee", "A flat amount taken at each month end, after interest is "
                + "credited. Watch it on a small balance: if the fee ever eats the whole balance the account "
                + "closes and the policy lapses.");
            w.gap();
            w.small("These three charges are set per policy year, so you can charge more in year 1 than");
            w.small("afterwards. One row covering 'year 1 and every year after' is fine and is what Malengo uses.");
            w.gap();

            // ---- 6 -------------------------------------------------------------------------
            w.heading(12, "6. Extra fields for a FIXED-TERM DEPOSIT (Ndoto)");
            w.gap();
            w.wrap("Instead of one rate there is a grid: one row for every combination of amount band and "
                + "term. Ndoto's card has four bands and three terms, so twelve rows.");
            w.gap();
            w.field("Minimum amount", "The FLOOR of the band, not its range. A row says 'from this amount "
                + "upwards'; the next row up is where it stops.");
            w.field("Term months", "3, 6 or 12 -- Miezi 3, Miezi 6, Mwaka 1.");
            w.field("Rate %", "The yearly rate for that amount and that term.");
            w.gap();
            w.wrap("Mind the gaps in a printed rate card. A card reading '500,000 - 5,000,000' then "
                + "'6,000,000 - 10,000,000' says nothing about 5,500,000. The platform puts an in-between "
                + "amount in the HIGHER band, so each band is entered starting one cent above the band below "
                + "it. If the business means the lower band instead, say so before the grid is entered.");
            w.gap();

            // ---- 7 -------------------------------------------------------------------------
            w.heading(12, "7. Changing the interest rate after launch");
            w.wrap("On a flexible account you do not publish a new version to pay a better rate. You declare "
                + "one: a rate, and the date it takes effect. Three rules protect it.");
            w.gap();
            w.bullet("One person proposes the rate and a DIFFERENT person approves it. Nobody can do both.");
            w.bullet("A declared rate may never be below the guaranteed rate. That promise holds.");
            w.bullet("It cannot take effect on or before a month that has already had interest credited. "
                + "The past is closed.");
            w.gap();

            // ---- 8 -------------------------------------------------------------------------
            w.heading(12, "8. Five things that catch people out");
            w.bullet("The sum assured cannot be zero, even on a product that has no sum assured. The "
                + "convention is to put the opening deposit there. It is a placeholder -- what the client is "
                + "owed is the account, which is why the maturity row says ACCOUNT_VALUE.");
            w.bullet("A new version does not change existing policies, and is not supposed to.");
            w.bullet("Interest appears at MONTH END, not on the day money is paid in. A brand-new account "
                + "shows no interest until its first month closes; this is not a fault.");
            w.bullet("Withdrawals need two people: one asks, another approves. A second withdrawal is "
                + "refused while the first is still being paid.");
            w.bullet("Entry ages are checked when the underwriting case is opened. A client who turns 61 "
                + "next week is fine; one who is 61 today cannot be sold the product at all.");

            w.close();
            doc.save(out.toFile());
        }

        // Read it back: a guide that writes without throwing can still have lost its last section
        // off the bottom of a page, which only extracting the text shows.
        try (PDDocument check = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(check);
            assertThat(text).contains("Setting up a savings product");
            assertThat(text).contains("Minimum balance");
            assertThat(text).contains("Mwaka 1");
            assertThat(text).contains("catch people out");
            System.out.println("Wrote " + out.toAbsolutePath() + " ("
                + Files.size(out) + " bytes, " + check.getNumberOfPages() + " pages)");
        }
    }
}

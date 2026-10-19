package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.SavingsQuoteView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteLine;
import tz.co.nlolo.lifeplatform.product.api.PortfolioCode;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.QuotationDocumentInput;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * The quotation a customer is handed, built from inputs and quoted AGAIN here (2026-10-16).
 *
 * <p><b>Why the server re-quotes.</b> The screen already shows these figures, and it would be shorter to print the
 * ones it has. But an applicant signs this document, so the premium on it has to be the premium the policy will
 * charge -- which means it must come from the module that owns the rate, on the day it is printed, from the
 * applicant's own record. A total that travelled through a browser is a total nobody can stand behind.
 *
 * <p><b>One document, every family.</b> Which question to ask is the product's portfolio, exactly as the console's
 * quotation panel decides it: a rate table for conventional cover, a plan's grid for a funeral family, a deposit's
 * band or an account's declared rate for savings.
 *
 * <p>Nothing is recorded. No quotation row, no case, no document in the store -- the PDF is built and streamed.
 */
@Component
class QuotationDocuments {

    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    /** Priced from a sum assured and the version's base rate table. */
    private static final Set<PortfolioCode> PREMIUM_RATED = Set.of(PortfolioCode.TERM, PortfolioCode.WL,
        PortfolioCode.END, PortfolioCode.MB, PortfolioCode.PAR);
    /** Priced from money paid in and a rate, with no sum assured at all. */
    private static final Set<PortfolioCode> SAVINGS = Set.of(PortfolioCode.SAV, PortfolioCode.DEP);

    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final AccumulationApi accumulationApi;

    QuotationDocuments(ProductApi productApi, PartyApi partyApi, AccumulationApi accumulationApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.accumulationApi = accumulationApi;
    }

    byte[] render(QuotationDocumentInput input) {
        ProductSummaryView product = productApi.getProduct(input.productId());
        PartyDetailView applicant = partyApi.getPartyDetail(input.applicantPartyId());
        LocalDate today = LocalDate.now(DAR);
        LocalDate asOf = input.commencement() != null ? input.commencement() : today;
        String currency = input.currency() == null || input.currency().isBlank()
            ? product.defaultCurrency() : input.currency();
        PortfolioCode portfolio = product.portfolioCode();

        QuotationDocumentPdf.Content content;
        if (portfolio == PortfolioCode.FUN) {
            content = funeral(input, product, applicant, currency, asOf, today);
        } else if (SAVINGS.contains(portfolio)) {
            content = savings(input, product, applicant, currency, asOf, today);
        } else if (PREMIUM_RATED.contains(portfolio)) {
            content = premium(input, product, applicant, currency, asOf, today);
        } else {
            // An annuity, a unit-linked policy, a group scheme: each is priced on terms this document does not
            // carry, and printing a blank quotation would be worse than saying so.
            throw new UnderwritingValidationException("A quotation document is not available for a "
                + portfolio + " product: its price comes from terms this form does not collect.");
        }
        return QuotationDocumentPdf.render(content);
    }

    private QuotationDocumentPdf.Content premium(QuotationDocumentInput in, ProductSummaryView product,
                                                 PartyDetailView applicant, String currency, LocalDate asOf,
                                                 LocalDate today) {
        if (in.sumAssured() == null || in.frequency() == null) {
            throw new UnderwritingValidationException("A premium quotation needs a sum assured and a payment frequency");
        }
        if (applicant.dateOfBirth() == null || applicant.sex() == null) {
            throw new UnderwritingValidationException("A premium quotation needs the applicant's date of birth and sex"
                + " on their record: the rate table is keyed on them.");
        }
        // The party record keeps its own Sex and SmokerStatus; the rate table is keyed on the product's. Converted by
        // name, as the customer portal's quote does (CustomerProducts).
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            in.productId(), in.sumAssured(), currency, applicant.dateOfBirth(),
            Sex.valueOf(applicant.sex().name()),
            // UNKNOWN is a real row in the rate table; null means nobody asked, and the table prices it the same way.
            applicant.smokerStatus() == null ? SmokerStatus.UNKNOWN : SmokerStatus.valueOf(applicant.smokerStatus().name()),
            applicant.occupationClass(), PremiumFrequency.valueOf(in.frequency()), asOf, in.policyTermMonths()));

        QuotationDocumentPdf.Facts facts = new QuotationDocumentPdf.Facts()
            .add("Sum assured", money(in.sumAssured(), currency))
            .add("Age at entry", String.valueOf(quote.ageAtEntry()))
            .add("Term", term(in.policyTermMonths()))
            .add("Premium-paying term", in.premiumPayingTermMonths() == null
                ? (in.policyTermMonths() == null ? null : "The whole term") : term(in.premiumPayingTermMonths()))
            .add("Payment frequency", frequency(in.frequency()))
            .add("Premium, each payment", money(quote.instalmentAmount(), currency))
            .add("Premium a year", money(quote.annualAfterFrequencyLoading(), currency))
            .add("Proposed commencement", DAY.format(asOf));
        if (quote.totalOverTerm() != null) {
            facts.total(quote.instalmentsOverTerm() == null ? "Total payable over the term"
                : "Total payable over the term (" + quote.instalmentsOverTerm() + " payments)",
                money(quote.totalOverTerm(), currency));
        }

        return new QuotationDocumentPdf.Content("Individual life premium quotation", reference(in), name(applicant),
            product.productName(), DAY.format(today), facts.list(), List.of(), List.of());
    }

    private QuotationDocumentPdf.Content funeral(QuotationDocumentInput in, ProductSummaryView product,
                                                 PartyDetailView applicant, String currency, LocalDate asOf,
                                                 LocalDate today) {
        if (in.funeralPlanCode() == null || in.funeralPlanCode().isBlank() || in.frequency() == null) {
            throw new UnderwritingValidationException("A funeral quotation needs a plan and a payment frequency");
        }
        FuneralQuote quote = productApi.quoteFuneral(in.productVersionId(), new FuneralQuoteInput(
            in.funeralPlanCode(), PremiumFrequency.valueOf(in.frequency()), asOf, in.funeralLives()));

        List<QuotationDocumentPdf.Line> lines = quote.lines().stream()
            .map((FuneralQuoteLine l) -> new QuotationDocumentPdf.Line(l.name(), role(l.role().name()),
                String.valueOf(l.age()), money(l.benefit(), currency), money(l.yearlyPremium(), currency)))
            .toList();

        QuotationDocumentPdf.Facts facts = new QuotationDocumentPdf.Facts()
            .add("Plan", in.funeralPlanCode())
            .add("Main member's benefit", money(quote.mainMemberBenefit(), currency))
            .add("Lives covered", String.valueOf(quote.lines().size()))
            .add("Payment frequency", frequency(in.frequency()))
            .add("Proposed commencement", DAY.format(asOf))
            .add("Family total a year", money(quote.totalYearlyPremium(), currency))
            .total("Each " + frequency(in.frequency()).toLowerCase() + " payment", money(quote.instalment(), currency));

        return new QuotationDocumentPdf.Content("Family funeral premium quotation", reference(in), name(applicant),
            product.productName(), DAY.format(today), facts.list(), lines,
            List.of("A funeral plan renews each year. The premium above is this year's, for the lives listed."));
    }

    private QuotationDocumentPdf.Content savings(QuotationDocumentInput in, ProductSummaryView product,
                                                 PartyDetailView applicant, String currency, LocalDate asOf,
                                                 LocalDate today) {
        if (in.sumAssured() == null) {
            throw new UnderwritingValidationException("A savings quotation needs the amount to be paid in");
        }
        SavingsQuoteView quote = accumulationApi.quoteProductSavings(in.productId(), in.productVersionId(),
            in.sumAssured(), in.policyTermMonths(), asOf);
        boolean deposit = quote.kind() == SavingsQuoteView.Kind.DEPOSIT;

        QuotationDocumentPdf.Facts facts = new QuotationDocumentPdf.Facts()
            .add(deposit ? "Deposit" : "Opening balance", money(in.sumAssured(), currency))
            .add("Interest rate", quote.ratePercent() == null ? null
                : quote.ratePercent().stripTrailingZeros().toPlainString() + "%"
                    + (deposit ? " for the term" : " a year"))
            .add("Term", deposit ? term(quote.termMonths()) : null)
            .add("Terms offered", deposit && !quote.offeredTermMonths().isEmpty()
                ? quote.offeredTermMonths().stream().map(QuotationDocuments::term).reduce((a, b) -> a + ", " + b).orElse(null)
                : null)
            .add("Interest", money(quote.interest(), currency))
            .add("Matures on", quote.maturityDate() == null ? null : DAY.format(quote.maturityDate()))
            .add("Minimum balance", money(quote.minimumBalance(), currency))
            .add("Starts on", DAY.format(asOf))
            .total(deposit ? "Value at maturity" : null, money(quote.maturityValue(), currency));

        List<String> notes = new java.util.ArrayList<>();
        if (quote.refusal() != null) {
            notes.add(quote.refusal());
        }
        notes.add(deposit
            ? "A fixed-term deposit earns its rate over the whole term, not each year."
            : "An account has no maturity value: it is funded by deposits, earns interest on its balance at the"
                + " declared rate, and runs until it is closed.");

        return new QuotationDocumentPdf.Content(deposit ? "Fixed-term deposit quotation" : "Savings account quotation",
            reference(in), name(applicant), product.productName(), DAY.format(today), facts.list(), List.of(), notes);
    }

    /**
     * The quotation's own reference: short, and derived from the version it was priced on.
     *
     * <p>Not a stored id -- nothing is stored. It is there so a customer ringing back can be matched to the version
     * whose rates were used, which is the fact that decides whether the figure still stands.
     */
    private static String reference(QuotationDocumentInput in) {
        return "Q-" + in.productVersionId().toString().substring(0, 8).toUpperCase();
    }

    private static String name(PartyDetailView applicant) {
        return applicant.displayName() == null ? "The applicant" : applicant.displayName();
    }

    private static String money(BigDecimal amount, String currency) {
        return amount == null ? null : currency + " " + MONEY.format(amount);
    }

    private static String term(Integer months) {
        if (months == null) return null;
        if (months % 12 == 0) {
            int years = months / 12;
            return years + (years == 1 ? " year" : " years");
        }
        return months + (months == 1 ? " month" : " months");
    }

    /** MONTHLY -> Monthly: the customer's word, not the enum's. */
    private static String frequency(String frequency) {
        if (frequency == null) return null;
        String word = frequency.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String role(String role) {
        String word = role.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}

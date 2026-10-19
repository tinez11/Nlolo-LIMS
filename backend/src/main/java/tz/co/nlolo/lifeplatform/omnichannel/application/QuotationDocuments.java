package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.SavingsQuoteView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Column;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Field;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Kind;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.GroupFuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.GroupRatePeriod;
import tz.co.nlolo.lifeplatform.product.api.PortfolioCode;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.CIVIL_ZONE;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.DMY;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.ISSUER;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.address;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.frequency;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.money;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.roleLabel;

/**
 * The quotation an applicant takes away and signs, before any case exists (2026-10-16, polished 2026-10-19).
 *
 * <p><b>A customer document like every other.</b> Built as a {@link CustomerDocument} and drawn by the shared
 * {@code DocumentPdf}, so it carries the issuer, the two-column header, the ruled table, the totals and the page
 * footer the policy schedule and the payment schedule already do -- the platform's one look for what a customer is
 * handed, rather than a second one invented for this page. The first version drew its own layout and read as
 * generic beside them.
 *
 * <p><b>The totals say what the screen says.</b> "Premium", "Annual equivalent", "Total over 5 years" with its number
 * of payments: the quotation panel's own labels, because a customer reading the paper and a staff member reading the
 * screen must be able to point at the same line.
 *
 * <p><b>Quoted again, here.</b> An applicant signs this, so every figure comes from the module that owns the rate,
 * with the applicant's own date of birth, sex, smoker status and occupation read from their record -- never a
 * figure that travelled through a browser. Nothing is stored by asking.
 */
@Service
public class QuotationDocuments {

    /** Priced from a sum assured and the version's base rate table. */
    private static final Set<PortfolioCode> PREMIUM_RATED = Set.of(PortfolioCode.TERM, PortfolioCode.WL,
        PortfolioCode.END, PortfolioCode.MB, PortfolioCode.PAR);
    /** Priced from money paid in and a rate, with no sum assured at all. */
    private static final Set<PortfolioCode> SAVINGS = Set.of(PortfolioCode.SAV, PortfolioCode.DEP);

    /** The close every customer document here ends on, in the quotation's words. */
    private static final String TERMS_GOVERN =
        "This quotation summarises the cover proposed. The policy terms and conditions govern what is paid.";

    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final AccumulationApi accumulationApi;

    public QuotationDocuments(ProductApi productApi, PartyApi partyApi, AccumulationApi accumulationApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.accumulationApi = accumulationApi;
    }

    /**
     * What to quote: the form's inputs, never its figures.
     *
     * @param sumAssured the sum assured, or a deposit's amount. Null on a funeral plan, whose plan sets the benefit.
     * @param premiumPayingTermMonths null means the whole term, as the form's blank does
     * @param funeralLives a funeral plan's lives, the main member first
     */
    public record Request(UUID productId, UUID productVersionId, UUID applicantPartyId, BigDecimal sumAssured,
                          String currency, String frequency, Integer policyTermMonths,
                          Integer premiumPayingTermMonths, LocalDate commencement, String funeralPlanCode,
                          List<FuneralLifeInput> funeralLives) {
        public Request {
            funeralLives = funeralLives != null ? List.copyOf(funeralLives) : List.of();
        }
    }

    @Transactional(readOnly = true)
    public CustomerDocument quotation(Request in) {
        ProductSummaryView product = productApi.getProduct(in.productId());
        PartyDetailView applicant = partyApi.getPartyDetail(in.applicantPartyId());
        LocalDate asOf = in.commencement() != null ? in.commencement() : LocalDate.now(CIVIL_ZONE);
        String currency = in.currency() == null || in.currency().isBlank() ? product.defaultCurrency() : in.currency();
        PortfolioCode portfolio = product.portfolioCode();

        if (portfolio == PortfolioCode.FUN) return funeral(in, product, applicant, currency, asOf);
        if (SAVINGS.contains(portfolio)) return savings(in, product, applicant, currency, asOf);
        if (PREMIUM_RATED.contains(portfolio)) return premium(in, product, applicant, currency, asOf);
        // An annuity, a unit-linked policy, a group scheme: each priced on terms this form does not collect, and a
        // blank quotation an applicant could sign is the failure to avoid.
        throw new CustomerRequestRefusedException("A quotation document is not available for " + product.productName()
            + ": its price comes from terms this form does not collect.");
    }

    private CustomerDocument premium(Request in, ProductSummaryView product, PartyDetailView applicant,
                                     String currency, LocalDate asOf) {
        if (in.sumAssured() == null || in.frequency() == null) {
            throw new CustomerRequestRefusedException("A premium quotation needs a sum assured and a payment frequency");
        }
        if (applicant.dateOfBirth() == null || applicant.sex() == null) {
            throw new CustomerRequestRefusedException("A premium quotation needs the applicant's date of birth and sex"
                + " on their record: the rate table is keyed on them.");
        }
        // The party record keeps its own Sex and SmokerStatus; the rate table is keyed on the product's. Converted by
        // name, as the customer portal's quote does (CustomerProducts).
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            in.productId(), in.sumAssured(), currency, applicant.dateOfBirth(), Sex.valueOf(applicant.sex().name()),
            // UNKNOWN is a real row in the rate table; null means nobody asked, and the table prices it the same way.
            applicant.smokerStatus() == null ? SmokerStatus.UNKNOWN : SmokerStatus.valueOf(applicant.smokerStatus().name()),
            applicant.occupationClass(), PremiumFrequency.valueOf(in.frequency()), asOf, in.policyTermMonths()));

        List<Field> extra = new ArrayList<>();
        extra.add(new Field("Age at entry", String.valueOf(quote.ageAtEntry())));
        extra.add(new Field("Coverage term", term(in.policyTermMonths())));
        extra.add(new Field("Premium-paying term", in.premiumPayingTermMonths() != null ? term(in.premiumPayingTermMonths())
            : in.policyTermMonths() != null ? "The whole term" : "None stated"));
        // "Cover from", the policy schedule's own label: "Proposed commencement" is wider than the header's label
        // column and ran into its date.
        extra.add(new Field("Cover from", DMY.format(asOf)));

        // What it pays, as the policy schedule lists it: the cover, the life, the amount.
        List<Column> columns = List.of(new Column("Cover", Kind.TEXT, 2.0f), new Column("Life insured", Kind.TEXT, 2.4f),
            new Column("Pays (" + currency + ")", Kind.MONEY, 1.4f));
        List<List<Object>> rows = List.of(List.of("Death benefit", name(applicant), in.sumAssured()));

        List<Field> totals = new ArrayList<>();
        totals.add(new Field("Premium", money(quote.instalmentAmount(), currency) + " " + frequency(in.frequency())));
        if (PremiumFrequency.valueOf(in.frequency()) != PremiumFrequency.SINGLE) {
            totals.add(new Field("Annual equivalent", money(quote.annualAfterFrequencyLoading(), currency)));
        }
        if (quote.totalOverTerm() != null && in.policyTermMonths() != null) {
            totals.add(new Field("Total over " + term(in.policyTermMonths()),
                money(quote.totalOverTerm(), currency) + " (" + quote.instalmentsOverTerm() + " payments)"));
        }

        return new CustomerDocument(ISSUER, "Premium quotation", header(applicant, product, extra), columns, rows,
            totals, signOff(applicant, List.of()), false);
    }

    private CustomerDocument funeral(Request in, ProductSummaryView product, PartyDetailView applicant,
                                     String currency, LocalDate asOf) {
        if (in.funeralPlanCode() == null || in.funeralPlanCode().isBlank() || in.frequency() == null) {
            throw new CustomerRequestRefusedException("A funeral quotation needs a plan and a payment frequency");
        }
        FuneralQuote quote = productApi.quoteFuneral(in.productVersionId(), new FuneralQuoteInput(
            in.funeralPlanCode(), PremiumFrequency.valueOf(in.frequency()), asOf, in.funeralLives()));

        List<Field> extra = List.of(
            new Field("Plan", in.funeralPlanCode()),
            new Field("Lives covered", String.valueOf(quote.lines().size())),
            new Field("Cover from", DMY.format(asOf)));

        // A line per life, because a funeral premium IS per life: the grid prices each role and age on its own.
        List<Column> columns = List.of(new Column("Cover", Kind.TEXT, 1.8f), new Column("Life insured", Kind.TEXT, 2.2f),
            new Column("Age", Kind.TEXT, 0.6f), new Column("Pays (" + currency + ")", Kind.MONEY, 1.4f),
            new Column("Yearly premium (" + currency + ")", Kind.MONEY, 1.6f));
        List<List<Object>> rows = quote.lines().stream()
            .map(l -> List.<Object>of("Death of " + roleLabel(l.role().name()), l.name(), String.valueOf(l.age()),
                l.benefit(), l.yearlyPremium()))
            .toList();

        List<Field> totals = List.of(
            new Field("Family total a year", money(quote.totalYearlyPremium(), currency)),
            new Field("Premium", money(quote.instalment(), currency) + " " + frequency(in.frequency())));

        return new CustomerDocument(ISSUER, "Funeral plan quotation", header(applicant, product, extra), columns, rows,
            totals, signOff(applicant, List.of(
                "A funeral plan renews each year. The premium above is this year's, for the lives listed.",
                "Each covered life has its own waiting period from the day its cover starts.")), false);
    }

    private CustomerDocument savings(Request in, ProductSummaryView product, PartyDetailView applicant,
                                     String currency, LocalDate asOf) {
        if (in.sumAssured() == null) {
            throw new CustomerRequestRefusedException("A savings quotation needs the amount to be paid in");
        }
        SavingsQuoteView quote = accumulationApi.quoteProductSavings(in.productId(), in.productVersionId(),
            in.sumAssured(), in.policyTermMonths(), asOf);
        boolean deposit = quote.kind() == SavingsQuoteView.Kind.DEPOSIT;
        String rate = quote.ratePercent() == null ? "-"
            : quote.ratePercent().stripTrailingZeros().toPlainString() + "% " + (deposit ? "for the term" : "a year");

        List<Field> extra = new ArrayList<>();
        extra.add(new Field("Interest rate", rate));
        if (deposit) {
            extra.add(new Field("Term", quote.termMonths() == null ? "Not chosen" : term(quote.termMonths())));
            extra.add(new Field("Matures on", quote.maturityDate() == null ? "-" : DMY.format(quote.maturityDate())));
        } else if (quote.minimumBalance() != null) {
            extra.add(new Field("Minimum balance", money(quote.minimumBalance(), currency)));
        }
        extra.add(new Field("Starts on", DMY.format(asOf)));

        List<Column> columns = List.of(new Column("Item", Kind.TEXT, 3.0f), new Column("Amount (" + currency + ")", Kind.MONEY, 1.4f));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(deposit ? "Deposit" : "Opening balance", in.sumAssured()));
        if (deposit && quote.interest() != null) rows.add(List.of("Interest for the term", quote.interest()));

        List<Field> totals = new ArrayList<>();
        if (deposit && quote.maturityValue() != null) {
            totals.add(new Field("Value at maturity", money(quote.maturityValue(), currency)));
        }

        List<String> notes = new ArrayList<>();
        if (quote.refusal() != null) notes.add(quote.refusal());
        notes.add(deposit
            ? "A fixed-term deposit earns its rate over the whole term, not each year."
            : "An account has no maturity value: it is funded by deposits, earns interest on its balance at the declared"
                + " rate, and runs until it is closed.");

        return new CustomerDocument(ISSUER, deposit ? "Fixed-term deposit quotation" : "Savings account quotation",
            header(applicant, product, extra), columns, rows, totals, signOff(applicant, notes), false);
    }

    /**
     * A group funeral scheme to quote: the association, the plan, and each member's family as the propose-scheme form
     * holds them. Inputs only, as for an individual.
     */
    public record SchemeRequest(UUID productId, UUID productVersionId, UUID associationPartyId, String planCode,
                                LocalDate commencement, List<GroupFuneralQuote.FamilyInput> families) {
        public SchemeRequest {
            families = families != null ? List.copyOf(families) : List.of();
        }
    }

    /**
     * The quotation an association signs for a group funeral scheme (2026-10-19): a line per member with the family's
     * cover, and the bill -- the plan's group rate per member -- priced by the same {@code quoteGroupFuneral} the
     * propose screen shows.
     *
     * <p>Refused, not printed, while any family is one the plan will not take: the proposal would refuse the whole
     * scheme, and a quotation an association signs must be for a scheme that can actually be proposed.
     */
    @Transactional(readOnly = true)
    public CustomerDocument schemeQuotation(SchemeRequest in) {
        ProductSummaryView product = productApi.getProduct(in.productId());
        PartyDetailView association = partyApi.getPartyDetail(in.associationPartyId());
        LocalDate asOf = in.commencement() != null ? in.commencement() : LocalDate.now(CIVIL_ZONE);
        String currency = product.defaultCurrency();

        GroupFuneralQuote quote = productApi.quoteGroupFuneral(in.productVersionId(),
            new GroupFuneralQuote.Input(in.planCode(), asOf, in.families()));
        if (!quote.problems().isEmpty()) {
            throw new CustomerRequestRefusedException("This scheme cannot be quoted as entered: "
                + String.join("; ", quote.problems()));
        }
        if (quote.members() == 0) {
            throw new CustomerRequestRefusedException("A scheme quotation needs at least one member");
        }
        String per = quote.period() == GroupRatePeriod.YEARLY ? "a year" : "a month";

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Association", association.displayName() == null ? "-" : association.displayName()));
        fields.add(new Field("Phone", association.phoneNumber() != null ? association.phoneNumber() : "-"));
        fields.add(new Field("Address", address(association)));
        fields.add(new Field("Product", product.productName()));
        // The plan's NAME, as staff and the association know it ("NLevel2"), with its code beside it.
        String planName = productApi.resolveFuneralPlan(in.productVersionId()).plans().stream()
            .filter(p -> p.planCode().equals(quote.planCode())).map(FuneralPlanOption::name).findFirst().orElse(null);
        fields.add(new Field("Plan", planName == null ? quote.planCode() : planName + " (" + quote.planCode() + ")"));
        fields.add(new Field("Group rate", money(quote.rate(), currency) + " per member " + per));
        fields.add(new Field("Cover from", DMY.format(asOf)));
        fields.add(new Field("Printed on", DMY.format(LocalDate.now(CIVIL_ZONE))));

        // A line per member: who leads the family, how many lives it covers, and what their deaths pay in all.
        List<Column> columns = List.of(new Column("Member no.", Kind.TEXT, 1.0f), new Column("Main member", Kind.TEXT, 2.4f),
            new Column("Lives", Kind.TEXT, 0.7f), new Column("Family cover (" + currency + ")", Kind.MONEY, 1.6f));
        List<List<Object>> rows = quote.families().stream()
            .map(f -> List.<Object>of(f.reference() == null ? "-" : f.reference(), f.mainMember() == null ? "-" : f.mainMember(),
                String.valueOf(f.lives()), f.cover()))
            .toList();

        List<Field> totals = List.of(
            new Field("Members", String.valueOf(quote.members())),
            new Field("Bill", money(quote.bill(), currency) + " " + per),
            new Field("Total cover", money(quote.totalCover(), currency)));

        // What each life on a family is covered for, read from the plan -- the line the propose screen shows.
        FuneralPlan plan = productApi.resolveFuneralPlan(in.productVersionId());
        String covered = plan.benefits().stream().filter(b -> b.planCode().equals(quote.planCode()))
            .map(b -> roleLabel(b.role().name()) + " " + money(b.benefit(), currency))
            .collect(java.util.stream.Collectors.joining("; "));

        List<String> notes = new ArrayList<>();
        notes.add("Covered per family: " + covered + ".");
        notes.add("The association pays the plan's group rate for each member, whatever their family holds.");
        notes.add("Each covered life has its own waiting period from the day its cover starts.");
        return new CustomerDocument(ISSUER, "Group funeral scheme quotation", fields, columns, rows, totals,
            signOff("Signature for the association ...................................",
                association.displayName() == null ? "The association" : association.displayName(), notes), false);
    }

    /** The policy schedule's header, with the proposer where a policy would have its holder and number. */
    private static List<Field> header(PartyDetailView applicant, ProductSummaryView product, List<Field> extra) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Proposer", name(applicant)));
        fields.add(new Field("Phone", applicant.phoneNumber() != null ? applicant.phoneNumber() : "-"));
        fields.add(new Field("Address", address(applicant)));
        fields.add(new Field("Date of birth", applicant.dateOfBirth() != null ? DMY.format(applicant.dateOfBirth()) : "-"));
        fields.add(new Field("Product", product.productName()));
        fields.addAll(extra);
        fields.add(new Field("Printed on", DMY.format(LocalDate.now(CIVIL_ZONE))));
        return fields;
    }

    /** The notes, then the terms that govern, then the two signatures a quotation is handed over with. */
    private static List<String> signOff(PartyDetailView applicant, List<String> notes) {
        return signOff("Signature of applicant .........................................", name(applicant), notes);
    }

    /** {@code signer} names whose signature it is -- the applicant's, or an association's representative's. */
    private static List<String> signOff(String signer, String signerName, List<String> notes) {
        List<String> all = new ArrayList<>(notes);
        all.add(TERMS_GOVERN);
        all.add("");
        all.add("I confirm that I have understood the information in this quotation and have received a copy of it.");
        all.add("");
        all.add(signer + "    Date ......................");
        all.add("(" + signerName + ")");
        all.add("");
        all.add("Signature (authorised officer) ..................................    Date ......................");
        return all;
    }

    private static String name(PartyDetailView applicant) {
        return applicant.displayName() == null ? "The applicant" : applicant.displayName();
    }

    private static String term(Integer months) {
        if (months == null) return "None stated";
        if (months % 12 == 0) {
            int years = months / 12;
            return years + (years == 1 ? " year" : " years");
        }
        return months + (months == 1 ? " month" : " months");
    }
}

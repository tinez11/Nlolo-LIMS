package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.SavingsQuoteView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView.Application;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView.HealthQuestion;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs;
import tz.co.nlolo.lifeplatform.product.api.OnlineListingView;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.BeneficiaryNomination;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.DisclosureAnswer;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.NominationType;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The products a customer may browse, price and ask for (2026-10-08, the customer portal design step 5). The product
 * module decides what is offered and prices it; underwriting owns the application from the moment it is asked for.
 * Nothing here is an offer: the price is indicative and the request is an application staff and underwriting decide.
 *
 * <p>2026-10-29, the self-service rework: a product is priced by what it IS. The portal used to price every term life,
 * whole life and endowment from a sum assured, so a fixed deposit filed as an endowment was quoted as cover; a deposit
 * is now priced from its grid and a funeral plan for the family, and an application carries who receives the money and
 * the customer's answers to the health questions, straight to underwriting.
 */
@Service
public class CustomerProducts {

    /**
     * What may be priced and asked for here (the user, 2026-10-29). The savings accounts wait on their contribution
     * being what an application bills -- today an account issued from a case is rated as if it were cover.
     */
    static final Set<String> ONLINE = Set.of("PROTECTION", "CASH_VALUE", "DEPOSIT", "FUNERAL");

    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;
    private final AccumulationApi accumulationApi;

    public CustomerProducts(ProductApi productApi, PartyApi partyApi, UnderwritingApi underwritingApi,
                            AccumulationApi accumulationApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
        this.accumulationApi = accumulationApi;
    }

    @Transactional(readOnly = true)
    public List<CustomerProductView> products() {
        LocalDate today = LocalDate.now(DAR);
        return productApi.listOnlineProducts().stream().map(l -> toView(l, today)).toList();
    }

    @Transactional(readOnly = true)
    public CustomerProductView.Quote quote(UUID customerPartyId, UUID productId, CustomerProductView.QuoteRequest request) {
        OnlineListingView listing = offered(productId);
        LocalDate today = LocalDate.now(DAR);
        ProductSnapshotView version = productApi.getActiveSnapshot(productId, today);
        String kind = kindOf(listing.category(), version.productVersionId());
        if (!ONLINE.contains(kind)) {
            throw new CustomerRequestRefusedException(listing.productName() + " is priced by an adviser: ask for it and we will"
                + " contact you with a price");
        }
        PartyDetailView me = partyApi.getPartyDetail(customerPartyId);
        return switch (kind) {
            case "DEPOSIT" -> depositQuote(listing, version, request, today);
            case "FUNERAL" -> funeralQuote(listing, version, me, request, today);
            default -> premiumQuote(listing, kind, me, request, today);
        };
    }

    private CustomerProductView.Quote premiumQuote(OnlineListingView listing, String kind, PartyDetailView me,
                                                   CustomerProductView.QuoteRequest request, LocalDate today) {
        BigDecimal sumAssured = positive(request.sumAssured());
        // Every rating factor the engine prices on, asked for up front in a customer's words rather than surfacing the
        // engine's own "OCCUPATION_CLASS is required" -- found by the test, 2026-10-09.
        if (me.dateOfBirth() == null || me.sex() == null || me.occupationClass() == null) {
            throw new CustomerRequestRefusedException("We need your date of birth, sex and occupation on file to price this. Contact us to add them, or ask"
                + " for the cover and an adviser will price it.");
        }
        PremiumFrequency frequency = frequency(request.frequency());
        ProductApi.PremiumQuoteView quote;
        try {
            quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(listing.productId(), sumAssured,
                listing.defaultCurrency(), me.dateOfBirth(), Sex.valueOf(me.sex().name()),
                me.smokerStatus() == null ? SmokerStatus.UNKNOWN : SmokerStatus.valueOf(me.smokerStatus().name()),
                me.occupationClass(), frequency, today,
                request.termYears() == null ? null : request.termYears() * 12));
        } catch (tz.co.nlolo.lifeplatform.product.api.PremiumNotQuotableException e) {
            // The engine's reason names rating tables and bands -- staff words. The customer is told what to do instead.
            throw new CustomerRequestRefusedException("We cannot price "
                + listing.productName() + " for you online. Ask for the cover and an adviser will work out your price.");
        }
        return new CustomerProductView.Quote(listing.productId(), listing.productName(), kind, quote.currency(), sumAssured,
            frequency.name(), quote.instalmentAmount(), quote.annualAfterFrequencyLoading(), quote.ageAtEntry(),
            request.termYears(), quote.totalOverTerm(),
            quote.paidByYear().stream().map(y -> new CustomerProductView.PaidByYear(y.year(), y.paid())).toList(),
            null, List.of());
    }

    private CustomerProductView.Quote depositQuote(OnlineListingView listing, ProductSnapshotView version,
                                                   CustomerProductView.QuoteRequest request, LocalDate today) {
        BigDecimal amount = positive(request.amount(), "Give the amount you want to deposit");
        SavingsQuoteView q = accumulationApi.quoteProductSavings(listing.productId(), version.productVersionId(), amount,
            request.termMonths(), today);
        return new CustomerProductView.Quote(listing.productId(), listing.productName(), "DEPOSIT",
            listing.defaultCurrency(), amount, PremiumFrequency.SINGLE.name(), amount, null, null, null, null, List.of(),
            new CustomerProductView.DepositAnswer(q.amount(), q.termMonths(), q.ratePercent(), q.interest(),
                q.maturityValue(), q.maturityDate(), q.offeredTermMonths(), q.refusal()),
            List.of());
    }

    private CustomerProductView.Quote funeralQuote(OnlineListingView listing, ProductSnapshotView version, PartyDetailView me,
                                                   CustomerProductView.QuoteRequest request, LocalDate today) {
        FuneralQuote q = priceFamily(version.productVersionId(), me, request.planCode(), request.frequency(),
            request.family(), today);
        return new CustomerProductView.Quote(listing.productId(), listing.productName(), "FUNERAL",
            listing.defaultCurrency(), q.mainMemberBenefit(), q.frequency().name(), q.instalment(),
            q.totalYearlyPremium(), null, null, null, List.of(), null,
            q.lines().stream().map(l -> new CustomerProductView.FuneralLine(l.role().name(), l.name(), l.age(),
                l.benefit(), l.yearlyPremium())).toList());
    }

    /** The customer is always the main member: their name and date of birth come from their record, never the request. */
    private FuneralQuote priceFamily(UUID versionId, PartyDetailView me, String planCode, String frequency,
                                     List<CustomerProductView.FamilyMember> family, LocalDate today) {
        if (planCode == null || planCode.isBlank()) {
            throw new CustomerRequestRefusedException("Choose a plan");
        }
        if (me.dateOfBirth() == null) {
            throw new CustomerRequestRefusedException("We need your date of birth on file to price this. Contact us to add it.");
        }
        List<FuneralLifeInput> lives = new ArrayList<>();
        lives.add(new FuneralLifeInput(FuneralRole.MAIN_MEMBER, me.displayName(), me.dateOfBirth(), false));
        for (CustomerProductView.FamilyMember m : family) {
            FuneralRole role = role(m.role());
            if (m.name() == null || m.name().isBlank() || m.dateOfBirth() == null) {
                throw new CustomerRequestRefusedException("Give the name and date of birth of everyone you add");
            }
            lives.add(new FuneralLifeInput(role, m.name().trim(), m.dateOfBirth(), m.student()));
        }
        try {
            return productApi.quoteFuneral(versionId, new FuneralQuoteInput(planCode, frequency(frequency), today, lives));
        } catch (tz.co.nlolo.lifeplatform.product.api.FuneralQuoteRefusedException e) {
            // The plan's own reason -- who it will not cover and why -- is already in plain words.
            throw new CustomerRequestRefusedException(e.getMessage());
        }
    }

    /**
     * Opens an application with the customer as applicant and life insured, on the version on sale today, straight to
     * underwriting. One transaction: a family the plan refuses, or answers that are incomplete, leave nothing opened.
     */
    @Transactional
    public Application apply(UUID customerPartyId, CustomerProductView.ApplicationRequest request, String openedBy) {
        OnlineListingView listing = offered(request.productId());
        boolean alreadyAsked = cases(customerPartyId).stream()
            .anyMatch(c -> c.productId().equals(request.productId()) && c.status() != UnderwritingCaseStatus.DECIDED);
        if (alreadyAsked) {
            throw new CustomerRequestRefusedException("You have already asked for "
                + listing.productName() + ". We will contact you about it.", true);
        }
        LocalDate today = LocalDate.now(DAR);
        UUID versionId = productApi.getActiveSnapshot(request.productId(), today).productVersionId();
        String kind = kindOf(listing.category(), versionId);
        List<BeneficiaryNomination> beneficiaries = nominations(request.beneficiaries());
        List<DisclosureAnswer> answers = answers(kind, request.answers(), request.language());

        BigDecimal sumAssured;
        String frequency;
        Integer termMonths;
        FuneralQuote family = null;
        switch (kind) {
            case "DEPOSIT" -> {
                sumAssured = positive(request.amount(), "Give the amount you want to deposit");
                frequency = PremiumFrequency.SINGLE.name();
                termMonths = request.termMonths();
                SavingsQuoteView q = accumulationApi.quoteProductSavings(request.productId(), versionId, sumAssured,
                    termMonths, today);
                if (q.refusal() != null) {
                    throw new CustomerRequestRefusedException(q.refusal());
                }
            }
            case "FUNERAL" -> {
                family = priceFamily(versionId, partyApi.getPartyDetail(customerPartyId), request.planCode(),
                    request.frequency(), request.family(), today);
                sumAssured = family.mainMemberBenefit();
                frequency = family.frequency().name();
                termMonths = null;
            }
            case "PROTECTION", "CASH_VALUE" -> {
                sumAssured = positive(request.sumAssured());
                frequency = frequency(request.frequency()).name();
                termMonths = request.termYears() == null ? null : request.termYears() * 12;
            }
            default -> {
                // Asked for through an adviser: no price here, so the amount is what the customer named, if anything.
                sumAssured = request.sumAssured() == null ? BigDecimal.ZERO : positive(request.sumAssured());
                frequency = request.frequency() == null ? null : frequency(request.frequency()).name();
                termMonths = request.termYears() == null ? null : request.termYears() * 12;
            }
        }

        UnderwritingCaseView opened = underwritingApi.openCase(customerPartyId, request.productId(), versionId, sumAssured,
            listing.defaultCurrency(), null,
            new ProposalDetails(null, null, "CUSTOMER_PORTAL", null, termMonths, null, frequency, beneficiaries),
            openedBy);
        if (family != null) {
            List<FuneralApplication.Life> dependants = request.family().stream()
                .map(m -> new FuneralApplication.Life(role(m.role()), m.name().trim(), m.dateOfBirth(), null, null,
                    m.student()))
                .toList();
            underwritingApi.recordFuneralApplication(opened.caseId(), request.planCode(), dependants, openedBy);
        }
        if (!answers.isEmpty()) {
            underwritingApi.recordDisclosures(opened.caseId(), answers, openedBy);
        }
        return toApplication(opened, new HashMap<>(Map.of(listing.productId(), listing.productName())));
    }

    /** Named by the customer, each with a share: the shares must total 100 -- underwriting refuses anything else too. */
    private static List<BeneficiaryNomination> nominations(List<CustomerProductView.Beneficiary> given) {
        List<BeneficiaryNomination> nominations = new ArrayList<>();
        for (CustomerProductView.Beneficiary b : given) {
            if (b.name() == null || b.name().isBlank()) {
                throw new CustomerRequestRefusedException("Give the name of everyone who receives the money");
            }
            if (b.sharePercent() == null || b.sharePercent().signum() <= 0) {
                throw new CustomerRequestRefusedException("Give " + b.name().trim() + " a share of the money");
            }
            StringBuilder designee = new StringBuilder(b.name().trim());
            if (b.relationship() != null && !b.relationship().isBlank()) designee.append(" (").append(b.relationship().trim()).append(')');
            if (b.phone() != null && !b.phone().isBlank()) designee.append(", ").append(b.phone().trim());
            nominations.add(new BeneficiaryNomination(NominationType.FREEFORM, null, designee.toString(), b.sharePercent(), true));
        }
        if (!nominations.isEmpty()) {
            BigDecimal total = nominations.stream().map(BeneficiaryNomination::sharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(BigDecimal.valueOf(100)) != 0) {
                throw new CustomerRequestRefusedException("The shares must add up to 100%, not "
                    + total.stripTrailingZeros().toPlainString() + "%");
            }
        }
        return nominations;
    }

    /** Every question the product asks, answered; a worrying answer with a few words. Recorded as it was asked. */
    private static List<DisclosureAnswer> answers(String kind, List<CustomerProductView.HealthAnswer> given, String language) {
        List<HealthQuestion> asked = HealthQuestions.forKind(kind);
        boolean swahili = "SW".equalsIgnoreCase(language);
        Map<String, CustomerProductView.HealthAnswer> byCode = new HashMap<>();
        for (CustomerProductView.HealthAnswer a : given) {
            byCode.put(a.code(), a);
        }
        List<DisclosureAnswer> answers = new ArrayList<>();
        for (HealthQuestion q : asked) {
            CustomerProductView.HealthAnswer a = byCode.get(q.code());
            if (a == null || !("YES".equals(a.answer()) || "NO".equals(a.answer()))) {
                throw new CustomerRequestRefusedException("Answer every health question");
            }
            boolean yes = "YES".equals(a.answer());
            String notes = a.notes() == null ? null : a.notes().trim();
            if (HealthQuestions.needsDetail(q.code(), yes) && (notes == null || notes.isEmpty())) {
                throw new CustomerRequestRefusedException("Tell us a little more where you answered "
                    + (yes ? "yes" : "no") + ": " + q.en());
            }
            answers.add(new DisclosureAnswer(q.code(), swahili ? q.sw() : q.en(), a.answer(),
                notes == null || notes.isEmpty() ? null : notes));
        }
        return answers;
    }

    @Transactional(readOnly = true)
    public List<Application> applications(UUID customerPartyId) {
        Map<UUID, String> names = new HashMap<>();
        return cases(customerPartyId).stream()
            .sorted(Comparator.comparing(UnderwritingCaseView::proposalNumber, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(c -> toApplication(c, names))
            .toList();
    }

    private List<UnderwritingCaseView> cases(UUID customerPartyId) {
        return underwritingApi.listCases(null, customerPartyId, null, PageRequest.of(0, 100)).getContent().stream()
            .filter(c -> !c.groupScheme())
            .toList();
    }

    private Application toApplication(UnderwritingCaseView c, Map<UUID, String> names) {
        String name = names.computeIfAbsent(c.productId(), id -> {
            try {
                return productApi.getProduct(id).productName();
            } catch (RuntimeException e) {
                return null;
            }
        });
        return new Application(c.caseId(), c.proposalNumber(), c.productId(), name,
            c.sumAssuredAmount(), c.sumAssuredCurrency(),
            c.status().name(), statusText(c), stage(c), c.decisionDecidedAt());
    }

    /** The same, as a step on the customer's progress line. */
    static String stage(UnderwritingCaseView c) {
        if (c.saleLockedAt() != null) return "ISSUED";
        if (c.status() == UnderwritingCaseStatus.RETURNED) return "NEEDS_YOU";
        if (c.status() != UnderwritingCaseStatus.DECIDED) return "REVIEWING";
        DecisionOutcome outcome = c.decisionOutcome();
        if (outcome == DecisionOutcome.ACCEPT || outcome == DecisionOutcome.LOADED) return "ACCEPTED";
        return outcome == DecisionOutcome.POSTPONED ? "POSTPONED" : "DECLINED";
    }

    /** Where an application is, in a customer's words. */
    static String statusText(UnderwritingCaseView c) {
        if (c.saleLockedAt() != null) return "Policy issued — see My policies";
        if (c.status() == UnderwritingCaseStatus.RETURNED) return "We need something from you — we will contact you";
        if (c.status() != UnderwritingCaseStatus.DECIDED) return "Being reviewed";
        DecisionOutcome outcome = c.decisionOutcome();
        if (outcome == DecisionOutcome.ACCEPT || outcome == DecisionOutcome.LOADED) {
            return "Accepted — your offer is being prepared";
        }
        if (outcome == DecisionOutcome.POSTPONED) return "Postponed — we will contact you";
        return "Not accepted — please contact us";
    }

    private OnlineListingView offered(UUID productId) {
        OnlineListingView listing = productApi.onlineListing(productId);
        if (!listing.available()) {
            // The same answer as a product that does not exist: an unlisted product is not the customer's to see.
            throw new tz.co.nlolo.lifeplatform.product.api.ProductNotFoundException(productId);
        }
        return listing;
    }

    /**
     * What a product is, from what its version carries: a savings category may be a cash-value plan, an account, a term
     * account or a deposit, and only the version can say which (the same rule the product page follows).
     */
    String kindOf(ProductCategory category, UUID versionId) {
        return switch (category) {
            case FUNERAL -> productApi.resolveFuneralPlan(versionId).soldAs() == FuneralSoldAs.GROUP ? "OTHER" : "FUNERAL";
            case ANNUITY -> "ANNUITY";
            case UNIT_LINKED -> "UNIT_LINKED";
            case TERM_LIFE -> "PROTECTION";
            case ENDOWMENT, WHOLE_LIFE, EDUCATION_SAVINGS -> {
                if (productApi.resolveDepositPlan(versionId).isDeposit()) yield "DEPOSIT";
                AccumulationPlan account = productApi.resolveAccumulationPlan(versionId);
                if (account.isTermAccount()) yield "TERM_ACCOUNT";
                if (account.isAccount()) yield "SAVINGS_ACCOUNT";
                yield category == ProductCategory.EDUCATION_SAVINGS ? "EDUCATION" : "CASH_VALUE";
            }
            default -> "OTHER";
        };
    }

    private CustomerProductView toView(OnlineListingView l, LocalDate today) {
        ProductSnapshotView version;
        try {
            version = productApi.getActiveSnapshot(l.productId(), today);
        } catch (RuntimeException e) {
            // Listed, but nothing on sale today: it can only be asked about.
            return new CustomerProductView(l.productId(), l.productName(), l.category().name(), l.defaultCurrency(),
                l.summary(), l.benefits(), false, "OTHER", false,
                new CustomerProductView.Terms(null, null, null, null, List.of(), List.of(), List.of()), List.of());
        }
        String kind = kindOf(l.category(), version.productVersionId());
        boolean online = ONLINE.contains(kind);
        return new CustomerProductView(l.productId(), l.productName(), l.category().name(), l.defaultCurrency(),
            l.summary(), l.benefits(), online, kind, online, terms(kind, version), HealthQuestions.forKind(kind));
    }

    private CustomerProductView.Terms terms(String kind, ProductSnapshotView version) {
        EligibilityBounds bounds = version.eligibility();
        Integer minYears = bounds == null || bounds.minTermMonths() == null ? null : (bounds.minTermMonths() + 11) / 12;
        Integer maxYears = bounds == null || bounds.maxTermMonths() == null ? null : bounds.maxTermMonths() / 12;
        BigDecimal minAmount = bounds == null ? null : bounds.minSumAssured();
        BigDecimal maxAmount = bounds == null ? null : bounds.maxSumAssured();
        if ("DEPOSIT".equals(kind)) {
            DepositPlan grid = productApi.resolveDepositPlan(version.productVersionId());
            return new CustomerProductView.Terms(null, null, grid.bandStarts().isEmpty() ? null : grid.bandStarts().get(0),
                null, grid.terms(), List.of(), List.of());
        }
        if ("FUNERAL".equals(kind)) {
            FuneralPlan plan = productApi.resolveFuneralPlan(version.productVersionId());
            return new CustomerProductView.Terms(null, null, null, null, List.of(),
                plan.plans().stream().map(p -> new CustomerProductView.FuneralPlanOffer(p.planCode(), p.name(),
                    plan.benefits().stream()
                        .filter(b -> b.planCode().equals(p.planCode()) && b.role() == FuneralRole.MAIN_MEMBER)
                        .map(tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit::benefit).findFirst().orElse(null)))
                    .toList(),
                plan.roles().stream().map(r -> new CustomerProductView.FuneralRoleOffer(r.role().name(), r.maxLives(),
                    r.minEntryAge(), r.maxEntryAge())).toList());
        }
        return new CustomerProductView.Terms(minYears, maxYears, minAmount, maxAmount, List.of(), List.of(), List.of());
    }

    private static FuneralRole role(String raw) {
        try {
            FuneralRole role = FuneralRole.valueOf(raw);
            if (role == FuneralRole.MAIN_MEMBER) throw new IllegalArgumentException();
            return role;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CustomerRequestRefusedException("Say who each person is: your spouse, a child, a parent or another relative");
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        return positive(amount, "Give the amount of cover you want");
    }

    private static BigDecimal positive(BigDecimal amount, String refusal) {
        if (amount == null || amount.signum() <= 0) {
            throw new CustomerRequestRefusedException(refusal);
        }
        return amount;
    }

    private static PremiumFrequency frequency(String raw) {
        try {
            return raw == null ? PremiumFrequency.MONTHLY : PremiumFrequency.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new CustomerRequestRefusedException("Choose how often to pay: monthly, quarterly or yearly");
        }
    }
}

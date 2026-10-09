package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView.Application;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.OnlineListingView;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
 */
@Service
public class CustomerProducts {

    /** Priced from a sum assured on the customer's own rating factors. The others need an adviser to price them. */
    static final Set<ProductCategory> QUOTABLE = Set.of(ProductCategory.TERM_LIFE, ProductCategory.WHOLE_LIFE,
        ProductCategory.ENDOWMENT);

    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;

    public CustomerProducts(ProductApi productApi, PartyApi partyApi, UnderwritingApi underwritingApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
    }

    @Transactional(readOnly = true)
    public List<CustomerProductView> products() {
        return productApi.listOnlineProducts().stream().map(CustomerProducts::toView).toList();
    }

    @Transactional(readOnly = true)
    public CustomerProductView.Quote quote(UUID customerPartyId, UUID productId, CustomerProductView.QuoteRequest request) {
        OnlineListingView listing = offered(productId);
        if (!QUOTABLE.contains(listing.category())) {
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException(listing.productName() + " is priced by an adviser: ask for it and we will"
                + " contact you with a price");
        }
        BigDecimal sumAssured = positive(request.sumAssured());
        PartyDetailView me = partyApi.getPartyDetail(customerPartyId);
        // Every rating factor the engine prices on, asked for up front in a customer's words rather than surfacing the
        // engine's own "OCCUPATION_CLASS is required" -- found by the test, 2026-10-09.
        if (me.dateOfBirth() == null || me.sex() == null || me.occupationClass() == null) {
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException("We need your date of birth, sex and occupation on file to price this. Contact us to add them, or ask"
                + " for the cover and an adviser will price it.");
        }
        PremiumFrequency frequency = frequency(request.frequency());
        ProductApi.PremiumQuoteView quote;
        try {
            quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(productId, sumAssured,
                listing.defaultCurrency(), me.dateOfBirth(), Sex.valueOf(me.sex().name()),
                me.smokerStatus() == null ? SmokerStatus.UNKNOWN : SmokerStatus.valueOf(me.smokerStatus().name()),
                me.occupationClass(), frequency, LocalDate.now(DAR),
                request.termYears() == null ? null : request.termYears() * 12));
        } catch (tz.co.nlolo.lifeplatform.product.api.PremiumNotQuotableException e) {
            // The engine's reason names rating tables and bands -- staff words. The customer is told what to do instead.
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException("We cannot price "
                + listing.productName() + " for you online. Ask for the cover and an adviser will work out your price.");
        }
        return new CustomerProductView.Quote(productId, listing.productName(), quote.currency(), sumAssured,
            frequency.name(), quote.instalmentAmount(), quote.annualAfterFrequencyLoading(), quote.ageAtEntry());
    }

    /** Opens an application with the customer as applicant and life insured, on the version on sale today. */
    @Transactional
    public Application apply(UUID customerPartyId, CustomerProductView.ApplicationRequest request, String openedBy) {
        OnlineListingView listing = offered(request.productId());
        boolean alreadyAsked = cases(customerPartyId).stream()
            .anyMatch(c -> c.productId().equals(request.productId()) && c.status() != UnderwritingCaseStatus.DECIDED);
        if (alreadyAsked) {
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException("You have already asked for "
                + listing.productName() + ". We will contact you about it.", true);
        }
        UUID versionId = productApi.getActiveSnapshot(request.productId(), LocalDate.now(DAR)).productVersionId();
        BigDecimal sumAssured = request.sumAssured() == null ? BigDecimal.ZERO : positive(request.sumAssured());
        UnderwritingCaseView opened = underwritingApi.openCase(customerPartyId, request.productId(), versionId, sumAssured,
            listing.defaultCurrency(), null,
            new ProposalDetails(null, null, "CUSTOMER_PORTAL", null,
                request.termYears() == null ? null : request.termYears() * 12, null,
                request.frequency() == null ? null : frequency(request.frequency()).name(), List.of()),
            openedBy);
        return toApplication(opened, new HashMap<>(Map.of(listing.productId(), listing.productName())));
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
            c.status().name(), statusText(c));
    }

    /** Where an application is, in a customer's words. */
    static String statusText(UnderwritingCaseView c) {
        if (c.saleLockedAt() != null) return "Policy issued — see My policies";
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

    private static CustomerProductView toView(OnlineListingView l) {
        return new CustomerProductView(l.productId(), l.productName(), l.category().name(), l.defaultCurrency(),
            l.summary(), l.benefits(), QUOTABLE.contains(l.category()));
    }

    private static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException("Give the amount of cover you want");
        }
        return amount;
    }

    private static PremiumFrequency frequency(String raw) {
        try {
            return raw == null ? PremiumFrequency.MONTHLY : PremiumFrequency.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException("Choose how often to pay: monthly, quarterly or yearly");
        }
    }
}

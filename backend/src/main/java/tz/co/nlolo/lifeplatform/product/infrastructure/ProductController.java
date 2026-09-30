package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.TiraFiling;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class ProductController {

    private final ProductApi productApi;

    public ProductController(ProductApi productApi) {
        this.productApi = productApi;
    }

    @GetMapping("/products")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<ProductSummaryView>> listProducts(@RequestParam(required = false) ProductCategory category) {
        return ResponseEntity.ok(productApi.listActiveProducts(category));
    }

    /**
     * The products created but never published.
     *
     * <p>Authoring is two phases — create the definition, then publish a version — and only
     * the second makes a product ACTIVE and therefore visible through {@code GET /products}.
     * Abandoning it left a DRAFT that no endpoint on this platform would return, while
     * {@code ux_product_code} kept holding its code: the code was burned, and the product
     * could be neither seen, finished nor removed. This is the list that makes it reachable
     * again, so the publish form can be reopened against it.
     *
     * <p>ADMIN, matching {@link #createProduct} and {@link #publishVersion}. It is deliberately
     * a separate path rather than a {@code status} parameter on the catalogue endpoint above:
     * that one answers to {@code REALM_CUSTOMERS} and {@code REALM_AGENTS}, and an unlaunched
     * product is not something a policyholder or a tied agent gets to enumerate.
     */
    @GetMapping("/products/drafts")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<List<ProductSummaryView>> listDraftProducts() {
        return ResponseEntity.ok(productApi.listDraftProducts());
    }

    /**
     * Authoring a product. ADMIN, and this is the endpoint that finally makes that role mean
     * something.
     *
     * <p>It was {@code hasRole('REALM_STAFF')}, so any staff member could create a product —
     * while PRODUCT.md described ADMIN as "everything a finance officer sees, PLUS product
     * authoring and configuration". That sentence was simply false: authoring was open to
     * everyone, and ADMIN carried no capability FINANCE_OFFICER lacked, on any endpoint. The
     * documented model is the correct one, so the code moves to it rather than the doc bending
     * to the code.
     *
     * <p>ADMIN alone rather than the {@code FINANCE_OFFICER or ADMIN} pair used everywhere else:
     * pricing a life product is rare actuarial set-up, not a finance officer's daily work, and
     * widening it to finance would leave ADMIN decorative again.
     *
     * <p>Every product READ stays exactly as it was. Issuing a policy needs the catalogue, the
     * active snapshot and a premium quote, so gating those would break underwriting for the
     * roles that must never be blocked from it.
     */
    @PostMapping("/products")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<ProductSummaryView> createProduct(@Valid @RequestBody CreateProductRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        ProductSummaryView view = productApi.createProduct(request.productCode(), request.productName(), request.category(), request.defaultCurrency(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** Publishing a version prices the product and puts it in force — same gate as authoring it. */
    @PostMapping("/products/{productId}/versions")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<Void> publishVersion(@PathVariable UUID productId, @Valid @RequestBody PublishVersionRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        productApi.publishVersion(productId, request.ifrsMeasurementModel(), request.effectiveDate(), request.retirementDate(),
            request.ratingTable().stream().map(r -> new ProductApi.RatingFactorInput(r.factorType(), r.band(), r.multiplier(),
                r.ageFrom(), r.ageTo(), r.sumAssuredFrom(), r.sumAssuredTo())).collect(Collectors.toList()),
            request.benefitSchedule().stream().map(b -> new ProductApi.BenefitInput(b.benefitType(),
                b.calculationMethod(), b.percent(), b.flatAmount())).collect(Collectors.toList()),
            request.fundDefinitions() != null
                ? request.fundDefinitions().stream().map(f -> new ProductApi.FundInput(f.fundCode(), f.currentNav())).collect(Collectors.toList())
                : null,
            request.baseRates() != null
                ? request.baseRates().stream().map(b -> new ProductApi.BaseRateInput(b.ageFrom(), b.ageTo(), b.sex(), b.smokerStatus(), b.ratePerMille(), b.termFromMonths(), b.termToMonths())).collect(Collectors.toList())
                : List.of(),
            // Never null downstream: an omitted block means an unbounded version, which is
            // a real design rather than a missing answer.
            request.eligibility() != null ? request.eligibility().toBounds() : EligibilityBounds.none(),
            // Same rule: an omitted block means a version that charges every frequency the same,
            // which is a real pricing decision and what every version published before V11 does.
            request.frequencyLoading() != null
                ? new FrequencyLoading(request.frequencyLoading().monthlyPercent(),
                                        request.frequencyLoading().quarterlyPercent())
                : FrequencyLoading.none(),
            // Required, with no fallback: a version may not exist without the filing that
            // authorises it. @NotNull on the request rejects an absent block at the edge, so
            // this dereference is safe.
            new TiraFiling(request.tiraFiling().reference(), request.tiraFiling().approvalDate()),
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * One product by id, so a screen holding an id can show a NAME.
     *
     * <p>Nothing could. {@code /products} is the catalogue and is keyed by nothing;
     * {@code /active-snapshot} below takes an id but its view carries no code and no name.
     * So the underwriting queue rendered a column of uuids, and the case detail rail printed
     * one with a note saying no lookup existed — on the screen where an underwriter decides
     * whether to accept the risk.
     *
     * <p>Same gate as the catalogue, NOT the ADMIN gate on {@code /products/drafts}: what
     * drafts protects is enumeration of unlaunched products, and resolving a single id the
     * caller already holds enumerates nothing. It is declared BEFORE the {@code {productId}}
     * paths that follow only for readability — Spring matches the literal {@code /drafts}
     * segment above ahead of this variable regardless of declaration order.
     */
    @GetMapping("/products/{productId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProductSummaryView> getProduct(@PathVariable UUID productId) {
        return ResponseEntity.ok(productApi.getProduct(productId));
    }

    @GetMapping("/products/{productId}/active-snapshot")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProductSnapshotView> getActiveSnapshot(@PathVariable UUID productId, @RequestParam(required = false) LocalDate effectiveDate) {
        return ResponseEntity.ok(productApi.getActiveSnapshot(productId, effectiveDate));
    }

    /**
     * 200, not 201: nothing is created. Persists nothing, publishes nothing, and
     * takes NO `Idempotency-Key` -- it is a calculation, so replaying it is free and
     * there is no duplicate to prevent. The frontend interceptor that asserts the
     * header on the six endpoints that hard-require it must not gain a seventh entry
     * for this path, or a live-pricing form throws on every keystroke.
     *
     * POST rather than GET despite being a read, because the body carries
     * `dateOfBirth`, `sex` and `smokerStatus`. A GET would put those in a URL, and
     * therefore into access logs, proxy logs and browser history.
     */
    @PostMapping("/products/{productId}/premium-quote")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProductApi.PremiumQuoteView> quotePremium(@PathVariable UUID productId,
                                                                    @Valid @RequestBody PremiumQuoteRequest request) {
        return ResponseEntity.ok(productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, request.sumAssuredAmount(), request.sumAssuredCurrency(), request.dateOfBirth(),
            request.sex(), request.smokerStatus(), request.occupationClass(),
            request.frequency(), request.asOf(), request.policyTermMonths())));
    }

    /**
     * The rating basis for one version, for actuarial and product review.
     *
     * Kept OFF `ProductSnapshot`, which Underwriting and Billing consume on the
     * issuance and billing path: fattening it with rate tables would make every
     * consumer carry data exactly one screen needs, and it could not be role-scoped
     * because Billing must keep reading it. This can be, and is.
     */
    @GetMapping("/products/{productId}/versions/{versionId}/rating")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<ProductApi.VersionRatingView> getVersionRating(@PathVariable UUID productId,
                                                                         @PathVariable UUID versionId) {
        return ResponseEntity.ok(productApi.getVersionRating(productId, versionId));
    }

    /**
     * Set (or clear) a version's policy-term exclusion windows.
     *
     * <p>Without this the windows could only be set by writing SQL, which made the claims
     * exclusion gate inert in any real deployment: a product ships with both windows null,
     * {@code ExclusionWindows.openAt} then returns nothing, and every exclusion decline an
     * assessor tries to record is refused for citing a window that is not open. For credit life
     * that is not a minor gap — below the free cover limit nobody is underwritten, so these two
     * windows are the entire anti-selection control the product has.
     *
     * <p>PUT, not POST: setting them is idempotent and replaces both values together. Sending one
     * field alone clears the other, deliberately — these are the version's exclusions as a whole,
     * and a partial update would make "no suicide exclusion" indistinguishable from "I did not
     * mention it".
     *
     * <p>ADMIN, matching every other write on this controller. A version's exclusions are a
     * priced term of the product, and the same separation-of-duties argument that gates
     * publishVersion applies unchanged.
     *
     * <p>Read them back through {@code GET /products/{productId}/active-snapshot}, which returns
     * {@code ProductSnapshotView} and so already carries both fields.
     */
    @PutMapping("/products/{productId}/versions/{versionId}/exclusion-periods")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<Void> setExclusionPeriods(@PathVariable UUID productId,
                                                     @PathVariable UUID versionId,
                                                     @Valid @RequestBody SetExclusionPeriodsRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        // productId is in the path for URL consistency with the sibling rating endpoint and is
        // deliberately not passed down: setExclusionPeriods resolves the version by id and scopes
        // it by tenant, so accepting productId as a second key would let a caller pass a pair that
        // disagrees and leave the two checks to argue about which one wins.
        productApi.setExclusionPeriods(versionId, request.suicideExclusionMonths(),
            request.preExistingExclusionMonths(), jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}

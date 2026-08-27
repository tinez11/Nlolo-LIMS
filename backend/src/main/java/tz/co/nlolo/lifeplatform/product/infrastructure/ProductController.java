package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.ProductApi;
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

    @PostMapping("/products")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<ProductSummaryView> createProduct(@Valid @RequestBody CreateProductRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        ProductSummaryView view = productApi.createProduct(request.productCode(), request.productName(), request.category(), request.defaultCurrency(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/products/{productId}/versions")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> publishVersion(@PathVariable UUID productId, @Valid @RequestBody PublishVersionRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        productApi.publishVersion(productId, request.ifrsMeasurementModel(), request.effectiveDate(), request.retirementDate(),
            request.ratingTable().stream().map(r -> new ProductApi.RatingFactorInput(r.factorType(), r.band(), r.multiplier())).collect(Collectors.toList()),
            request.benefitSchedule().stream().map(b -> new ProductApi.BenefitInput(b.benefitType(), b.calculationMethod())).collect(Collectors.toList()),
            request.fundDefinitions() != null
                ? request.fundDefinitions().stream().map(f -> new ProductApi.FundInput(f.fundCode(), f.currentNav())).collect(Collectors.toList())
                : null,
            request.baseRates() != null
                ? request.baseRates().stream().map(b -> new ProductApi.BaseRateInput(b.ageFrom(), b.ageTo(), b.sex(), b.smokerStatus(), b.ratePerMille())).collect(Collectors.toList())
                : List.of(),
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).build();
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
            request.sex(), request.smokerStatus(), request.occupationClass(), request.sumAssuredBand(),
            request.frequency(), request.asOf())));
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
}

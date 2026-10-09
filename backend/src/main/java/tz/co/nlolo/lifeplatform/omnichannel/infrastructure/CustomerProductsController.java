package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerProducts;

import java.util.List;
import java.util.UUID;

/**
 * Products, quotes and applications in the customer portal (2026-10-08, the customer portal design step 5). Customers
 * only, always as the token's own party: the applicant is never taken from the request.
 */
@RestController
public class CustomerProductsController {

    private final CustomerProducts products;

    public CustomerProductsController(CustomerProducts products) {
        this.products = products;
    }

    @GetMapping("/customer/products")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<List<CustomerProductView>> products() {
        return ResponseEntity.ok(products.products());
    }

    /** An indicative price on the customer's own details. Read-only, so no idempotency key. */
    @PostMapping("/customer/products/{productId}/quote")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<CustomerProductView.Quote> quote(@PathVariable UUID productId,
            @RequestBody CustomerProductView.QuoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(products.quote(CustomerPortalController.customer(jwt), productId, request));
    }

    @GetMapping("/customer/applications")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<List<CustomerProductView.Application>> applications(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(products.applications(CustomerPortalController.customer(jwt)));
    }

    /** Ask for a product. A second request for one already being reviewed is a 409, not a second application. */
    @PostMapping("/customer/applications")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<CustomerProductView.Application> apply(@RequestBody CustomerProductView.ApplicationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(products.apply(CustomerPortalController.customer(jwt), request, jwt.getSubject()));
    }

    @ExceptionHandler(CustomerRequestRefusedException.class)
    public ProblemDetail refused(CustomerRequestRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            e.conflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle(e.conflict() ? "Already asked" : "Cannot do that");
        problem.setProperty("errorCode", e.conflict() ? "ALREADY_REQUESTED" : "CUSTOMER_REQUEST_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}

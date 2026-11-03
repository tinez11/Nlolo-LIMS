package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerProductView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerProducts;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicAccess;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The public quote link (2026-11-02, the user): anybody may open it, with no login, to see what the products the
 * portal sells online would cost them. Nothing is stored -- a visit, the products and a price are all reads.
 *
 * <p>Open to everyone in {@code SecurityConfig}, and guarded here instead: a visit starts only after Cloudflare
 * Turnstile is passed (checked on the server), pricing needs that visit's signed token, every call is rate-limited,
 * and the insurer is this deployment's -- see {@link PublicAccess}.
 */
@RestController
public class PublicQuoteController {

    /** The header a visit token travels in. */
    public static final String VISIT_HEADER = "X-Visit";

    private final CustomerProducts products;
    private final PublicAccess access;

    public PublicQuoteController(CustomerProducts products, PublicAccess access) {
        this.products = products;
        this.access = access;
    }

    public record VisitRequest(String turnstileToken) {}

    public record Visit(String visitToken, int minutes) {}

    public record QuoteBody(CustomerProductView.Person person, CustomerProductView.QuoteRequest request) {}

    /** Starts a visit once the browser has passed the challenge. */
    @PostMapping("/public/visits")
    public ResponseEntity<Visit> visit(@RequestBody(required = false) VisitRequest body, HttpServletRequest http) {
        String token = access.startVisit(body == null ? null : body.turnstileToken(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Visit(token, PublicAccess.VISIT_MINUTES));
    }

    /** What the portal sells online, in this deployment's insurer -- the same list a customer sees. */
    @GetMapping("/public/products")
    public ResponseEntity<List<CustomerProductView>> products(HttpServletRequest http) {
        access.limit("products:" + http.getRemoteAddr(), 120, Duration.ofMinutes(10));
        return ResponseEntity.ok(asTenant(products::products));
    }

    /** An indicative price on the facts the visitor typed. Needs a visit; read-only. */
    @PostMapping("/public/products/{productId}/quote")
    public ResponseEntity<CustomerProductView.Quote> quote(@PathVariable UUID productId, @RequestBody QuoteBody body,
            @RequestHeader(value = VISIT_HEADER, required = false) String visitToken, HttpServletRequest http) {
        String visit = access.visit(visitToken);
        access.limit("quote:" + http.getRemoteAddr(), 120, Duration.ofMinutes(10));
        access.limit("quote-visit:" + visit, 60, Duration.ofMinutes(10));
        if (body == null || body.request() == null) {
            throw new CustomerRequestRefusedException("Answer the questions to see a price");
        }
        return ResponseEntity.ok(asTenant(() -> products.quoteForVisitor(productId, body.request(), body.person())));
    }

    /** Runs a read as this deployment's insurer, and leaves no tenant behind on the thread. */
    private <T> T asTenant(Supplier<T> read) {
        TenantContext.set(access.tenant());
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    @ExceptionHandler(PublicAccess.Refused.class)
    public ProblemDetail refused(PublicAccess.Refused e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setTitle(e.status() == 429 ? "Too many requests" : "Not available");
        problem.setProperty("errorCode", e.status() == 429 ? "RATE_LIMITED" : "PUBLIC_ACCESS_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    /** As the portal answers it: a request this product cannot meet, in its own words. */
    @ExceptionHandler(CustomerRequestRefusedException.class)
    public ProblemDetail cannot(CustomerRequestRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Cannot do that");
        problem.setProperty("errorCode", "CUSTOMER_REQUEST_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}

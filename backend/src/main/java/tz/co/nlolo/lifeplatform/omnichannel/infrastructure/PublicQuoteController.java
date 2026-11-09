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
import tz.co.nlolo.lifeplatform.omnichannel.application.PhoneCodes;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicAccess;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicApplications;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicConsent;

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
    private final PhoneCodes codes;
    private final PublicApplications applications;

    public PublicQuoteController(CustomerProducts products, PublicAccess access, PhoneCodes codes,
                                 PublicApplications applications) {
        this.products = products;
        this.access = access;
        this.codes = codes;
        this.applications = applications;
    }

    public record VisitRequest(String turnstileToken) {}

    public record Visit(String visitToken, int minutes) {}

    public record QuoteBody(CustomerProductView.Person person, CustomerProductView.QuoteRequest request) {}

    /** The declaration a visitor agrees to before applying, in both languages, with the version the agreement records. */
    public record ConsentWording(String version, String en, String sw) {}

    public record CodeRequest(String phoneNumber, String language) {}

    public record CodeCheck(String phoneNumber, String code, String language) {}

    /** The phone as it will be recorded, and the proof the code confirmed it, for the application to carry. */
    public record ConfirmedPhone(String phoneNumber, String phoneToken) {}

    public record ApplyBody(String phoneToken, PublicApplications.Applicant applicant, PublicApplications.Consent consent,
                            CustomerProductView.ApplicationRequest request) {}

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

    /** What applying means, word for word as the application will record it (2026-11-08: a draft for compliance). */
    @GetMapping("/public/consent")
    public ResponseEntity<ConsentWording> consent(HttpServletRequest http) {
        access.tenant();
        access.limit("consent:" + http.getRemoteAddr(), 120, Duration.ofMinutes(10));
        return ResponseEntity.ok(new ConsentWording(PublicConsent.VERSION, PublicConsent.EN, PublicConsent.SW));
    }

    /**
     * Texts a six-digit code to the phone the visitor gave (2026-11-02, the user: the phone is required to apply, and
     * confirmed by SMS). Three an hour to a phone, and few per address and per visit, so the link cannot be used to
     * send texts to strangers.
     */
    @PostMapping("/public/phone-codes")
    public ResponseEntity<Void> sendCode(@RequestBody(required = false) CodeRequest body,
            @RequestHeader(value = VISIT_HEADER, required = false) String visitToken, HttpServletRequest http) {
        String visit = access.visit(visitToken);
        // Per address loosely: a mobile network puts many people behind one; the per-phone cap does the real work.
        access.limit("code:" + http.getRemoteAddr(), 30, Duration.ofHours(1));
        access.limit("code-visit:" + visit, 5, Duration.ofHours(1));
        String phone = PhoneCodes.phone(body == null ? null : body.phoneNumber());
        asTenant(() -> {
            codes.send(phone, visit, swahili(body.language()));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** Checks the code; a right one is exchanged for a token the application carries. */
    @PostMapping("/public/phone-codes/check")
    public ResponseEntity<ConfirmedPhone> checkCode(@RequestBody(required = false) CodeCheck body,
            @RequestHeader(value = VISIT_HEADER, required = false) String visitToken, HttpServletRequest http) {
        String visit = access.visit(visitToken);
        access.limit("code-check:" + http.getRemoteAddr(), 30, Duration.ofMinutes(10));
        String phone = PhoneCodes.phone(body == null ? null : body.phoneNumber());
        codes.check(phone, visit, body.code(), swahili(body.language()));
        return ResponseEntity.ok(new ConfirmedPhone(phone, access.confirmPhone(phone, visit)));
    }

    /**
     * Applies: a new client, KYC pending, and an underwriting case with source "Online (public)". The phone is the one
     * the code confirmed in this visit -- never one from the body.
     */
    @PostMapping("/public/applications")
    public ResponseEntity<CustomerProductView.Application> apply(@RequestBody(required = false) ApplyBody body,
            @RequestHeader(value = VISIT_HEADER, required = false) String visitToken, HttpServletRequest http) {
        String visit = access.visit(visitToken);
        access.limit("apply:" + http.getRemoteAddr(), 30, Duration.ofHours(1));
        if (body == null || body.request() == null || body.request().productId() == null) {
            throw new CustomerRequestRefusedException("Choose the cover you are applying for");
        }
        // Any live visit of this page: it renews its visit by itself, and the proof outlives one (2026-11-09).
        String phone = access.confirmedPhone(body.phoneToken());
        access.limit("apply-phone:" + phone, 5, Duration.ofDays(1));
        CustomerProductView.Application opened = asTenant(() -> applications.apply(phone, body.applicant(),
            body.consent(), body.request(), http.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED).body(opened);
    }

    /** Swahili unless the visitor chose English -- the platform's default language. */
    private static boolean swahili(String language) {
        return language == null || !"EN".equalsIgnoreCase(language);
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

    /** A code not sent or not right, in the visitor's language. */
    @ExceptionHandler(PhoneCodes.Refused.class)
    public ProblemDetail code(PhoneCodes.Refused e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Phone not confirmed");
        problem.setProperty("errorCode", "PHONE_CODE_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    @ExceptionHandler(PublicAccess.Refused.class)
    public ProblemDetail refused(PublicAccess.Refused e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setTitle(e.status() == 429 ? "Too many requests" : "Not available");
        problem.setProperty("errorCode", e.status() == 429 ? "RATE_LIMITED" : "PUBLIC_ACCESS_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    /** As the portal answers it: a request this product cannot meet, in its own words; asked twice is a 409. */
    @ExceptionHandler(CustomerRequestRefusedException.class)
    public ProblemDetail cannot(CustomerRequestRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            e.conflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle(e.conflict() ? "Already asked" : "Cannot do that");
        problem.setProperty("errorCode", e.conflict() ? "ALREADY_REQUESTED" : "CUSTOMER_REQUEST_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}

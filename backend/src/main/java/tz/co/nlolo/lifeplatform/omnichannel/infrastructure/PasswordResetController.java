package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordResets;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicAccess;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The public /reset page's calls (2026-11-19, Authentik cut-over ticket 07): ask for a code for a phone number, then
 * exchange the right code for Authentik's one-time recovery link. No login: each call carries a visit, started once
 * Turnstile passed (POST /public/visits, shared with the quote link), and the tenant is this deployment's configured
 * insurer, never one from the request. Works with the quote link switched off.
 */
@RestController
public class PasswordResetController {

    public record ResetRequest(String phoneNumber, String language) {}

    public record ResetCheck(String phoneNumber, String code, String language) {}

    public record RecoveryLink(String link) {}

    private final PasswordResets resets;
    private final PublicAccess access;

    public PasswordResetController(PasswordResets resets, PublicAccess access) {
        this.resets = resets;
        this.access = access;
    }

    /** Texts a code when the number has an agent's or customer's login; answered the same either way. */
    @PostMapping("/public/password-reset")
    public ResponseEntity<Void> request(@RequestBody(required = false) ResetRequest body,
            @RequestHeader(value = PublicQuoteController.VISIT_HEADER, required = false) String visitToken,
            HttpServletRequest http) {
        String visit = access.visit(visitToken);
        // Per address loosely (many people share a mobile network's address); the per-number caps do the real work.
        access.limit("reset:" + http.getRemoteAddr(), 30, Duration.ofHours(1));
        access.limit("reset-visit:" + visit, 5, Duration.ofHours(1));
        asInsurer(() -> {
            resets.request(body == null ? null : body.phoneNumber(), swahili(body == null ? null : body.language()));
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    /** The link the browser follows to choose a new password, once the code checks out. */
    @PostMapping("/public/password-reset/check")
    public ResponseEntity<RecoveryLink> check(@RequestBody(required = false) ResetCheck body,
            @RequestHeader(value = PublicQuoteController.VISIT_HEADER, required = false) String visitToken,
            HttpServletRequest http) {
        access.visit(visitToken);
        access.limit("reset-check:" + http.getRemoteAddr(), 30, Duration.ofMinutes(10));
        String link = asInsurer(() -> resets.check(body == null ? null : body.phoneNumber(),
            body == null ? null : body.code(), swahili(body == null ? null : body.language())));
        return ResponseEntity.ok(new RecoveryLink(link));
    }

    private <T> T asInsurer(Supplier<T> work) {
        TenantContext.set(access.insurer());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean swahili(String language) {
        return "sw".equalsIgnoreCase(language);
    }

    @ExceptionHandler(PasswordResets.Refused.class)
    public ProblemDetail refused(PasswordResets.Refused e) {
        HttpStatus status = e.tooMany() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(e.tooMany() ? "Too many codes" : "Code not accepted");
        problem.setProperty("errorCode", e.tooMany() ? "RESET_RATE_LIMITED" : "RESET_CODE_REFUSED");
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
}

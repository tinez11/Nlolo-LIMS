package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Authenticates the mobile-money aggregator's callback. This is the FIRST unauthenticated path
 * in the platform's filter chain (SecurityConfig otherwise ends in anyRequest().authenticated()),
 * so it carries its own authentication rather than having none:
 *
 * <ul>
 *   <li>HMAC-SHA256 over the exact raw request body with a shared secret from config.</li>
 *   <li>MessageDigest.isEqual for the comparison — constant-time, so the filter does not leak
 *       the expected signature one byte at a time through response timing.</li>
 *   <li>A timestamp header inside a bounded replay window, signed as part of the payload, so a
 *       captured-and-replayed callback stops working once the window closes.</li>
 *   <li>Fails CLOSED: any missing header, malformed timestamp, stale timestamp, or signature
 *       mismatch is a 401 and the request never reaches the controller.</li>
 * </ul>
 *
 * <p>Deliberately NOT in the payment module's own security config — the filter chain is owned by
 * iam, and this filter is registered there so the exemption is visible in one place.
 *
 * <p><b>Review fix (Critical 4):</b> {@code shouldNotFilter} originally compared
 * {@code request.getRequestURI()} against a literal string. {@code getRequestURI()} is neither
 * decoded nor stripped of path parameters (matrix variables like {@code ;x=1}), while
 * {@code SecurityConfig}'s {@code requestMatchers(HttpMethod.POST, CALLBACK_PATH)} and the
 * controller's {@code @PostMapping(CALLBACK_PATH)} both resolve through Spring MVC's own request
 * mapping (via {@link MvcRequestMatcher}/{@code PathPatternParser} respectively), which does
 * decode and does strip them. A request like {@code POST /webhooks/mobile-money-callback;x=1}
 * could therefore satisfy the security {@code permitAll()} rule and the MVC mapping while this
 * filter's old check returned {@code true} (skip) — an unauthenticated write path into the
 * ledger. Fixed by matching through the exact same mechanism Spring MVC itself uses to decide
 * whether a request reaches the controller: {@link MvcRequestMatcher}, backed by the same
 * {@link HandlerMappingIntrospector} Spring Security's own {@code requestMatchers(...)} uses when
 * Spring MVC is on the classpath (true here). This guarantees the filter's applicability check
 * can never be narrower than what actually dispatches to the controller.
 *
 * <p><b>Review fix (Critical 2):</b> {@code doFilterInternal} originally wrapped the request in a
 * {@code ContentCachingRequestWrapper} and read its body via {@code getInputStream()} to compute
 * the signature, then forwarded that SAME wrapper down the chain expecting the controller to be
 * able to re-read the body. In this Spring version, {@code ContentCachingRequestWrapper
 * .getInputStream()} memoizes a single stream instance and returns the already-exhausted one on
 * every subsequent call -- there is no reset, so Jackson would throw
 * {@code HttpMessageNotReadableException} on every correctly-signed callback, never reaching the
 * controller's business logic at all. Fixed by reading the raw body directly from the original
 * request (no caching wrapper needed at all), then -- once verified -- wrapping the ORIGINAL
 * request in a small {@link ReplayableRequestWrapper} whose {@code getInputStream()}/
 * {@code getReader()} construct a genuinely FRESH stream from the already-captured {@code byte[]}
 * on every call, so the controller's own read is a real, independent replay rather than a second
 * call against an exhausted stream.
 */
@Component
public class MobileMoneyHmacFilter extends OncePerRequestFilter {

    static final String CALLBACK_PATH = "/webhooks/mobile-money-callback";
    private static final String SIGNATURE_HEADER = "X-MobileMoney-Signature";
    private static final String TIMESTAMP_HEADER = "X-MobileMoney-Timestamp";

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyHmacFilter.class);

    private final byte[] secret;
    private final Duration replayWindow;
    private final RequestMatcher callbackMatcher;

    public MobileMoneyHmacFilter(@Value("${mobile-money.callback-hmac-secret}") String secret,
                                  @Value("${mobile-money.callback-replay-window-seconds}") long replayWindowSeconds,
                                  HandlerMappingIntrospector handlerMappingIntrospector) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.replayWindow = Duration.ofSeconds(replayWindowSeconds);
        MvcRequestMatcher matcher = new MvcRequestMatcher(handlerMappingIntrospector, CALLBACK_PATH);
        matcher.setMethod(HttpMethod.POST);
        this.callbackMatcher = matcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !callbackMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Read the raw body directly -- no caching wrapper needed, since verification happens
        // right here and the controller gets its own independent replay via ReplayableRequestWrapper
        // below, not a second read of this same stream.
        byte[] body = request.getInputStream().readAllBytes();

        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (signature == null || timestamp == null || !withinReplayWindow(timestamp)) {
            reject(response, "missing or stale callback authentication headers");
            return;
        }
        String expected = hmacHex((timestamp + "." + new String(body, StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            reject(response, "callback signature mismatch");
            return;
        }
        chain.doFilter(new ReplayableRequestWrapper(request, body), response);
    }

    private boolean withinReplayWindow(String timestampHeader) {
        try {
            Instant sent = Instant.parse(timestampHeader);
            Duration skew = Duration.between(sent, Instant.now()).abs();
            return skew.compareTo(replayWindow) <= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute callback HMAC", e);
        }
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        // Reason logged, never returned — a caller failing authentication learns only that it
        // failed, not which check caught it.
        log.warn("Rejected mobile-money callback: {}", reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\"Callback authentication failed\",\"errorCode\":\"CALLBACK_AUTH_FAILED\"}");
    }

    /**
     * A genuinely replayable request wrapper -- unlike {@code ContentCachingRequestWrapper} in
     * this Spring version (see class javadoc, Critical 2), {@code getInputStream()}/
     * {@code getReader()} here construct a brand-new stream over the already-captured
     * {@code body} array on every call, so the controller's {@code @RequestBody} deserialization
     * reads a real, independent copy rather than an exhausted one.
     */
    private static final class ReplayableRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        ReplayableRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous servlet processing only (no async I/O anywhere on this path) --
                    // nothing to notify.
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name();
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }
}

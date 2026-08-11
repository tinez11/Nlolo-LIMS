package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
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
 */
@Component
public class MobileMoneyHmacFilter extends OncePerRequestFilter {

    static final String CALLBACK_PATH = "/webhooks/mobile-money-callback";
    private static final String SIGNATURE_HEADER = "X-MobileMoney-Signature";
    private static final String TIMESTAMP_HEADER = "X-MobileMoney-Timestamp";

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyHmacFilter.class);

    private final byte[] secret;
    private final Duration replayWindow;

    public MobileMoneyHmacFilter(@Value("${mobile-money.callback-hmac-secret}") String secret,
                                  @Value("${mobile-money.callback-replay-window-seconds}") long replayWindowSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.replayWindow = Duration.ofSeconds(replayWindowSeconds);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CALLBACK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        // Read the body through the wrapper so both this filter and the controller can see it —
        // a raw ServletInputStream is single-pass, so verifying the signature would otherwise
        // consume the body the controller needs.
        byte[] body = wrapped.getInputStream().readAllBytes();

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
        chain.doFilter(wrapped, response);
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
}

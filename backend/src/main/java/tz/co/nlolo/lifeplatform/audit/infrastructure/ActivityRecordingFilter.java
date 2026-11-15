package tz.co.nlolo.lifeplatform.audit.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.TokenNames;
import tz.co.nlolo.lifeplatform.audit.application.ActivityContext;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRecorder;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRedaction;
import tz.co.nlolo.lifeplatform.audit.application.RecordRefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records every write a signed-in person makes (2026-11-14, activity log): who, which action, which record, what was
 * sent (secrets removed), and how it ended -- done, refused with the words they saw, or failed.
 *
 * <p>Registered just inside Spring Security ({@link ActivityFilterConfig}), so the person and the tenant are known, and
 * the route and its variables are read after the controller has run. Writes made without a signed-in person -- the
 * public quote link, Keycloak's internal calls, the mobile-money callback -- are not recorded here; what they cause
 * still reaches the log as System lines.
 */
public class ActivityRecordingFilter extends OncePerRequestFilter {

    static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** The most of a body kept; a larger one is recorded without its body. */
    static final int BODY_CAP = 64 * 1024;

    private final ActivityLabels labels;
    private final ActivityRecorder recorder;
    private final ObjectMapper json;

    public ActivityRecordingFilter(ActivityLabels labels, ActivityRecorder recorder, ObjectMapper json) {
        this.labels = labels;
        this.recorder = recorder;
        this.json = json;
    }

    /** Routes no person calls: visitors, Keycloak, the payment gateway. */
    public static boolean unrecorded(String pattern) {
        return pattern.startsWith("/public/") || pattern.startsWith("/internal/") || pattern.startsWith("/webhooks/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITES.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID tenant = TenantContext.getOrNull();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || tenant == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID actionId = UUID.randomUUID();
        Instant started = Instant.now();
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, BODY_CAP);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        ActivityContext.set(actionId);
        try {
            chain.doFilter(req, res);
        } finally {
            ActivityContext.clear();
            try {
                record(actionId, tenant, started, jwt, auth, req, res);
            } finally {
                res.copyBodyToResponse();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void record(UUID actionId, UUID tenant, Instant started, Jwt jwt, Authentication auth,
                        ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        String pattern = (String) req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null || unrecorded(pattern)) return;   // never reached a controller, or not a person's act
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        ActivityLabels.Label label = labels.find(req.getMethod(), pattern);
        JsonNode body = readJson(req.getContentAsByteArray(), req.getContentType());
        JsonNode answer = readJson(res.getContentAsByteArray(), res.getContentType());
        RecordRefs.Ref ref = RecordRefs.of(vars, pattern);
        RecordRefs.Amount money = RecordRefs.amount(body, answer);
        int status = res.getStatus();
        String outcome = status < 400 ? "DONE" : status < 500 ? "REFUSED" : "FAILED";
        String reason = status >= 400 && status < 500 && answer != null && answer.hasNonNull("detail")
            ? answer.get("detail").asText() : null;
        String kept = body != null ? ActivityRedaction.withoutSecrets(body).toString() : uploadNote(req);
        recorder.record(new ActivityRecorder.Row(actionId, tenant, started, jwt.getSubject(), TokenNames.displayName(jwt),
            kindOf(auth), req.getMethod(), pattern,
            label == null ? req.getMethod() + " " + pattern : label.text(), label == null ? "other" : label.kind(),
            ref == null ? null : ref.type(), ref == null ? null : ref.id(), RecordRefs.label(answer),
            money == null ? null : money.amount(), money == null ? null : money.currencyCode(),
            kept, outcome, status, reason));
    }

    /** The workspace the request ran in, from the authority the converter granted (ROLE_REALM_STAFF and so on). */
    static String kindOf(Authentication auth) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            switch (a.getAuthority()) {
                case "ROLE_REALM_AGENTS" -> { return "AGENT"; }
                case "ROLE_REALM_CUSTOMERS" -> { return "CUSTOMER"; }
                default -> { }
            }
        }
        return "STAFF";
    }

    private JsonNode readJson(byte[] bytes, String contentType) {
        if (bytes.length == 0 || bytes.length >= BODY_CAP || contentType == null || !contentType.contains("json")) {
            return null;
        }
        try {
            return json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** A file upload: each file's name and size, never its content. */
    private String uploadNote(HttpServletRequest req) {
        String type = req.getContentType();
        if (type == null || !type.startsWith("multipart/")) return null;
        try {
            ArrayNode files = json.createArrayNode();
            for (Part part : req.getParts()) {
                if (part.getSubmittedFileName() != null) {
                    files.addObject().put("file", part.getSubmittedFileName()).put("bytes", part.getSize());
                }
            }
            return json.createObjectNode().set("files", files).toString();
        } catch (Exception unreadable) {
            return null;
        }
    }
}

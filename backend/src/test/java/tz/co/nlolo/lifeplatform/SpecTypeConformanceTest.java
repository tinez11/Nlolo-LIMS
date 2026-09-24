package tz.co.nlolo.lifeplatform;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link SpecTypeConformance} itself, and -- the point of the whole exercise -- pins the gap
 * it closes: every "caught here" case below asserts on ONE AND THE SAME body that
 * {@code openApi().isValid(SPEC_PATH)} PASSES while {@code matchesDeclaredTypes} FAILS. If a future
 * swagger-request-validator upgrade ever starts enforcing primitive types itself, the
 * {@code isValid} half of these assertions is what will fail and tell us this utility has become
 * redundant.
 *
 * <p>No Spring context and no containers here: the real {@code OpenApiValidationMatchers} matcher
 * operates on an {@link MvcResult}, so a {@link StubMvcResult} over a hand-built request/response
 * pair drives BOTH matchers with a fully controlled body -- which is the only way to put a
 * deliberately spec-violating body on the wire without also breaking production code.
 */
class SpecTypeConformanceTest {

    private static final String SPEC_PATH = "api/openapi/openapi-claims.yaml";
    private static final String CLAIM_ID = "3f1c3d1e-1111-4222-8333-444455556666";
    private static final String PARTY_ID = "5a2d4e2f-2222-4333-8444-555566667777";

    /** A ClaimView that satisfies openapi-claims.yaml in full, used as the base for each mutation. */
    private static String validDisabilityClaimView(String impairmentPercent, String permanent,
                                                   String requiresContestabilityReview, String policyNumber) {
        return """
            {"claimId":"%s","policyNumber":%s,"claimantPartyId":"%s","claimType":"DISABILITY",
             "status":"REGISTERED","dateOfEvent":"2026-01-15",
             "details":{"claimType":"DISABILITY","disabilityType":"Loss of limb","onsetDate":"2026-01-15",
                        "permanent":%s,"impairmentPercent":%s},
             "approvedAmount":null,"requiresContestabilityReview":%s}
            """.formatted(CLAIM_ID, policyNumber, PARTY_ID, permanent, impairmentPercent,
                requiresContestabilityReview);
    }

    private static String validDisabilityClaimView() {
        return validDisabilityClaimView("\"62.50\"", "true", "false", "\"POL-000000001\"");
    }

    // --- driving both matchers over the same body -------------------------------------------------

    private static MvcResult jsonResponse(String method, String uri, int status, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        // swagger-request-validator's MockMvcRequest reads the path from getPathInfo(), which real
        // MockMvc request builders populate but MockHttpServletRequest's constructor does not.
        request.setPathInfo(uri);
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return new BodyOnlyMvcResult(request, response);
    }

    /**
     * The minimum {@link MvcResult} both matchers actually consult: the request (for operation
     * lookup) and the response (status, content type, body). Spring's own StubMvcResult is not
     * published in the spring-test artifact, and a real MockMvc round trip cannot produce a
     * deliberately spec-violating body without breaking production code -- which is the whole point
     * of stubbing here. Everything else throws, so any future reliance on it is loud, not silent.
     */
    private record BodyOnlyMvcResult(MockHttpServletRequest request, MockHttpServletResponse response)
            implements MvcResult {

        @Override public MockHttpServletRequest getRequest() {
            return request;
        }

        @Override public MockHttpServletResponse getResponse() {
            return response;
        }

        @Override public Object getHandler() {
            throw new UnsupportedOperationException();
        }

        @Override public HandlerInterceptor[] getInterceptors() {
            throw new UnsupportedOperationException();
        }

        @Override public ModelAndView getModelAndView() {
            throw new UnsupportedOperationException();
        }

        @Override public Exception getResolvedException() {
            throw new UnsupportedOperationException();
        }

        @Override public FlashMap getFlashMap() {
            throw new UnsupportedOperationException();
        }

        @Override public Object getAsyncResult() {
            throw new UnsupportedOperationException();
        }

        @Override public Object getAsyncResult(long timeToWait) {
            throw new UnsupportedOperationException();
        }
    }

    private static MvcResult getClaim(String body) throws Exception {
        return jsonResponse("GET", "/claims/" + CLAIM_ID, 200, body);
    }

    private static void assertIsValidPasses(MvcResult result) {
        assertThatCode(() -> OpenApiValidationMatchers.openApi().isValid(SPEC_PATH).match(result))
            .as("openApi().isValid() is expected NOT to catch this -- that is the gap "
                + "SpecTypeConformance exists to cover")
            .doesNotThrowAnyException();
    }

    // --- the gap: primitive type coercion --------------------------------------------------------

    @Test
    @DisplayName("a type:string property emitted as a JSON number: isValid() passes, this matcher fails")
    void topLevelStringEmittedAsANumberIsCaughtHereAndMissedByIsValid() throws Exception {
        // policyNumber -> openapi-common.yaml PolicyNumberRef: type string, pattern ^[A-Z0-9-]{6,20}$.
        // As a bare number the pattern check does not even fire.
        MvcResult result = getClaim(validDisabilityClaimView("\"62.50\"", "true", "false", "12345678"));

        assertIsValidPasses(result);

        assertThatThrownBy(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/policyNumber")
            .hasMessageContaining("declares type 'string'")
            .hasMessageContaining("JSON number");
    }

    @Test
    @DisplayName("a type:boolean property emitted as a JSON string: isValid() passes, this matcher fails")
    void booleanEmittedAsAStringIsCaughtHereAndMissedByIsValid() throws Exception {
        MvcResult result = getClaim(validDisabilityClaimView("\"62.50\"", "true", "\"false\"", "\"POL-000000001\""));

        assertIsValidPasses(result);

        assertThatThrownBy(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/requiresContestabilityReview")
            .hasMessageContaining("declares type 'boolean'")
            .hasMessageContaining("JSON string");
    }

    /**
     * The real M6 violation's exact shape: {@code impairmentPercent} is nested two levels deep,
     * inside {@code ClaimView.details}, which is a {@code oneOf} over four branches -- so this also
     * proves the branch selection by single-value {@code claimType} enum works.
     */
    @Test
    @DisplayName("a nested string-as-number inside the details oneOf is caught, and missed by isValid()")
    void nestedStringInsideTheDetailsOneOfIsCaughtHereAndMissedByIsValid() throws Exception {
        MvcResult result = getClaim(validDisabilityClaimView("62.50", "true", "false", "\"POL-000000001\""));

        assertIsValidPasses(result);

        assertThatThrownBy(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/details/impairmentPercent")
            .hasMessageContaining("declares type 'string'")
            .hasMessageContaining("JSON number");
    }

    @Test
    @DisplayName("a nested boolean-as-string inside the details oneOf is caught")
    void nestedBooleanInsideTheDetailsOneOfIsCaught() throws Exception {
        MvcResult result = getClaim(validDisabilityClaimView("\"62.50\"", "\"true\"", "false", "\"POL-000000001\""));

        assertThatThrownBy(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/details/permanent")
            .hasMessageContaining("declares type 'boolean'");
    }

    /**
     * Money.amount three levels down and inside an array element -- the highest-consequence case on
     * a financial platform, and simultaneously the proof that swagger-parser genuinely resolves the
     * cross-file {@code openapi-common.yaml#/components/schemas/Money} {@code $ref} from this path
     * (an unresolved {@code $ref} would fail with the "could not be resolved" message instead, and
     * the walk would otherwise have skipped the field entirely).
     */
    @Test
    @DisplayName("Money.amount emitted as a number inside an array of objects is caught")
    void moneyAmountAsANumberInsideAnArrayElementIsCaught() throws Exception {
        String body = """
            {"items":[{"claimId":"%s","policyNumber":"POL-000000001","claimantPartyId":"%s",
                       "claimType":"MATURITY","status":"APPROVED","dateOfEvent":"2026-01-15",
                       "details":{"claimType":"MATURITY","maturityDate":"2026-01-15"},
                       "approvedAmount":{"amount":2000000.00,"currencyCode":"TZS"},
                       "requiresContestabilityReview":false}],
             "page":{"page":0,"pageSize":20,"totalElements":1}}
            """.formatted(CLAIM_ID, PARTY_ID);
        MvcResult result = jsonResponse("GET", "/claims", 200, body);

        assertIsValidPasses(result);

        assertThatThrownBy(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimSearchResponse").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/items/0/approvedAmount/amount")
            .hasMessageContaining("declares type 'string'")
            .hasMessageContaining("JSON number");
    }

    // --- what must NOT fail ----------------------------------------------------------------------

    @Test
    @DisplayName("a fully valid body passes both matchers")
    void aFullyValidBodyPassesBothMatchers() throws Exception {
        MvcResult result = getClaim(validDisabilityClaimView());

        assertIsValidPasses(result);
        assertThatCode(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(result))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an absent optional property is not an error (required is isValid()'s job)")
    void absentOptionalPropertyIsNotAnError() throws Exception {
        // approvedAmount omitted entirely -- legitimately absent until the claim is approved.
        String body = """
            {"claimId":"%s","policyNumber":"POL-000000001","claimantPartyId":"%s","claimType":"MATURITY",
             "status":"REGISTERED","dateOfEvent":"2026-01-15",
             "details":{"claimType":"MATURITY","maturityDate":"2026-01-15"},
             "requiresContestabilityReview":false}
            """.formatted(CLAIM_ID, PARTY_ID);

        assertThatCode(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(getClaim(body)))
            .doesNotThrowAnyException();
    }

    /**
     * Nulls pass, and deliberately do so on BOTH a {@code nullable: true} property
     * ({@code ClaimEvidenceView.description}) and one that never declares nullability
     * ({@code ClaimView.approvedAmount}, which real responses genuinely emit as null before
     * approval -- see {@code ClaimResponseDto.from}). This test pins that documented tolerance so
     * nobody tightens it by accident: null-vs-nullable is left to {@code isValid()}.
     */
    @Test
    @DisplayName("nulls pass, whether or not the schema marks the property nullable")
    void nullsPassWhetherOrNotTheSchemaMarksThemNullable() throws Exception {
        MvcResult claim = getClaim(validDisabilityClaimView()); // approvedAmount:null, not nullable in spec
        assertThatCode(() -> SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView").match(claim))
            .doesNotThrowAnyException();

        String evidence = """
            [{"claimEvidenceId":"%s","claimId":"%s","documentRef":"claim-evidence/abc.pdf",
              "description":null,"uploadedBy":"customer","uploadedByName":null,
              "uploadedAt":"2026-01-15T10:00:00Z"}]
            """.formatted(PARTY_ID, CLAIM_ID);
        MvcResult evidenceResult = jsonResponse("GET", "/claims/" + CLAIM_ID + "/evidence", 200, evidence);
        assertIsValidPasses(evidenceResult);
        // The body is a JSON array and ClaimEvidenceView is the ELEMENT schema -- supported directly.
        assertThatCode(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimEvidenceView").match(evidenceResult))
            .doesNotThrowAnyException();
    }

    // --- the matcher's own guard rails -----------------------------------------------------------

    @Test
    @DisplayName("a walk that checks nothing fails as vacuous rather than passing silently")
    void aVacuousApplicationFails() throws Exception {
        // Nothing in this body corresponds to any ClaimView property, so no assertion could ever
        // fail -- the shape of a matcher pointed at the wrong response.
        assertThatThrownBy(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView")
                    .match(jsonResponse("GET", "/claims/" + CLAIM_ID, 200, "{}")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("ZERO type checks");
    }

    /**
     * Discovered while writing this test, and pinned here as a warning rather than smoothed over:
     * pointing the matcher at an ERROR body while naming a view schema is a misuse that the vacuity
     * guard does NOT reliably catch, because property names can collide across unrelated schemas --
     * {@code ProblemDetails.status} is a {@code type: integer} HTTP code while
     * {@code ClaimView.status} is a {@code type: string} enum, so a perfectly correct 403 body
     * reports a spurious {@code /status} mismatch. Apply this matcher only to the success response
     * whose schema you name.
     */
    @Test
    @DisplayName("naming a view schema for an error body is a misuse: colliding names report spuriously")
    void namingAViewSchemaForAnErrorBodyIsAMisuse() throws Exception {
        String problem = """
            {"type":"https://api.nlolo-lifeplatform.tz/problems/forbidden","title":"Forbidden",
             "status":403,"traceId":"%s","errorCode":"FORBIDDEN"}
            """.formatted(UUID.randomUUID());

        assertThatThrownBy(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView")
                    .match(jsonResponse("GET", "/claims/" + CLAIM_ID, 403, problem)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("/status: spec declares type 'string'");
    }

    @Test
    @DisplayName("an unknown schema name fails loudly instead of silently checking nothing")
    void anUnknownSchemaNameFails() throws Exception {
        MvcResult result = getClaim(validDisabilityClaimView());

        assertThatThrownBy(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "NoSuchView").match(result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("no components.schemas.NoSuchView");
    }

    @Test
    @DisplayName("an empty response body fails rather than passing as a no-op")
    void anEmptyBodyFails() throws Exception {
        assertThatThrownBy(() ->
                SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView")
                    .match(jsonResponse("GET", "/claims/" + CLAIM_ID, 200, "")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("empty response body");
    }
}

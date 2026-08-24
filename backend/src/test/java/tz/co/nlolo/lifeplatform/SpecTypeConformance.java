package tz.co.nlolo.lifeplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.test.web.servlet.ResultMatcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A strict PRIMITIVE-TYPE conformance matcher for JSON response bodies, meant to be applied
 * <em>alongside</em> {@code OpenApiValidationMatchers.openApi().isValid(SPEC_PATH)} in the contract
 * tests, not instead of it. It exists to close one specific, empirically-measured hole in that
 * matcher.
 *
 * <h2>Why this exists: what {@code openApi().isValid(...)} does and does not catch</h2>
 * Measured during the M6 final review with a throwaway probe that mutated real
 * {@code openapi-claims.yaml} {@code ClaimView} response bodies one field at a time and read
 * swagger-request-validator's own report. Verbatim results:
 * <ul>
 *   <li><b>CAUGHT</b> — a missing required property ({@code validation.response.body.schema.required}),
 *       a bad enum value ({@code ...schema.enum}), a violated string {@code pattern}
 *       ({@code ...schema.pattern}), an {@code additionalProperties} violation
 *       ({@code ...schema.additionalProperties}).</li>
 *   <li><b>NOT CAUGHT</b> — a {@code type: string} property emitted as a JSON <b>number</b>
 *       ({@code hasErrors=false}); a {@code type: boolean} property emitted as a JSON
 *       <b>string</b> ({@code hasErrors=false}).</li>
 * </ul>
 * So the single uncaught dimension is <b>primitive JSON type coercion</b>, and that is the entire
 * scope of this class. It deliberately does NOT re-check required/enum/pattern/additionalProperties:
 * {@code isValid(...)} already enforces those, and duplicating them here would add failure modes
 * without adding coverage. {@code SpecTypeConformanceTest} pins the contrast by asserting, on one
 * and the same body, that {@code isValid(...)} PASSES while this matcher FAILS.
 *
 * <p>This is a guard against future drift, not a fix for a present defect. At the time of writing
 * there are zero live violations: all five module-local {@code MoneyDto} records declare
 * {@code amount} as a Java {@code String}, and {@code DisabilityClaimDetails.impairmentPercent} --
 * the one real violation this gap hid, nested two levels deep inside {@code ClaimView.details}'s
 * {@code oneOf} -- was fixed in M6 with {@code @JsonFormat(shape = STRING)}.
 *
 * <h2>Semantics</h2>
 * For every property PRESENT in the actual body whose resolved schema declares exactly one
 * primitive type, the JSON node's own kind must agree: {@code string} -&gt; textual,
 * {@code number}/{@code integer} -&gt; numeric, {@code boolean} -&gt; boolean. Beyond that:
 * <ul>
 *   <li><b>Absent properties are not an error.</b> {@code isValid(...)} already enforces
 *       {@code required}; failing here on a legitimately-optional field would be a false alarm.</li>
 *   <li><b>A JSON {@code null} always passes</b>, and is treated exactly like an absent property.
 *       This is wider than "passes where {@code nullable: true}" and deliberately so: this codebase
 *       configures no {@code NON_NULL} inclusion, so real responses do emit explicit nulls for
 *       absent optional objects ({@code ClaimResponseDto.approvedAmount} is null until a claim is
 *       approved), and {@code isValid(...)} accepts those today. Rejecting them here would invent a
 *       new failure mode outside the measured gap. Whether a null belongs on a non-nullable field
 *       is therefore left entirely to {@code isValid(...)}; nothing here asserts it either way.
 *       Both 3.1-era spellings of permitted nulls ({@code nullable: true} and
 *       {@code type: [string, "null"]}) consequently pass, the latter also because a union type is
 *       never treated as a single declared primitive.</li>
 *   <li><b>Nested objects and arrays are walked</b> to any depth -- required, since the one real
 *       violation this closes over lived at {@code /details/impairmentPercent}.</li>
 *   <li><b>{@code allOf}</b> is walked branch by branch against the same node.</li>
 *   <li><b>{@code oneOf}/{@code anyOf}</b>: a branch is selected only when the payload identifies it
 *       unambiguously -- a branch matches when every property it pins to a single-value {@code enum}
 *       (the shape {@code ClaimDetails}'s four branches use for {@code claimType}, and the only
 *       shape in these specs) is present in the payload with that exact value. If exactly one branch
 *       matches it is walked; if none or several do, the subtree is SKIPPED rather than guessed at,
 *       so an undeterminable {@code oneOf} silently loses coverage instead of producing a false
 *       failure. An explicit {@code discriminator} is not consulted -- {@code ClaimDetails} has none
 *       on purpose (see its description in {@code openapi-claims.yaml}).</li>
 *   <li><b>Vacuity guard:</b> if a walk performs zero primitive checks, the matcher FAILS rather
 *       than passing silently -- that means the named schema and the body have nothing in common
 *       (usually the wrong schema name, or a matcher applied to an error/empty body), which would
 *       otherwise be an assertion that cannot fail.</li>
 *   <li>An unresolvable {@code $ref} is a FAILURE, not a skip, so cross-file resolution breaking
 *       cannot quietly turn this matcher into a no-op.</li>
 * </ul>
 * Apply it only to the SUCCESS response whose schema you name, never to an error response: property
 * names collide across unrelated schemas, and {@code ProblemDetails.status} (a {@code type: integer}
 * HTTP code) against {@code ClaimView.status} (a {@code type: string} enum) reports a spurious
 * {@code /status} mismatch on a perfectly correct 403 body. That is a measured misuse, pinned by
 * {@code SpecTypeConformanceTest.namingAViewSchemaForAnErrorBodyIsAMisuse}, and the vacuity guard
 * does not catch it.
 *
 * <p>Failures name the JSON pointer, the declared type, the actual JSON type and the offending
 * value.
 *
 * <h2>Parsing</h2>
 * Uses swagger-parser ({@link OpenAPIV3Parser}) with {@code ParseOptions.setResolve(true)}, which is
 * what makes the cross-file {@code $ref}s into {@code openapi-common.yaml} ({@code Money},
 * {@code PolicyNumberRef}, {@code PartyRef}) resolvable -- {@code SpecTypeConformanceTest} proves
 * {@code Money.amount} is genuinely reached rather than skipped. No hand-rolled YAML or {@code $ref}
 * walking. Parsed specs are cached per path; the parse is the expensive part and every contract test
 * re-applies the same handful of specs.
 *
 * <p>Usage, next to the existing matcher:
 * <pre>{@code
 * .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
 * .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView"))
 * }</pre>
 */
public final class SpecTypeConformance {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, OpenAPI> SPEC_CACHE = new ConcurrentHashMap<>();

    private SpecTypeConformance() {}

    /**
     * @param specPath   path to the module's OpenAPI file, exactly as handed to
     *                   {@code openApi().isValid(...)} (e.g. {@code api/openapi/openapi-claims.yaml}),
     *                   resolved relative to the working directory
     * @param schemaName a {@code components.schemas} key in that file (e.g. {@code ClaimView}). If
     *                   the body is a JSON array and this schema is not itself an array, every
     *                   element is checked against it.
     */
    public static ResultMatcher matchesDeclaredTypes(String specPath, String schemaName) {
        return result -> assertMatchesDeclaredTypes(specPath, schemaName,
            result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** The matcher's whole behaviour, callable directly on a body string (used by its own test). */
    public static void assertMatchesDeclaredTypes(String specPath, String schemaName, String body) throws Exception {
        Map<String, Schema> components = componentsOf(specPath);
        Schema<?> schema = components.get(schemaName);
        if (schema == null) {
            throw new AssertionError("Spec " + specPath + " declares no components.schemas." + schemaName
                + " -- known schemas: " + new java.util.TreeSet<>(components.keySet()));
        }
        if (body == null || body.isBlank()) {
            throw new AssertionError("SpecTypeConformance was applied to an empty response body against schema "
                + schemaName + ", which cannot fail -- remove the matcher or assert a body-bearing response.");
        }
        Walker walker = new Walker(components);
        JsonNode root = JSON.readTree(body);
        if (root.isArray() && schema.getItems() == null && !walker.declaredTypes(schema).contains("array")) {
            for (int i = 0; i < root.size(); i++) {
                walker.walk(root.get(i), schema, "/" + i);
            }
        } else {
            walker.walk(root, schema, "");
        }
        if (!walker.failures.isEmpty()) {
            throw new AssertionError("Response body violates the primitive types declared by "
                + specPath + "#/components/schemas/" + schemaName + " ("
                + walker.failures.size() + " violation(s); openApi().isValid(...) does not check these):"
                + walker.failures.stream().map(f -> "\n  - " + f).reduce("", String::concat)
                + "\nBody: " + body);
        }
        if (walker.primitiveChecks == 0) {
            throw new AssertionError("SpecTypeConformance performed ZERO type checks for schema " + schemaName
                + " against this body, so it asserted nothing. The schema name is probably wrong for this"
                + " response (an error body? an empty collection?). Body: " + body);
        }
    }

    private static Map<String, Schema> componentsOf(String specPath) {
        OpenAPI api = SPEC_CACHE.computeIfAbsent(specPath, path -> {
            ParseOptions options = new ParseOptions();
            // resolve(true) pulls the cross-file openapi-common.yaml $refs (Money, PartyRef,
            // PolicyNumberRef) into this document's components. resolveFully is deliberately NOT
            // set: it inlines and rewrites oneOf/allOf composition, which is exactly the structure
            // the walker below needs to see intact.
            options.setResolve(true);
            SwaggerParseResult parsed = new OpenAPIV3Parser().readLocation(path, null, options);
            if (parsed.getOpenAPI() == null) {
                throw new IllegalStateException("swagger-parser could not read " + path + ": " + parsed.getMessages());
            }
            return parsed.getOpenAPI();
        });
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return Map.of();
        }
        return api.getComponents().getSchemas();
    }

    private static final class Walker {

        private final Map<String, Schema> components;
        private final List<String> failures = new ArrayList<>();
        private int primitiveChecks;

        private Walker(Map<String, Schema> components) {
            this.components = components;
        }

        private void walk(JsonNode node, Schema<?> declared, String pointer) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return; // absent, or a null -- see this class's javadoc
            }
            Schema<?> schema = resolve(declared, pointer);
            if (schema == null) {
                return; // resolve() has already recorded the failure
            }

            if (schema.getAllOf() != null) {
                for (Schema<?> part : schema.getAllOf()) {
                    walk(node, part, pointer);
                }
            }

            List<Schema> branches = schema.getOneOf() != null ? schema.getOneOf() : schema.getAnyOf();
            if (branches != null && !branches.isEmpty()) {
                Schema<?> selected = selectBranch(branches, node);
                if (selected != null) {
                    walk(node, selected, pointer);
                }
                // Otherwise: undeterminable branch -- skipped by design, never guessed.
            }

            checkPrimitive(node, schema, pointer);

            if (node.isObject() && schema.getProperties() != null) {
                for (Map.Entry<String, Schema> property : schema.getProperties().entrySet()) {
                    JsonNode child = node.get(property.getKey());
                    if (child != null) {
                        walk(child, property.getValue(), pointer + "/" + property.getKey());
                    }
                }
            }
            if (node.isArray() && schema.getItems() != null) {
                for (int i = 0; i < node.size(); i++) {
                    walk(node.get(i), schema.getItems(), pointer + "/" + i);
                }
            }
        }

        private void checkPrimitive(JsonNode node, Schema<?> schema, String pointer) {
            Set<String> types = declaredTypes(schema);
            types.remove("null");
            if (types.size() != 1) {
                return; // no declared type, or a union type -- nothing unambiguous to assert
            }
            String declared = types.iterator().next();
            boolean ok;
            switch (declared) {
                case "string" -> ok = node.isTextual();
                case "number", "integer" -> ok = node.isNumber();
                case "boolean" -> ok = node.isBoolean();
                default -> {
                    return; // object / array -- structure, handled by the recursion instead
                }
            }
            primitiveChecks++;
            if (!ok) {
                failures.add((pointer.isEmpty() ? "(root)" : pointer) + ": spec declares type '" + declared
                    + "' but the response emitted a JSON " + actualType(node) + " (" + node + ")");
            }
        }

        /**
         * Picks the single {@code oneOf}/{@code anyOf} branch the payload identifies via its
         * single-value {@code enum} properties, or null when that is ambiguous.
         */
        private Schema<?> selectBranch(List<Schema> branches, JsonNode node) {
            Schema<?> match = null;
            for (Schema<?> branch : branches) {
                Schema<?> resolved = quietResolve(branch);
                if (resolved == null || !branchMatches(resolved, node)) {
                    continue;
                }
                if (match != null) {
                    return null; // ambiguous
                }
                match = resolved;
            }
            return match;
        }

        private boolean branchMatches(Schema<?> branch, JsonNode node) {
            if (!node.isObject() || branch.getProperties() == null) {
                return false;
            }
            boolean discriminated = false;
            for (Map.Entry<String, Schema> property : branch.getProperties().entrySet()) {
                Schema<?> propertySchema = quietResolve(property.getValue());
                if (propertySchema == null || propertySchema.getEnum() == null
                        || propertySchema.getEnum().size() != 1) {
                    continue;
                }
                JsonNode value = node.get(property.getKey());
                if (value == null || !value.isValueNode()
                        || !String.valueOf(propertySchema.getEnum().get(0)).equals(value.asText())) {
                    return false;
                }
                discriminated = true;
            }
            return discriminated;
        }

        /** Follows {@code $ref}s, recording a failure if one cannot be resolved. */
        private Schema<?> resolve(Schema<?> schema, String pointer) {
            Schema<?> resolved = quietResolve(schema);
            if (resolved == null && schema != null && schema.get$ref() != null) {
                failures.add((pointer.isEmpty() ? "(root)" : pointer) + ": spec $ref '" + schema.get$ref()
                    + "' could not be resolved, so its declared types went unchecked");
            }
            return resolved;
        }

        private Schema<?> quietResolve(Schema<?> schema) {
            Set<String> seen = new HashSet<>();
            Schema<?> current = schema;
            while (current != null && current.get$ref() != null) {
                String ref = current.get$ref();
                if (!seen.add(ref)) {
                    return null; // cyclic $ref
                }
                current = components.get(ref.substring(ref.lastIndexOf('/') + 1));
            }
            return current;
        }

        /** OpenAPI 3.1 allows {@code type} to be a list; swagger-parser exposes that as getTypes(). */
        private Set<String> declaredTypes(Schema<?> schema) {
            Set<String> types = new LinkedHashSet<>();
            if (schema.getTypes() != null) {
                types.addAll(schema.getTypes());
            } else if (schema.getType() != null) {
                types.add(schema.getType());
            }
            return types;
        }

        private static String actualType(JsonNode node) {
            if (node.isTextual()) {
                return "string";
            }
            if (node.isNumber()) {
                return "number";
            }
            if (node.isBoolean()) {
                return "boolean";
            }
            if (node.isArray()) {
                return "array";
            }
            if (node.isObject()) {
                return "object";
            }
            return node.getNodeType().toString().toLowerCase();
        }
    }
}

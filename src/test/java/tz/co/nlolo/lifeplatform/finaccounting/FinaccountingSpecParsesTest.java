package tz.co.nlolo.lifeplatform.finaccounting;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads openapi-finaccounting.yaml the same way {@code openApi().isValid(...)} and
 * {@code SpecTypeConformance} do -- swagger-parser with {@code setResolve(true)}, so cross-file
 * $refs into openapi-common.yaml are genuinely followed -- and fails on any parse or resolution
 * error.
 *
 * <p>Cheap and container-free, so it runs in milliseconds and localises a spec mistake to the spec
 * rather than surfacing it as a confusing failure inside every contract test at once. M4 lost time
 * to exactly that: an unquoted flow-style YAML description containing a comma broke a whole
 * contract test class's spec load.
 */
class FinaccountingSpecParsesTest {

    @Test
    void theFinaccountingSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation("api/openapi/openapi-finaccounting.yaml", null, options);

        assertThat(result.getMessages())
            .as("openapi-finaccounting.yaml must parse with no errors or unresolved $refs")
            .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        // Guards against a "successful" parse of an empty or truncated document.
        assertThat(result.getOpenAPI().getPaths()).containsKeys(
            "/gl-postings", "/gl-postings/{journalEntryId}", "/chart-of-accounts");
    }
}

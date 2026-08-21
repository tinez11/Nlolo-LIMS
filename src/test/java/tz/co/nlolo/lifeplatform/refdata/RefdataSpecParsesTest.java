package tz.co.nlolo.lifeplatform.refdata;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Container-free proof that openapi-refdata.yaml is a single loadable document. See
 * {@code DocumentSpecParsesTest} for why this exists as its own test. */
class RefdataSpecParsesTest {

    @Test
    void theRefdataSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation("api/openapi/openapi-refdata.yaml", null, options);

        assertThat(result.getMessages())
            .as("openapi-refdata.yaml must parse with no errors or unresolved $refs")
            .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKey("/reference-codes/{codeSetKey}");
    }
}

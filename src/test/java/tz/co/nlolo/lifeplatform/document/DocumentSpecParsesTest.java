package tz.co.nlolo.lifeplatform.document;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Container-free proof that openapi-document.yaml is a single loadable document. Until M11 this
 * module's spec lived inside a "---"-separated multi-document file, which swagger-parser cannot
 * load at all -- which is precisely why neither document nor refdata could ever have a contract
 * test. M4 lost time to an unquoted flow-style description containing a comma breaking a whole
 * contract class's spec load; localising that to one fast test is the point.
 */
class DocumentSpecParsesTest {

    @Test
    void theDocumentSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation("api/openapi/openapi-document.yaml", null, options);

        assertThat(result.getMessages())
            .as("openapi-document.yaml must parse with no errors or unresolved $refs")
            .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getPaths()).containsKeys(
            "/claims/{claimId}/evidence/{documentRef}", "/documents/{documentRef}",
            "/documents/{documentRef}/metadata");
    }
}

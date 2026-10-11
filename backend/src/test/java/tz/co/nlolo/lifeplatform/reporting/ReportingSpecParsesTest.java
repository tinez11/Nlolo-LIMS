package tz.co.nlolo.lifeplatform.reporting;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads openapi-reporting.yaml as the contract tests do, so a spec mistake fails here, not inside every test. */
class ReportingSpecParsesTest {

    @Test
    void theReportingSpecParsesAndEveryRefResolves() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation("api/openapi/openapi-reporting.yaml", null, options);
        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getPaths()).containsKeys("/reports/catalogue", "/reports/{reportKey}",
            "/reports/{reportKey}/export", "/reports/rebuild", "/reports/rebuild/status");
    }
}

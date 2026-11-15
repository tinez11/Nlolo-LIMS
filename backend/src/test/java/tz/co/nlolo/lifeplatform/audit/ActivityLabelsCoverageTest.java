package tz.co.nlolo.lifeplatform.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;
import tz.co.nlolo.lifeplatform.audit.infrastructure.ActivityRecordingFilter;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Every write route a person can call has words in the activity log (2026-11-14); a new one cannot ship unlabelled. */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ActivityLabelsCoverageTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final Set<RequestMethod> WRITES =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ActivityLabels labels;

    @Test
    void everyWriteRouteHasALabel() {
        Set<String> missing = new TreeSet<>();
        int writes = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var info = entry.getKey();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (!WRITES.contains(method)) continue;
                for (String pattern : info.getPatternValues()) {
                    if (ActivityRecordingFilter.unrecorded(pattern)) continue;
                    writes++;
                    if (labels.find(method.name(), pattern) == null) missing.add(method + " " + pattern);
                }
            }
        }
        assertThat(writes).as("write routes found -- none would prove nothing").isGreaterThan(200);
        assertThat(missing).as("write routes with no line in audit/activity-labels.tsv").isEmpty();
    }
}

package tz.co.nlolo.lifeplatform.refdata;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ReferenceDataApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql");
    }

    @Autowired
    private ReferenceDataApi referenceDataApi;

    @Test
    void returnsSeededPlaceholderValues() {
        assertThat(referenceDataApi.getValue("TZ_CONTESTABILITY_MONTHS", "TZ")).isEqualTo("24");
        assertThat(referenceDataApi.getValue("TZ_REINSTATEMENT_WINDOW_MONTHS", "TZ")).isEqualTo("12");
        assertThat(referenceDataApi.getValue("TZ_SUSPENSION_TO_LAPSE_MONTHS", "TZ")).isEqualTo("6");
        assertThat(referenceDataApi.getValue("OFFLINE_RECEIPT_SLA_HOURS", "TZ")).isEqualTo("24");
    }

    @Test
    void getCodesReturnsAllCodesForAKey() {
        assertThat(referenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")).hasSize(1);
    }
}

package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.DuplicateProductCodeException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code createProduct} used to report EVERY {@link
 * org.springframework.dao.DataIntegrityViolationException} as a duplicate product code.
 *
 * <p>This is not hypothetical: adding the CREDIT_LIFE category, a CHECK violation on
 * {@code category} surfaced as <i>"A product with code CREDIT-LIFE-01 already exists for
 * this tenant"</i> against a code that had just been invented, and cost real diagnosis
 * time. It is the same bug class as M7's {@code onboardAgent}, which mislabelled a plain
 * value-too-long as a duplicate licence.
 *
 * <p>This class exists because the failure needs a database that does <b>not</b> know the
 * category, which is the one thing {@link ProductApiIntegrationTest} cannot provide: it
 * applies V14. The migration list here deliberately stops at V13, so CREDIT_LIFE is a
 * value {@code product_definition_category_check} rejects — reproducing the exact
 * conditions, not an approximation of them.
 *
 * <p><b>Do not "fix" this class by adding V14.</b> That would make every test here pass
 * while proving nothing.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ProductIntegrityViolationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V8__rating_table_multiplier_positive.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
            "db-migrations/product/V31__online_listing.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql");
        // V14 is deliberately absent -- see the class javadoc.
    }

    @BeforeEach
    void setTenant() { TenantContext.set(UUID.randomUUID()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Autowired
    private ProductApi productApi;

    @Test
    void aCategoryTheDatabaseRejectsIsNotReportedAsADuplicateCode() {
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            productApi.createProduct("CL-CHECK-01", "Credit life",
                ProductCategory.CREDIT_LIFE, "TZS", "actuary@nlolo.co.tz"));

        assertFalse(thrown instanceof DuplicateProductCodeException,
            "a category CHECK violation was reported as a duplicate product code: "
                + thrown.getMessage());
        assertFalse(thrown.getMessage() != null && thrown.getMessage().contains("already exists"),
            "the message claims the code already exists, which is false: " + thrown.getMessage());
    }

    @Test
    void aGenuineDuplicateCodeIsStillReportedAsOne() {
        // The narrowing must not throw away the case the catch was written for.
        productApi.createProduct("DUP-01", "First", ProductCategory.TERM_LIFE, "TZS",
            "actuary@nlolo.co.tz");

        assertThrows(DuplicateProductCodeException.class, () ->
            productApi.createProduct("DUP-01", "Second", ProductCategory.TERM_LIFE, "TZS",
                "actuary@nlolo.co.tz"));
    }
}

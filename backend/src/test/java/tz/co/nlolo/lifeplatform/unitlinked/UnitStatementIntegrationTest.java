package tz.co.nlolo.lifeplatform.unitlinked;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitStatementView;
import tz.co.nlolo.lifeplatform.unitlinked.application.StatementDrain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Unit statements (U2, spec §6, Q9 = C) against a real database and object store: last calendar year's statement is
 * filed once per policy and tells the customer by SMS; an on-demand one adds to it and tells them nothing; a fund with
 * no price yet on a statement's day is shown without one, never at zero; and no period may end in the future.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class UnitStatementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** Its own object store: a statement is a real PDF filed through DocumentApi. */
    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String[] DOCUMENT_AND_COMMUNICATION = {
        "db-migrations/document/V1__create_document_schema.sql",
        "db-migrations/document/V2__add_content_type_and_file_name.sql",
        "db-migrations/document/V3__rls_fail_closed.sql",
        "db-migrations/document/V4__enrolment_schedule_document_type.sql",
        "db-migrations/document/V5__exits_file_document_type.sql",
        "db-migrations/document/V6__account_statement_document_type.sql",
        "db-migrations/document/V7__journal_support_document_type.sql",
        "db-migrations/document/V8__reinsurance_statement_document_type.sql",
        "db-migrations/document/V9__ifrs17_engine_document_types.sql",
        "db-migrations/communication/V1__create_communication_schema.sql",
        "db-migrations/communication/V2__template_identity.sql",
        "db-migrations/communication/V3__seed_offer_templates.sql",
        "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
        "db-migrations/communication/V5__dispatch_claimed_status.sql",
        "db-migrations/communication/V6__grants_and_rls.sql",
        "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
        "db-migrations/communication/V8__platform_default_templates.sql",
        "db-migrations/communication/V9__payment_received_template.sql",
        "db-migrations/communication/V10__account_statement_template.sql",
        "db-migrations/communication/V11__vesting_reminder_template.sql",
        "db-migrations/communication/V12__funeral_templates.sql",
        "db-migrations/communication/V13__unit_linked_templates.sql",
        "db-migrations/communication/V14__unit_linked_statement_template.sql",
        "db-migrations/communication/V15__dispatch_body_and_inbox.sql",
    };

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(UnitLinkedTestMigrations.ALL), Stream.of(DOCUMENT_AND_COMMUNICATION))
                .toArray(String[]::new));
        // The container never runs compose's minio-init job: ACCOUNT_STATEMENT files to the general bucket.
        MinioClient minio = MinioClient.builder()
            .endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        minio.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
    /** A day of last year: the policy's first premium is priced on it. */
    private static final LocalDate LAST_YEAR = LocalDate.of(TODAY.getYear() - 1, 6, 1);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private StatementDrain drain;
    @Autowired private JdbcTemplate jdbc;

    private record Sold(UUID tenant, String policyNumber) {}

    /** 100,000 collected on LAST_YEAR before the cut-off and priced that day at 1.00: 90,000 of units. */
    private Sold investedLastYear() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.backdate(policy, LAST_YEAR.minusDays(10));
        fixtures.collectAt(tenant, policy, "100000.00", eat(LAST_YEAR, 9, 0), UUID.randomUUID());
        fixtures.priceBoth(tenant, LAST_YEAR, "1.000000", "1.000000");
        return new Sold(tenant, policy);
    }

    private int sent(String policyNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM communication.notification_dispatch"
            + " WHERE policy_number = ? AND template_key = 'UNIT_LINKED_STATEMENT'", Integer.class, policyNumber);
    }

    @Test
    void theAnnualStatementIsFiledOncePerPolicyAndYearAndOnDemandAddsToIt() throws Exception {
        Sold s = investedLastYear();
        drain.sweepOne(s.policyNumber(), s.tenant(), LocalDate.of(TODAY.getYear(), 1, 5));
        drain.sweepOne(s.policyNumber(), s.tenant(), LocalDate.of(TODAY.getYear(), 1, 6));     // a rerun: nothing new
        UnitStatementView onDemand = asTenant(s.tenant(), () -> api.fileStatement(s.policyNumber(),
            LAST_YEAR.minusDays(30), LAST_YEAR.plusDays(30), "staff-one"));

        assertThat(asTenant(s.tenant(), () -> api.statements(s.policyNumber())))
            .extracting(UnitStatementView::kind).containsExactlyInAnyOrder("ANNUAL", "ON_DEMAND");
        assertThat(asTenant(s.tenant(), () -> api.statements(s.policyNumber()))).filteredOn(v -> v.kind().equals("ANNUAL"))
            .singleElement().satisfies(v -> {
                assertThat(v.periodFrom()).isEqualTo(LocalDate.of(TODAY.getYear() - 1, 1, 1));
                assertThat(v.periodTo()).isEqualTo(LocalDate.of(TODAY.getYear() - 1, 12, 31));
            });
        assertThat(sent(s.policyNumber())).isEqualTo(1);                                       // the annual one only

        byte[] pdf = asTenant(s.tenant(), () -> api.statementPdf(onDemand.statementId()));
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            // Before the first price nothing could be valued; after it, 90,000 at 1.00.
            assertThat(text).contains(s.policyNumber(), "No price yet", "TZS 90,000.00", "Allocation charge:  TZS 10,000.00");
        }
    }

    @Test
    void aPolicyThatNeverHeldUnitsInTheYearGetsNoAnnualStatement() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        drain.sweepOne(policy, tenant, LocalDate.of(TODAY.getYear(), 1, 5));
        assertThat(asTenant(tenant, () -> api.statements(policy))).isEmpty();
    }

    @Test
    void aStatementCannotEndInTheFutureOrStartAfterItEnds() {
        Sold s = investedLastYear();
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.fileStatement(s.policyNumber(), TODAY.minusDays(5),
            TODAY.plusDays(1), "staff-one"))).hasMessageContaining("A statement period ends today at the latest");
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.fileStatement(s.policyNumber(), TODAY,
            TODAY.minusDays(1), "staff-one"))).hasMessageContaining("starts on or before its last day");
    }
}

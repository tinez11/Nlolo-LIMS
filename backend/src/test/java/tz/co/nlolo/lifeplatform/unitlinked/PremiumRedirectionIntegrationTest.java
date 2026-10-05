package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Premium redirection (U2, spec §4): the split is a history, and each premium is divided by the split in force at
 * the instant it was RECEIVED -- a premium already waiting keeps the split it arrived under, and a redirection names
 * only offered, open funds in whole percents totalling 100.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class PremiumRedirectionIntegrationTest {

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
            UnitLinkedTestMigrations.ALL);
    }

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    @MockBean(name = "unitLinkedClock") private Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;

    @BeforeEach
    void realTime() {
        now.set(Instant.now());
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.getZone()).thenReturn(EAT);
    }

    private record Sold(UUID tenant, String policyNumber) {}

    /** Sold on 60% EQ1 / 40% BD1, funds cutting off one second past midnight so every premium waits for tomorrow. */
    private Sold sold() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandardBindingTomorrow(tenant, UnitLinkedTestFixtures.standardTerms(List.of("EQ1", "BD1")));
        return new Sold(tenant, fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice()));
    }

    @Test
    void aRedirectionAppliesToPremiumsReceivedAfterItNotToOneAlreadyWaiting() {
        Sold s = sold();
        Instant first = now.get();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", first, UUID.randomUUID());   // 60/40, waiting

        now.set(first.plusSeconds(60));
        PremiumSplitView redirected = asTenant(s.tenant(), () -> api.redirect(s.policyNumber(),
            List.of(new UnitLinkedChoice.Split("EQ1", 30), new UnitLinkedChoice.Split("BD1", 70)), "staff-one"));
        assertThat(redirected.recordedBy()).isEqualTo("staff-one");
        assertThat(redirected.effectiveFrom()).isEqualTo(first.plusSeconds(60));

        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", first.plusSeconds(120), UUID.randomUUID()); // 30/70

        // 90% of each premium is allocated in year 1 (90,000): the first 54,000/36,000, the second 27,000/63,000.
        assertThat(asTenant(s.tenant(), () -> api.units(s.policyNumber())).pending())
            .extracting(o -> o.fundCode() + ":" + o.amount().toPlainString())
            .containsExactlyInAnyOrder("EQ1:54000.00", "BD1:36000.00", "EQ1:27000.00", "BD1:63000.00");
        assertThat(asTenant(s.tenant(), () -> api.splitHistory(s.policyNumber())))
            .extracting(v -> v.shares().toString())
            .containsExactly("[Share[fundCode=BD1, percent=70], Share[fundCode=EQ1, percent=30]]",
                "[Share[fundCode=BD1, percent=40], Share[fundCode=EQ1, percent=60]]");
    }

    @Test
    void aRedirectionNamesOnlyOfferedFundsInWholePercentsTotallingAHundred() {
        Sold s = sold();
        fixtures.collectAt(s.tenant(), s.policyNumber(), "100000.00", now.get(), UUID.randomUUID());
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.redirect(s.policyNumber(),
            List.of(new UnitLinkedChoice.Split("EQ1", 50), new UnitLinkedChoice.Split("BD1", 40)), "staff-one")))
            .hasMessageContaining("The fund split totals 90%; it must total 100%");
        assertThatThrownBy(() -> asTenant(s.tenant(), () -> api.redirect(s.policyNumber(),
            List.of(new UnitLinkedChoice.Split("XX9", 100)), "staff-one")))
            .hasMessageContaining("Fund XX9 is not offered by this product");
        assertThat(asTenant(s.tenant(), () -> api.splitHistory(s.policyNumber()))).hasSize(1);   // nothing written
    }
}

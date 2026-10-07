package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.groupProposal;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.life;

/** Group funeral schemes (2026-10-07): a proposal carries an association's members and families, checked by the plan. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class GroupFuneralProposalIntegrationTest {

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private UnderwritingApi underwritingApi;

    /** Juma with his wife and two children; Rehema alone. */
    private static List<GroupProposal.LifeLine> twoFamilies() {
        return List.of(life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40), life("M001", FuneralRole.SPOUSE, "Asha Juma", 38),
            life("M001", FuneralRole.CHILD, "Neema Juma", 10), life("M001", FuneralRole.CHILD, "Baraka Juma", 7),
            life("M002", FuneralRole.MAIN_MEMBER, "Rehema Said", 50));
    }

    @Test
    void aProposalCarriesItsPlanAndFamilies() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        UUID caseId = fixtures.openGroupCase(TENANT, product, association, groupProposal("A", twoFamilies()));

        var view = asTenant(TENANT, () -> underwritingApi.getCase(caseId));
        assertThat(view.groupScheme()).isTrue();
        GroupProposal proposal = asTenant(TENANT, () -> underwritingApi.getCase(caseId)).groupProposal();
        assertThat(proposal.planCode()).isEqualTo("A");
        assertThat(proposal.premiumAmount()).as("computed at issue").isNull();
        assertThat(proposal.premiumFrequency()).isEqualTo("MONTHLY");
        assertThat(proposal.lives()).extracting(GroupProposal.LifeLine::fullName)
            .containsExactly("Juma Ali", "Asha Juma", "Neema Juma", "Baraka Juma", "Rehema Said");
    }

    @Test
    void everyFamilyIsCheckedByThePlanAndEveryProblemNamed() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        List<GroupProposal.LifeLine> lives = List.of(
            life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40), life("M001", FuneralRole.SPOUSE, "Asha", 38),
            life("M001", FuneralRole.SPOUSE, "Mwanaisha", 35),
            life("M002", FuneralRole.MAIN_MEMBER, "Rehema Said", 50), life("M002", FuneralRole.CHILD, "Zuberi", 30));
        assertThatThrownBy(() -> fixtures.openGroupCase(TENANT, product, association, groupProposal("A", lives)))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("Member M001: At most 1 spouse may be covered, not 2")
            .hasMessageContaining("Member M002: Zuberi: a child must be 0 to");
    }

    @Test
    void aGroupProposalOnAProductNotSoldToGroupsIsRefused() {
        var product = fixtures.publishFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        assertThatThrownBy(() -> fixtures.openGroupCase(TENANT, product, association, groupProposal("A", twoFamilies())))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("This product is not sold to group schemes");
    }

    @Test
    void aScheduleFileReplacesTheFamiliesOnlyWhenEveryRowPasses() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        UUID caseId = fixtures.openGroupCase(TENANT, product, association, groupProposal("A", twoFamilies()));
        String header = "member_reference,role,full_name,date_of_birth,sex,id_number,student,beneficiary_name,"
            + "beneficiary_relationship,beneficiary_phone\n";
        String bad = header + "M010,MAIN_MEMBER,Hamisi,1980-01-01,,,,,,\nM010,CHILD,Old Child," + TODAY.minusYears(30) + ",,,,,,\n";
        var refused = asTenant(TENANT, () -> underwritingApi.replaceGroupSchedule(caseId, bad.getBytes(StandardCharsets.UTF_8), "uw"));
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.problems()).anyMatch(p -> p.startsWith("Member M010: Old Child: a child must be 0 to"));
        assertThat(asTenant(TENANT, () -> underwritingApi.getCase(caseId)).groupProposal().lives()).hasSize(5);

        String good = header + "M010,MAIN_MEMBER,Hamisi,1980-01-01,,,,,,\nM010,CHILD,Mtoto," + TODAY.minusYears(5) + ",,,,,,\n";
        var accepted = asTenant(TENANT, () -> underwritingApi.replaceGroupSchedule(caseId, good.getBytes(StandardCharsets.UTF_8), "uw"));
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.families()).isEqualTo(1);
        assertThat(asTenant(TENANT, () -> underwritingApi.getCase(caseId)).groupProposal().lives())
            .extracting(GroupProposal.LifeLine::fullName).containsExactly("Hamisi", "Mtoto");
    }

    @Test
    void aGroupFuneralCaseIsNeverLoaded() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        UUID caseId = fixtures.openGroupCase(TENANT, product, association, groupProposal("A", twoFamilies()));
        assertThatThrownBy(() -> fixtures.decide(TENANT, caseId, DecisionOutcome.LOADED, new java.math.BigDecimal("1.25")))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessage("A group funeral scheme is priced at its plan's group rate; accept, decline or postpone it");
    }
}

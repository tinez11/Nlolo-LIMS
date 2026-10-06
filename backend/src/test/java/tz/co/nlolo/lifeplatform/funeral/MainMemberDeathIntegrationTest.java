package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PromoteMemberRequest;
import tz.co.nlolo.lifeplatform.policy.application.CoveredLifeSweep;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.FuneralPlans;
import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.dependant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/**
 * The main member's death, settled through the real chain, under each of the version's rules: the policy
 * ends with every life; or the family stays covered free to the next premium date with nothing more billed
 * (R5); or the spouse takes over (R8) -- and, with no spouse, the rule falls back to the policy ending.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class MainMemberDeathIntegrationTest {

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
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private BillingApi billingApi;
    @Autowired private CoveredLifeSweep sweep;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private PaymentGatewayPort gateway;

    @BeforeEach
    void railAccepts() {
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-FUNERAL", null));
    }

    /** A family on a version with {@code rule} and free cover on or off, in force, its waiting period run. */
    private String familyOn(MainMemberDeathRule rule, boolean freeCover, List<FuneralApplication.Life> dependants) {
        var product = fixtures.publish(TENANT, FuneralPlans.familia(6, DependantClaimPayee.MAIN_MEMBER, rule, freeCover));
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamilyInForce(TENANT, product, juma, dependants, annuityFixtures);
        jdbc.update("UPDATE policy.covered_life SET cover_start = cover_start - interval '7 months'"
            + " WHERE tenant_id = ? AND policy_number = ?", TENANT, policyNumber);
        return policyNumber;
    }

    private List<CoveredLifeView> lives(String policyNumber) {
        return asTenant(TENANT, () -> policyApi.coveredLives(policyNumber));
    }

    private CoveredLifeView life(String policyNumber, String name) {
        return lives(policyNumber).stream().filter(l -> l.fullName().equals(name)).findFirst().orElseThrow();
    }

    private String status(String policyNumber) {
        return asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).status().name();
    }

    /** Registered by a stranger (the main member's own death has no payee rule), assessed, approved, settled. */
    private UUID settleDeath(String policyNumber, UUID coveredLifeId, UUID claimant, String amount) {
        UUID claimId = asTenant(TENANT, () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null,
            claimant, ClaimType.DEATH, TODAY, new DeathClaimDetails("Heart failure", "Dar es Salaam", TODAY, "Dr. Test", null),
            coveredLifeId, false), UUID.randomUUID().toString(), "claims-clerk").claimId());
        asTenant(TENANT, () -> {
            claimsApi.submitAssessment(claimId, "Death certificate seen", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claimId, true, new BigDecimal(amount), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
        assertThat(asTenant(TENANT, () -> claimsApi.getClaim(claimId)).status()).isEqualTo(ClaimStatus.SETTLED);
        return claimId;
    }

    private UUID mainMember(String policyNumber) {
        return lives(policyNumber).get(0).coveredLifeId();
    }

    @Test
    void policyEndsWithoutFreeCoverEndsEveryLifeAndClosesThePolicy() {
        String policyNumber = familyOn(MainMemberDeathRule.POLICY_ENDS, false, family());

        settleDeath(policyNumber, mainMember(policyNumber), fixtures.person(TENANT, 30, Sex.FEMALE), "2000000.00");

        assertThat(lives(policyNumber)).allMatch(l -> l.status().equals("ENDED"));
        assertThat(lives(policyNumber).get(0).endReason()).isEqualTo("DECEASED");
        assertThat(life(policyNumber, "Neema").endReason()).isEqualTo("MAIN_MEMBER_DIED");
        assertThat(status(policyNumber)).isEqualTo("SURRENDERED");
    }

    @Test
    void freeCoverKeepsTheFamilyCoveredToTheNextPremiumDateWithNoFurtherInvoices() {
        String policyNumber = familyOn(MainMemberDeathRule.POLICY_ENDS, true, family());
        LocalDate issued = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).issueDate();
        LocalDate freeCoverEnds = InstalmentDates.nextAfter(issued, "MONTHLY", TODAY);

        settleDeath(policyNumber, mainMember(policyNumber), fixtures.person(TENANT, 30, Sex.FEMALE), "2000000.00");

        assertThat(status(policyNumber)).isEqualTo("ACTIVE");
        CoveredLifeView neema = life(policyNumber, "Neema");
        assertThat(neema.status()).isEqualTo("ACTIVE");
        assertThat(neema.coverEnd()).isEqualTo(freeCoverEnds);
        List<InvoiceView> invoices = asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null));
        assertThat(invoices).filteredOn(i -> i.dueDate().isAfter(TODAY)).isNotEmpty()
            .allMatch(i -> i.status().name().equals("WAIVED"));
        // The ledger's receivable is what is still owed: the invoices not withdrawn, less the one collection. Funeral
        // cover is PAA by the register's baseline, so it sits on 2142 LRC (PAA) premiums due (IFRS 17 I3a).
        BigDecimal owed = invoices.stream().filter(i -> !i.status().name().equals("WAIVED"))
            .map(InvoiceView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receivable = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND policy_number = ? AND account_code = '2142'",
            BigDecimal.class, TENANT, policyNumber);
        assertThat(receivable).isEqualByComparingTo(owed.subtract(new BigDecimal("12075.00")));

        // Billing has ended: no life joins a policy that is ending.
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber,
                new FuneralApplication.Life(FuneralRole.CHILD, "Imani", TODAY.minusYears(1), "FEMALE", null, false), "staff")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("free cover");

        // A child's death during free cover is still paid -- and restates nothing, since nothing is billed.
        BigDecimal premiumBefore = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount();
        settleDeath(policyNumber, life(policyNumber, "Baraka").coveredLifeId(),
            asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).policyholderPartyId(), "1000000.00");
        assertThat(life(policyNumber, "Baraka").endReason()).isEqualTo("DECEASED");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo(premiumBefore);

        // On the free-cover end the sweep ends the rest, and the policy closes.
        sweep.sweepOne(policyNumber, TENANT, freeCoverEnds);
        assertThat(life(policyNumber, "Neema").endReason()).isEqualTo("FREE_COVER_ENDED");
        assertThat(status(policyNumber)).isEqualTo("SURRENDERED");
    }

    @Test
    void theSpouseTakesOverAndBecomesTheMainMemberAtTheMainMemberRate() {
        String policyNumber = familyOn(MainMemberDeathRule.SPOUSE_TAKES_OVER, false, family());
        UUID deceased = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).policyholderPartyId();

        settleDeath(policyNumber, mainMember(policyNumber), fixtures.person(TENANT, 30, Sex.FEMALE), "2000000.00");
        assertThat(status(policyNumber)).isEqualTo("ACTIVE");
        assertThat(life(policyNumber, "Asha").status()).isEqualTo("ACTIVE");
        // While the takeover waits the family is billed, but not for the main member who died:
        // spouse 60,000 + three children 18,000 = 78,000; x 1.05 / 12.
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("6825.00");

        UUID asha = asTenant(TENANT, () -> policyApi.takeOverFuneralPolicy(policyNumber,
            new PromoteMemberRequest(new IdentityDocument(IdType.NATIONAL_ID, "19880101-22222-00002-22"), "+255711000222", Sex.FEMALE),
            "staff-one"));

        var policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.policyholderPartyId()).isEqualTo(asha).isNotEqualTo(deceased);
        CoveredLifeView main = lives(policyNumber).stream().filter(l -> l.status().equals("ACTIVE")
            && l.role() == FuneralRole.MAIN_MEMBER).findFirst().orElseThrow();
        assertThat(main.fullName()).isEqualTo("Asha");
        assertThat(main.partyId()).isEqualTo(asha);
        assertThat(main.yearlyPremium()).isEqualByComparingTo("60000");
        assertThat(main.pricedAtAge()).isEqualTo(38);
        // Asha 60,000 as main member + three children 18,000 = 78,000; x 1.05 / 12
        assertThat(policy.premiumAmount()).isEqualByComparingTo("6825.00");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).lifeAssuredPartyId()).isEqualTo(asha);
    }

    @Test
    void noSpouseFallsBackToThePolicyEnding() {
        String policyNumber = familyOn(MainMemberDeathRule.SPOUSE_TAKES_OVER, false,
            List.of(dependant(FuneralRole.CHILD, "Neema", 10)));

        settleDeath(policyNumber, mainMember(policyNumber), fixtures.person(TENANT, 30, Sex.FEMALE), "2000000.00");

        assertThat(life(policyNumber, "Neema").endReason()).isEqualTo("MAIN_MEMBER_DIED");
        assertThat(status(policyNumber)).isEqualTo("SURRENDERED");
    }
}

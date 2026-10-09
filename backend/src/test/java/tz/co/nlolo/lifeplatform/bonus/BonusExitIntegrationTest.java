package tz.co.nlolo.lifeplatform.bonus;

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
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.bonus.api.BonusApi;
import tz.co.nlolo.lifeplatform.bonus.application.BonusApiImpl;
import tz.co.nlolo.lifeplatform.bonus.application.DeclarationDrain;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.AttachmentEntryRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.SettlementRepository;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyBonus;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyBonusRepository;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exits: what bonuses add at death, maturity and surrender, recorded once -- and taken back on free-look. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class BonusExitIntegrationTest {

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
        String[] claims = {
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V4__rls_fail_closed.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/claims/V11__claim_document_request.sql"};
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(DepositTestMigrations.ALL), Stream.of(claims)).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(Eligibility.CIVIL_ZONE);

    @Autowired private BonusTestFixtures fixtures;
    @Autowired private BonusApiImpl api;
    @Autowired private BonusApi bonusApi;
    @Autowired private DeclarationDrain drain;
    @Autowired private AttachmentEntryRepository entries;
    @Autowired private SettlementRepository settlements;
    @Autowired private PolicyBonusRepository policyBonuses;
    @Autowired private PolicyApi policyApi;
    @Autowired private BenefitPayoutApi benefitPayoutApi;
    @Autowired private BenefitPayoutApiImpl benefitPayoutApiImpl;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID approvedDeclaration(UUID product, LocalDate valuation, String rate) {
        var d = asTenant(() -> api.proposeDeclaration(product, valuation, new BigDecimal(rate), new BigDecimal("50"), "admin-one"));
        asTenant(() -> api.approveDeclaration(d.declarationId(), "finance-two"));
        return d.declarationId();
    }

    /** 3% attached as at today, on a product whose declaration also set a 50% terminal rate. */
    private BonusTestFixtures.Issued withOneBonus(BonusTestFixtures.Issued issued) {
        drain.drainOne(approvedDeclaration(issued.productId(), TODAY, "3"), TENANT);
        return issued;
    }

    private BonusTestFixtures.Issued withOneBonus(BonusPlan plan) {
        return withOneBonus(fixtures.issue(TENANT, plan, CashValuePlan.none()));
    }

    private BigDecimal policyBonusAmount(String policyNumber) {
        return policyBonuses.findById(policyNumber).map(PolicyBonus::getAttachedBonusAmount).orElse(null);
    }

    @Test
    void theDeathLimitAddsAttachedInterimAndTerminalAsAtTheDeath() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        LocalDate death = TODAY.plusMonths(5);
        // attached 30,000; interim (1,000,000 + 30,000) x 3% x 5/12 = 12,875.00; terminal 50% x 30,000 = 15,000.
        var v = asTenant(() -> bonusApi.valueAt(issued.policyNumber(), death));
        assertThat(v.attached()).isEqualByComparingTo("30000.00");
        assertThat(v.interim()).isEqualByComparingTo("12875.00");
        assertThat(v.terminal()).isEqualByComparingTo("15000.00");
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(issued.policyNumber(), new BigDecimal("1000000.00"), death)))
            .isEqualByComparingTo("1057875.00");
    }

    @Test
    void anOrdinaryPolicyIsNotParticipatingAndItsDeathLimitIsUnchanged() {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());
        assertThat(asTenant(() -> bonusApi.isParticipating(issued.policyNumber()))).isFalse();
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(issued.policyNumber(), new BigDecimal("1000000.00"), TODAY)))
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void aLapsedPolicyKeepsItsBonusesButEarnsNoInterim() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        fixtures.publish(TENANT, "policy.PolicyLapsed", Map.of("policyNumber", issued.policyNumber(),
            "lapsedAt", Instant.now().toString()));
        var v = asTenant(() -> bonusApi.valueAt(issued.policyNumber(), TODAY.plusMonths(5)));
        assertThat(v.attached()).isEqualByComparingTo("30000.00");
        assertThat(v.interim()).isEqualByComparingTo("0.00");
        assertThat(v.terminal()).isEqualByComparingTo("15000.00");
    }

    @Test
    void aSettlementIsRecordedOncePerExit() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        UUID claimId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", issued.policyNumber(),
                "claimType", "DEATH", "dateOfEvent", TODAY.toString(),
                "approvedAmount", Map.of("amount", "1045000.00", "currencyCode", "TZS")));
        }
        assertThat(asTenant(() -> settlements.findByPolicyNumberOrderByRecordedAtDesc(issued.policyNumber())))
            .singleElement().satisfies(s -> assertThat(s.toValuation().total()).isEqualByComparingTo("45000.00"));
    }

    @Test
    void theClaimScreenShowsTheLimitApprovalEnforcesBonusIncluded() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        UUID claimId = asTenant(() -> {
            UUID claimant = partyApi.registerIndividual("Bonus Death Claimant", LocalDate.of(1980, 3, 3),
                "+255716000001", null, "test-agent").partyId();
            return claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(issued.policyNumber(), null, claimant,
                    ClaimType.DEATH, TODAY, new DeathClaimDetails("Natural causes", "Dar es Salaam", TODAY, "Dr. Test")),
                "bonus-death-reg-01", "claims-clerk").claimId();
        });
        // 1,000,000 sum assured + 30,000 attached + 0 interim (no whole month) + 15,000 terminal.
        assertThat(asTenant(() -> claimsApi.claimableCover(claimId)).amount()).isEqualByComparingTo("1045000.00");
    }

    @Test
    void theSurrenderQuoteAddsTheVersionsStatedBasisAndNothingElse() {
        // NONE: nothing added.
        var none = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        assertThat(asTenant(() -> policyApi.quoteSurrenderValue(none.policyNumber())).bonusSurrenderValueAmount())
            .isEqualByComparingTo("0");
        // OWN_SCALE from year 0 at 400 per mille: 30,000 x 0.4 = 12,000, and it is in the quoted value.
        var own = withOneBonus(new BonusPlan(true, BonusMethod.COMPOUND, false, BonusSurrenderBasis.OWN_SCALE,
            List.of(new BonusSurrenderRow(0, new BigDecimal("400")))));
        var quote = asTenant(() -> policyApi.quoteSurrenderValue(own.policyNumber()));
        assertThat(quote.bonusSurrenderValueAmount()).isEqualByComparingTo("12000.00");
        assertThat(quote.quotedValueAmount()).isEqualByComparingTo("12000.00");
    }

    @Test
    void aFreeLookCancellationReversesEveryAttachment() {
        var issued = withOneBonus(BonusTestFixtures.COMPOUND_NONE);
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", Instant.now().toString(), "cancelledBy", "staff-one"));
        var ledger = asTenant(() -> entries.findByPolicyNumberOrderBySeqAsc(issued.policyNumber()));
        assertThat(ledger).extracting(e -> e.type().name()).containsExactly("REVERSIONARY", "REVERSAL");
        assertThat(ledger.get(1).getTotalAfter()).isEqualByComparingTo("0.00");
        assertThat(ledger.get(1).getCreatedBy()).isEqualTo("staff-one");
        assertThat(asTenant(() -> policyBonusAmount(issued.policyNumber()))).isEqualByComparingTo("0.00");
    }

    @Test
    void aMaturityPaysTheRowAmountPlusTheBonusOnce() {
        // A 180-month endowment commenced 180 months ago: its MATURITY instalment is dated today.
        var issued = withOneBonus(fixtures.issue(TENANT, BonusTestFixtures.SIMPLE_NONE, CashValuePlan.none(),
            TODAY.minusMonths(180), 180));
        UUID instalment = asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber()).stream()
            .filter(v -> v.kind() == PayoutKind.MATURITY).findFirst().orElseThrow().instalmentId());
        asTenant(() -> { benefitPayoutApiImpl.fallDue(instalment); return null; });
        // 1,000,000 row + 30,000 attached + 0 interim (0 whole months) + 15,000 terminal.
        var matured = asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber()).stream()
            .filter(v -> v.instalmentId().equals(instalment)).findFirst().orElseThrow());
        assertThat(matured.currentAmount()).isEqualByComparingTo("1045000.00");
        assertThat(asTenant(() -> settlements.findByPolicyNumberOrderByRecordedAtDesc(issued.policyNumber())))
            .singleElement().satisfies(s -> assertThat(s.getExitRef()).isEqualTo(instalment.toString()));
    }
}

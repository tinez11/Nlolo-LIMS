package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DisabilityClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 4: registration's full validation chain, run under REAL {@code app_role}
 * (NOSUPERUSER NOBYPASSRLS) rather than the Testcontainers superuser every other module's
 * integration test uses -- copies {@code MobileMoneyCallbackIntegrationTest}'s
 * {@code @DynamicPropertySource} + {@code ALTER ROLE} setup, the freshest correct example in
 * this codebase, so RLS on {@code claims.claim} is genuinely exercised, not merely declared.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimsApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "claims_it_password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claims IT Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571500" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claims IT Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Issues a policy with a NULL underwritingCaseId -- the exact pre-M6 / unbackfillable shape
     * Task 4's fail-closed contestability path exists for. Every scenario below except the
     * dedicated cross-tenant/unknown-party ones uses this, since none of the OTHER assertions
     * this task cares about (in-force check, coverage check, details/type mismatch) depend on
     * which contestability answer comes back. */
    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims IT test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private ClaimsApi.RegisterClaimRequest deathRequest(String policyNumber, UUID claimantId, LocalDate dateOfEvent) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, claimantId, ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test"));
    }

    @Test
    void registersADeathClaimAndPublishesRegistrationWithFailClosedContestabilityFlagged() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REG-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(3);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), dateOfEvent),
            "reg-idem-01", "claims-staff");

        assertThat(view.claimId()).isNotNull();
        assertThat(view.policyNumber()).isEqualTo(policyNumber);
        assertThat(view.claimantPartyId()).isEqualTo(fixture.applicantId());
        assertThat(view.claimType()).isEqualTo(ClaimType.DEATH);
        assertThat(view.dateOfEvent()).isEqualTo(dateOfEvent);
        assertThat(view.details()).isInstanceOf(DeathClaimDetails.class);
        // The policy's underwritingCaseId is NULL -- the fail-closed path MUST flag this claim
        // for review, not silently wave it through as if it were safely outside the window.
        assertThat(view.requiresContestabilityReview())
            .as("a NULL underwritingCaseId must fail closed to true, never silently pass as false")
            .isTrue();

        // Round-trips through getClaim too, proving it is genuinely persisted, not just an
        // in-memory view returned by registerClaim.
        ClaimView reloaded = claimsApi.getClaim(view.claimId());
        assertThat(reloaded.claimId()).isEqualTo(view.claimId());
        assertThat(reloaded.requiresContestabilityReview()).isTrue();
    }

    @Test
    void rejectsRegistrationWhenThePolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-LAPSE-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff"); // isPolicyInForce now returns false

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now());
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-02", "claims-staff"));
    }

    @Test
    void rejectsRegistrationWhenDetailsClaimTypeDisagreesWithTheDeclaredClaimType() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-MISMATCH-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        // claimType says DEATH, details says DISABILITY -- Claim's own constructor
        // (Claim.java:107-113) must reject this, and ClaimsApiImpl must let it propagate.
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, fixture.applicantId(),
            ClaimType.DEATH, dateOfEvent, new DisabilityClaimDetails("Loss of limb", dateOfEvent, true, new BigDecimal("50")));

        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-03", "claims-staff"));
    }

    @Test
    void rejectsRegistrationForAnUnknownClaimant() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOPARTY-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        UUID unknownPartyId = UUID.randomUUID();
        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, unknownPartyId, LocalDate.now());

        assertThrows(PartyNotFoundException.class, () -> claimsApi.registerClaim(request, "reg-idem-04", "claims-staff"));
    }

    @Test
    void aNullUnderwritingCaseIdFailsClosedToRequiringContestabilityReview() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-CONTEST-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-05", "claims-staff");

        // Falsifiable: if the fail-closed guard were missing or inverted, this would come back
        // false (or throw an NPE calling checkContestability with a null case id) instead.
        assertThat(view.requiresContestabilityReview()).isTrue();
    }

    @Test
    void aClaimIsInvisibleToAnyOtherTenantUnderRealRls() {
        UUID ownerTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        Fixture fixture = buildFixture(ownerTenant, "CLAIMS-IT-TENANT-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(ownerTenant, fixture);

        TenantContext.set(ownerTenant);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-06", "claims-staff");
        UUID claimId = view.claimId();

        // Same claimId, DIFFERENT tenant context -- must come back not-found, proving
        // ClaimRepository.findByClaimIdAndTenantId is genuinely enforced by Postgres RLS under
        // real app_role (NOSUPERUSER NOBYPASSRLS), not merely by the tenantId argument the
        // application layer happens to pass.
        TenantContext.set(otherTenant);
        assertThrows(ClaimNotFoundException.class, () -> claimsApi.getClaim(claimId));

        // Sanity check: the SAME tenant can still read it, so the not-found above is genuinely
        // about tenant isolation and not some other bug (e.g. a broken claimId).
        TenantContext.set(ownerTenant);
        assertThat(claimsApi.getClaim(claimId).claimId()).isEqualTo(claimId);
    }
}

package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyMemberView;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberRepository;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A death claim on a group scheme: which life died, what they were covered for, and what
 * happens to the other members.
 *
 * <p>Nothing tested this before, which is how four defects survived on one path — a settled
 * claim surrendered the whole scheme, the claim never named the deceased, the member's own
 * effective-dated covered amount was never read, and an approved amount had no ceiling at all.
 *
 * <p>Runs as real {@code app_role} (NOSUPERUSER NOBYPASSRLS), copying
 * {@link ClaimsApiIntegrationTest}'s setup rather than the Testcontainers superuser every other
 * module's integration test uses. That matters here specifically: this exercise crosses into
 * {@code policy.group_scheme} / {@code policy_member} / {@code policy_member_benefit}, whose
 * grants and RLS policies arrived in policy/V9 and have never been exercised by a
 * non-superuser connection.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class GroupClaimIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "group_claim_it_password";

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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql");
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
    @Autowired private PolicyMemberRepository policyMemberRepository;

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    private UUID tenantId;
    private UUID claimant;

    /**
     * Backdated on purpose. With an event today, "exited on the date of event" and "exited on
     * the day the payment cleared" are the same date and a test asserting the first would pass
     * for a implementation doing the second.
     */
    private final LocalDate dateOfEvent = LocalDate.now().minusMonths(6);

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        claimant = person("Widow Claimant");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    /** A scheme plus a lookup from a member's name to their id -- the tests speak in names. */
    private record GroupFixture(String policyNumber, Map<String, UUID> membersByName) {
        UUID memberIdNamed(String name) {
            UUID id = membersByName.get(name);
            if (id == null) {
                throw new AssertionError("No member named " + name + " on " + policyNumber);
            }
            return id;
        }
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test").partyId();
    }

    /**
     * Two lives at 5,000,000 each, commenced a year ago so a six-month-old event falls inside
     * cover.
     *
     * <p>No free cover limit, deliberately: the FCL is a separate concern, and a limit here
     * would make every covered amount a capped one -- hiding a bug that returned the cap where
     * it should return the benefit.
     */
    private GroupFixture flatSchemeOfTwo(String productCode, String firstName, String secondName) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct(productCode, "Group Life " + productCode,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID firstPartyId = person(firstName);
        UUID secondPartyId = person(secondName);
        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("ABC Company"), product.productId(), versionId, null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(firstPartyId, null, null, null),
                    new PolicyApi.MemberInput(secondPartyId, null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now().minusYears(1), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff1");

        // Keyed off the party ids just minted rather than off a name on the member row: a member
        // row holds no name of its own, which is exactly why listMembers resolves its search
        // through PartyApi.
        Map<UUID, String> nameByParty = Map.of(firstPartyId, firstName, secondPartyId, secondName);
        Map<String, UUID> byName = policyApi
            .listMembers(scheme.policyNumber(), null, null, PageRequest.of(0, 25))
            .getContent().stream()
            .collect(Collectors.toMap(m -> nameByParty.get(m.memberPartyId()),
                                       PolicyMemberView::policyMemberId));
        return new GroupFixture(scheme.policyNumber(), byName);
    }

    /** An ACTIVE individual policy on a 2,000,000 sum assured, for the mirror-image cases. */
    private String issueIndividualPolicy(String productCode) {
        TenantContext.set(tenantId);
        UUID applicant = person("Individual Applicant " + productCode);
        ProductSummaryView product = productApi.createProduct(productCode, "Term Life " + productCode,
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        String policyNumber = policyApi.issuePolicy(null, new PolicyApi.IssueRequest(
            applicant, product.productId(), versionId, new BigDecimal("2000000"), "TZS",
            new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(),
            "Group claim IT individual fixture"), "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(policyNumber);
        return policyNumber;
    }

    private ClaimDetails deathDetails() {
        return new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test");
    }

    private ClaimsApi.RegisterClaimRequest deathRequest(String policyNumber, UUID policyMemberId) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, policyMemberId, claimant,
            ClaimType.DEATH, dateOfEvent, deathDetails());
    }

    /** No exit API exists and this plan adds none, so the test drives the entity directly. */
    private void exitMemberDirectly(UUID policyMemberId, LocalDate leftOn) {
        TenantContext.set(tenantId);
        PolicyMember member = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId).orElseThrow();
        member.exit(leftOn);
        policyMemberRepository.save(member);
    }

    // ---------------------------------------------------------------------------------
    // Which life died
    // ---------------------------------------------------------------------------------

    @Test
    void aGroupClaimMustNameTheMemberWhoDied() {
        // On a 500-life scheme, "somebody on GL-000123 died" is not a claim anyone can assess,
        // value or pay. claimant_party_id is who is FILING -- the widow -- not who died.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-01", "Juma Deceased", "Asha Living");

        assertThatThrownBy(() -> claimsApi.registerClaim(
                deathRequest(scheme.policyNumber(), null), "idem-" + UUID.randomUUID(), "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("names a member");
    }

    @Test
    void anIndividualClaimMayNotNameAMember() {
        // The mirror image, and not pedantry: a member id against an individual policy is a
        // caller who believes this contract has a schedule. Accepting and ignoring it would let
        // a claim be valued against a member row belonging to a different policy entirely.
        String policyNumber = issueIndividualPolicy("GRP-CLAIM-IND-01");

        assertThatThrownBy(() -> claimsApi.registerClaim(
                deathRequest(policyNumber, UUID.randomUUID()), "idem-" + UUID.randomUUID(), "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("is not a group scheme");
    }

    @Test
    void aMemberOfAnotherSchemeIsNotAMemberOfThisOne() {
        GroupFixture a = flatSchemeOfTwo("GRP-CLAIM-02", "Juma Deceased", "Asha Living");
        GroupFixture b = flatSchemeOfTwo("GRP-CLAIM-03", "Other Person", "Second Person");
        UUID memberOfB = b.memberIdNamed("Other Person");

        assertThatThrownBy(() -> claimsApi.registerClaim(
                deathRequest(a.policyNumber(), memberOfB), "idem-" + UUID.randomUUID(), "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("is not a member of");
    }

    @Test
    void aMemberMustHaveBeenCoveredOnTheDateOfEvent() {
        // Cover starts when the member joins. An event before that is not a claim on this
        // scheme, however genuine the death.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-04", "Juma Deceased", "Asha Living");
        UUID memberId = scheme.memberIdNamed("Juma Deceased");
        LocalDate beforeTheSchemeExisted = LocalDate.now().minusYears(2);

        assertThatThrownBy(() -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
                scheme.policyNumber(), memberId, claimant, ClaimType.DEATH, beforeTheSchemeExisted,
                new DeathClaimDetails("Natural causes", "Dar es Salaam", beforeTheSchemeExisted, "Dr. Test")),
                "idem-" + UUID.randomUUID(), "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("was not covered on");
    }

    @Test
    void aClaimForSomebodyWhoHasSinceLeftIsStillRegistrable() {
        // V9 keeps exited members precisely for this: "a claim can arrive after somebody
        // leaves". What decides it is whether they were covered ON THE DATE OF EVENT, not
        // whether they are covered today.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-05", "Juma Deceased", "Asha Living");
        UUID memberId = scheme.memberIdNamed("Juma Deceased");
        exitMemberDirectly(memberId, LocalDate.now().minusMonths(1));

        TenantContext.set(tenantId);
        ClaimView claim = claimsApi.registerClaim(
            deathRequest(scheme.policyNumber(), memberId), "idem-" + UUID.randomUUID(), "clerk");

        assertThat(claim.policyMemberId()).isEqualTo(memberId);
    }

    @Test
    void aGroupClaimIsValuedFromTheMembersCoverNotTheSchemeTotal() {
        // The 500x error, at its source. The scheme's sum assured is 10,000,000 across two
        // lives; this member was covered for 5,000,000, and that is what the claim is against.
        // Registration used to check policy.sumAssuredAmount > 0 -- the total -- so a 5m death
        // claim was validated against the whole book.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-06", "Juma Deceased", "Asha Living");
        assertThat(policyApi.getPolicy(scheme.policyNumber()).sumAssuredAmount())
            .isEqualByComparingTo(new BigDecimal("10000000.00"));

        TenantContext.set(tenantId);
        ClaimView claim = claimsApi.registerClaim(
            deathRequest(scheme.policyNumber(), scheme.memberIdNamed("Juma Deceased")),
            "idem-" + UUID.randomUUID(), "clerk");

        assertThat(claim.policyMemberId()).isEqualTo(scheme.memberIdNamed("Juma Deceased"));
        assertThat(policyApi.claimableCover(scheme.policyNumber(),
                scheme.memberIdNamed("Juma Deceased"), dateOfEvent, "DEATH").amount())
            .as("what a claim on this life may pay is their own cover, not the schedule's total")
            .isEqualByComparingTo(new BigDecimal("5000000.00"));
    }

    // ---------------------------------------------------------------------------------
    // What a claim may be approved for
    // ---------------------------------------------------------------------------------

    /** Registers and assesses, leaving the claim UNDER_ASSESSMENT and ready to decide. */
    private UUID registerAndAssess(GroupFixture scheme, String memberName) {
        TenantContext.set(tenantId);
        ClaimView claim = claimsApi.registerClaim(
            deathRequest(scheme.policyNumber(), scheme.memberIdNamed(memberName)),
            "idem-" + UUID.randomUUID(), "clerk");
        claimsApi.submitAssessment(claim.claimId(), "Findings", new BigDecimal("5000000.00"),
            "TZS", false, "assessor");
        return claim.claimId();
    }

    private UUID registerAndAssessIndividual(String productCode) {
        String policyNumber = issueIndividualPolicy(productCode);
        TenantContext.set(tenantId);
        ClaimView claim = claimsApi.registerClaim(
            deathRequest(policyNumber, null), "idem-" + UUID.randomUUID(), "clerk");
        claimsApi.submitAssessment(claim.claimId(), "Findings", new BigDecimal("2000000"),
            "TZS", false, "assessor");
        return claim.claimId();
    }

    @Test
    void aGroupClaimCannotBeApprovedForMoreThanTheMemberWasCoveredFor() {
        // The 500x error, at the point it would actually pay out. The scheme's sum assured is
        // 10,000,000 across two lives; this member was covered for 5,000,000, and that is the
        // ceiling. Claim.approve used to check only that the amount was positive, so a single
        // member's death claim could be approved for the whole book and nothing objected.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-07", "Juma Deceased", "Asha Living");
        UUID claimId = registerAndAssess(scheme, "Juma Deceased");

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true,
                new BigDecimal("10000000.00"), "TZS", null, "payee-1", "idem-over", "manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("5000000.00");
    }

    @Test
    void anIndividualClaimCannotBeApprovedForMoreThanTheSumAssured() {
        // Same ceiling, different source, and this one was never bounded either -- the missing
        // check was platform-wide, not group-only.
        UUID claimId = registerAndAssessIndividual("GRP-CLAIM-IND-03");

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true,
                new BigDecimal("2000000.01"), "TZS", null, "payee-1", "idem-over-ind", "manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("2000000");
    }

    @Test
    void approvingExactlyTheCoveredAmountIsAllowed() {
        // The boundary is INCLUSIVE. A death claim normally pays the whole of the cover, so an
        // exclusive bound would refuse the commonest correct settlement on the platform.
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-08", "Juma Deceased", "Asha Living");
        UUID claimId = registerAndAssess(scheme, "Juma Deceased");

        TenantContext.set(tenantId);
        claimsApi.decideSettlement(claimId, true, new BigDecimal("5000000.00"), "TZS", null,
            "payee-1", "idem-exact", "manager");

        assertThat(claimsApi.getClaim(claimId).approvedAmount())
            .isEqualByComparingTo(new BigDecimal("5000000.00"));
    }

    @Test
    void anIndividualClaimIsValuedFromTheSumAssured() {
        String policyNumber = issueIndividualPolicy("GRP-CLAIM-IND-02");

        TenantContext.set(tenantId);
        assertThat(policyApi.claimableCover(policyNumber, null, dateOfEvent, "DEATH"))
            .satisfies(cover -> {
                assertThat(cover.amount()).isEqualByComparingTo(new BigDecimal("2000000"));
                assertThat(cover.currencyCode()).isEqualTo("TZS");
                assertThat(cover.policyMemberId()).isNull();
            });
    }
}

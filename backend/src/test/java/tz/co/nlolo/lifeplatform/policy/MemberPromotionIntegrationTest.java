package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.*;
import tz.co.nlolo.lifeplatform.policy.api.*;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A borrower stops being a name on a spreadsheet.
 *
 * <p>A credit-life member is enrolled FREEFORM — a name and a date of birth off a lender's
 * CSV, because no lender sends a national ID and party de-duplication cannot fire without one
 * (spec §2.2). That is deliberate and correct for four hundred rows a month.
 *
 * <p>It stops being correct at exactly one moment: the claim. The platform is about to pay out
 * against this person, and "who died" cannot be a string in a spreadsheet cell. Promotion is
 * that moment, and it is the only one.
 *
 * <p>The thing this must not do is lose the loan. Cover is measured against the loan columns,
 * and a promotion that dropped them would make the member unvaluable — the exact shape of the
 * {@code getLoanTerms} defect plan 3 closed, where a member existed but could not be covered.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class MemberPromotionIntegrationTest {

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
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
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
            "db-migrations/product/V14__credit_life_category.sql",
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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V17__enrolment_row_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    private static final BigDecimal PRINCIPAL = new BigDecimal("2400000.00");
    private static final LocalDate DISBURSED = LocalDate.of(2026, 8, 3);
    private static final String NATIONAL_ID = "19880314-12345-00001-14";

    private String scheme;
    private PolicyMemberView borrower;

    @BeforeEach
    void seedScheme() {
        TenantContext.set(UUID.randomUUID());
        scheme = issueCreditLifeScheme();
        borrower = policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private PromoteMemberRequest identity() {
        return new PromoteMemberRequest(
            new IdentityDocument(IdType.NATIONAL_ID, NATIONAL_ID), "+255712345678", tz.co.nlolo.lifeplatform.party.api.Sex.FEMALE);
    }

    // ---- the promotion ------------------------------------------------------

    @Test
    void promotingAFreeformMemberGivesThemARealPartyWithoutLosingTheirLoan() {
        assertThat(borrower.memberType()).isEqualTo(MemberType.FREEFORM);
        BigDecimal coverBefore = policyApi.claimableCover(scheme, borrower.policyMemberId(),
            DISBURSED.plusMonths(6), BenefitType.DEATH.name()).amount();

        PolicyMemberView after = policyApi.promoteMember(scheme, borrower.policyMemberId(),
            identity(), "claims.clerk");

        assertThat(after.memberType()).isEqualTo(MemberType.PARTY);
        assertThat(after.memberPartyId()).isNotNull();
        // THE THING THIS MUST NOT BREAK. Cover is measured against the loan columns; a
        // promotion that dropped them would leave a member nobody can value -- the shape of
        // the getLoanTerms defect plan 3 closed.
        assertThat(policyApi.claimableCover(scheme, borrower.policyMemberId(),
            DISBURSED.plusMonths(6), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo(coverBefore);
    }

    @Test
    void theNameTheLenderUsedSurvivesPromotion() {
        // It is how their file reconciles to our roll. A promoted member that answers only to
        // a party id cannot be matched against the spreadsheet that enrolled them.
        PolicyMemberView after = policyApi.promoteMember(scheme, borrower.policyMemberId(),
            identity(), "claims.clerk");

        assertThat(after.memberName()).isEqualTo(borrower.memberName());
        assertThat(after.memberReference()).isEqualTo(borrower.memberReference());
    }

    @Test
    void promotingTwiceReturnsTheSamePartyRatherThanRegisteringASecondPerson() {
        // A claim is registered, assessed, maybe reopened, then settled. Promotion must not
        // mint a new person every time somebody touches it.
        UUID first = policyApi.promoteMember(scheme, borrower.policyMemberId(),
            identity(), "claims.clerk").memberPartyId();

        UUID second = policyApi.promoteMember(scheme, borrower.policyMemberId(),
            identity(), "another.clerk").memberPartyId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void anExistingPartyIsReusedWhenTheNationalIdAlreadyNamesSomebody() {
        // The borrower may already be a customer -- they bank with the lender, after all. Two
        // party rows for one national ID is precisely the duplicate-person problem the party
        // module's identity index exists to prevent.
        PartyView alreadyACustomer = partyApi.registerIndividual(
            new IndividualRegistration("Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14),
                "+255700000001", null, tz.co.nlolo.lifeplatform.party.api.Sex.FEMALE, null,
                new IdentityDocument(IdType.NATIONAL_ID, NATIONAL_ID),
                null, null, null, null, null), "staff-1");

        PolicyMemberView after = policyApi.promoteMember(scheme, borrower.policyMemberId(),
            identity(), "claims.clerk");

        assertThat(after.memberPartyId()).isEqualTo(alreadyACustomer.partyId());
    }

    // ---- what is refused ----------------------------------------------------

    @Test
    void anOrdinaryPartyMemberCannotBePromotedAgain() {
        String employerScheme = issueEmployerScheme();
        PolicyMemberView employee = policyApi.listMembers(employerScheme, null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        assertThatThrownBy(() -> policyApi.promoteMember(employerScheme,
            employee.policyMemberId(), identity(), "claims.clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("already");
    }

    @Test
    void promotionNeedsAnIdentityDocumentBecauseThatIsTheWholePoint() {
        // Promoting with no ID produces a second nameless person rather than a identified one,
        // which is worse than leaving them freeform: it looks resolved and is not.
        assertThatThrownBy(() -> policyApi.promoteMember(scheme, borrower.policyMemberId(),
            new PromoteMemberRequest(IdentityDocument.none(), "+255712345678", tz.co.nlolo.lifeplatform.party.api.Sex.FEMALE),
            "claims.clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("identity document");
    }

    @Test
    void aMemberOfAnotherSchemeCannotBePromotedThroughThisOne() {
        String otherScheme = issueCreditLifeScheme();
        PolicyMemberView theirs = policyApi.listMembers(otherScheme, null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        assertThatThrownBy(() -> policyApi.promoteMember(scheme, theirs.policyMemberId(),
            identity(), "claims.clerk"))
            .isInstanceOf(InvalidPolicyStateException.class);
    }

    // ---- fixtures -----------------------------------------------------------

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private String issueCreditLifeScheme() {
        GroupProduct product = publish(ProductCategory.CREDIT_LIFE, "CL-PROMO-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(PRINCIPAL, BigDecimal.ZERO, 18, RepaymentFrequency.MONTHLY,
                    DISBURSED, DISBURSED.plusMonths(1)))),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-1").policyNumber();
    }

    private String issueEmployerScheme() {
        GroupProduct product = publish(ProductCategory.GROUP_LIFE, "GRP-PROMO-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Employee A"), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff-1").policyNumber();
    }

    private GroupProduct publish(ProductCategory category, String code) {
        ProductSummaryView product = productApi.createProduct(code, category + " " + code,
            category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test-agent").partyId();
    }
}

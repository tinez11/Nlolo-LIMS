package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.*;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionAccrualRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * What the bank earns on a credit-life scheme, and what it gives back.
 *
 * <p>Two halves, and the second is meaningless without the first.
 *
 * <p><b>The basis.</b> FIRST_YEAR commission accrues once, at policy activation, from the
 * policy's own premium — which on a credit-life master policy is the placeholder figure whoever
 * issued it happened to type. The real premium arrives file by file, for the life of the scheme,
 * and accrued nothing at all. So the bank earned commission on a number that means nothing, once,
 * and never again on the money that actually came in.
 *
 * <p><b>The clawback.</b> When a borrower settles early the insurer returns the unearned
 * premium, and the commission on it has to come back too. Without that the insurer refunds the
 * borrower while the bank keeps commission on money that was given back — a guaranteed loss on
 * every early settlement, on a product whose settlement volume the bank controls entirely.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class CreditLifeCommissionTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
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
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V4__enrolment_schedule_document_type.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/document/V9__ifrs17_engine_document_types.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V4__partial_reversals.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/distribution/V6__commission_withholding.sql");

        MinioClient minio = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        for (String bucket : new String[] {"policy-documents", "kyc-evidence",
                "underwriting-evidence", "claim-evidence"}) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private EnrolmentApi enrolmentApi;
    @Autowired private DistributionApi distributionApi;
    @Autowired private CommissionAccrualRepository commissionAccrualRepository;
    /** Only to put a scheme into the state a pre-rule one is in; no API can, by design. */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger(6000);

    /** 10% of first-year premium, so every figure below is a tenth of a premium. */
    private static final BigDecimal FIRST_YEAR_RATE = new BigDecimal("0.10");

    /**
     * 2,400,000 over 18m + 1,200,000 over 12m + 6,000,000 over 24m, all at 0.5% per annum.
     * 18,000 + 6,000 + 60,000 = 84,000 charged for the file; 8,400 of commission on it.
     */
    private static final String THREE_BORROWERS =
        ",Amina Hassan Mwinyi,1988-03-14,F,,,2400000.00,18,2026-08-03\n"
        + ",Joseph Mkenda,1975-11-02,M,,,1200000.00,12,2026-08-05\n"
        + ",Grace Shirima,1992-06-21,F,,,6000000.00,24,2026-08-06\n";

    private UUID tenantId;
    private UUID bankAgentId;
    private String scheme;

    @BeforeEach
    void setTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---- the basis: commission follows the money that actually came in ------

    @Test
    void anAcceptedFileAccruesCommissionOnItsOwnPremium() {
        // The defect this closes. 84,000 of premium came in for this file; at 10% the bank
        // earns 8,400 on it. Before, the bank earned a tenth of whatever placeholder figure
        // was typed at issuance, once, and nothing at all on any file.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);

        assertThat(firstYearAccruals()).hasSize(1);
        assertThat(firstYearAccruals().get(0).getAmount()).isEqualByComparingTo("8400.00");
    }

    @Test
    void everyMonthlyFileAccruesItsOwnCommission() {
        // The other half of the defect: commission on a scheme is not a one-off at issuance.
        // A lender sends a file a month for the life of the scheme and earns on each.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        submitAndAccept(",Salum Juma Rashid,1969-01-30,M,,,3600000.00,12,2026-09-02\n");

        // 3,600,000 x 0.5% x 1 year = 18,000 premium; 1,800 of commission.
        assertThat(firstYearAccruals()).hasSize(2);
        assertThat(firstYearAccruals()).extracting(CommissionAccrual::getAmount)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactlyInAnyOrder(new BigDecimal("8400.00"), new BigDecimal("1800.00"));
    }

    @Test
    void theMasterPolicyItselfAccruesNothingAtActivation() {
        // The placeholder premium on the master policy is not production and must not be paid
        // on. Billing already refuses to invoice it (the SINGLE guard); this is the same fact
        // stated to distribution.
        schemeWithBankAsAgent();

        assertThat(firstYearAccruals()).isEmpty();
    }

    @Test
    void aRedeliveredAcceptanceAccruesCommissionOnce() {
        // sourceRef is the submission id, so a redelivered AFTER_COMMIT event finds the accrual
        // already there.
        schemeWithBankAsAgent();
        UUID submissionId = submitAndAccept(THREE_BORROWERS);

        assertThat(firstYearAccruals()).hasSize(1);
        assertThat(firstYearAccruals().get(0).getSourceRef()).isEqualTo(submissionId.toString());
    }

    @Test
    void aSchemeSoldDirectAccruesNothingAndDoesNotThrow() {
        // No agent of record. Must log and move on: an AFTER_COMMIT listener that throws
        // retries for ever.
        scheme = issueScheme(null);
        submitAndAccept(THREE_BORROWERS);

        assertThat(firstYearAccruals()).isEmpty();
    }

    // ---- the clawback: commission goes back with the premium ----------------

    @Test
    void aRefundedPremiumClawsBackItsCommissionProRata() {
        // Amina was charged 18,000 of the file's 84,000. She settles at month 9 of 18, so
        // 9,000 comes back -- and with it the commission on 9,000, which at 10% is 900.
        //
        // A PROPORTION, not the whole accrual: the other two borrowers are still on cover and
        // the bank keeps what it earned on them.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        PolicyMemberView amina = memberNamed("Amina Hassan Mwinyi");

        policyApi.exitMember(scheme, amina.policyMemberId(), LocalDate.of(2027, 5, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        List<CommissionAccrual> reversals = reversals();
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getAmount()).isEqualByComparingTo("-900.00");
        assertThat(reversals.get(0).getReversesAccrualId())
            .isEqualTo(firstYearAccruals().get(0).getAccrualId());
    }

    @Test
    void theClawbackIsNotSubjectToTheLapseWindow() {
        // TZ_COMMISSION_CLAWBACK_MONTHS gates the LAPSE path, correctly: a policy that lapses
        // in year four keeps its first-year commission. A refund is different in kind -- the
        // insurer physically returned the money, in month forty as much as in month two -- so
        // applying the window here would let the bank keep commission on premium the insurer no
        // longer has. This test is why task 6 is not "reuse handlePolicyLapsed".
        //
        // Grace: 6,000,000 over 24 months, so an exit at month 20 is far outside any
        // plausible window and still claws back.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        PolicyMemberView grace = memberNamed("Grace Shirima");

        policyApi.exitMember(scheme, grace.policyMemberId(), LocalDate.of(2028, 4, 6),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        // 60,000 charged, 4 of 24 months unexpired = 10,000 back, 1,000 of commission.
        assertThat(reversals()).hasSize(1);
        assertThat(reversals().get(0).getAmount()).isEqualByComparingTo("-1000.00");
    }

    @Test
    void aSettledClaimClawsBackNothingBecauseNothingWasRefunded() {
        // The clawback follows the REFUND, not the exit. No refund, no reversal -- and that
        // falls out of the design rather than being a second rule: billing publishes
        // PremiumRefundDue only when it actually credits something.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        PolicyMemberView amina = memberNamed("Amina Hassan Mwinyi");

        policyApi.dischargeForSettledClaim(scheme, amina.policyMemberId(),
            LocalDate.of(2027, 5, 3), UUID.randomUUID(), "claims.officer");

        assertThat(reversals()).isEmpty();
    }

    @Test
    void aRedeliveredRefundClawsBackOnce() {
        // persistAccrual is idempotent on reversesAccrualId, so a second refund event for the
        // same accrual finds the reversal already booked.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        PolicyMemberView amina = memberNamed("Amina Hassan Mwinyi");

        policyApi.exitMember(scheme, amina.policyMemberId(), LocalDate.of(2027, 5, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");
        policyApi.exitMember(scheme, amina.policyMemberId(), LocalDate.of(2027, 5, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.two");

        assertThat(reversals()).hasSize(1);
    }

    @Test
    void clawingBackEveryBorrowerNeverExceedsWhatWasAccrued() {
        // The property that matters once four hundred borrowers settle one at a time. Exiting
        // everybody on their own start date refunds the whole file, so the reversals must come
        // to exactly the accrual and never a cent more -- a statement driven negative by
        // rounding would be paid as a debt owed BY the bank.
        schemeWithBankAsAgent();
        submitAndAccept(THREE_BORROWERS);
        BigDecimal accrued = firstYearAccruals().get(0).getAmount();

        for (PolicyMemberView member : policyApi.listMembers(scheme, null, null,
                PageRequest.of(0, 10)).getContent()) {
            policyApi.exitMember(scheme, member.policyMemberId(), member.joinedOn(),
                ExitReason.CANCELLED, BigDecimal.ZERO, "staff.one");
        }

        BigDecimal clawedBack = reversals().stream()
            .map(CommissionAccrual::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(clawedBack.abs()).isEqualByComparingTo(accrued);
    }

    // ---- the lender's own rate, and who earns it ---------------------------

    /** The rate is agreed per lender (client answer 3.1). With no product-wide plan at all, the
     * lender's own rate is what its files earn -- 12.5% of 84,000 is 10,500. Before, nothing could
     * attach a rate to an agent, so a scheme with no product plan earned nothing, silently. */
    @Test
    void aLendersOwnRateIsWhatItsFilesEarn() {
        GroupProduct product = creditLifeProduct();
        UUID lenderAgent = onboardAgent("Lender Rate");
        distributionApi.setAgentCommissionRate(lenderAgent, product.productId(), new BigDecimal("12.5"), "finance");
        scheme = issueScheme(lenderAgent, product);

        submitAndAccept(THREE_BORROWERS);

        assertThat(firstYearAccruals()).singleElement().satisfies(a -> {
            assertThat(a.getAgentId()).isEqualTo(lenderAgent);
            assertThat(a.getAmount()).isEqualByComparingTo("10500.00");
        });
    }

    /** A rate is a percentage above 0 and at most 100, to two places -- never a fraction typed as
     * a percent, and never anything a double would round. */
    @Test
    void aRateOutsideZeroToOneHundredIsRefused() {
        GroupProduct product = creditLifeProduct();
        UUID agent = onboardAgent("Lender Bad Rate");
        assertThatThrownBy(() -> distributionApi.setAgentCommissionRate(agent, product.productId(), BigDecimal.ZERO, "f"))
            .isInstanceOf(DistributionValidationException.class);
        assertThatThrownBy(() -> distributionApi.setAgentCommissionRate(agent, product.productId(), new BigDecimal("100.01"), "f"))
            .isInstanceOf(DistributionValidationException.class);
        assertThatThrownBy(() -> distributionApi.setAgentCommissionRate(agent, product.productId(), new BigDecimal("12.345"), "f"))
            .isInstanceOf(DistributionValidationException.class);
    }

    /** Making the lender the earner applies to the NEXT file. A file accepted while the scheme was
     * direct earned nobody, and stays that way -- the "from now on" the business chose. */
    @Test
    void makingTheLenderTheEarnerAppliesToTheNextFileOnly() {
        GroupProduct product = creditLifeProduct();
        UUID lenderParty = newLenderParty();
        scheme = issueSchemeFor(lenderParty, null, product);
        submitAndAccept(THREE_BORROWERS);

        UUID lenderAgent = onboardAgentFor(lenderParty);
        distributionApi.setAgentCommissionRate(lenderAgent, product.productId(), new BigDecimal("10"), "finance");
        policyApi.changeSchemeAgentOfRecord(scheme, lenderAgent, "The lender earns commission (spec 2.8)", "finance");
        submitAndAccept(",Salum Juma Rashid,1969-01-30,M,,,3600000.00,12,2026-09-02\n");

        assertThat(policyApi.getPolicy(scheme).agentOfRecordId()).isEqualTo(lenderAgent);
        assertThat(firstYearAccruals()).extracting(CommissionAccrual::getAgentId)
            .containsExactly(lenderAgent);

        // And never to an agent that does not exist -- a mistyped id would make the scheme direct
        // without anyone deciding it.
        assertThatThrownBy(() -> policyApi.changeSchemeAgentOfRecord(scheme, UUID.randomUUID(), "typo", "finance"))
            .isInstanceOf(UnknownAgentOfRecordException.class);
        assertThat(policyApi.getPolicy(scheme).agentOfRecordId()).isEqualTo(lenderAgent);
    }

    // ---- Only the lender earns on its own scheme (spec 2.8) ------------------------------------
    //
    // Five dev schemes carried the individual agent who had merely registered the lender, and earned
    // nothing only because he had no credit-life plan yet. Giving him one would have handed him every
    // file's commission. Refused where the earner is set, and again where commission accrues.

    @Test
    void anAgentWhoIsNotTheLenderCannotBeMadeTheEarner() {
        GroupProduct product = creditLifeProduct();
        UUID outsider = onboardAgent("Outside Agent");

        // At set-up.
        assertThatThrownBy(() -> issueSchemeFor(newLenderParty(), outsider, product))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("only the lender itself earns");

        // And by a later change.
        scheme = issueSchemeFor(newLenderParty(), null, product);
        assertThatThrownBy(() -> policyApi.changeSchemeAgentOfRecord(scheme, outsider, "wrong", "finance"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("only the lender itself earns");
        assertThat(policyApi.getPolicy(scheme).agentOfRecordId()).isNull();
    }

    /** The backstop: a scheme from before the rule, still naming another agent, earns that agent
     * nothing -- even once they hold a credit-life plan. */
    @Test
    void aSchemeFromBeforeTheRulePaysNoCommissionToAnAgentWhoIsNotTheLender() {
        GroupProduct product = creditLifeProduct();
        UUID outsider = onboardAgent("Legacy Agent");
        distributionApi.setAgentCommissionRate(outsider, product.productId(), new BigDecimal("10"), "finance");
        scheme = issueSchemeFor(newLenderParty(), null, product);
        // The state a pre-rule scheme is in: the outsider named as its agent of record, on both sides.
        jdbcTemplate.update("UPDATE policy.policy SET agent_of_record_id = ? WHERE policy_number = ?", outsider, scheme);
        jdbcTemplate.update("UPDATE distribution.policy_projection SET agent_id = ? WHERE policy_number = ?", outsider, scheme);

        submitAndAccept(THREE_BORROWERS);

        assertThat(firstYearAccruals()).as("the outsider earns nothing on the lender's file").isEmpty();
    }

    /** On credit life the chosen agent is the agent. Whoever registered the lender's party used to
     * win at issuance -- which is how an individual agent came to sit on a lender's scheme. */
    @Test
    void theAgentWhoRegisteredTheLenderDoesNotOverrideTheChosenOne() {
        GroupProduct product = creditLifeProduct();
        UUID registeringAgent = onboardAgent("Registering Agent");
        int tag = SEQ.incrementAndGet();
        UUID registeringAgentParty = distributionApi.getAgent(registeringAgent).partyId();
        UUID lenderParty = partyApi.registerIndividual(new tz.co.nlolo.lifeplatform.party.api.IndividualRegistration(
                "Lender Registered By Agent " + tag, LocalDate.of(1985, 6, 15), "+2557" + String.format("%08d", tag),
                null, null, null, null, null, null, null, null, null),
            "agent-user", registeringAgentParty).partyId();
        // The chosen earner is the LENDER itself, as it must be on credit life.
        UUID lenderAgent = onboardAgentFor(lenderParty);

        String issued = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            lenderParty, product.productId(), product.productVersionId(), lenderAgent,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, List.of(PolicyApi.MemberInput.borrower("Opening Schedule Borrower",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(new BigDecimal("8500000.00"), BigDecimal.ZERO, 48,
                    RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3)))),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-1").policyNumber();

        assertThat(policyApi.getPolicy(issued).agentOfRecordId()).isEqualTo(lenderAgent);
    }

    private UUID onboardAgent(String name) {
        int tag = SEQ.incrementAndGet();
        UUID party = partyApi.registerIndividual(name + " " + tag, LocalDate.of(1980, 1, 1),
            "+2557" + String.format("%08d", tag), null, "test").partyId();
        return onboardAgentFor(party);
    }

    /** Make an existing party -- a lender -- an agent, so it can earn on its own scheme. */
    private UUID onboardAgentFor(UUID party) {
        int tag = SEQ.incrementAndGet();
        partyApi.submitKycEvidence(party, tz.co.nlolo.lifeplatform.party.api.KycStatus.VERIFIED, "doc-" + tag,
            "kyc-officer");
        return distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party, "LIC-" + tag, LocalDate.now().plusYears(1), null), "staff-1").agentId();
    }

    // ---- fixtures -----------------------------------------------------------

    private List<CommissionAccrual> firstYearAccruals() {
        return commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
                tenantId, scheme, TierType.FIRST_YEAR);
    }

    /** Every reversing accrual on this scheme, the way ClawbackIntegrationTest finds them. */
    private List<CommissionAccrual> reversals() {
        return commissionAccrualRepository.findAll().stream()
            .filter(a -> tenantId.equals(a.getTenantId())
                && scheme.equals(a.getPolicyNumber())
                && a.getReversesAccrualId() != null)
            .toList();
    }

    private PolicyMemberView memberNamed(String name) {
        List<PolicyMemberView> matches = policyApi.listMembers(scheme, null, null,
                PageRequest.of(0, 20)).getContent().stream()
            .filter(m -> name.equals(m.memberName())).toList();
        assertThat(matches).as("exactly one member named %s", name).hasSize(1);
        return matches.get(0);
    }

    private static final String CSV_HEADER =
        "member_reference,borrower_full_name,borrower_date_of_birth,borrower_sex,"
        + "borrower_national_id,borrower_phone,loan_principal_amount,"
        + "loan_term_months,disbursement_date\n";

    private UUID submitAndAccept(String rows) {
        var submitted = enrolmentApi.submit(scheme,
            new ByteArrayInputStream((CSV_HEADER + rows).getBytes(StandardCharsets.UTF_8)),
            "lender-file.csv", "staff.proposer");
        enrolmentApi.accept(submitted.submissionId(), "staff.accepter");
        return submitted.submissionId();
    }

    /** A scheme whose agent of record is the bank, with a plan that pays 10% first year. */
    private void schemeWithBankAsAgent() {
        GroupProduct product = creditLifeProduct();
        distributionApi.createCommissionPlan(product.productId(), List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, FIRST_YEAR_RATE, null, null)),
            "actuary");

        int tag = SEQ.incrementAndGet();
        UUID bankParty = partyApi.registerIndividual("Bank Agent " + tag, LocalDate.of(1980, 1, 1),
            "+2557" + String.format("%08d", tag), null, "test").partyId();
        // An agent must be KYC-verified before they can be onboarded.
        partyApi.submitKycEvidence(bankParty,
            tz.co.nlolo.lifeplatform.party.api.KycStatus.VERIFIED, "doc-" + tag, "kyc-officer");
        bankAgentId = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            bankParty, "LIC-CL-" + tag, LocalDate.now().plusYears(1), null), "staff-1").agentId();

        scheme = issueScheme(bankAgentId, product);
    }

    private String issueScheme(UUID agentOfRecordId) {
        return issueScheme(agentOfRecordId, creditLifeProduct());
    }

    /**
     * A scheme whose LENDER is the given agent's own party -- on credit life only the lender may
     * earn (spec 2.8), so an agent of record is always the lender itself. Null issues it direct,
     * with a lender of its own.
     */
    private String issueScheme(UUID agentOfRecordId, GroupProduct product) {
        UUID lenderParty = agentOfRecordId != null
            ? distributionApi.getAgent(agentOfRecordId).partyId()
            : newLenderParty();
        return issueSchemeFor(lenderParty, agentOfRecordId, product);
    }

    private UUID newLenderParty() {
        return partyApi.registerIndividual("Lender Co", LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test").partyId();
    }

    private String issueSchemeFor(UUID lenderParty, UUID agentOfRecordId, GroupProduct product) {
        scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            lenderParty,
            product.productId(), product.productVersionId(), agentOfRecordId,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, List.of(PolicyApi.MemberInput.borrower("Opening Schedule Borrower",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(new BigDecimal("8500000.00"), BigDecimal.ZERO, 48,
                    RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3)))),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-1").policyNumber();
        return scheme;
    }

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private GroupProduct creditLifeProduct() {
        String code = "CL-COMM-" + SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Credit Life " + code,
            ProductCategory.CREDIT_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }
}

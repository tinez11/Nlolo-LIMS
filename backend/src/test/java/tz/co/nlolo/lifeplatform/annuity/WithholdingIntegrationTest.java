package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.benefitpayout.application.WithholdingRules;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Tax withheld from an annuity instalment (product step 5, Task 4): rules finance proposes and a
 * second person approves, applied at approval, the rail asked for the net, and the ledger booking the
 * gross expense, the net cash and the tax owed.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class WithholdingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String[] FINACCOUNTING = {
        "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
        "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
        "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
        "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
        "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
        "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
        "db-migrations/finaccounting/V8__withholding_tax_account.sql",
        "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
        "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
        "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql",
        "db-migrations/finaccounting/V13__disbursement_method.sql",
        "db-migrations/finaccounting/V14__manual_journals.sql",
        "db-migrations/finaccounting/V15__engine_period_cycle.sql",
        "db-migrations/finaccounting/V16__expense_allocation.sql",
    };

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(AnnuityTestMigrations.ALL), Stream.of(FINACCOUNTING)).toArray(String[]::new));
    }

    private static final BigDecimal BASE = new BigDecimal("294000.00");

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private BenefitPayoutApi payouts;
    @Autowired private BenefitPayoutApiImpl engine;
    @Autowired private WithholdingRules rules;
    @Autowired private JournalEntryRepository journal;
    @Autowired private GlPostingRepository postings;

    /** An annuity whose first instalment is due today, brought DUE and reviewed. Returns its id. */
    private UUID reviewedInstalment(UUID tenant, String policy) {
        asTenant(tenant, () -> payouts.openAnnuityStream(policy, TODAY, "MONTHLY", BASE, "TZS", BigDecimal.ZERO, 12));
        UUID first = asTenant(tenant, () -> payouts.listForPolicy(policy)).stream()
            .filter(i -> i.kind() == PayoutKind.ANNUITY).findFirst().orElseThrow().instalmentId();
        asTenant(tenant, () -> {
            // The single premium, as billing would report it: an annuity unpaid for pays nothing, and
            // without this the instalment is (rightly) held for premiums.
            engine.recordPremium(policy, new BigDecimal("50000000.00"), TODAY);
            engine.fallDue(first);
            payouts.review(first, "+255700000901", ProofOfLifeMethod.IN_PERSON, null, "finance-reviewer");
            return null;
        });
        return first;
    }

    private String annuityPolicy(UUID tenant) {
        var product = fixtures.publish(tenant, AnnuityTestFixtures.plan(AnnuityTiming.ADVANCE, AnnuityTestFixtures.lifeOnly()));
        return fixtures.issueInForce(tenant, product, fixtures.person(tenant, 61, null), "50000000.00");
    }

    private UUID approvedRule(UUID tenant, List<String> kinds, String rate) {
        return approvedRule(tenant, kinds, rate, TODAY);
    }

    private UUID approvedRule(UUID tenant, List<String> kinds, String rate, java.time.LocalDate from) {
        return asTenant(tenant, () -> {
            var rule = rules.propose(kinds, new BigDecimal(rate), from, null, "Income Tax Act s.82 (test)", "finance-one",
                UUID.randomUUID().toString());
            return rules.approve(rule.getRuleId(), "finance-two").getRuleId();
        });
    }

    @Test
    void withNoRuleNothingIsWithheldAndTheInstalmentSaysItChecked() {
        UUID tenant = UUID.randomUUID();
        String policy = annuityPolicy(tenant);
        UUID instalment = reviewedInstalment(tenant, policy);
        PayoutInstalmentView approved = asTenant(tenant, () -> payouts.approve(instalment, "finance-approver"));
        assertThat(approved.grossAmount()).isEqualByComparingTo("294000.00");
        assertThat(approved.withheldAmount()).isEqualByComparingTo("0.00");
        assertThat(approved.netAmount()).isEqualByComparingTo("294000.00");
    }

    @Test
    void anApprovedRuleWithholdsItsRateAndTheLedgerBooksGrossNetAndTax() {
        UUID tenant = UUID.randomUUID();
        approvedRule(tenant, List.of("ANNUITY"), "10");
        String policy = annuityPolicy(tenant);
        UUID instalment = reviewedInstalment(tenant, policy);
        PayoutInstalmentView approved = asTenant(tenant, () -> payouts.approve(instalment, "finance-approver"));
        assertThat(approved.withheldAmount()).isEqualByComparingTo("29400.00");
        assertThat(approved.netAmount()).isEqualByComparingTo("264600.00");

        asTenant(tenant, () -> { engine.markPaid(instalment, UUID.randomUUID()); return null; });
        List<JournalEntry> entries = asTenant(tenant, () ->
            journal.findByTenantIdAndPolicyNumberOrderByPostedAtDesc(tenant, policy, PageRequest.of(0, 10)).getContent());
        JournalEntry payout = entries.stream().filter(e -> "benefitpayout.PayoutPaid".equals(e.getSourceEvent()))
            .findFirst().orElseThrow();
        List<GlPosting> legs = asTenant(tenant, () ->
            postings.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenant, payout.getJournalEntryId()));
        assertThat(legs).hasSize(3);
        // IFRS 17 I3b (guide H-03): paid, the gross clears the annuity instalment payable that H-02 raised when it fell
        // due (2215); the expense was booked then, not here.
        assertThat(legs).anySatisfy(l -> {
            assertThat(l.getAccountCode()).isEqualTo("2215");
            assertThat(l.getDirection()).isEqualTo(PostingDirection.DR);
            assertThat(l.getAmount()).isEqualByComparingTo("294000.00");
        });
        assertThat(legs).anySatisfy(l -> {
            assertThat(l.getAccountCode()).isEqualTo("1140");
            assertThat(l.getDirection()).isEqualTo(PostingDirection.CR);
            assertThat(l.getAmount()).isEqualByComparingTo("264600.00");
        });
        assertThat(legs).anySatisfy(l -> {
            assertThat(l.getAccountCode()).isEqualTo("2615");
            assertThat(l.getDirection()).isEqualTo(PostingDirection.CR);
            assertThat(l.getAmount()).isEqualByComparingTo("29400.00");
        });
    }

    @Test
    void aRuleNamesItsKinds() {
        UUID tenant = UUID.randomUUID();
        approvedRule(tenant, List.of("MATURITY"), "10");
        String policy = annuityPolicy(tenant);
        UUID instalment = reviewedInstalment(tenant, policy);
        assertThat(asTenant(tenant, () -> payouts.approve(instalment, "finance-approver")).withheldAmount())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void theProposerCannotApproveTheirOwnRule() {
        UUID tenant = UUID.randomUUID();
        var rule = asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), BigDecimal.TEN, TODAY, null, "Act (test)",
            "finance-one", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.approve(rule.getRuleId(), "finance-one")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A withholding rule must be approved by someone other than the person who proposed it");
    }

    @Test
    void twoApprovedRulesMayNotOverlapForOneKind() {
        UUID tenant = UUID.randomUUID();
        approvedRule(tenant, List.of("ANNUITY"), "10");
        var second = asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), new BigDecimal("15"), TODAY.plusMonths(1), null,
            "Act (test)", "finance-one", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.approve(second.getRuleId(), "finance-two")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageStartingWith("An approved withholding rule already applies to ANNUITY from " + TODAY);
    }

    @Test
    void oneFinanceOfficerEndsAnApprovedRuleAndItsReplacementThenApproves() {
        UUID tenant = UUID.randomUUID();
        UUID first = approvedRule(tenant, List.of("ANNUITY"), "10");
        // Alone, and the proposer at that -- ending takes one person (the user's decision).
        var ended = asTenant(tenant, () -> rules.end(first, TODAY, "finance-one"));
        assertThat(ended.getEffectiveTo()).isEqualTo(TODAY);
        assertThat(ended.getEndedBy()).isEqualTo("finance-one");
        assertThat(ended.getEndedAt()).isNotNull();

        // The overlap refusal's advice is now something a person can act on.
        var replacement = asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), new BigDecimal("15"), TODAY.plusDays(1), null,
            "Finance Act 2026 (test)", "finance-one", UUID.randomUUID().toString()));
        assertThat(asTenant(tenant, () -> rules.approve(replacement.getRuleId(), "finance-two")).status())
            .isEqualTo(tz.co.nlolo.lifeplatform.benefitpayout.domain.WithholdingRule.Status.APPROVED);

        // An instalment due on the ended rule's last day is still withheld by it.
        String policy = annuityPolicy(tenant);
        UUID instalment = reviewedInstalment(tenant, policy);
        assertThat(asTenant(tenant, () -> payouts.approve(instalment, "finance-approver")).withheldAmount())
            .isEqualByComparingTo("29400.00");
    }

    @Test
    void anEndIsNeverInThePastNeverAnExtensionAndOnlyForAnApprovedRule() {
        UUID tenant = UUID.randomUUID();
        UUID running = approvedRule(tenant, List.of("ANNUITY"), "10", TODAY.minusDays(10));
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.end(running, TODAY.minusDays(1), "finance-one")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageStartingWith("A withholding rule cannot be ended in the past");
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.end(running, TODAY.minusDays(11), "finance-one")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A withholding rule cannot end before it begins");

        asTenant(tenant, () -> rules.end(running, TODAY.plusDays(30), "finance-one"));
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.end(running, TODAY.plusDays(60), "finance-one")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageStartingWith("This rule already ends on " + TODAY.plusDays(30));
        // Earlier is fine.
        assertThat(asTenant(tenant, () -> rules.end(running, TODAY.plusDays(5), "finance-one")).getEffectiveTo())
            .isEqualTo(TODAY.plusDays(5));

        var proposal = asTenant(tenant, () -> rules.propose(List.of("MATURITY"), BigDecimal.TEN, TODAY, null, "Act (test)",
            "finance-one", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.end(proposal.getRuleId(), TODAY, "finance-one")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("Only an approved withholding rule can be ended; this one is proposed");
    }

    @Test
    void aProposalRetriedWithItsKeyIsMadeOnceAndTheKeyCannotBeReused() {
        UUID tenant = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var first = asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), BigDecimal.TEN, TODAY, null, "Act (test)", "f1", key));
        var again = asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), BigDecimal.TEN, TODAY, null, "Act (test)", "f1", key));
        assertThat(again.getRuleId()).isEqualTo(first.getRuleId());
        assertThatThrownBy(() -> asTenant(tenant, () -> rules.propose(List.of("ANNUITY"), new BigDecimal("12"), TODAY, null,
                "Act (test)", "f1", key)))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageStartingWith("This Idempotency-Key was already used for a different request.");
    }
}

package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingCalculator;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlPostingCalculatorTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static Optional<JournalEntry> calc(String eventType, String amount) {
        return GlPostingCalculator.calculate(TENANT, eventType, "ref-1", "POL-0001",
            new BigDecimal(amount), "TZS", "2026-08", "system:test");
    }

    private static String accountFor(JournalEntry entry, PostingDirection direction) {
        return entry.getLegs().stream()
            .filter(l -> l.direction() == direction)
            .map(JournalEntry.Leg::accountCode)
            .findFirst().orElseThrow();
    }

    /** The invariant that matters most: EVERY mapping produces a balanced entry. */
    @Test
    void everyMappedEventProducesABalancedTwoLeggedEntry() {
        for (String eventType : new String[] {
                "billing.PremiumInvoiceGenerated", "billing.PremiumCollected", "claims.ClaimSettled",
                "distribution.CommissionPaid", "reinsurance.CessionRecorded",
                "reinsurance.RecoveryConfirmed", "policyloan.LoanDisbursed", "policyloan.LoanRepaid" }) {
            Optional<JournalEntry> entry = calc(eventType, "15000.00");
            assertThat(entry).as("%s must produce an entry", eventType).isPresent();
            assertThat(entry.get().getLegs()).as("%s must have exactly two legs", eventType).hasSize(2);
            assertThat(entry.get().isBalanced()).as("%s must balance", eventType).isTrue();
        }
    }

    @Test
    void premiumInvoiceGeneratedRaisesTheReceivableAgainstUnearnedPremium() {
        JournalEntry entry = calc("billing.PremiumInvoiceGenerated", "15000.00").orElseThrow();
        assertThat(accountFor(entry, PostingDirection.DR)).isEqualTo(PostingRule.PREMIUM_RECEIVABLE);
        assertThat(accountFor(entry, PostingDirection.CR)).isEqualTo(PostingRule.UNEARNED_PREMIUM);
    }

    /** A premium credit is the invoice posting run backwards, for the part given back. It had no
     * rule, so billing showed a lender 4,200 credited while the ledger went on carrying the whole
     * invoice as receivable. */
    @Test
    void aPremiumCreditReversesTheReceivableAgainstUnearnedPremium() {
        JournalEntry entry = calc("billing.PremiumRefundDue", "4200.00").orElseThrow();
        assertThat(accountFor(entry, PostingDirection.DR)).isEqualTo(PostingRule.UNEARNED_PREMIUM);
        assertThat(accountFor(entry, PostingDirection.CR)).isEqualTo(PostingRule.PREMIUM_RECEIVABLE);
        assertThat(entry.isBalanced()).isTrue();
    }

    @Test
    void premiumCollectedSettlesTheReceivableAgainstCash() {
        JournalEntry entry = calc("billing.PremiumCollected", "15000.00").orElseThrow();
        assertThat(accountFor(entry, PostingDirection.DR)).isEqualTo(PostingRule.CASH);
        assertThat(accountFor(entry, PostingDirection.CR)).isEqualTo(PostingRule.PREMIUM_RECEIVABLE);
    }

    /**
     * The accrual invariant across the two premium events: an invoice generated then collected
     * leaves 1200 Premium Receivable net FLAT (debited once, credited once, same amount). This is
     * the single assertion proving the two-entry split is coherent rather than double-counting.
     */
    @Test
    void thePremiumReceivableRoundTripsToNetZero() {
        JournalEntry generated = calc("billing.PremiumInvoiceGenerated", "15000.00").orElseThrow();
        JournalEntry collected = calc("billing.PremiumCollected", "15000.00").orElseThrow();

        BigDecimal net = BigDecimal.ZERO;
        for (JournalEntry entry : new JournalEntry[] { generated, collected }) {
            for (JournalEntry.Leg l : entry.getLegs()) {
                if (!PostingRule.PREMIUM_RECEIVABLE.equals(l.accountCode())) continue;
                net = l.direction() == PostingDirection.DR
                    ? net.add(l.amount()) : net.subtract(l.amount());
            }
        }
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** No 4xxx account is ever credited: premium is earned via LRC release, which is C1-blocked. */
    @Test
    void noMappingEverTouchesAnIncomeAccount() {
        for (String eventType : new String[] {
                "billing.PremiumInvoiceGenerated", "billing.PremiumCollected", "claims.ClaimSettled",
                "distribution.CommissionPaid", "reinsurance.CessionRecorded",
                "reinsurance.RecoveryConfirmed", "policyloan.LoanDisbursed", "policyloan.LoanRepaid" }) {
            JournalEntry entry = calc(eventType, "100.00").orElseThrow();
            assertThat(entry.getLegs())
                .as("%s must not post to a 4xxx income account", eventType)
                .noneMatch(l -> l.accountCode().startsWith("4"));
        }
    }

    /** policy.PolicyIssued has no accounting consequence under M9's flat model -- LRC/CSM initial
     * recognition is C1-blocked. Empty is the correct answer, not an error. */
    @Test
    void policyIssuedProducesNoEntry() {
        assertThat(calc("policy.PolicyIssued", "2000000.00")).isEmpty();
    }

    @Test
    void anUnmappedEventProducesNoEntry() {
        assertThat(calc("policy.BeneficiaryChanged", "100.00")).isEmpty();
        assertThat(calc("policy.PolicySuspended", "100.00")).isEmpty();
        assertThat(calc("policy.SurrenderValueCalculated", "100.00")).isEmpty();
    }

    @Test
    void theEntryCarriesItsSourceAndPeriodForTraceability() {
        JournalEntry entry = calc("claims.ClaimSettled", "500.00").orElseThrow();
        assertThat(entry.getSourceEvent()).isEqualTo("claims.ClaimSettled");
        assertThat(entry.getSourceRef()).isEqualTo("ref-1");
        assertThat(entry.getPeriod()).isEqualTo("2026-08");
        assertThat(entry.getPolicyNumber()).isEqualTo("POL-0001");
        assertThat(entry.getLegs()).allMatch(l -> "TZS".equals(l.currency()));
        entry.getLegs().forEach(l ->
            assertThat(l.amount()).isEqualByComparingTo(new BigDecimal("500.00"))
        );
    }

    @Test
    void aNonPositiveAmountProducesNoEntry() {
        assertThat(calc("billing.PremiumCollected", "0.00")).isEmpty();
        assertThat(calc("billing.PremiumCollected", "-15000.00")).isEmpty();
    }
}

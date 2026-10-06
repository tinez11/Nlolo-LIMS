package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each event tells the posting rules (IFRS 17 I3a), from payloads shaped as the emitting modules publish them.
 * The source refs are the ones the old listeners keyed on, so nothing already posted is posted again.
 */
class PostingFactsExtractorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currencyCode", "TZS");
    }

    @Test
    void anInvoiceIsKeyedOnTheInvoiceAndCarriesItsCover() {
        UUID invoice = UUID.randomUUID();
        List<PostingFacts> facts = PostingFactsExtractor.extract("billing.PremiumInvoiceGenerated", Map.of(
            "invoiceId", invoice, "policyNumber", "POL-1", "dueDate", "2026-11-01", "amount", money("1200.00"),
            "covers", List.of(Map.of("memberRef", "", "coversFrom", "2026-10-01", "coversTo", "2026-10-31",
                "amount", "1200.00"))), TODAY);
        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.sourceRef()).isEqualTo(invoice.toString());
            assertThat(f.policyNumber()).isEqualTo("POL-1");
            assertThat(f.amount("amount")).isEqualByComparingTo("1200.00");
            assertThat(f.currency()).isEqualTo("TZS");
            assertThat(f.attribute("refType")).isEqualTo("INVOICE");
            assertThat(PaaEarningSchedule.parse(f.attribute("covers"))).singleElement()
                .isEqualTo(new PaaEarningSchedule.Cover("", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                    new BigDecimal("1200.00")));
        });
    }

    @Test
    void aCreditIsKeyedOnTheCreditAndNamesItsInvoiceAndBorrower() {
        UUID credit = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PostingFacts f = PostingFactsExtractor.extract("billing.PremiumRefundDue", Map.of("creditId", credit,
            "policyNumber", "POL-1", "policyMemberId", member, "originalInvoiceId", invoice, "amount", money("300.00")),
            TODAY).get(0);
        assertThat(f.sourceRef()).isEqualTo(credit.toString());
        assertThat(f.attribute("invoiceRef")).isEqualTo(invoice.toString());
        assertThat(f.attribute("memberRef")).isEqualTo(member.toString());
    }

    @Test
    void aPayoutWithTaxWithheldGrossesUpSoTheJournalBalances() {
        Map<String, Object> p = new HashMap<>(Map.of("instalmentId", "I-1", "policyNumber", "POL-1",
            "paidAmount", money("900.00"), "withheldAmount", money("100.00"), "grossAmount", money("1000.00")));
        PostingFacts f = PostingFactsExtractor.extract("benefitpayout.PayoutPaid", p, TODAY).get(0);
        assertThat(f.amount("gross")).isEqualByComparingTo("1000.00");
        assertThat(f.amount("paid")).isEqualByComparingTo("900.00");
        assertThat(f.amount("withheld")).isEqualByComparingTo("100.00");

        p.remove("withheldAmount");
        PostingFacts untaxed = PostingFactsExtractor.extract("benefitpayout.PayoutPaid", p, TODAY).get(0);
        assertThat(untaxed.amount("gross")).isEqualByComparingTo("900.00");
        assertThat(untaxed.amount("withheld")).isZero();
    }

    @Test
    void theEftRailNoLongerPostsItsOwnPairApprovalBooksTheClaim() {
        // IFRS 17 I3b: approval books 2211, payment clears it; the EFT steps would double it.
        assertThat(PostingFactsExtractor.handles("payment.EftDisbursementExecuted")).isFalse();
        assertThat(PostingFactsExtractor.handles("payment.EftDisbursementAwaitingExecution")).isFalse();
    }

    @Test
    void anApprovedClaimSplitsItsInvestmentComponentFromTheInsuranceCost() {
        UUID claim = UUID.randomUUID();
        PostingFacts f = PostingFactsExtractor.extract("claims.ClaimApproved", Map.of("claimId", claim,
            "policyNumber", "END-1", "approvedAmount", money("24000.00"), "investmentComponent", "9000.00"), TODAY).get(0);
        assertThat(f.sourceRef()).isEqualTo(claim.toString());
        assertThat(f.amount("amount")).isEqualByComparingTo("24000.00");
        assertThat(f.amount("investmentComponent")).isEqualByComparingTo("9000.00");
        assertThat(f.amount("insured")).isEqualByComparingTo("15000.00");   // guide C-04

        PostingFacts capped = PostingFactsExtractor.extract("claims.ClaimApproved", Map.of("claimId", claim,
            "policyNumber", "END-1", "approvedAmount", money("5000.00"), "investmentComponent", "9000.00"), TODAY).get(0);
        assertThat(capped.amount("investmentComponent")).as("never more than the claim").isEqualByComparingTo("5000.00");
        assertThat(capped.amount("insured")).isZero();
    }

    @Test
    void aPaidEventNamesThePaymentItWasPaidBySoTheRailCanBeFound() {
        UUID claim = UUID.randomUUID();
        PostingFacts f = PostingFactsExtractor.extract("claims.ClaimSettled", Map.of("claimId", claim,
            "policyNumber", "POL-1", "settledAmount", money("700.00")), TODAY).get(0);
        assertThat(f.attribute("paymentRef")).isEqualTo(claim.toString());
    }

    @Test
    void aPayoutFallingDueStatesItsKindGrossAndInvestmentComponent() {
        PostingFacts f = PostingFactsExtractor.extract("benefitpayout.PayoutRequested", Map.of("instalmentId", "I-9",
            "policyNumber", "MB-1", "purpose", "SURVIVAL_BENEFIT_PAYOUT", "amount", money("1800.00"), "kind", "SURVIVAL",
            "grossAmount", "2000.00", "withheldAmount", "200.00", "investmentComponent", "1600.00"), TODAY).get(0);
        assertThat(f.sourceRef()).isEqualTo("I-9");
        assertThat(f.attribute("kind")).isEqualTo("SURVIVAL");
        assertThat(f.amount("gross")).isEqualByComparingTo("2000.00");
        assertThat(f.amount("investmentComponent")).isEqualByComparingTo("1600.00");
        assertThat(f.amount("insured")).isEqualByComparingTo("400.00");   // guide D-01

        PostingFacts refund = PostingFactsExtractor.extract("benefitpayout.PayoutRequested", Map.of("cancellationId", "C-1",
            "policyNumber", "TL-1", "purpose", "FREE_LOOK_REFUND", "amount", money("1150.00")), TODAY).get(0);
        assertThat(refund.sourceRef()).isEqualTo("C-1");
        assertThat(refund.attribute("kind")).isEqualTo("FREE_LOOK");
        assertThat(refund.amount("gross")).isEqualByComparingTo("1150.00");
    }

    @Test
    void aCommissionAccrualCarriesItsChannelAndAClawbackIsAReversalMagnitude() {
        Map<String, Object> accrual = new HashMap<>(Map.of("accrualId", UUID.randomUUID(), "policyNumber", "POL-1",
            "tierType", "FIRST_YEAR", "salesChannel", "BANCASSURANCE", "amount", money("240.00")));
        PostingFacts earned = PostingFactsExtractor.extract("distribution.CommissionAccrued", accrual, TODAY).get(0);
        assertThat(earned.attribute("direction")).isEqualTo("ACCRUAL");
        assertThat(earned.attribute("channel")).isEqualTo("BANCASSURANCE");
        assertThat(earned.attribute("movement")).isEqualTo("IACF_BANC");

        accrual.put("amount", money("-120.00"));
        accrual.put("reversesAccrualId", UUID.randomUUID());
        accrual.put("salesChannel", null);
        PostingFacts clawback = PostingFactsExtractor.extract("distribution.CommissionAccrued", accrual, TODAY).get(0);
        assertThat(clawback.attribute("direction")).isEqualTo("REVERSAL");
        assertThat(clawback.attribute("channel")).as("no channel is a tied agent").isEqualTo("AGENT");
        assertThat(clawback.attribute("movement")).isEqualTo("IACF_CLAW");
        assertThat(clawback.amount("amount")).isEqualByComparingTo("120.00");
    }

    @Test
    void aCommissionPaymentStatesGrossNetAndTaxWithheld() {
        UUID statement = UUID.randomUUID();
        PostingFacts f = PostingFactsExtractor.extract("distribution.CommissionPaid", Map.of("statementId", statement,
            "amount", money("240000.00"), "withheldAmount", "12000.00", "paidAmount", "228000.00",
            "salesChannel", "BROKER"), TODAY).get(0);
        assertThat(f.amount("gross")).isEqualByComparingTo("240000.00");
        assertThat(f.amount("paid")).isEqualByComparingTo("228000.00");
        assertThat(f.amount("withheld")).isEqualByComparingTo("12000.00");
        assertThat(f.attribute("channel")).isEqualTo("BROKER");
        assertThat(f.attribute("paymentRef")).isEqualTo(statement.toString());
    }

    @Test
    void aLoanRepaymentAndAForeclosureCarryTheirSplit() {
        UUID txn = UUID.randomUUID();
        PostingFacts repaid = PostingFactsExtractor.extract("policyloan.LoanRepaid", Map.of("loanId", UUID.randomUUID(),
            "loanTransactionId", txn, "policyNumber", "WL-1", "interestAmount", "10000.00",
            "principalAmount", "200000.00", "amount", money("210000.00")), TODAY).get(0);
        assertThat(repaid.amount("interest")).isEqualByComparingTo("10000.00");
        assertThat(repaid.amount("principal")).isEqualByComparingTo("200000.00");

        UUID loan = UUID.randomUUID();
        PostingFacts lapse = PostingFactsExtractor.extract("policyloan.LoanForcedLapseTriggered", Map.of("loanId", loan,
            "policyNumber", "WL-1", "principalOutstanding", "1000000.00", "interestOutstanding", "100000.00",
            "currencyCode", "TZS"), TODAY).get(0);
        assertThat(lapse.sourceRef()).isEqualTo(loan + ":forced-lapse");
        assertThat(lapse.amount("total")).isEqualByComparingTo("1100000.00");   // guide E-06
    }

    @Test
    void anAccountLedgerPostingIsOneFactPerEntryByFlow() {
        List<PostingFacts> facts = PostingFactsExtractor.extract("accumulation.PostingRecorded", Map.of(
            "postingId", "P1", "policyNumber", "SAV-1", "entries", List.of(
                Map.of("seq", 1, "type", "CONTRIBUTION", "amount", "500000.00"),
                Map.of("seq", 2, "type", "POLICY_FEE", "amount", "-5000.00"),
                Map.of("seq", 3, "type", "REVERSAL", "amount", "2000.00"))), TODAY);
        assertThat(facts).extracting(PostingFacts::sourceRef).containsExactly("P1:1", "P1:2", "P1:3");
        assertThat(facts).extracting(f -> f.attribute("flow")).containsExactly("IN", "CHARGE", "REVERSAL_UP");
        assertThat(facts.get(1).amount("amount")).isEqualByComparingTo("5000.00");
    }

    @Test
    void aWithdrawalPostsItsProceedsAndItsChargeUnderTwoRefs() {
        List<PostingFacts> facts = PostingFactsExtractor.extract("unitlinked.WithdrawalPriced", Map.of(
            "policyNumber", "UL-1", "sourceRef", "WDL:1", "proceeds", "5000.00", "surrenderCharge", "150.00",
            "currencyCode", "TZS"), TODAY);
        assertThat(facts).extracting(PostingFacts::sourceRef).containsExactly("WDL:1", "WDL:1:surrender-charge");
        assertThat(facts).extracting(f -> f.attribute("part")).containsExactly("PROCEEDS", "SURRENDER_CHARGE");
    }

    @Test
    void aFundRevaluationIsAMagnitudeWithItsSignAndFundAndNoPolicy() {
        PostingFacts f = PostingFactsExtractor.extract("unitlinked.FundRevalued", Map.of("priceId", "P1",
            "fundCode", "EQ1", "carried", "1000.00", "delta", Map.of("amount", "-25.50", "currencyCode", "TZS")), TODAY)
            .get(0);
        assertThat(f.sourceRef()).isEqualTo("P1:1000.00");
        assertThat(f.policyNumber()).isNull();
        assertThat(f.amount("delta")).isEqualByComparingTo("25.50");
        assertThat(f.attribute("sign")).isEqualTo("NEGATIVE");
        assertThat(f.attribute("fund")).isEqualTo("EQ1");
    }

    @Test
    void aPriceCorrectionPostsEachChangedSaleAndWhatAPaidOneNowOwes() {
        List<PostingFacts> facts = PostingFactsExtractor.extract("unitlinked.PriceCorrected", Map.of(
            "correctedPriceId", "C1", "currencyCode", "TZS", "movements", List.of(
                Map.of("entryType", "ALLOCATION", "originalAmount", "100", "correctedAmount", "90", "policyNumber", "UL-1"),
                Map.of("entryType", "EXIT_SALE", "originalAmount", "-1000", "correctedAmount", "-1100",
                    "policyNumber", "UL-1", "paidAlready", true),
                Map.of("entryType", "EXIT_SALE", "originalAmount", "-500", "correctedAmount", "-500",
                    "policyNumber", "UL-2"))), TODAY);
        assertThat(facts).extracting(PostingFacts::sourceRef).containsExactly("C1:2:liability", "C1:2:owed");
        assertThat(facts).allSatisfy(f -> {
            assertThat(f.amount("difference")).isEqualByComparingTo("100");
            assertThat(f.attribute("sign")).isEqualTo("POSITIVE");
        });
    }

    @Test
    void aMissingAmountIsZeroSoNothingPosts() {
        PostingFacts f = PostingFactsExtractor.extract("reinsurance.RecoveryCalculated", Map.of("recoveryId", UUID.randomUUID(),
            "policyNumber", "POL-1"), TODAY).get(0);
        assertThat(f.amount("amount")).isZero();
    }

    /** IFRS 17 I3c: one journal per treaty and month, dated the month's last day so it lands in the month it charges. */
    @Test
    void aBordereauIsDatedItsMonthEndAndCarriesPremiumAndCommission() {
        UUID bordereauId = UUID.randomUUID();
        PostingFacts f = PostingFactsExtractor.extract("reinsurance.BordereauPosted", Map.of(
            "bordereauId", bordereauId.toString(),
            "treatyId", UUID.randomUUID().toString(),
            "period", "2026-09",
            "premium", Map.of("amount", "50000.00", "currencyCode", "TZS"),
            "commission", Map.of("amount", "10000.00", "currencyCode", "TZS")), TODAY).get(0);
        assertThat(f.sourceRef()).isEqualTo(bordereauId.toString());
        assertThat(f.policyNumber()).isNull();
        assertThat(f.currency()).isEqualTo("TZS");
        assertThat(f.eventDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(f.amount("premium")).isEqualByComparingTo("50000.00");
        assertThat(f.amount("commission")).isEqualByComparingTo("10000.00");
    }

    /** IFRS 17 I3c: the recovery carries the policy, so B-05 posts against its IFRS 17 dimensions. */
    @Test
    void aRecoveryPostsAgainstItsPolicy() {
        PostingFacts f = PostingFactsExtractor.extract("reinsurance.RecoveryCalculated", Map.of(
            "recoveryId", "R1", "policyNumber", "POL-9",
            "recoverableAmount", Map.of("amount", "1000000.00", "currencyCode", "TZS")), TODAY).get(0);
        assertThat(f.policyNumber()).isEqualTo("POL-9");
        assertThat(f.amount("amount")).isEqualByComparingTo("1000000.00");
        assertThat(f.attribute("refType")).isEqualTo("RECOVERY");
    }

    @Test
    void everyEventWithAShapeIsHandled() {
        assertThat(PostingFactsExtractor.handles("billing.PremiumCollected")).isTrue();
        assertThat(PostingFactsExtractor.handles("policy.PolicyIssued")).isFalse();
    }
}

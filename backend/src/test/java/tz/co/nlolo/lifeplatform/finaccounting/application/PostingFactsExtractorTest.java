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
    void onlyAClaimSettlementPostsFromTheEftRail() {
        assertThat(PostingFactsExtractor.extract("payment.EftDisbursementExecuted", Map.of("purpose", "CLAIM_SETTLEMENT",
            "sourceRef", "CLM-1", "disbursementId", UUID.randomUUID(), "amount", money("50.00")), TODAY))
            .singleElement().satisfies(f -> {
                assertThat(f.sourceRef()).isEqualTo("CLM-1");
                assertThat(f.policyNumber()).isNull();
                assertThat(f.attribute("purpose")).isEqualTo("CLAIM_SETTLEMENT");
            });
        assertThat(PostingFactsExtractor.extract("payment.EftDisbursementExecuted", Map.of("purpose", "SURRENDER_PAYOUT",
            "sourceRef", "S-1", "disbursementId", UUID.randomUUID(), "amount", money("50.00")), TODAY)).isEmpty();
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
        PostingFacts f = PostingFactsExtractor.extract("reinsurance.CessionRecorded", Map.of("cessionId", UUID.randomUUID(),
            "policyNumber", "POL-1"), TODAY).get(0);
        assertThat(f.amount("amount")).isZero();
    }

    @Test
    void everyEventWithAShapeIsHandled() {
        assertThat(PostingFactsExtractor.handles("billing.PremiumCollected")).isTrue();
        assertThat(PostingFactsExtractor.handles("policy.PolicyIssued")).isFalse();
    }
}

package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleParser;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleSet;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleValidator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The posting rules file (IFRS 17 I3a): the real one validates, every way a rule could post wrongly is refused at load
 * naming the rule, and the choice of rule is the documented one -- never file order.
 */
class PostingRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private static PostingRuleSet parse(String yaml) {
        return PostingRuleParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void validate(String yaml) {
        PostingRuleValidator.validate(parse(yaml), ChartOfAccountBlueprint.accounts(), PostingFactsExtractor.SHAPES);
    }

    private static String rule(String id, String event, String models, String when, String lines) {
        return "  - id: " + id + "\n    event: " + event + "\n    models: " + models + "\n"
            + (when == null ? "" : "    when: " + when + "\n") + "    lines:\n" + lines;
    }

    private static final String INVOICE_LINES = "      - {dr: \"2122\", amount: amount, movement: PRM_REN}\n"
        + "      - {cr: \"2121\", amount: amount, movement: PRM_REN}\n";

    @Test
    void theRealRulesFileValidatesAndPostsEveryEventThePlatformExtracts() {
        PostingRuleSet set = PostingRules.load();
        assertThat(set.version()).isPositive();
        for (String event : PostingFactsExtractor.SHAPES.keySet()) {
            assertThat(set.rules()).as("a rule for " + event).anyMatch(r -> r.event().equals(event));
        }
    }

    // ---- source: SYSTEM (IFRS 17 I3d) ----

    private static final String MAN_LINES = "      - {dr: \"1434\", amount: amount}\n"
        + "      - {cr: \"4160\", amount: amount}\n";

    /** A SYSTEM rule's journal is the platform's own, which the ledger guard lets post to MAN accounts. */
    @Test
    void aSystemRuleIsReadAndMayPostToAManualAccount() {
        String yaml = "version: 1\nrules:\n" + rule("X-01", "finaccounting.PaaRevenueEarned", "[PAA]", null, MAN_LINES)
            .replace("    lines:", "    source: SYSTEM\n    lines:");
        assertThat(parse(yaml).rules().get(0).system()).isTrue();
        validate(yaml);
    }

    @Test
    void anEventRuleStillMayNotPostToAManualAccount() {
        String yaml = "version: 1\nrules:\n" + rule("X-01", "finaccounting.PaaRevenueEarned", "[PAA]", null, MAN_LINES);
        assertThat(parse(yaml).rules().get(0).system()).isFalse();
        assertThatThrownBy(() -> validate(yaml)).hasMessageContaining("X-01: account 1434 is MAN");
    }

    @Test
    void aSourceOtherThanSystemIsRefused() {
        String yaml = "version: 1\nrules:\n" + rule("X-01", "finaccounting.PaaRevenueEarned", "[PAA]", null, MAN_LINES)
            .replace("    lines:", "    source: MANUAL\n    lines:");
        assertThatThrownBy(() -> parse(yaml)).hasMessageContaining("X-01: 'source' must be SYSTEM");
    }

    @Test
    void theShippedPaaEarningRuleIsTheSystems() {
        assertThat(PostingRules.load().rules()).filteredOn(r -> "I-03".equals(r.id())).singleElement()
            .satisfies(r -> assertThat(r.system()).isTrue());
    }

    @Test
    void anInvoicePostsByTheContractsModel() {
        PostingRuleSet set = PostingRules.load();
        assertThat(set.select("billing.PremiumInvoiceGenerated", "GMM", Map.of(), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("A-06");
        assertThat(set.select("billing.PremiumInvoiceGenerated", "PAA", Map.of(), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("I-01");
    }

    @Test
    void anUnclassifiedPolicyMatchesNoRuleNotEvenAnAnyRule() {
        PostingRuleSet set = PostingRules.load();
        assertThat(set.select("billing.PremiumInvoiceGenerated", PostingRuleSet.UNCLASSIFIED, Map.of(), TODAY)).isEmpty();
        assertThat(set.select("claims.ClaimSettled", PostingRuleSet.UNCLASSIFIED, Map.of(), TODAY)).isEmpty();
        assertThat(set.select("claims.ClaimSettled", "VFA", Map.of(), TODAY)).isPresent();
    }

    @Test
    void theRuleWithMoreWhenAttributesWins() {
        PostingRuleSet set = PostingRules.load();
        assertThat(set.select("unitlinked.PayoutPaid", "VFA", Map.of("purpose", "TOP_UP_REFUND"), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("PLAT-UL-PAYOUT-TOPUP");
        assertThat(set.select("unitlinked.PayoutPaid", "VFA", Map.of("purpose", "SURRENDER"), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("PLAT-UL-PAYOUT");
    }

    @Test
    void aRuleAppliesOnlyInItsDates() {
        PostingRuleSet set = parse("version: 1\nrules:\n" + rule("A-06", "billing.PremiumInvoiceGenerated", "[GMM]", null,
            INVOICE_LINES).replace("    lines:", "    effectiveFrom: \"2027-01-01\"\n    lines:"));
        assertThat(set.select("billing.PremiumInvoiceGenerated", "GMM", Map.of(), TODAY)).isEmpty();
        assertThat(set.select("billing.PremiumInvoiceGenerated", "GMM", Map.of(), LocalDate.of(2027, 1, 1))).isPresent();
    }

    @Test
    void aManualAccountIsRefusedNamingTheRule() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n" + rule("X-1", "billing.PremiumCollected", "[GMM]", null,
                "      - {dr: \"1150\", amount: amount}\n      - {cr: \"2122\", amount: amount}\n")))
            .hasMessageContaining("X-1: account 1150 is MAN");
    }

    @Test
    void aHeadingAnUnknownAccountAnUnknownFactAndAnUnknownMovementAreRefused() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n" + rule("X-2", "billing.PremiumCollected", "[GMM]", null,
                "      - {dr: \"1100\", amount: amount}\n      - {cr: \"2999\", amount: premium, movement: PRM_XYZ}\n")))
            .hasMessageContaining("X-2: account 1100 is a heading")
            .hasMessageContaining("X-2: account 2999 is not in the chart")
            .hasMessageContaining("X-2: amount 'premium' is not a fact")
            .hasMessageContaining("X-2: movement 'PRM_XYZ'");
    }

    @Test
    void anUnknownEventModelOrAttributeAndAOneSidedRuleAreRefused() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n"
                + rule("X-3", "billing.Nonsense", "[GMM]", null, INVOICE_LINES)
                + rule("X-4", "billing.PremiumCollected", "[XYZ]", "{colour: RED}",
                    "      - {dr: \"1140\", amount: amount}\n")))
            .hasMessageContaining("X-3: event 'billing.Nonsense' is not one the platform posts")
            .hasMessageContaining("X-4: model 'XYZ'")
            .hasMessageContaining("X-4: 'when' tests 'colour'")
            .hasMessageContaining("X-4: a rule needs at least one Dr and one Cr line");
    }

    @Test
    void twoRulesThatCouldBothWinAreRefused() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n"
                + rule("A", "billing.PremiumInvoiceGenerated", "[GMM, PAA]", null, INVOICE_LINES)
                + rule("B", "billing.PremiumInvoiceGenerated", "[ANY]", null, INVOICE_LINES)))
            .hasMessageContaining("B: overlaps A");
    }

    @Test
    void rulesForDifferentModelsOrDifferentWhenDoNotOverlap() {
        validate("version: 1\nrules:\n"
            + rule("A", "billing.PremiumInvoiceGenerated", "[GMM]", null, INVOICE_LINES)
            + rule("B", "billing.PremiumInvoiceGenerated", "[PAA]", null, INVOICE_LINES)
            + rule("C", "unitlinked.PayoutPaid", "[VFA]", "{purpose: TOP_UP_REFUND}",
                "      - {dr: \"2211\", amount: amount}\n      - {cr: \"5110\", amount: amount}\n")
            + rule("D", "unitlinked.PayoutPaid", "[VFA]", null,
                "      - {dr: \"2211\", amount: amount}\n      - {cr: \"5110\", amount: amount}\n"));
    }

    @Test
    void aDuplicateIdIsRefused() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n"
                + rule("A", "billing.PremiumInvoiceGenerated", "[GMM]", null, INVOICE_LINES)
                + rule("A", "billing.PremiumInvoiceGenerated", "[PAA]", null, INVOICE_LINES)))
            .hasMessageContaining("A: the id is used twice");
    }

    // ---- IFRS 17 I3b ----

    @Test
    void anIfrs9InvoicePostsNothingOnPurposeRatherThanQueueing() {
        PostingRuleSet set = PostingRules.load();
        PostingRuleSet.Rule rule = set.select("billing.PremiumInvoiceGenerated", "IFRS9", Map.of(), TODAY).orElseThrow();
        assertThat(rule.post()).isFalse();
        assertThat(rule.lines()).isEmpty();
    }

    @Test
    void aPayoutDuePostsByItsKind() {
        PostingRuleSet set = PostingRules.load();
        assertThat(set.select("benefitpayout.PayoutRequested", "GMM", Map.of("kind", "MATURITY"), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("C-01");
        assertThat(set.select("benefitpayout.PayoutRequested", "GMM", Map.of("kind", "ANNUITY"), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("H-02");
        assertThat(set.select("benefitpayout.PayoutRequested", "IFRS9", Map.of("kind", "FREE_LOOK"), TODAY)).get()
            .extracting(PostingRuleSet.Rule::id).isEqualTo("G-FREE-LOOK");
    }

    @Test
    void commissionPostsToThePayableOfItsChannel() {
        PostingRuleSet set = PostingRules.load();
        PostingRuleSet.Rule broker = set.select("distribution.CommissionAccrued", "GMM",
            Map.of("direction", "ACCRUAL", "channel", "BROKER", "movement", "IACF_BRK"), TODAY).orElseThrow();
        assertThat(broker.lines()).extracting(PostingRuleSet.Line::account).containsExactly("2123", "2520");
        PostingRuleSet.Rule paid = set.select("distribution.CommissionPaid", "NONE", Map.of("channel", "AGENT"), TODAY)
            .orElseThrow();
        assertThat(paid.lines()).extracting(PostingRuleSet.Line::account).containsExactly("2510", "BANK", "2610");
    }

    @Test
    void aRuleThatPostsNothingMayHaveNoLinesAndBankIsAnAccount() {
        validate("version: 1\nrules:\n  - id: Z\n    event: billing.PremiumCollected\n    models: [IFRS9]\n    post: false\n"
            + rule("Y", "claims.ClaimSettled", "[GMM]", null,
                "      - {dr: \"2211\", amount: amount}\n      - {cr: BANK, amount: amount}\n"));
        assertThatThrownBy(() -> validate("version: 1\nrules:\n" + rule("Z", "billing.PremiumCollected", "[IFRS9]", null,
                INVOICE_LINES).replace("    lines:", "    post: false\n    lines:")))
            .hasMessageContaining("Z: a rule that posts nothing (post: false) has no lines");
    }
}

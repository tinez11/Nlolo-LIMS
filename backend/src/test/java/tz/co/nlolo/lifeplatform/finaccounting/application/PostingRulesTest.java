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
            + rule("C", "payment.EftDisbursementExecuted", "[NONE]", "{purpose: CLAIM_SETTLEMENT}",
                "      - {dr: \"2211\", amount: amount}\n      - {cr: \"5110\", amount: amount}\n")
            + rule("D", "payment.EftDisbursementExecuted", "[NONE]", null,
                "      - {dr: \"2211\", amount: amount}\n      - {cr: \"5110\", amount: amount}\n"));
    }

    @Test
    void aDuplicateIdIsRefused() {
        assertThatThrownBy(() -> validate("version: 1\nrules:\n"
                + rule("A", "billing.PremiumInvoiceGenerated", "[GMM]", null, INVOICE_LINES)
                + rule("A", "billing.PremiumInvoiceGenerated", "[PAA]", null, INVOICE_LINES)))
            .hasMessageContaining("A: the id is used twice");
    }
}

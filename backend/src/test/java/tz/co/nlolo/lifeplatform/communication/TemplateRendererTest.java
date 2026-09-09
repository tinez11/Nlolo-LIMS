package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.communication.domain.TemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Substitution is a pure function, and this is why it is worth keeping that way: the whole thing
 * is testable without a database, a Spring context or a template library.
 */
class TemplateRendererTest {

    @Test
    void substitutesEveryPlaceholderItIsGiven() {
        assertThat(TemplateRenderer.render(
            "Pay {{premium}} by {{expiryDate}} to start it.",
            Map.of("premium", "TZS 50,000.00", "expiryDate", "2026-10-09")))
            .isEqualTo("Pay TZS 50,000.00 by 2026-10-09 to start it.");
    }

    @Test
    void substitutesTheSamePlaceholderEveryTimeItAppears() {
        assertThat(TemplateRenderer.render("{{n}} and {{n}}", Map.of("n", "POL-1")))
            .isEqualTo("POL-1 and POL-1");
    }

    /**
     * The one that matters.
     *
     * <p>A placeholder nobody supplied is a bug in the caller or the template, and shipping
     * "Pay {{premium}} by" to a customer is worse than not sending at all — it is unreadable,
     * it looks broken, and it teaches people to ignore messages from this platform. Throwing
     * means the dispatch is recorded FAILED with a reason somebody can act on, which is the
     * outcome an operator can actually do something about.
     */
    @Test
    void refusesToRenderAPlaceholderNobodySupplied() {
        assertThatThrownBy(() -> TemplateRenderer.render("Pay {{premium}} by {{expiryDate}}.",
            Map.of("premium", "TZS 50,000.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expiryDate");
    }

    @Test
    void namesEveryMissingPlaceholderAtOnce() {
        // Not just the first. Somebody fixing a template wants the whole list, not one per
        // deploy-and-retry cycle.
        assertThatThrownBy(() -> TemplateRenderer.render("{{a}} {{b}} {{c}}", Map.of("b", "2")))
            .hasMessageContaining("a")
            .hasMessageContaining("c");
    }

    @Test
    void aValueSuppliedButUnusedIsNotAnError() {
        // The caller passes a fixed set of facts about a policy; a template that mentions only
        // some of them is a shorter message, not a fault. The asymmetry with the test above is
        // deliberate: an unused value harms nobody, an unfilled hole reaches a customer.
        assertThat(TemplateRenderer.render("Policy {{policyNumber}}.",
            Map.of("policyNumber", "POL-1", "premium", "TZS 50,000.00")))
            .isEqualTo("Policy POL-1.");
    }

    @Test
    void aTemplateWithNoPlaceholdersRendersUnchanged() {
        assertThat(TemplateRenderer.render("Payment received.", Map.of()))
            .isEqualTo("Payment received.");
    }

    @Test
    void aValueContainingBracesIsNotItselfSubstituted() {
        // Substitution happens once, over the template. A value that happens to look like a
        // placeholder is data, not an instruction -- otherwise a policy number or a free-text
        // designee could inject a token and change what the message says.
        assertThat(TemplateRenderer.render("Policy {{policyNumber}}.",
            Map.of("policyNumber", "{{premium}}", "premium", "TZS 1.00")))
            .isEqualTo("Policy {{premium}}.");
    }

    @Test
    void reportsThePlaceholdersATemplateDeclares() {
        // Used by the console's template editor, so somebody rewriting the wording can see which
        // tokens are real -- and by the PUT that refuses an edit introducing a new one.
        assertThat(TemplateRenderer.placeholdersIn("Pay {{premium}} by {{expiryDate}}, {{premium}}."))
            .containsExactlyInAnyOrder("premium", "expiryDate");
    }
}

package tz.co.nlolo.lifeplatform.product.api;

import java.util.List;

/**
 * A version's payout schedule and terms.
 *
 * <p>{@code authored} is true only for a plan that came through the publish endpoint. The forty-odd
 * internal {@code publishVersion} fixtures pass {@link #none()}, which is exempt from the authoring
 * requirements -- free-look days on an individual product, a maturity row on an endowment -- because
 * those rules describe what a person authoring a product must supply, not what every historical
 * version already has. The HTTP path always passes an authored plan, so a real product cannot
 * escape them.
 */
public record PayoutPlan(PayoutTerms terms, List<PayoutRowInput> rows, boolean authored) {

    public PayoutPlan {
        terms = terms != null ? terms : PayoutTerms.none();
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static PayoutPlan none() { return new PayoutPlan(PayoutTerms.none(), List.of(), false); }

    public static PayoutPlan authored(PayoutTerms terms, List<PayoutRowInput> rows) {
        return new PayoutPlan(terms, rows, true);
    }

    public List<PayoutRowInput> rowsOf(PayoutKind kind) {
        return rows.stream().filter(r -> r.kind() == kind).toList();
    }

    /**
     * True when the version pays at the end of the term, so its policies MATURE rather than expire.
     * {@code CoverExpiryDrain} reads this to leave such a policy alone -- expiring it would close
     * cover on a contract that is owed money.
     */
    public boolean hasEndOfTermRow() {
        return rows.stream().anyMatch(r -> r.kind() == PayoutKind.MATURITY || r.kind() == PayoutKind.RETURN_OF_PREMIUM);
    }
}

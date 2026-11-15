package tz.co.nlolo.lifeplatform.audit.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an event may say about itself on a screen (moved here from the dashboard's RecentActivity, 2026-11-14, so the
 * activity log words events the same way): the whitelisted references, and its money where it carries one. A payer's
 * phone number or a free-text reason never leaves the server through these.
 */
public final class EventRefs {

    private EventRefs() {}

    public record Amount(BigDecimal amount, String currencyCode) {}

    /**
     * Events the platform raises by itself in bulk -- one per GL posting, one per earned-revenue run, one per invoice in
     * a billing run. Kept as lines of their own, they would bury everything a person did.
     */
    public static final Set<String> NOISE = Set.of("finaccounting.GlPostingRecorded", "finaccounting.PaaRevenueEarned",
        "billing.PremiumInvoiceGenerated");

    /** The only payload keys that leave the server. */
    public static final List<String> REF_KEYS = List.of("caseId", "outcome", "claimId", "claimType", "policyNumber",
        "receiptId", "disbursementId", "purpose");

    /** Where an event keeps its money: plainly, or under the name of what the money was. */
    private static final List<String> AMOUNT_KEYS = List.of("amount", "settledAmount", "approvedAmount");

    public static Map<String, String> refs(JsonNode payload) {
        Map<String, String> refs = new LinkedHashMap<>();
        if (payload == null) return refs;
        for (String key : REF_KEYS) {
            JsonNode value = payload.get(key);
            if (value != null && value.isValueNode() && !value.isNull()) {
                refs.put(key, value.asText());
            }
        }
        return refs;
    }

    public static Amount amount(JsonNode payload) {
        if (payload == null) return null;
        for (String key : AMOUNT_KEYS) {
            JsonNode money = payload.get(key);
            if (money != null && money.isObject() && money.hasNonNull("amount") && money.hasNonNull("currencyCode")) {
                JsonNode value = money.get("amount");
                BigDecimal decimal = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
                return new Amount(decimal, money.get("currencyCode").asText());
            }
        }
        return null;
    }
}

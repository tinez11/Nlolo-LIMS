package tz.co.nlolo.lifeplatform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which record a write acted on, what to call it, and the money it carried (2026-11-14, activity log). */
public final class RecordRefs {

    private RecordRefs() {}

    public record Ref(String type, String id) {}

    public record Amount(BigDecimal amount, String currencyCode) {}

    /**
     * Path variable -> record type. A route naming several is about the LAST listed here that it carries: a claim on a
     * policy is the claim's, a case for a client is the case's. Group and credit-life schemes are addressed by policy
     * number, so they are policy records.
     */
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("policyNumber", "policy");
        TYPES.put("partyId", "client");
        TYPES.put("agentId", "agent");
        TYPES.put("userId", "staff user");
        TYPES.put("productId", "product");
        TYPES.put("caseId", "underwriting case");
        TYPES.put("claimId", "claim");
    }

    public static Ref of(Map<String, String> variables, String pattern) {
        if (variables == null || variables.isEmpty()) return null;
        Ref found = null;
        for (Map.Entry<String, String> t : TYPES.entrySet()) {
            String id = variables.get(t.getKey());
            if (id != null) found = new Ref(t.getValue(), id);
        }
        if (found != null) return found;
        // Anything else: named by the route's first segment ("treaties"), with the route's first variable.
        String first = pattern.replaceFirst("^/", "").split("/")[0];
        return new Ref(first, variables.values().iterator().next());
    }

    private static final List<String> LABEL_KEYS = List.of("claimNumber", "caseNumber", "receiptNumber",
        "policyNumber", "fullName", "displayName", "name");

    /** The record's readable name or number from the answer, or null: an id is never shown as a name. */
    public static String label(JsonNode response) {
        if (response == null || !response.isObject()) return null;
        for (String key : LABEL_KEYS) {
            JsonNode v = response.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                String text = v.asText().strip();
                return text.length() > 200 ? text.substring(0, 200) : text;
            }
        }
        return null;
    }

    private static final List<String> AMOUNT_KEYS = List.of("amount", "approvedAmount", "settledAmount", "sumAssured");

    /** The first {amount, currencyCode} object, the request before the answer; a bare decimal "amount" in TZS. */
    public static Amount amount(JsonNode request, JsonNode response) {
        for (JsonNode body : new JsonNode[] {request, response}) {
            if (body == null || !body.isObject()) continue;
            for (String key : AMOUNT_KEYS) {
                JsonNode m = body.get(key);
                if (m == null || m.isNull()) continue;
                try {
                    if (m.isObject() && m.hasNonNull("amount") && m.hasNonNull("currencyCode")) {
                        return new Amount(new BigDecimal(m.get("amount").asText()), m.get("currencyCode").asText());
                    }
                    if (m.isNumber() || m.isTextual() && m.asText().matches("-?\\d+(\\.\\d+)?")) {
                        String currency = body.hasNonNull("currency") ? body.get("currency").asText()
                            : body.hasNonNull("currencyCode") ? body.get("currencyCode").asText() : "TZS";
                        return new Amount(new BigDecimal(m.asText()), currency.length() == 3 ? currency : "TZS");
                    }
                } catch (NumberFormatException notMoney) {
                    // Not a figure after all; the line simply carries no amount.
                }
            }
        }
        return null;
    }
}

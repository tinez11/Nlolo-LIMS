package tz.co.nlolo.lifeplatform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the activity log never keeps, and what it shows only partly (2026-11-14, the user: secrets never kept; ID and
 * phone numbers masked in the line, full when the line is opened).
 */
public final class ActivityRedaction {

    private ActivityRedaction() {}

    /** Whole field names that are secrets. "code" alone is a sign-in or reset code; "productCode" is not a secret. */
    private static final Set<String> SECRET_NAMES = Set.of("password", "code", "otp", "pin", "token", "secret",
        "authorization", "signincode", "verificationcode", "resetcode", "turnstiletoken", "visittoken");
    private static final String[] SECRET_ENDINGS = {"password", "token", "secret", "otp", "signincode", "pin"};
    private static final String[] PERSONAL = {"phone", "msisdn", "nationalid", "idnumber", "accountnumber", "payee"};

    public static boolean secret(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(f)) return true;
        for (String end : SECRET_ENDINGS) {
            if (f.endsWith(end)) return true;
        }
        return false;
    }

    public static boolean personal(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        for (String p : PERSONAL) {
            if (f.contains(p)) return true;
        }
        return false;
    }

    /** A copy with every secret field removed, at any depth. */
    public static JsonNode withoutSecrets(JsonNode body) {
        if (body == null) return null;
        JsonNode copy = body.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (secret(name)) o.remove(name);
                else strip(o.get(name));
            }
        } else if (node instanceof ArrayNode a) {
            a.elements().forEachRemaining(ActivityRedaction::strip);
        }
    }

    /** A copy with every personal number shown by its last three characters, at any depth. */
    public static JsonNode masked(JsonNode body) {
        if (body == null) return null;
        JsonNode copy = body.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode v = o.get(name);
                if (personal(name) && v.isValueNode() && !v.isNull()) o.set(name, TextNode.valueOf(mask(v.asText())));
                else mask(v);
            }
        } else if (node instanceof ArrayNode a) {
            a.elements().forEachRemaining(ActivityRedaction::mask);
        }
    }

    public static String mask(String value) {
        if (value == null || value.length() <= 3) return "••••";
        return "••••" + value.substring(value.length() - 3);
    }
}

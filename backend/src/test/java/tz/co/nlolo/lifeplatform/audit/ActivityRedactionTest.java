package tz.co.nlolo.lifeplatform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRedaction;

import static org.assertj.core.api.Assertions.assertThat;

/** The user, 2026-11-14: secrets are never kept; ID and phone numbers are masked in the line. */
class ActivityRedactionTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(String s) throws Exception {
        return json.readTree(s);
    }

    @Test
    void secretsAreNeverKeptAtAnyDepth() throws Exception {
        JsonNode kept = ActivityRedaction.withoutSecrets(body(
            "{\"password\":\"x\",\"code\":\"123456\",\"signInCode\":\"9\",\"pin\":\"1\",\"accessToken\":\"t\","
                + "\"nested\":{\"newPassword\":\"y\",\"clientSecret\":\"s\"},\"rows\":[{\"otp\":\"5\"}],"
                + "\"productCode\":\"FUN-01\",\"amount\":5}"));
        assertThat(kept.toString()).doesNotContain("\"x\"", "123456", "accessToken", "newPassword", "clientSecret",
            "\"pin\"", "signInCode", "otp");
        assertThat(kept.get("productCode").asText()).as("a code that names a thing is not a secret").isEqualTo("FUN-01");
        assertThat(kept.get("amount").asInt()).isEqualTo(5);
    }

    @Test
    void personalNumbersAreMaskedToTheirLastThree() throws Exception {
        JsonNode masked = ActivityRedaction.masked(body(
            "{\"phoneNumber\":\"+255755418369\",\"nationalId\":\"19800101-12345-00001-23\",\"payeeRef\":\"0712345678\","
                + "\"accountNumber\":\"0150123456789\",\"fullName\":\"Juma K\",\"nested\":{\"msisdn\":\"255700000001\"}}"));
        assertThat(masked.get("phoneNumber").asText()).isEqualTo("••••369");
        assertThat(masked.get("nationalId").asText()).isEqualTo("••••-23");
        assertThat(masked.get("payeeRef").asText()).isEqualTo("••••678");
        assertThat(masked.get("accountNumber").asText()).isEqualTo("••••789");
        assertThat(masked.get("nested").get("msisdn").asText()).isEqualTo("••••001");
        assertThat(masked.get("fullName").asText()).isEqualTo("Juma K");
    }
}

package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The prod profile refuses to boot on a dev placeholder (2026-10-13, deployment pilot design §5). */
class ProductionSettingsGuardTest {

    private static Map<String, String> realLooking() {
        Map<String, String> s = new HashMap<>();
        s.put("spring.datasource.password", "Zq8-real-db-password");
        s.put("minio.access-key", "platform-docs");
        s.put("minio.secret-key", "Lk2-real-object-store-secret");
        s.put("app.portal.keycloak.client-secret", "Pt7-real-portal-secret");
        s.put("mobile-money.callback-hmac-secret", "Hm4-real-hmac-secret");
        s.put("password-reset.sms-secret", "Rs5-real-reset-sms-secret");
        s.put("communication.sms-gateway.live", "true");
        s.put("communication.sms-gateway.authorization", "Basic cmVhbA==");
        s.put("app.portal.url", "https://portal.example.tz/customers");
        for (String realm : new String[] {"customers", "agents", "staff", "regulators"}) {
            s.put("app.security.issuers." + realm, "https://login.example.tz/realms/" + realm);
        }
        return s;
    }

    @Test
    void realLookingSettingsPass() {
        assertThat(ProductionSettingsGuard.problems(realLooking()::get)).isEmpty();
    }

    @Test
    void eachDevPlaceholderIsNamed() {
        Map<String, String> s = realLooking();
        s.put("spring.datasource.password", "devapppassword");
        s.put("minio.secret-key", "minioadmin");
        s.put("app.portal.keycloak.client-secret", "dev-secret-portal-admin");
        s.put("mobile-money.callback-hmac-secret", "dev-only-placeholder-secret");
        s.put("password-reset.sms-secret", "dev-only-reset-sms-secret");
        assertThat(ProductionSettingsGuard.problems(s::get)).containsExactlyInAnyOrder(
            "spring.datasource.password still holds the dev value",
            "minio.secret-key still holds the dev value",
            "app.portal.keycloak.client-secret still holds the dev value",
            "mobile-money.callback-hmac-secret still holds the dev value",
            "password-reset.sms-secret still holds the dev value");
    }

    @Test
    void missingValuesLiveSmsWithoutCredentialsAndPlainHttpAreRefused() {
        Map<String, String> s = realLooking();
        s.remove("spring.datasource.password");
        s.put("communication.sms-gateway.authorization", "");
        s.put("app.security.issuers.staff", "http://localhost:8081/realms/staff");
        s.put("app.portal.url", "http://localhost:5173/customers");
        assertThat(ProductionSettingsGuard.problems(s::get)).containsExactlyInAnyOrder(
            "spring.datasource.password is not set",
            "communication.sms-gateway.live is true but communication.sms-gateway.authorization is empty",
            "app.security.issuers.staff must be an https:// address",
            "app.portal.url must be an https:// address");
    }

    @Test
    void theGuardOnlyExistsUnderTheProdProfile() {
        assertThat(ProductionSettingsGuard.class.getAnnotation(Profile.class).value()).containsExactly("prod");
    }
}

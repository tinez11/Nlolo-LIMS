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
        s.put("app.staff-admin.keycloak.client-secret", "Sa3-real-staff-admin-secret");
        s.put("app.agent-logins.keycloak.client-secret", "Ag6-real-agents-admin-secret");
        s.put("mobile-money.callback-hmac-secret", "Hm4-real-hmac-secret");
        s.put("password-reset.sms-secret", "Rs5-real-reset-sms-secret");
        s.put("communication.sms-gateway.live", "true");
        s.put("communication.sms-gateway.authorization", "Basic cmVhbA==");
        s.put("app.portal.url", "https://portal.example.tz/customers");
        s.put("app.security.issuer", "https://login.example.tz/realms/lifeplatform");
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
        s.put("app.security.issuer", "http://localhost:8081/realms/lifeplatform");
        s.put("app.portal.url", "http://localhost:5173/customers");
        assertThat(ProductionSettingsGuard.problems(s::get)).containsExactlyInAnyOrder(
            "spring.datasource.password is not set",
            "communication.sms-gateway.live is true but communication.sms-gateway.authorization is empty",
            "app.security.issuer must be an https:// address",
            "app.portal.url must be an https:// address");
    }

    /** The public quote link (2026-11-02): on in production only with real secrets and the deployment's insurer. */
    @Test
    void thePublicQuoteLinkNeedsRealSecretsWhenSwitchedOn() {
        Map<String, String> s = realLooking();
        s.put("app.public-quote.enabled", "true");
        s.put("app.public-quote.turnstile.enabled", "true");
        s.put("app.public-quote.turnstile.secret", "1x0000000000000000000000000000000AA");
        s.put("app.public-quote.visit-secret", "short");
        s.put("app.public-quote.tenant-id", "11111111-1111-1111-1111-111111111111");
        assertThat(ProductionSettingsGuard.problems(s::get)).containsExactlyInAnyOrder(
            "app.public-quote is enabled but app.public-quote.turnstile.secret is a Cloudflare test secret or empty",
            "app.public-quote.visit-secret must be at least 32 characters",
            "app.public-quote.tenant-id must name this deployment's insurer");

        s.put("app.public-quote.turnstile.secret", "0x4AAAAAAA-real-turnstile-secret");
        s.put("app.public-quote.visit-secret", "a-real-visit-secret-of-at-least-32-characters");
        s.put("app.public-quote.tenant-id", "6f1d2c34-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
        assertThat(ProductionSettingsGuard.problems(s::get)).isEmpty();

        // Off, nothing about it is asked.
        Map<String, String> off = realLooking();
        off.put("app.public-quote.enabled", "false");
        assertThat(ProductionSettingsGuard.problems(off::get)).isEmpty();
    }

    @Test
    void theGuardOnlyExistsUnderTheProdProfile() {
        assertThat(ProductionSettingsGuard.class.getAnnotation(Profile.class).value()).containsExactly("prod");
    }
}

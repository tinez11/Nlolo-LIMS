package tz.co.nlolo.lifeplatform;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Refuses to start the {@code prod} profile while any secret still holds its development value (2026-10-13, the
 * deployment pilot design §5). Every one of these settings has a working dev default in application.yml, so a
 * forgotten line in the server's .env would otherwise boot quietly on {@code minioadmin}. Failing the boot fails the
 * release's smoke check, and the previous version keeps running.
 */
@Component
@Profile("prod")
public class ProductionSettingsGuard {

    private static final List<String[]> DEV_VALUES = List.of(
        new String[] {"spring.datasource.password", "devapppassword"},
        new String[] {"minio.access-key", "minioadmin"},
        new String[] {"minio.secret-key", "minioadmin"},
        new String[] {"app.portal.keycloak.client-secret", "dev-secret-"},
        new String[] {"app.staff-admin.keycloak.client-secret", "dev-secret-"},
        new String[] {"app.agent-logins.keycloak.client-secret", "dev-secret-"},
        new String[] {"mobile-money.callback-hmac-secret", "dev-only-placeholder-secret"},
        new String[] {"password-reset.sms-secret", "dev-only-reset-sms-secret"});

    private static final List<String> MUST_BE_HTTPS = List.of(
        "app.security.issuers.customers", "app.security.issuers.agents", "app.security.issuers.staff",
        "app.security.issuers.regulators", "app.portal.url");

    public ProductionSettingsGuard(Environment environment) {
        List<String> problems = problems(environment::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The prod profile will not start on development settings: "
                + String.join("; ", problems) + ". Set them in the server's .env.");
        }
    }

    static List<String> problems(Function<String, String> settings) {
        List<String> problems = new ArrayList<>();
        for (String[] devValue : DEV_VALUES) {
            String value = settings.apply(devValue[0]);
            if (value == null || value.isBlank()) {
                problems.add(devValue[0] + " is not set");
            } else if (value.startsWith(devValue[1])) {
                problems.add(devValue[0] + " still holds the dev value");
            }
        }
        if ("true".equalsIgnoreCase(settings.apply("communication.sms-gateway.live"))) {
            String authorization = settings.apply("communication.sms-gateway.authorization");
            if (authorization == null || authorization.isBlank()) {
                problems.add("communication.sms-gateway.live is true but communication.sms-gateway.authorization is empty");
            }
        }
        // The public quote link (2026-11-02): on only with a real Turnstile secret, a visit secret and a named insurer.
        if ("true".equalsIgnoreCase(settings.apply("app.public-quote.enabled"))) {
            String secret = settings.apply("app.public-quote.turnstile.secret");
            if (secret == null || secret.isBlank() || secret.startsWith("1x0000") || secret.startsWith("2x0000")
                    || secret.startsWith("3x0000")) {
                problems.add("app.public-quote is enabled but app.public-quote.turnstile.secret is a Cloudflare test"
                    + " secret or empty");
            }
            if (!"true".equalsIgnoreCase(settings.apply("app.public-quote.turnstile.enabled"))) {
                problems.add("app.public-quote is enabled with its Turnstile check switched off");
            }
            String visitSecret = settings.apply("app.public-quote.visit-secret");
            if (visitSecret == null || visitSecret.length() < 32) {
                problems.add("app.public-quote.visit-secret must be at least 32 characters");
            }
            String tenant = settings.apply("app.public-quote.tenant-id");
            if (tenant == null || tenant.isBlank() || tenant.startsWith("11111111-")) {
                problems.add("app.public-quote.tenant-id must name this deployment's insurer");
            }
        }
        for (String key : MUST_BE_HTTPS) {
            String value = settings.apply(key);
            if (value == null || !value.startsWith("https://")) {
                problems.add(key + " must be an https:// address");
            }
        }
        return problems;
    }
}

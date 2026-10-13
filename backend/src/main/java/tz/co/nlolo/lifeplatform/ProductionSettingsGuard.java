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
        new String[] {"mobile-money.callback-hmac-secret", "dev-only-placeholder-secret"});

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
        for (String key : MUST_BE_HTTPS) {
            String value = settings.apply(key);
            if (value == null || !value.startsWith("https://")) {
                problems.add(key + " must be an https:// address");
            }
        }
        return problems;
    }
}

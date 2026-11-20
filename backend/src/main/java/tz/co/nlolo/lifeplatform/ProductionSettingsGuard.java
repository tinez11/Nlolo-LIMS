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
        new String[] {"app.authentik.api-token", "dev-only-"},
        new String[] {"mobile-money.callback-hmac-secret", "dev-only-placeholder-secret"},
        new String[] {"password-reset.sms-secret", "dev-only-reset-sms-secret"},
        new String[] {"app.staff-sign-in-code.webhook-secret", "dev-only-"},
        new String[] {"app.password-changed.webhook-secret", "dev-only-"});

    private static final List<String> MUST_BE_HTTPS =
        List.of("app.security.issuer", "app.portal.url", "app.authentik.public-url");

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
        // "Your password was changed -- if this wasn't you, call ..." (2026-11-06) must name a number to call.
        String supportPhone = settings.apply("app.support-phone");
        if (supportPhone == null || supportPhone.isBlank()) {
            problems.add("app.support-phone is not set (SUPPORT_PHONE: the number the password-changed text gives)");
        }
        // Staff consoles move money and change access: a password alone is not enough in production (2026-11-14).
        if (!"true".equalsIgnoreCase(settings.apply("app.staff-sign-in-code.enabled"))) {
            problems.add("app.staff-sign-in-code.enabled is off (STAFF_SIGN_IN_CODE: staff must get a sign-in code)");
        }
        if ("true".equalsIgnoreCase(settings.apply("communication.sms-gateway.live"))) {
            String authorization = settings.apply("communication.sms-gateway.authorization");
            if (authorization == null || authorization.isBlank()) {
                problems.add("communication.sms-gateway.live is true but communication.sms-gateway.authorization is empty");
            }
        }
        // The reset page and the public quote link (2026-11-02; the reset page since the Authentik cut-over, ticket 10):
        // both check visitors with Turnstile, sign their visit, and work in the insurer's tenant. /reset is always on, so
        // these are needed whether or not the quote link is.
        String secret = settings.apply("app.public-quote.turnstile.secret");
        if (secret == null || secret.isBlank() || secret.startsWith("1x0000") || secret.startsWith("2x0000")
                || secret.startsWith("3x0000")) {
            problems.add("app.public-quote.turnstile.secret is a Cloudflare test secret or empty (TURNSTILE_SECRET: /reset"
                + " and the quote link check visitors with it)");
        }
        if (!"true".equalsIgnoreCase(settings.apply("app.public-quote.turnstile.enabled"))) {
            problems.add("app.public-quote.turnstile.enabled is off (/reset and the quote link must check visitors)");
        }
        String visitSecret = settings.apply("app.public-quote.visit-secret");
        if (visitSecret == null || visitSecret.length() < 32) {
            problems.add("app.public-quote.visit-secret must be at least 32 characters (PUBLIC_VISIT_SECRET)");
        }
        String tenant = settings.apply("app.public-quote.tenant-id");
        if (tenant == null || tenant.isBlank() || tenant.startsWith("11111111-")) {
            problems.add("app.public-quote.tenant-id must name this deployment's insurer (PUBLIC_QUOTE_TENANT_ID)");
        }
        // Authentik's issuer ends in a slash, and a token's iss must match it exactly (ticket 10).
        String issuer = settings.apply("app.security.issuer");
        if (issuer != null && issuer.startsWith("https://") && !issuer.endsWith("/")) {
            problems.add("app.security.issuer must end with a slash (AUTHENTIK_ISSUER: Authentik's issuer is"
                + " https://<auth domain>/application/o/lifeplatform/)");
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

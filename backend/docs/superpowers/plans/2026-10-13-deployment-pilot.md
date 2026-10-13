# Deployment Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the platform on one internet-facing VPS for a pilot: Caddy + TLS, Keycloak 26 configured as code, Flyway migrations, SMS portal invites, a release script, backups and a restore path.

**Architecture:** One Docker Compose stack (`deploy/server/compose.yml`) on the server; four images built on the developer's machine and pushed to ghcr.io; `deploy/release.sh` packs the server files, SSHes in and runs `remote-release.sh`, which backs up, migrates, configures Keycloak, restarts and smoke-checks. Every hostname and name is a setting in the server's `.env`.

**Tech Stack:** Spring Boot 3.3.5 / Java 21, React + Vite, Keycloak 26, keycloak-config-cli, Flyway CLI (Docker), Caddy 2, PostgreSQL 16 + pg_partman + pg_cron, MinIO, rclone, Bash.

Spec: `backend/docs/superpowers/specs/2026-10-13-deployment-pilot-design.md`.

## Global Constraints

- Hostnames are ONLY ever `${APP_DOMAIN}` / `${AUTH_DOMAIN}` (Caddy `{$APP_DOMAIN}`, keycloak-config-cli `$(env:APP_DOMAIN)`). Never write `app.` or `auth.` into code, config or scripts; `app.nlolo.co.tz` / `auth.nlolo.co.tz` appear only in docs as examples.
- Every third-party image is pinned to an exact tag in `deploy/server/versions.env` (Task 0); no `:latest` anywhere under `deploy/`.
- Exactly one backend container.
- Backend container time zone `Africa/Dar_es_Salaam`.
- The one-time portal password never reaches the database: the message log stores `••••••••` in its place.
- Dev workflow keeps working unchanged: `infra/docker-compose.yml` dev stack, dev realm JSON import, realm picker in dev.
- Edit files with Write/Edit tools, never shell heredocs (project rule). Never run Prettier. Never run Maven inside Docker for tests (Testcontainers breaks); the backend IMAGE build uses Maven inside Docker with `-DskipTests`, which is fine.
- Stop the dev backend before running backend tests (`target/` is shared).
- Backend tests: run the affected classes only (`./mvnw -B -o test -Dtest=A,B -Dsurefire.failIfNoSpecifiedTests=false`), using `JAVA_HOME=/c/Users/USER/.vscode/extensions/redhat.java-1.56.0-win32-x64/jre/21.0.12.1-win32-x86_64`. After any signature change run `clean test-compile` first.
- Commit after every task; messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Work on a branch `deployment-pilot` (worktree under `.worktrees/deployment-pilot`).

**Refinement over the spec (agreed in the spirit of §3):** instead of hand-maintained production realm files plus a parity test, production realms are GENERATED from the dev realm files by `deploy/keycloak/build-realms.mjs`, with a `node --test` suite asserting the production shape. They cannot drift because there is only one source.

## File Structure

```
backend/
  .dockerignore                                  NEW  keeps target/, logs, .env out of the image
  infra/app/Dockerfile                           MOD  tz, heap, healthcheck, extra-CA entrypoint
  infra/app/docker-entrypoint.sh                 NEW  imports an optional extra CA, then runs the jar
  infra/postgres/init/02-keycloak-owner.sh       NEW  keycloak DB gets its own login when KEYCLOAK_DB_PASSWORD is set
  infra/docker-compose.yml                       MOD  dev Keycloak 24 -> 26
  scripts/migrate.sh                             REWRITE  Flyway, per-module history tables, --baseline
  scripts/configure-db.sh                        MOD  adds `server` mode
  src/main/resources/application.yml             MOD  email switch, mail health follows it
  src/main/resources/application-prod.yml        NEW  the prod profile
  src/main/java/.../ProductionSettingsGuard.java NEW  refuses to boot on dev placeholders
  src/main/java/.../communication/infrastructure/EmailSenderAdapter.java   MOD  honours the email switch
  src/main/java/.../communication/api/NotificationApi.java                 MOD  sendUnloggedSms
  src/main/java/.../communication/application/NotificationApiImpl.java     MOD  sendUnloggedSms
  src/main/java/.../omnichannel/domain/PortalAccess.java                   MOD  Delivery.SMS_PASSWORD
  src/main/java/.../omnichannel/api/PortalInvite.java                      MOD  smsSentTo, notice
  src/main/java/.../omnichannel/application/PortalAccessService.java       MOD  channel rule + SMS
  db-migrations/omnichannel/V2__sms_password_delivery.sql                  NEW
  api/openapi/openapi-omnichannel.yaml                                     MOD
  src/test/...                                    tests per task
frontend/
  src/App.tsx                                    MOD  picker switch + per-realm fallback
  src/entryPath.ts (+ test)                      NEW
  src/features/party/PortalAccessPanel.tsx (+ test)  MOD
deploy/
  server/versions.env                            NEW  every pinned third-party tag
  server/.env.example                            NEW  every setting, documented
  server/compose.yml                             NEW
  server/remote-release.sh                       NEW  runs ON the server
  server/smoke.sh, backup.sh, restore.sh, status.sh, add-user.sh   NEW
  web/Dockerfile, web/Caddyfile                  NEW
  auth/Dockerfile                                NEW
  keycloak/build-realms.mjs (+ .test.mjs)        NEW
  release.sh                                     NEW  runs on the developer's machine
  release.env.example                            NEW  local, non-secret release settings
  bootstrap-server.sh                            NEW  one-time, as root on a fresh Ubuntu 24.04
  README.md                                      NEW  the runbook
.dockerignore                                    NEW  (repo root, for the web image context)
.github/workflows/ci-cd.yml                      MOD  migrations via migrate.sh ci, twice
backend/docs/07-infrastructure-architecture.md   MOD
```

---

## Stage 0 — Pins

### Task 0: Resolve and pin every third-party image

**Files:**
- Create: `deploy/server/versions.env`

- [ ] **Step 1: Write the pin file**

```bash
# Every third-party image the server runs, pinned. Read by compose (--env-file) and remote-release.sh.
# Change a version here, re-run the release; nothing else names these tags.
KEYCLOAK_VERSION=26.0.7
KC_CONFIG_CLI_VERSION=6.2.1-26.0.5
FLYWAY_VERSION=10.20.1
CADDY_VERSION=2.8.4-alpine
MINIO_VERSION=RELEASE.2024-12-18T13-15-44Z
MC_VERSION=RELEASE.2024-11-21T17-21-54Z
WIREMOCK_VERSION=3.5.4
RCLONE_VERSION=1.68.2
NODE_VERSION=22.12.0-alpine
TEMURIN_VERSION=21-jre-alpine
```

- [ ] **Step 2: Verify each tag exists; bump to the newest patch of the same line where one exists**

Run (one call):
```bash
for i in quay.io/keycloak/keycloak:26.0.7 adorsys/keycloak-config-cli:6.2.1-26.0.5 flyway/flyway:10.20.1 caddy:2.8.4-alpine minio/minio:RELEASE.2024-12-18T13-15-44Z minio/mc:RELEASE.2024-11-21T17-21-54Z wiremock/wiremock:3.5.4 rclone/rclone:1.68.2 node:22.12.0-alpine; do docker manifest inspect "$i" >/dev/null 2>&1 && echo "OK  $i" || echo "MISSING $i"; done
```
Expected: every line `OK`. For any `MISSING`, look up the registry's tag list (quay.io / hub.docker.com), choose the nearest existing tag on the same line (Keycloak: newest `26.x.y`; keycloak-config-cli: the tag whose suffix matches the chosen Keycloak minor), and update both `versions.env` and the loop until all are `OK`. If Keycloak moves to a newer 26.x, keep `KC_CONFIG_CLI_VERSION`'s `-26.x` suffix matching it.

- [ ] **Step 3: Commit**

```bash
git add deploy/server/versions.env
git commit -m "chore(deploy): pin every third-party image the server runs"
```

---

## Stage 1 — Backend production settings

### Task 1: The `prod` profile and the startup guard

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuard.java`
- Create: `backend/src/main/resources/application-prod.yml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuardTest.java`

**Interfaces:**
- Produces: `ProductionSettingsGuard.problems(Function<String,String> settings): List<String>` (package-private static). Active only under profile `prod`.

- [ ] **Step 1: Write the failing test**

```java
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
        s.put("minio.secret-key", "Lk2-real-minio-secret");
        s.put("app.portal.keycloak.client-secret", "Pt7-real-portal-secret");
        s.put("mobile-money.callback-hmac-secret", "Hm4-real-hmac-secret");
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
        assertThat(ProductionSettingsGuard.problems(s::get)).containsExactlyInAnyOrder(
            "spring.datasource.password still holds the dev value",
            "minio.secret-key still holds the dev value",
            "app.portal.keycloak.client-secret still holds the dev value",
            "mobile-money.callback-hmac-secret still holds the dev value");
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
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -B -o test -Dtest=ProductionSettingsGuardTest -Dsurefire.failIfNoSpecifiedTests=false` (from `backend/`)
Expected: compilation failure — `ProductionSettingsGuard` does not exist.

- [ ] **Step 3: Implement the guard**

```java
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
```

Note: `dev-secret-` is matched with `startsWith`, so `dev-secret-portal-admin` and any other `dev-secret-*` are caught; the exact-value entries are matched the same way, which is harmless (a real password never starts with `minioadmin`).

- [ ] **Step 4: Write `application-prod.yml`**

```yaml
# The prod profile (2026-10-13, deployment pilot design §5). Everything here comes from the server's .env through
# deploy/server/compose.yml; ProductionSettingsGuard refuses to start while a secret still holds its dev default.
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME:app_role}
    password: ${SPRING_DATASOURCE_PASSWORD}
  lifecycle:
    timeout-per-shutdown-phase: 30s
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: ${MAIL_SMTP_AUTH:true}
      mail.smtp.starttls.enable: ${MAIL_STARTTLS:true}

server:
  port: 8080
  shutdown: graceful
  # Behind Caddy: honour X-Forwarded-* so anything that builds an absolute URL builds the public one.
  forward-headers-strategy: framework

communication:
  email:
    # Off until a mail provider is configured (the user's D4): email notifications are recorded FAILED with a
    # reason instead of trying a server that is not there.
    enabled: ${MAIL_ENABLED:false}

management:
  health:
    mail:
      enabled: ${MAIL_ENABLED:false}
```

- [ ] **Step 5: Run the test to see it pass**

Run: `./mvnw -B -o test -Dtest=ProductionSettingsGuardTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuard.java backend/src/main/resources/application-prod.yml backend/src/test/java/tz/co/nlolo/lifeplatform/ProductionSettingsGuardTest.java
git commit -m "feat(deploy): a prod profile that refuses to boot on dev secrets"
```

### Task 2: The email switch, and mail out of health

**Files:**
- Modify: `backend/src/main/resources/application.yml` (the `communication:` block, the `management:` block)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/communication/infrastructure/EmailSenderAdapter.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/communication/infrastructure/EmailSenderAdapterSwitchTest.java`

**Interfaces:**
- Produces: property `communication.email.enabled` (default `true` in application.yml so dev/tests keep sending to Mailpit; `false` by default under `prod`, Task 1). `management.health.mail.enabled` follows `MAIL_ENABLED`.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.communication.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Email off (the pilot, before a provider exists) is a recorded outcome, not an attempt (2026-10-13). */
class EmailSenderAdapterSwitchTest {

    @Test
    void switchedOffItTriesNothingAndSaysWhy() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", false);

        var result = adapter.send("client@example.tz", "Your cover has started.");

        assertThat(result.sent()).isFalse();
        assertThat(result.detail()).isEqualTo("Email is switched off (communication.email.enabled=false)");
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void switchedOnItSends() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", true);

        assertThat(adapter.send("client@example.tz", "Your cover has started.").sent()).isTrue();
        verify(mail).send(any(SimpleMailMessage.class));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -B -o test -Dtest=EmailSenderAdapterSwitchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — no 4-argument constructor.

- [ ] **Step 3: Implement the switch in `EmailSenderAdapter`**

Replace the fields/constructor block and the start of `send`:

```java
    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;
    private final String fromAddress;
    private final boolean enabled;

    public EmailSenderAdapter(JavaMailSender mailSender, MeterRegistry meterRegistry,
                               @Value("${communication.from-address}") String fromAddress,
                               @Value("${communication.email.enabled:true}") boolean enabled) {
        this.mailSender = mailSender;
        this.meterRegistry = meterRegistry;
        this.fromAddress = fromAddress;
        this.enabled = enabled;
    }
```

and as the first statements of `send(...)`:

```java
        if (!enabled) {
            // Mirrors SmsGatewayAdapter's live=false: recorded as a real outcome, so the outbox never claims a
            // customer was emailed while no mail server is configured (2026-10-13, deployment pilot).
            meterRegistry.counter(COUNTER, "status", "SUPPRESSED").increment();
            return SendResult.failed("Email is switched off (communication.email.enabled=false)");
        }
```

- [ ] **Step 4: Add the settings to `application.yml`**

In the `communication:` block, after `from-address: ...`, add:

```yaml
  email:
    # The email channel's on/off switch (2026-10-13). On here so local dev keeps sending to Mailpit; the prod
    # profile defaults it OFF until a mail provider exists.
    enabled: ${MAIL_ENABLED:true}
```

In the `management:` block, add as a sibling of `server:` / `endpoints:` / `endpoint:`:

```yaml
  health:
    mail:
      # With email off there is no mail server to probe; with it on, a mail outage still must not make the
      # platform report itself down to a load balancer (2026-10-13).
      enabled: ${MAIL_ENABLED:true}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -B -o clean test-compile` then `./mvnw -B -o test -Dtest=EmailSenderAdapterSwitchTest,ActuatorExposureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both classes pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/application.yml backend/src/main/java/tz/co/nlolo/lifeplatform/communication/infrastructure/EmailSenderAdapter.java backend/src/test/java/tz/co/nlolo/lifeplatform/communication/infrastructure/EmailSenderAdapterSwitchTest.java
git commit -m "feat(communication): an email on/off switch; mail health follows it"
```

### Task 3: The backend image

**Files:**
- Create: `backend/.dockerignore`
- Create: `backend/infra/app/docker-entrypoint.sh`
- Modify: `backend/infra/app/Dockerfile` (full replacement)
- Create: `backend/infra/postgres/init/02-keycloak-owner.sh`

- [ ] **Step 1: `.dockerignore`**

```
target/
**/*.log
hs_err_pid*.log
replay_pid*.log
.env
docs/
observability/
```

- [ ] **Step 2: The entrypoint**

```sh
#!/bin/sh
# Starts the platform. When EXTRA_CA_FILE names a readable certificate (the local dress rehearsal, where Caddy signs
# with its own CA), it is added to a copy of the JVM trust store first, so the backend can fetch Keycloak's keys over
# https. In production the variable is unset and nothing is added.
set -e
if [ -n "${EXTRA_CA_FILE:-}" ]; then
  i=0
  while [ ! -r "$EXTRA_CA_FILE" ] && [ "$i" -lt 30 ]; do i=$((i + 1)); sleep 2; done
  cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts
  keytool -importcert -noprompt -alias extra-ca -file "$EXTRA_CA_FILE" -keystore /tmp/cacerts -storepass changeit
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  export JAVA_TOOL_OPTIONS
fi
exec java -jar /app/app.jar
```

- [ ] **Step 3: The Dockerfile**

```dockerfile
# The platform image (2026-10-13 deployment pilot). One deployable: the Spring Modulith monolith.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
# mvnw may carry CRLF on a Windows checkout (see the autocrlf quirk); normalise before running it.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata && addgroup -S app && adduser -S app -G app
# East Africa Time: development and every test ran in it, and six known sites use the default zone.
ENV TZ=Africa/Dar_es_Salaam \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Africa/Dar_es_Salaam"
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
COPY infra/app/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod +x /app/docker-entrypoint.sh
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD wget -qO- http://localhost:9090/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
```

Before building, check `target/*.jar` matches exactly one jar: `grep -n "<finalName>\|<classifier>" backend/pom.xml`. If the build produces a `-plain.jar` as well, change the COPY to the exact boot jar name.

- [ ] **Step 4: The Keycloak database owner (production only)**

```sh
#!/bin/bash
# Gives Keycloak's database its own login (2026-10-13, deployment pilot §2). Only when KEYCLOAK_DB_PASSWORD is set --
# the dev stack leaves it unset and keeps using postgres. Runs once, on an empty data volume, after 01-*.
set -e
if [ -z "${KEYCLOAK_DB_PASSWORD:-}" ]; then
  echo "02-keycloak-owner: KEYCLOAK_DB_PASSWORD not set, keycloak keeps the postgres login"
  exit 0
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'kc_password';
ALTER DATABASE keycloak OWNER TO keycloak;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname keycloak -c "ALTER SCHEMA public OWNER TO keycloak;"
```

(This file is a shell script run by Postgres's entrypoint, so the heredoc is inside the script's own content — write the file with the Write tool.)

- [ ] **Step 5: Build the image locally to prove it**

Run (from `backend/`): `docker build -f infra/app/Dockerfile -t platform-backend:check .`
Expected: `naming to docker.io/library/platform-backend:check` and exit 0. Then `docker run --rm --entrypoint sh platform-backend:check -c 'date; ls /app'` prints an EAT (`EAT`/`+03`) time and `app.jar docker-entrypoint.sh`.

- [ ] **Step 6: Commit**

```bash
git add backend/.dockerignore backend/infra/app/Dockerfile backend/infra/app/docker-entrypoint.sh backend/infra/postgres/init/02-keycloak-owner.sh
git commit -m "build(deploy): backend image in EAT with healthcheck; keycloak gets its own DB login"
```

---

## Stage 2 — SMS portal invites

### Task 4: `NotificationApi.sendUnloggedSms`

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/communication/api/NotificationApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/communication/application/NotificationApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/communication/application/SendUnloggedSmsTest.java`

**Interfaces:**
- Produces: `String NotificationApi.sendUnloggedSms(UUID partyId, String phoneNumber, String templateKey, String body, String loggedBody)` — returns `null` when the gateway accepted it, otherwise the reason. Requires `TenantContext` set.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.communication.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.ProcessedEventRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A message carrying a secret is sent as written and logged as masked (2026-10-13, deployment pilot §6). */
class SendUnloggedSmsTest {

    static class Recording implements NotificationSender {
        final List<String> sent = new ArrayList<>();
        boolean accept = true;
        @Override public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.SMS; }
        @Override public SendResult send(String destination, String body) {
            sent.add(destination + "|" + body);
            return accept ? SendResult.ok() : SendResult.failed("Recipient 255700000001 is not on the SMS testing allowlist");
        }
    }

    private final NotificationDispatchRepository dispatches = mock(NotificationDispatchRepository.class);
    private final Recording sms = new Recording();
    private final NotificationApiImpl api = new NotificationApiImpl(mock(PartyApi.class),
        mock(NotificationTemplateRepository.class), dispatches, mock(ProcessedEventRepository.class), List.of(sms));
    private final UUID party = UUID.randomUUID();

    @BeforeEach void tenant() { TenantContext.set(UUID.randomUUID()); }
    @AfterEach void clear() { TenantContext.clear(); }

    private NotificationDispatch saved() {
        ArgumentCaptor<NotificationDispatch> row = ArgumentCaptor.forClass(NotificationDispatch.class);
        verify(dispatches).save(row.capture());
        return row.getValue();
    }

    @Test
    void theGatewayGetsTheSecretAndTheLogGetsTheMask() {
        String failure = api.sendUnloggedSms(party, "+255700000001", "PORTAL_INVITE",
            "one-time password K7QMx4PAb9cd", "one-time password ••••••••");

        assertThat(failure).isNull();
        assertThat(sms.sent).containsExactly("+255700000001|one-time password K7QMx4PAb9cd");
        NotificationDispatch row = saved();
        assertThat(row.getBody()).isEqualTo("one-time password ••••••••").doesNotContain("K7QMx4PAb9cd");
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getTemplateKey()).isEqualTo("PORTAL_INVITE");
        assertThat(row.getChannel()).isEqualTo("SMS");
        assertThat(row.getEventId()).isNotNull();
    }

    @Test
    void aRefusalIsReturnedAndRecorded() {
        sms.accept = false;

        String failure = api.sendUnloggedSms(party, "+255700000001", "PORTAL_INVITE", "secret", "masked");

        assertThat(failure).isEqualTo("Recipient 255700000001 is not on the SMS testing allowlist");
        NotificationDispatch row = saved();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getBody()).isEqualTo("masked");
    }
}
```

Check before running: `NotificationDispatch` has `getTemplateKey()` and `getChannel()` getters (`grep -n "getTemplateKey\|getChannel" backend/src/main/java/tz/co/nlolo/lifeplatform/communication/domain/NotificationDispatch.java`). Both are used by `NotificationApiImpl.toView`, so they exist.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -B -o test -Dtest=SendUnloggedSmsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `sendUnloggedSms` undefined.

- [ ] **Step 3: Add to `NotificationApi`**

```java
    /**
     * Sends one SMS now, outside the templates, and records it with {@code loggedBody} in place of {@code body} -- for a
     * message carrying a secret the message log must never hold, such as a portal one-time password (2026-10-13).
     * Not deduplicated: every call sends. The caller words the text.
     *
     * @return null when the gateway accepted the message, otherwise why it did not
     */
    String sendUnloggedSms(UUID partyId, String phoneNumber, String templateKey, String body, String loggedBody);
```

- [ ] **Step 4: Implement in `NotificationApiImpl`** (after `notify`)

```java
    @Override
    @Transactional
    public String sendUnloggedSms(UUID partyId, String phoneNumber, String templateKey, String body, String loggedBody) {
        NotificationDispatch dispatch = new NotificationDispatch(TenantContext.get(), partyId, templateKey,
            NotificationChannel.SMS.name(), null);
        // An event id of its own, so the inbox shows it as one message; the text kept is the masked one.
        dispatch.recordBody(UUID.randomUUID(), loggedBody);
        NotificationSender.SendResult result = senderFor(NotificationChannel.SMS)
            .map(sender -> sender.send(phoneNumber, body))
            .orElseGet(() -> NotificationSender.SendResult.failed("No sender configured for SMS"));
        if (result.sent()) {
            dispatch.markSent();
        } else {
            dispatch.markFailed(result.detail());
        }
        dispatchRepository.save(dispatch);
        return result.sent() ? null : result.detail();
    }
```

- [ ] **Step 5: Run the test to see it pass**

Run: `./mvnw -B -o clean test-compile` then `./mvnw -B -o test -Dtest=SendUnloggedSmsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/communication/api/NotificationApi.java backend/src/main/java/tz/co/nlolo/lifeplatform/communication/application/NotificationApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/communication/application/SendUnloggedSmsTest.java
git commit -m "feat(communication): send a secret by SMS and log it masked"
```

### Task 5: Portal invites by SMS (backend)

**Files:**
- Create: `backend/db-migrations/omnichannel/V2__sms_password_delivery.sql`
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/DepositTestMigrations.java:134` (add V2 after V1)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/domain/PortalAccess.java:22`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/api/PortalInvite.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel/application/PortalAccessService.java`
- Modify: `backend/api/openapi/openapi-omnichannel.yaml` (`PortalAccess.delivery`, `PortalInvite`)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/omnichannel/application/PortalDeliveryRuleTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/omnichannel/PortalAccessIntegrationTest.java`

**Interfaces:**
- Consumes: `NotificationApi.sendUnloggedSms` (Task 4); `communication.email.enabled` (Task 2); `app.portal.url`.
- Produces: `PortalAccess.Delivery { EMAIL_LINK, TEMPORARY_PASSWORD, SMS_PASSWORD }`; `PortalInvite(PortalAccessView access, String temporaryPassword, String smsSentTo, String notice)`; `static PortalAccess.Delivery PortalAccessService.deliveryFor(String email, String phone, boolean emailEnabled)`.

- [ ] **Step 1: Write the failing unit test for the channel rule**

```java
package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PortalAccess.Delivery;

import static org.assertj.core.api.Assertions.assertThat;

/** The channel table of the deployment pilot design §6, row by row. */
class PortalDeliveryRuleTest {

    @Test
    void emailOn() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", true)).isEqualTo(Delivery.EMAIL_LINK);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, true)).isEqualTo(Delivery.EMAIL_LINK);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", true)).isEqualTo(Delivery.SMS_PASSWORD);
    }

    @Test
    void emailOff() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, false)).isEqualTo(Delivery.TEMPORARY_PASSWORD);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -B -o test -Dtest=PortalDeliveryRuleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`SMS_PASSWORD`, `deliveryFor` undefined).

- [ ] **Step 3: The migration**

`backend/db-migrations/omnichannel/V2__sms_password_delivery.sql`:

```sql
-- Portal invites by SMS (2026-10-13, deployment pilot §6): a third way the first password reaches a client.
ALTER TABLE omnichannel.portal_access DROP CONSTRAINT portal_access_delivery_check;
ALTER TABLE omnichannel.portal_access ADD CONSTRAINT portal_access_delivery_check
    CHECK (delivery IN ('EMAIL_LINK', 'TEMPORARY_PASSWORD', 'SMS_PASSWORD'));
```

Verify the constraint name first against the dev database: `docker exec infra-postgres-1 psql -U postgres -d lifeplatform -tAc "select conname from pg_constraint where conrelid='omnichannel.portal_access'::regclass and contype='c'"` — expected `portal_access_delivery_check`. If it differs, use the real name. Then in `DepositTestMigrations.java` add `"db-migrations/omnichannel/V2__sms_password_delivery.sql",` on the line after `"db-migrations/omnichannel/V1__portal_access.sql",`.

- [ ] **Step 4: Domain + API record**

`PortalAccess.java` line 22: `public enum Delivery { EMAIL_LINK, TEMPORARY_PASSWORD, SMS_PASSWORD }`

`PortalInvite.java` (whole file):

```java
package tz.co.nlolo.lifeplatform.omnichannel.api;

/**
 * The outcome of inviting (or re-inviting) a client to the portal.
 *
 * @param temporaryPassword the one-time password, shown to the staff member ONCE and never stored: for a client with no
 *     email and no phone that SMS could reach, or when the SMS failed. Null when it went by email link or by SMS.
 * @param smsSentTo the number the one-time password was texted to; null when it was not texted
 * @param notice why the staff member is being shown the password (the SMS was not sent, and the gateway's reason);
 *     null otherwise
 */
public record PortalInvite(PortalAccessView access, String temporaryPassword, String smsSentTo, String notice) {}
```

- [ ] **Step 5: The service**

In `PortalAccessService`:

1. Add imports: `org.springframework.beans.factory.annotation.Value`, `tz.co.nlolo.lifeplatform.communication.api.NotificationApi`.
2. Fields + constructor:

```java
    /** What the message log keeps in place of the password. */
    static final String MASK = "••••••••";

    private final PortalAccessRepository accesses;
    private final CustomerAccounts accounts;
    private final PartyApi partyApi;
    private final PolicyApi policyApi;
    private final NotificationApi notifications;
    private final boolean emailEnabled;
    private final String portalUrl;

    public PortalAccessService(PortalAccessRepository accesses, CustomerAccounts accounts, PartyApi partyApi,
                               PolicyApi policyApi, NotificationApi notifications,
                               @Value("${communication.email.enabled:true}") boolean emailEnabled,
                               @Value("${app.portal.url}") String portalUrl) {
        this.accesses = accesses;
        this.accounts = accounts;
        this.partyApi = partyApi;
        this.policyApi = policyApi;
        this.notifications = notifications;
        this.emailEnabled = emailEnabled;
        this.portalUrl = portalUrl;
    }
```

3. In `invite`, replace the two lines computing `delivery` with `PortalAccess.Delivery delivery = deliveryFor(email, phone, emailEnabled);` and the final `return` with `return firstPassword(access, party);`.
4. Replace `resend`'s body:

```java
        PortalAccess access = existing(partyId);
        if (access.getStatus() == PortalAccess.Status.REVOKED) {
            throw new PortalAccessRefusedException("This client's portal access is revoked: invite them again instead");
        }
        return firstPassword(access, partyApi.getPartyDetail(partyId));
```

5. Replace `deliverFirstPassword` with:

```java
    /** The channel table (deployment pilot §6): email when it is on and the client has one, else SMS, else staff. */
    static PortalAccess.Delivery deliveryFor(String email, String phone, boolean emailEnabled) {
        if (email != null && emailEnabled) return PortalAccess.Delivery.EMAIL_LINK;
        if (phone != null) return PortalAccess.Delivery.SMS_PASSWORD;
        return PortalAccess.Delivery.TEMPORARY_PASSWORD;
    }

    private PortalInvite firstPassword(PortalAccess access, PartyDetailView party) {
        String userId = access.getKeycloakUserId();
        if (access.getDelivery() == PortalAccess.Delivery.EMAIL_LINK) {
            accounts.sendSetPasswordLink(userId);
            return new PortalInvite(view(access), null, null, null);
        }
        String password = temporaryPassword();
        accounts.setTemporaryPassword(userId, password);
        if (access.getDelivery() == PortalAccess.Delivery.TEMPORARY_PASSWORD) {
            return new PortalInvite(view(access), password, null, null);
        }
        String phone = blankToNull(party.phoneNumber());
        String failure = phone == null ? "no phone number on file"
            : notifications.sendUnloggedSms(access.getPartyId(), phone, "PORTAL_INVITE",
                inviteText(access.getUsername(), password), inviteText(access.getUsername(), MASK));
        if (failure == null) {
            return new PortalInvite(view(access), null, phone, null);
        }
        // Onboarding never dead-ends on a failed SMS: the staff member hands the password over, as for a phone-less client.
        return new PortalInvite(view(access), password, null,
            "The SMS was not sent (" + failure + "). Give the client this password yourself.");
    }

    private String inviteText(String username, String password) {
        return "Your portal login: username " + username + ", one-time password " + password + ". Sign in at "
            + portalUrl + " - you will set your own password first.";
    }
```

- [ ] **Step 6: OpenAPI**

In `openapi-omnichannel.yaml`:
- `PortalAccess.delivery`: `enum: [EMAIL_LINK, TEMPORARY_PASSWORD, SMS_PASSWORD, null]`
- `PortalInvite`: `required: [access, temporaryPassword, smsSentTo, notice]` and add under `properties`:

```yaml
        smsSentTo:
          type: [string, "null"]
          description: The number the one-time password was texted to; null when it was not texted.
        notice:
          type: [string, "null"]
          description: Why the password is being shown to staff (the SMS was not sent, with the gateway's reason).
```

and change `temporaryPassword`'s description to: `The one-time password when staff must hand it over (no email and no reachable phone, or the SMS failed); shown once, stored nowhere. Null when emailed or texted.`

- [ ] **Step 7: Update the integration test to the new behaviour**

In `PortalAccessIntegrationTest`:

1. Add a recording SMS sender bean that wins over the real gateway, and expose it:

```java
    static class RecordingSms implements tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender {
        final List<String> sent = new ArrayList<>();
        boolean accept = true;
        @Override public boolean supports(tz.co.nlolo.lifeplatform.communication.api.NotificationChannel channel) {
            return channel == tz.co.nlolo.lifeplatform.communication.api.NotificationChannel.SMS;
        }
        @Override public SendResult send(String destination, String body) {
            sent.add(destination + "|" + body);
            return accept ? SendResult.ok() : SendResult.failed("Recipient is not on the SMS testing allowlist");
        }
    }
```

In `FakeAccounts` add `@Bean @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE) RecordingSms recordingSms() { return new RecordingSms(); }`, autowire it as `@Autowired private RecordingSms sms;`, and in `clearCalls()` also `sms.sent.clear(); sms.accept = true;`.

2. In `aPolicyholderWithOnlyAPhone...`: replace the invite assertions from `.andExpect(jsonPath("$.access.delivery").value("TEMPORARY_PASSWORD"))` through `assertThat(password).hasSize(12)...` with:

```java
            .andExpect(jsonPath("$.access.delivery").value("SMS_PASSWORD"))
            .andExpect(jsonPath("$.access.invitedBy").value("Rose Service"))
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
            .andExpect(jsonPath("$.smsSentTo").value(org.hamcrest.Matchers.startsWith("+25571500")))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        // The client's phone got the password; the message log got the mask.
        assertThat(sms.sent).hasSize(1);
        String password = sms.sent.get(0).replaceAll(".*one-time password (\\S{12})\\..*", "$1");
        assertThat(password).hasSize(12).doesNotContainPattern("[0O1lI]");
        assertThat(jdbc.queryForObject("SELECT body FROM communication.notification_dispatch WHERE party_id = ? "
            + "AND template_key = 'PORTAL_INVITE'", String.class, partyId))
            .contains("••••••••").doesNotContain(password);
```

(`invited` is no longer read for the password; keep the variable or drop the assignment — drop it if the compiler flags it unused.)

3. Replace the resend assertion with:

```java
        mockMvc.perform(staff(post(base + "/resend"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.smsSentTo").isNotEmpty());
        assertThat(sms.sent).hasSize(2);
        assertThat(sms.sent.get(1)).doesNotContain(password);
```

4. Add a test for the failed SMS:

```java
    @Test
    void aFailedSmsShowsStaffThePasswordAndSaysWhy() throws Exception {
        UUID partyId = policyholder();
        sms.accept = false;

        mockMvc.perform(staff(post("/parties/" + partyId + "/portal-access"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.access.delivery").value("SMS_PASSWORD"))
            .andExpect(jsonPath("$.smsSentTo").doesNotExist())
            .andExpect(jsonPath("$.temporaryPassword").value(org.hamcrest.Matchers.hasLength(12)))
            .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("not on the SMS testing allowlist")))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }
```

The email test (`aPolicyholderWithAnEmail...`) stays unchanged: `communication.email.enabled` defaults to true outside `prod`.

- [ ] **Step 8: Run the tests**

Run: `./mvnw -B -o clean test-compile` then `./mvnw -B -o test -Dtest=PortalDeliveryRuleTest,PortalAccessIntegrationTest,CustomerPortalIntegrationTest,ModularityTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass. `ModularityTests` confirms omnichannel → communication::api is allowed (it already is).

- [ ] **Step 9: Apply the migration to the dev database** (dev DB is migrated by hand — Stage 3 has not landed yet)

Run: `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < backend/db-migrations/omnichannel/V2__sms_password_delivery.sql`
Expected: `ALTER TABLE` twice.

- [ ] **Step 10: Commit**

```bash
git add backend/db-migrations/omnichannel/V2__sms_password_delivery.sql backend/src/test/java/tz/co/nlolo/lifeplatform/accumulation/DepositTestMigrations.java backend/src/main/java/tz/co/nlolo/lifeplatform/omnichannel backend/api/openapi/openapi-omnichannel.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/omnichannel
git commit -m "feat(portal): invite by SMS one-time password, masked in the message log"
```

### Task 6: Portal invites by SMS (staff screen)

**Files:**
- Modify: `frontend/src/types/api/omnichannel.ts` (regenerated)
- Modify: `frontend/src/features/party/PortalAccessPanel.tsx`
- Test: `frontend/src/features/party/PortalAccessPanel.test.tsx`

**Interfaces:**
- Consumes: `PortalInvite { access, temporaryPassword, smsSentTo, notice }`, `delivery: 'SMS_PASSWORD'` (Task 5).

- [ ] **Step 1: Regenerate the API types**

Run (from `frontend/`): `npm run generate:api`
Expected: `src/types/api/omnichannel.ts` now has `smsSentTo` and `notice` on `PortalInvite` and `"SMS_PASSWORD"` in the delivery union.

- [ ] **Step 2: Write the failing test** (add to `PortalAccessPanel.test.tsx`, reusing its `access(...)` helper; also add `smsSentTo: null, notice: null` to the existing `invitePortal` mock result so it type-checks)

```tsx
  it('reports an SMS invite without showing any password', async () => {
    vi.mocked(portalApi.getPortalAccess)
      .mockResolvedValueOnce(access({}))
      .mockResolvedValue(access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }));
    vi.mocked(portalApi.invitePortal).mockResolvedValue({
      access: access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }),
      temporaryPassword: null, smsSentTo: '+255715000001', notice: null,
    });
    render(<PortalAccessPanel partyId="p-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('One-time password sent by SMS to +255715000001')).toBeInTheDocument();
    expect(screen.queryByText(/shown once/)).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Send a new one-time password' })).toBeInTheDocument();
  });

  it('shows the password with the reason when the SMS failed', async () => {
    vi.mocked(portalApi.getPortalAccess)
      .mockResolvedValueOnce(access({}))
      .mockResolvedValue(access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }));
    vi.mocked(portalApi.invitePortal).mockResolvedValue({
      access: access({ status: 'INVITED', username: '+255715000001', delivery: 'SMS_PASSWORD' }),
      temporaryPassword: 'Kp7mWx3qRt9a', smsSentTo: null,
      notice: 'The SMS was not sent (gateway down). Give the client this password yourself.',
    });
    render(<PortalAccessPanel partyId="p-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Invite to portal' }));

    expect(await screen.findByText('Kp7mWx3qRt9a')).toBeInTheDocument();
    expect(screen.getByText('The SMS was not sent (gateway down). Give the client this password yourself.')).toBeInTheDocument();
  });
```

Match the file's existing imports (`render`, `screen`, `userEvent`) — if it uses a different render helper, use that one.

- [ ] **Step 3: Run it to see it fail**

Run: `npx vitest run src/features/party/PortalAccessPanel.test.tsx`
Expected: the two new tests fail (text not found).

- [ ] **Step 4: Implement in `PortalAccessPanel.tsx`**

1. Replace `const [password, setPassword] = useState<string | null>(null);` with:

```tsx
  const [outcome, setOutcome] = useState<{ password: string | null; smsSentTo: string | null; notice: string | null } | null>(null);
```

2. In `act`, replace `setPassword(null);` with `setOutcome(null);`, change the work type to `() => Promise<{ temporaryPassword?: string | null; smsSentTo?: string | null; notice?: string | null } | PortalAccessView>`, and replace the `if ('temporaryPassword' in result) ...` line with:

```tsx
      if ('temporaryPassword' in result) {
        setOutcome({ password: result.temporaryPassword ?? null, smsSentTo: result.smsSentTo ?? null, notice: result.notice ?? null });
      }
```

3. Add above the component:

```tsx
const DELIVERY_LABEL: Record<string, string> = {
  EMAIL_LINK: 'Emailed as a set-your-password link',
  SMS_PASSWORD: 'One-time password sent by SMS',
  TEMPORARY_PASSWORD: 'One-time password given by staff',
};
```

and use it: `<Field label="First password" value={DELIVERY_LABEL[access.delivery] ?? access.delivery} />`.

4. Replace the `{password && ( ... )}` block with:

```tsx
      {outcome?.smsSentTo && (
        <p role="status" className="text-sm">One-time password sent by SMS to {outcome.smsSentTo}</p>
      )}
      {outcome?.password && (
        <div role="status" className="rounded-md border border-status-warning-fg/40 bg-status-warning-bg p-3 text-sm">
          <p className="font-medium">One-time password — shown once</p>
          <p className="mt-1 font-mono text-base tracking-wider">{outcome.password}</p>
          {outcome.notice && <p className="mt-1">{outcome.notice}</p>}
          <p className="mt-1 text-xs text-muted-foreground">
            Give it to the client in person or by phone. They sign in as {access.username} and must choose their own
            password straight away. It is not kept anywhere: if it is lost, re-send to make a new one.
          </p>
        </div>
      )}
```

5. The re-send button label: `{access.delivery === 'EMAIL_LINK' ? 'Re-send the link' : access.delivery === 'SMS_PASSWORD' ? 'Send a new one-time password' : 'New one-time password'}`.

6. Update the component's doc comment: "...without one, a one-time password goes by SMS; if the SMS fails, or the client has no phone, it is shown here ONCE...".

- [ ] **Step 5: Run the tests, typecheck and lint**

Run (from `frontend/`): `npx vitest run src/features/party/PortalAccessPanel.test.tsx; npm run typecheck; npm run lint`
Expected: all tests pass, no type or lint errors. (No e2e spec exercises the portal invite — `grep -rln "Invite to portal" frontend/e2e` returns nothing — so no e2e change.)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/api/omnichannel.ts frontend/src/features/party/PortalAccessPanel.tsx frontend/src/features/party/PortalAccessPanel.test.tsx
git commit -m "feat(portal): the staff screen reports an SMS invite instead of a password"
```

---

## Stage 3 — Flyway migrations

### Task 7: `migrate.sh` on Flyway

**Files:**
- Modify: `backend/scripts/migrate.sh` (full replacement; keep the `MODULES="..."` line byte-for-byte — `MigrationScriptCoverageTest` parses it)
- Modify: `backend/scripts/configure-db.sh` (add `server` mode)

**Interfaces:**
- Produces: `scripts/migrate.sh <local|ci|server|staging|production> [--baseline]`; prints a final line `Applied N migrations across 23 modules against <env>.` (CI greps it). `scripts/configure-db.sh server`.

- [ ] **Step 1: Write `migrate.sh`**

```bash
#!/usr/bin/env bash
# Applies every module's migrations with Flyway (2026-10-13, deployment pilot §4). One history table per module,
# public.flyway_history_<module>, so module version numbers never collide; modules run in the order below. Safe to
# re-run: only migrations not yet applied run, and an applied migration whose file was edited since stops the run
# (checksum mismatch). Runs the Flyway CLI image, so no local Flyway install is needed.
#
# Usage: scripts/migrate.sh <local|ci|server|staging|production> [--baseline]
#   local       the dev stack (infra/docker-compose.yml, network infra_default), as postgres
#   ci          a Postgres on localhost:5432, database postgres (the CI service container), PGPASSWORD
#   server      the deploy/server/compose.yml stack, run from the server's platform directory (reads .env)
#   staging|production  MIGRATE_JDBC_URL, MIGRATE_USER, MIGRATE_PASSWORD from the environment
#   --baseline  ONCE, on a database migrated before Flyway (the dev database): records each module's existing
#               migrations as applied without running them. Flyway refuses where a history table already has rows.
set -euo pipefail
cd "$(dirname "$0")/.."

ENVIRONMENT="${1:?Usage: scripts/migrate.sh <local|ci|server|staging|production> [--baseline]}"
BASELINE=0
[ "${2:-}" = "--baseline" ] && BASELINE=1

FLYWAY_IMAGE="flyway/flyway:${FLYWAY_VERSION:-10.20.1}"

case "$ENVIRONMENT" in
  local)
    JDBC_URL="jdbc:postgresql://postgres:5432/lifeplatform"; DB_USER=postgres
    DB_PASSWORD="${POSTGRES_PASSWORD:-devpassword}"; NETWORK=infra_default ;;
  ci)
    JDBC_URL="jdbc:postgresql://localhost:5432/postgres"; DB_USER=postgres
    DB_PASSWORD="${PGPASSWORD:?PGPASSWORD is not set}"; NETWORK=host ;;
  server)
    set -a; . ./.env; set +a
    [ -f versions.env ] && { set -a; . ./versions.env; set +a; FLYWAY_IMAGE="flyway/flyway:${FLYWAY_VERSION}"; }
    JDBC_URL="jdbc:postgresql://postgres:5432/lifeplatform"; DB_USER=postgres
    DB_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is not set in .env}"; NETWORK=platform_default ;;
  staging|production)
    JDBC_URL="${MIGRATE_JDBC_URL:?MIGRATE_JDBC_URL is not set}"; DB_USER="${MIGRATE_USER:?MIGRATE_USER is not set}"
    DB_PASSWORD="${MIGRATE_PASSWORD:?MIGRATE_PASSWORD is not set}"; NETWORK=host ;;
  *)
    echo "Unknown environment: $ENVIRONMENT (expected local, ci, server, staging or production)" >&2; exit 1 ;;
esac

# Git Bash on Windows rewrites /flyway/... arguments and needs a Windows path for the volume.
export MSYS_NO_PATHCONV=1
MOUNT="$(pwd -W 2>/dev/null || pwd)/db-migrations"

flyway() {
  local module="$1"; shift
  docker run --rm --network "$NETWORK" -v "$MOUNT:/flyway/sql:ro" "$FLYWAY_IMAGE" \
    -url="$JDBC_URL" -user="$DB_USER" -password="$DB_PASSWORD" \
    -locations="filesystem:/flyway/sql/$module" -table="flyway_history_$module" \
    -schemas=public -createSchemas=false -placeholderReplacement=false -outputType=json "$@"
}

# Every folder under db-migrations/ except _post-migration (configure-db.sh's). MigrationScriptCoverageTest fails the
# build when a folder is not listed here.
MODULES="party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata benefitpayout accumulation bonus annuity unitlinked omnichannel reporting"

applied=0
for mod in $MODULES; do
  if [ "$BASELINE" = "1" ]; then
    latest=$(ls "db-migrations/$mod"/V*.sql | sed -E 's#.*/V([0-9_.]+)__.*#\1#' | tr _ . | sort -V | tail -1)
    echo "Baselining $mod at $latest"
    flyway "$mod" baseline -baselineVersion="$latest" -baselineDescription="migrated by hand before Flyway" >/dev/null
    continue
  fi
  echo "Migrating $mod"
  out=$(flyway "$mod" migrate) || { echo "$out" >&2; echo "Migration of $mod FAILED" >&2; exit 1; }
  n=$(printf '%s' "$out" | grep -o '"migrationsExecuted": *[0-9]*' | grep -o '[0-9]*$' || echo 0)
  applied=$((applied + ${n:-0}))
done

if [ "$BASELINE" = "1" ]; then
  echo "Baselined $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
else
  echo "Applied $applied migrations across $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
fi
```

- [ ] **Step 2: `configure-db.sh` server mode**

In its `case`, add before `*)`:

```bash
  server)     LOCAL_MODE=2; DB_URL="" ;;
```

and in `apply()`, add a branch before the `else`:

```bash
  elif [ "$LOCAL_MODE" = "2" ]; then
    docker compose --env-file .env --env-file versions.env -f compose.yml exec -T postgres \
      psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < "$script"
```

(the existing `if` becomes `if ...; then ... elif ...; then ... else ... fi`). Update the usage text to `<local|server|staging|production>`. Note `configure-db.sh` does `cd "$(dirname "$0")/.."`, so on the server it runs from the platform directory where `compose.yml`, `.env` and `db-migrations/` sit.

- [ ] **Step 3: Prove it on a throwaway database** (the dev DB is untouched)

```bash
docker exec infra-postgres-1 psql -U postgres -c "DROP DATABASE IF EXISTS migrate_check" -c "CREATE DATABASE migrate_check"
```

Make a copy of the script that targets `migrate_check` (the copy must sit in `scripts/` because the script `cd`s relative to itself), run it twice, delete it — from `backend/`:

```bash
sed 's#5432/lifeplatform"; DB_USER=postgres#5432/migrate_check"; DB_USER=postgres#' scripts/migrate.sh > scripts/.migrate-check.sh
bash scripts/.migrate-check.sh local | tail -1
bash scripts/.migrate-check.sh local | tail -1
rm scripts/.migrate-check.sh
```

Expected: the first run prints `Applied N migrations across 23 modules against local.` where N equals `find db-migrations -name 'V*.sql' -not -path '*_post-migration*' | wc -l` (246 after Task 5); the second prints `Applied 0 migrations across 23 modules against local.`
Then `docker exec infra-postgres-1 psql -U postgres -c "DROP DATABASE migrate_check"`.

- [ ] **Step 4: Run the coverage guard**

Run: `./mvnw -B -o test -Dtest=MigrationScriptCoverageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: pass.

- [ ] **Step 5: Baseline the dev database (opt-in, once)**

Ask the user before running. Then: `docker exec infra-postgres-1 pg_dump -U postgres -Fc lifeplatform > ../lifeplatform-before-baseline.dump` (from `backend/`), then `scripts/migrate.sh local --baseline`, then `scripts/migrate.sh local` → expected `Applied 0 migrations`. If any module reports applied migrations, those were never hand-applied to dev — report them to the user rather than ignoring.

- [ ] **Step 6: Commit**

```bash
git add backend/scripts/migrate.sh backend/scripts/configure-db.sh
git commit -m "feat(deploy): migrations through Flyway, one history table per module, safe to re-run"
```

### Task 8: CI runs the real migration script, twice

**Files:**
- Modify: `.github/workflows/ci-cd.yml` (job `db-migration-validation`, step "Run every module's Flyway migration in sequence"; jobs `deploy-staging` / `deploy-production` migration steps)

- [ ] **Step 1: Replace the psql loop step**

```yaml
      - name: Migrate a fresh database with scripts/migrate.sh -- twice; the second run must apply nothing
        env:
          PGPASSWORD: ci_password
        run: |
          set -e
          ./scripts/migrate.sh ci | tee first.log
          ./scripts/migrate.sh ci | tee second.log
          grep -q "^Applied 0 migrations across" second.log || { echo "::error::a second migrate run applied migrations -- not idempotent"; exit 1; }
```

- [ ] **Step 2: Deploy jobs**

In `deploy-staging` and `deploy-production`, change the migration steps' `env:` to `MIGRATE_JDBC_URL: ${{ secrets.STAGING_MIGRATE_JDBC_URL }}` / `MIGRATE_USER: ${{ secrets.STAGING_MIGRATE_USER }}` / `MIGRATE_PASSWORD: ${{ secrets.STAGING_MIGRATE_PASSWORD }}` (PRODUCTION_ equivalents), and rename the step "Run Flyway migrations (scripts/migrate.sh)". Leave `deploy.sh` steps alone; the pilot deploys with `deploy/release.sh` (spec §10: CI deploys later).

- [ ] **Step 3: Validate the YAML**

Run: `node -e "require('fs').readFileSync('.github/workflows/ci-cd.yml','utf8')" && npx --yes yaml-lint .github/workflows/ci-cd.yml` (or `python -c "import yaml,sys;yaml.safe_load(open('.github/workflows/ci-cd.yml'))"`)
Expected: no error.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci-cd.yml
git commit -m "ci: migrate with the real script, twice, and fail if the second run applies anything"
```

---

## Stage 4 — Keycloak 26 and realms as code

### Task 9: Dev Keycloak 24 → 26

**Files:**
- Modify: `backend/infra/docker-compose.yml` (service `keycloak`)

- [ ] **Step 1: Back up the dev Keycloak database**

Run: `docker exec infra-postgres-1 pg_dump -U postgres -Fc keycloak > keycloak-kc24.dump` (from `backend/`; the file is gitignored-by-location — do not commit it; delete after the task).

- [ ] **Step 2: Change the service**

```yaml
  keycloak:
    image: quay.io/keycloak/keycloak:26.0.7
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-devadmin}
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: postgres
      KC_DB_PASSWORD: ${POSTGRES_PASSWORD:-devpassword}
```

(use the same tag as `KEYCLOAK_VERSION` in `deploy/server/versions.env`; keep `command`, `ports`, `volumes`, `depends_on` as they are).

- [ ] **Step 3: Start it and watch the database migrate**

Run: `cd backend/infra && docker compose up -d keycloak && docker compose logs -f keycloak` until `Listening on: http://0.0.0.0:8080` (Ctrl-C the follow; or poll `docker compose logs keycloak | grep -m1 "Listening on"`).
Expected: Liquibase update lines, no `ERROR`. The existing realms are kept (import skips existing realms).

- [ ] **Step 4: Prove logins and the theme still work**

Run (from `frontend/`, dev backend and Vite running): `npx playwright test --project=setup` (the auth setup projects log in through the custom theme with `#username`/`#password`/`#kc-login`).
Expected: all setup projects pass. Then open `http://localhost:8081/realms/staff/account` once by hand and confirm the green "Split leaf" login theme renders. If an FTL template errors on 26 (log shows `FreeMarker template error`), fix the named template variable in `backend/keycloak/themes/lifeplatform/login/*.ftl` against the 26 base theme and re-run.

- [ ] **Step 5: Commit** (and delete `keycloak-kc24.dump` after confirming)

```bash
git add backend/infra/docker-compose.yml backend/keycloak/themes
git commit -m "chore(dev): Keycloak 26, the version production runs"
```

### Task 10: Production realms generated from the dev realms

**Files:**
- Create: `deploy/keycloak/build-realms.mjs`
- Test: `deploy/keycloak/build-realms.test.mjs`

**Interfaces:**
- Produces: `node deploy/keycloak/build-realms.mjs <outDir>` writes `staff.json`, `agents.json`, `customers.json`, `regulators.json` with keycloak-config-cli `$(env:NAME)` tokens. Exported `buildRealm(devRealm, staffSpaClient)` for the test.

- [ ] **Step 1: Write the failing test**

```js
// The production realms (2026-10-13, deployment pilot §3), checked against the shape the design promised.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildAll } from './build-realms.mjs';

const out = mkdtempSync(join(tmpdir(), 'realms-'));
buildAll(out);
const realm = (name) => JSON.parse(readFileSync(join(out, `${name}.json`), 'utf8'));
const REALMS = ['staff', 'agents', 'customers', 'regulators'];

test('no dev users and no dev secrets anywhere', () => {
  for (const name of REALMS) {
    const text = readFileSync(join(out, `${name}.json`), 'utf8');
    assert.doesNotMatch(text, /devpassword|dev-secret|localhost|mailpit/, name);
    const humans = (realm(name).users ?? []).filter((u) => !u.username.startsWith('service-account-'));
    assert.deepEqual(humans, [], name);
  }
});

test('every realm has the SPA client on APP_DOMAIN only, and no password-grant client', () => {
  for (const name of REALMS) {
    const clients = realm(name).clients;
    assert.equal(clients.find((c) => c.clientId === 'lifeplatform-app'), undefined, name);
    const spa = clients.find((c) => c.clientId === 'lifeplatform-spa');
    assert.ok(spa, `${name} has lifeplatform-spa`);
    assert.deepEqual(spa.redirectUris, ['https://$(env:APP_DOMAIN)/*'], name);
    assert.deepEqual(spa.webOrigins, ['https://$(env:APP_DOMAIN)'], name);
    assert.equal(spa.publicClient, true, name);
    assert.ok(spa.protocolMappers.some((m) => m.name === 'tenant_id'), `${name} maps tenant_id`);
  }
});

test('regulators gain the client they lacked, mapping tenant_id only', () => {
  const spa = realm('regulators').clients.find((c) => c.clientId === 'lifeplatform-spa');
  assert.deepEqual(spa.protocolMappers.map((m) => m.name), ['tenant_id']);
});

test('the portal admin client takes its secret from the environment', () => {
  const admin = realm('customers').clients.find((c) => c.clientId === 'lifeplatform-admin');
  assert.equal(admin.secret, '$(env:PORTAL_ADMIN_CLIENT_SECRET)');
  assert.ok(realm('customers').users.some((u) => u.username === 'service-account-lifeplatform-admin'));
});

test('password policy, lockout and SMTP settings on every realm', () => {
  for (const name of REALMS) {
    const r = realm(name);
    assert.equal(r.passwordPolicy, 'length(10) and notUsername(undefined) and passwordHistory(3)', name);
    assert.equal(r.bruteForceProtected, true, name);
    assert.equal(r.sslRequired, 'external', name);
    assert.equal(r.resetPasswordAllowed, '$(env:MAIL_ENABLED)', name);
    assert.equal(r.smtpServer.host, '$(env:MAIL_HOST)', name);
  }
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `node --test deploy/keycloak/build-realms.test.mjs`
Expected: fails — `build-realms.mjs` not found.

- [ ] **Step 3: Implement the generator**

```js
#!/usr/bin/env node
/**
 * Generates the production realm files from the dev ones (2026-10-13, deployment pilot §3). One source, so the two
 * cannot drift: roles, clients and token mappers come from backend/keycloak/*-realm.json; what production changes is
 * applied here. Output carries keycloak-config-cli `$(env:NAME)` tokens, substituted on the server from its .env.
 *
 * Usage: node deploy/keycloak/build-realms.mjs <outDir>
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const DEV = resolve(here, '../../backend/keycloak');
const REALMS = ['staff', 'agents', 'customers', 'regulators'];
const APP = 'https://$(env:APP_DOMAIN)';

const dev = (name) => JSON.parse(readFileSync(join(DEV, `${name}-realm.json`), 'utf8'));

function productionSpa(client) {
  const spa = structuredClone(client);
  spa.redirectUris = [`${APP}/*`];
  spa.webOrigins = [APP];
  spa.attributes = { ...(spa.attributes ?? {}), 'post.logout.redirect.uris': `${APP}/*` };
  return spa;
}

export function buildRealm(realm, staffSpa) {
  const r = structuredClone(realm);
  r.users = (r.users ?? []).filter((u) => u.username.startsWith('service-account-'));
  r.clients = (r.clients ?? []).filter((c) => c.clientId !== 'lifeplatform-app');
  delete r.roles?.client?.['lifeplatform-app'];
  if (r.clientScopeMappings) delete r.clientScopeMappings['lifeplatform-app'];
  if (r.scopeMappings) r.scopeMappings = r.scopeMappings.filter((m) => m.client !== 'lifeplatform-app');

  let spa = r.clients.find((c) => c.clientId === 'lifeplatform-spa');
  if (!spa) {
    // The regulators realm never had the console's client, so regulator login could not work (2026-10-13).
    spa = structuredClone(staffSpa);
    delete spa.id;
    spa.protocolMappers = (spa.protocolMappers ?? []).filter((m) => m.name === 'tenant_id').map((m) => {
      const copy = structuredClone(m);
      delete copy.id;
      return copy;
    });
    r.clients.push(spa);
  }
  r.clients = r.clients.map((c) => (c.clientId === 'lifeplatform-spa' ? productionSpa(c) : c));
  for (const c of r.clients) {
    if (c.clientId === 'lifeplatform-admin') c.secret = '$(env:PORTAL_ADMIN_CLIENT_SECRET)';
  }

  r.sslRequired = 'external';
  r.passwordPolicy = 'length(10) and notUsername(undefined) and passwordHistory(3)';
  r.bruteForceProtected = true;
  r.permanentLockout = false;
  r.failureFactor = 5;
  r.waitIncrementSeconds = 60;
  r.maxFailureWaitSeconds = 900;
  r.resetPasswordAllowed = '$(env:MAIL_ENABLED)';
  r.smtpServer = {
    host: '$(env:MAIL_HOST)', port: '$(env:MAIL_PORT)', from: '$(env:MAIL_FROM)', fromDisplayName: '$(env:MAIL_FROM_NAME)',
    auth: 'true', user: '$(env:MAIL_USERNAME)', password: '$(env:MAIL_PASSWORD)', starttls: 'true',
  };
  return r;
}

export function buildAll(outDir) {
  mkdirSync(outDir, { recursive: true });
  const staffSpa = dev('staff').clients.find((c) => c.clientId === 'lifeplatform-spa');
  for (const name of REALMS) {
    writeFileSync(join(outDir, `${name}.json`), JSON.stringify(buildRealm(dev(name), staffSpa), null, 2) + '\n');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outDir = process.argv[2];
  if (!outDir) {
    console.error('Usage: node deploy/keycloak/build-realms.mjs <outDir>');
    process.exit(1);
  }
  buildAll(resolve(outDir));
  console.log(`Wrote ${REALMS.length} production realms to ${outDir}`);
}
```

- [ ] **Step 4: Run the test**

Run: `node --test deploy/keycloak/build-realms.test.mjs`
Expected: 5 tests pass. If "no dev users" fails on a realm-level field that legitimately mentions `localhost` (e.g. a `frontendUrl` attribute), remove or rewrite that field in `buildRealm` — do not weaken the test.

- [ ] **Step 5: Commit**

```bash
git add deploy/keycloak/build-realms.mjs deploy/keycloak/build-realms.test.mjs
git commit -m "feat(deploy): production realms generated from the dev realms"
```

### Task 11: `add-user.sh` and the frontend entry point

**Files:**
- Create: `deploy/server/add-user.sh`
- Create: `frontend/src/entryPath.ts`, `frontend/src/entryPath.test.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Produces: `add-user.sh <staff|agents|regulators> <username> --roles A,B|ALL [--home-branch X] [--party-id UUID] [--email E] [--first-name F] [--last-name L]` → prints the one-time password. `entryPath(showPicker: boolean): string | null`.

- [ ] **Step 1: `add-user.sh`**

```bash
#!/usr/bin/env bash
# Creates a staff, agent or regulator login with the claims the platform needs (2026-10-13, deployment pilot §3):
# tenant_id always, home_branch for staff, party_id for agents (the agent's party id, from the console). Prints a
# one-time password; Keycloak makes them choose their own at first sign-in. Run on the server, in the platform directory.
#
# Usage: ./add-user.sh <staff|agents|regulators> <username> --roles ROLE1,ROLE2|ALL [--home-branch DSM]
#                      [--party-id UUID] [--email a@b.tz] [--first-name Asha] [--last-name Juma]
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a

REALM="${1:?realm}"; USERNAME="${2:?username}"; shift 2
ROLES=""; HOME_BRANCH=""; PARTY_ID=""; EMAIL=""; FIRST=""; LAST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --roles) ROLES="$2"; shift 2 ;;
    --home-branch) HOME_BRANCH="$2"; shift 2 ;;
    --party-id) PARTY_ID="$2"; shift 2 ;;
    --email) EMAIL="$2"; shift 2 ;;
    --first-name) FIRST="$2"; shift 2 ;;
    --last-name) LAST="$2"; shift 2 ;;
    *) echo "Unknown option $1" >&2; exit 1 ;;
  esac
done
case "$REALM" in staff|agents|regulators) ;; *) echo "realm must be staff, agents or regulators (customers are invited from the console)" >&2; exit 1 ;; esac
[ "$REALM" = staff ] && [ -z "$HOME_BRANCH" ] && { echo "staff need --home-branch" >&2; exit 1; }
[ "$REALM" = agents ] && [ -z "$PARTY_ID" ] && { echo "agents need --party-id (the agent's party id from the console)" >&2; exit 1; }

kc() { docker compose --env-file .env --env-file versions.env -f compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user admin --password "$KC_ADMIN_PASSWORD" >/dev/null

ATTRS="\"tenant_id\":[\"$PLATFORM_TENANT_ID\"]"
[ -n "$HOME_BRANCH" ] && ATTRS="$ATTRS,\"home_branch\":[\"$HOME_BRANCH\"]"
[ -n "$PARTY_ID" ] && ATTRS="$ATTRS,\"party_id\":[\"$PARTY_ID\"]"
BODY="{\"username\":\"$USERNAME\",\"enabled\":true,\"firstName\":\"$FIRST\",\"lastName\":\"$LAST\",\"attributes\":{$ATTRS}"
[ -n "$EMAIL" ] && BODY="$BODY,\"email\":\"$EMAIL\",\"emailVerified\":true"
BODY="$BODY}"
printf '%s' "$BODY" | kc create users -r "$REALM" -f - >/dev/null

PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-14)Aa1"
kc set-password -r "$REALM" --username "$USERNAME" --new-password "$PASSWORD" --temporary

if [ "$ROLES" = "ALL" ]; then
  ROLES=$(kc get roles -r "$REALM" --fields name --format csv --noquotes \
    | grep -vE '^(offline_access|uma_authorization|default-roles-.*)$' | paste -sd, -)
fi
IFS=',' read -ra R <<< "$ROLES"
for role in "${R[@]}"; do [ -n "$role" ] && kc add-roles -r "$REALM" --uusername "$USERNAME" --rolename "$role"; done

echo "Created $REALM login $USERNAME with roles [$ROLES]."
echo "One-time password (shown once): $PASSWORD"
```

(`PASSWORD` gets `Aa1` appended so it always meets the length-10 policy regardless of `tr` removals.)

- [ ] **Step 2: Failing frontend test**

`frontend/src/entryPath.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { entryPath } from './entryPath';

describe('entryPath', () => {
  it('shows the realm picker in development', () => {
    expect(entryPath(true)).toBeNull();
  });
  it('opens the customer portal in production', () => {
    expect(entryPath(false)).toBe('/customers');
  });
});
```

Run: `npx vitest run src/entryPath.test.ts` → fails (module not found).

- [ ] **Step 3: Implement**

`frontend/src/entryPath.ts`:

```ts
/**
 * Where the bare site address goes (2026-10-13, the user's D8). Development shows the realm picker; production opens
 * the customer portal, and staff, agents and TIRA use their own paths -- nobody is shown logins that are not theirs.
 * The build sets VITE_REALM_PICKER=false for production.
 */
export const SHOW_REALM_PICKER = import.meta.env.VITE_REALM_PICKER !== 'false';

export function entryPath(showPicker: boolean): string | null {
  return showPicker ? null : '/customers';
}
```

In `App.tsx`: import `{ entryPath, SHOW_REALM_PICKER } from '@/entryPath'`, add `const ENTRY = entryPath(SHOW_REALM_PICKER);` after `realms`, and change the root and realm routes to:

```tsx
        <Route path="/" element={ENTRY ? <Navigate to={ENTRY} replace /> : <RealmPicker />} />

        {realms.map((realm) => {
          const home = REALM_HOME[realm];
          return (
            <Route key={realm} path={`/${realm}`} element={<RealmSubtree realm={realm} />}>
              {home && <Route index element={<RealmHome realm={realm} />} />}
              {SCREENS[realm].map((screen) => (
                <Route key={screen.path} path={screen.path} element={screen.element} />
              ))}
              {/* An unknown path inside a realm goes to that realm's home, not the picker. */}
              <Route path="*" element={<Navigate to={`/${realm}`} replace />} />
            </Route>
          );
        })}
```

- [ ] **Step 4: Run tests, typecheck, lint**

Run: `npx vitest run src/entryPath.test.ts src/screens.test.ts; npm run typecheck; npm run lint`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add deploy/server/add-user.sh frontend/src/entryPath.ts frontend/src/entryPath.test.ts frontend/src/App.tsx
git commit -m "feat(deploy): add-user script with platform claims; production opens the customer portal"
```

---

## Stage 5 — The server stack, release, backups

### Task 12: Images — web (Caddy + SPA) and auth (Keycloak)

**Files:**
- Create: `deploy/web/Caddyfile`, `deploy/web/Dockerfile`, `.dockerignore` (repo root)
- Create: `deploy/auth/Dockerfile`

- [ ] **Step 1: Caddyfile**

```
# The public front door (2026-10-13, deployment pilot §2). Both site addresses are settings.
{
	{$CADDY_GLOBAL_OPTIONS}
	email {$ACME_EMAIL}
}

{$APP_DOMAIN} {
	encode zstd gzip
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "strict-origin-when-cross-origin"
		X-Frame-Options "DENY"
	}
	# The SPA calls /api on its own origin; the prefix is stripped, exactly as the Vite dev proxy does. One origin: no CORS.
	handle_path /api/* {
		reverse_proxy backend:8080
	}
	handle {
		root * /srv
		@html path / /index.html
		header @html Cache-Control "no-cache"
		try_files {path} /index.html
		file_server
	}
}

{$AUTH_DOMAIN} {
	# The admin console is reached through an SSH tunnel only (deploy/README.md), never from the internet.
	@admin path /admin /admin/*
	respond @admin 404
	reverse_proxy keycloak:8080
}
```

- [ ] **Step 2: Web Dockerfile** (build context: repo root)

```dockerfile
# Caddy serving the built console (2026-10-13). AUTH_DOMAIN is compiled into the SPA, so the image is per hostname.
ARG NODE_VERSION=22.12.0-alpine
ARG CADDY_VERSION=2.8.4-alpine
FROM node:${NODE_VERSION} AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json frontend/
RUN cd frontend && npm ci
COPY backend/api backend/api
COPY frontend frontend
ARG AUTH_DOMAIN
ENV VITE_KEYCLOAK_BASE_URL=https://${AUTH_DOMAIN} \
    VITE_KEYCLOAK_CLIENT_ID=lifeplatform-spa \
    VITE_API_BASE_URL=/api \
    VITE_REALM_PICKER=false
RUN test -n "$AUTH_DOMAIN" && cd frontend && npm run generate:api && npm run build

FROM caddy:${CADDY_VERSION}
COPY --from=build /src/frontend/dist /srv
COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
```

Root `.dockerignore`:

```
**/node_modules
**/target
**/dist
.git
.worktrees
.claude
frontend/test-results
frontend/playwright-report
backend/.env
frontend/.env*
**/*.log
```

- [ ] **Step 3: Auth Dockerfile** (build context: `backend/`)

```dockerfile
# Keycloak in production mode with the login theme baked in (2026-10-13, deployment pilot §3).
ARG KEYCLOAK_VERSION=26.0.7
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres KC_HEALTH_ENABLED=true
COPY keycloak/themes/lifeplatform /opt/keycloak/themes/lifeplatform
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
```

- [ ] **Step 4: Build both to prove them**

Run (repo root): `docker build -f deploy/web/Dockerfile --build-arg AUTH_DOMAIN=auth.localhost -t platform-web:check . && docker build -f deploy/auth/Dockerfile -t platform-auth:check backend`
Expected: both build. `docker run --rm platform-web:check caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` with `-e APP_DOMAIN=app.localhost -e AUTH_DOMAIN=auth.localhost -e ACME_EMAIL=ops@example.tz` → `Valid configuration`.

- [ ] **Step 5: Commit**

```bash
git add deploy/web deploy/auth .dockerignore
git commit -m "build(deploy): web image (Caddy + console) and auth image (Keycloak with theme)"
```

### Task 13: The server compose file and settings template

**Files:**
- Create: `deploy/server/compose.yml`
- Create: `deploy/server/.env.example`

- [ ] **Step 1: `.env.example`**

```bash
# The server's settings (2026-10-13). bootstrap-server.sh copies this to .env and fills every secret; edit the rest.
# NEVER commit the filled .env.

# --- what is deployed (remote-release.sh writes RELEASE) ---
RELEASE=
IMAGE_PREFIX=ghcr.io/OWNER/platform

# --- names: change these to rename; then DNS + re-release ---
APP_DOMAIN=app.example.co.tz
AUTH_DOMAIN=auth.example.co.tz
ACME_EMAIL=ops@example.co.tz
# Empty in production. "local_certs" for the local dress rehearsal (Caddy signs with its own CA).
CADDY_GLOBAL_OPTIONS=
# Set only in the rehearsal: the backend trusts Caddy's local CA.
EXTRA_CA_FILE=

# --- the one tenant ---
PLATFORM_TENANT_ID=

# --- databases ---
POSTGRES_PASSWORD=
APP_DB_PASSWORD=
KEYCLOAK_DB_PASSWORD=
KC_ADMIN_PASSWORD=

# --- documents ---
MINIO_ROOT_USER=platform-docs
MINIO_ROOT_PASSWORD=

# --- integrations ---
PORTAL_ADMIN_CLIENT_SECRET=
MOBILE_MONEY_CALLBACK_HMAC_SECRET=
SMS_GATEWAY_BASE_URL=https://messaging-service.co.tz
SMS_GATEWAY_AUTHORIZATION=
SMS_GATEWAY_SENDER_NAME=AMC
SMS_GATEWAY_LIVE=true
# Comma-separated 255... numbers during the pilot; empty when real customers onboard.
SMS_GATEWAY_ALLOWED_RECIPIENTS=

# --- email: off until a provider account and DNS records exist ---
MAIL_ENABLED=false
MAIL_HOST=
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=no-reply@example.co.tz
MAIL_FROM_NAME=Life Platform

# --- off-server backups (S3-compatible, e.g. Backblaze B2); empty = local copies only, with a warning ---
BACKUP_S3_ENDPOINT=
BACKUP_S3_ACCESS_KEY=
BACKUP_S3_SECRET_KEY=
BACKUP_S3_BUCKET=
```

- [ ] **Step 2: `compose.yml`**

```yaml
# The pilot server (2026-10-13, deployment pilot §2). Run from the platform directory with:
#   docker compose --env-file .env --env-file versions.env -f compose.yml <command>
name: platform

x-logging: &logging
  driver: json-file
  options: { max-size: "10m", max-file: "5" }

services:
  postgres:
    image: ${IMAGE_PREFIX}-postgres:${RELEASE}
    restart: unless-stopped
    logging: *logging
    environment:
      POSTGRES_DB: lifeplatform
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      APP_DB_PASSWORD: ${APP_DB_PASSWORD}
      KEYCLOAK_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres-init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 20

  minio:
    image: minio/minio:${MINIO_VERSION}
    restart: unless-stopped
    logging: *logging
    command: server /data
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes:
      - miniodata:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 10s
      timeout: 5s
      retries: 10

  minio-init:
    image: minio/mc:${MC_VERSION}
    profiles: [tools]
    entrypoint: >
      /bin/sh -c "mc alias set local http://minio:9000 $${MINIO_ROOT_USER} $${MINIO_ROOT_PASSWORD} &&
      mc mb -p local/policy-documents local/kyc-evidence local/underwriting-evidence local/claim-evidence"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}

  mock-mobile-money:
    image: wiremock/wiremock:${WIREMOCK_VERSION}
    restart: unless-stopped
    logging: *logging
    command: ["--global-response-templating"]
    volumes:
      - ./mock-mobile-money:/home/wiremock:ro

  keycloak:
    image: ${IMAGE_PREFIX}-auth:${RELEASE}
    restart: unless-stopped
    logging: *logging
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: ${KEYCLOAK_DB_PASSWORD}
      KC_HOSTNAME: https://${AUTH_DOMAIN}
      # Admin console only through the SSH tunnel (README): ssh -L 8081:localhost:8081 deploy@server
      KC_HOSTNAME_ADMIN: http://localhost:8081
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HTTP_ENABLED: "true"
      KC_PROXY_HEADERS: xforwarded
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KC_ADMIN_PASSWORD}
    ports:
      - "127.0.0.1:8081:8080"
    depends_on:
      postgres: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/localhost/9000 && printf 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 60s

  keycloak-config:
    image: adorsys/keycloak-config-cli:${KC_CONFIG_CLI_VERSION}
    profiles: [tools]
    environment:
      KEYCLOAK_URL: http://keycloak:8080
      KEYCLOAK_USER: admin
      KEYCLOAK_PASSWORD: ${KC_ADMIN_PASSWORD}
      KEYCLOAK_AVAILABILITYCHECK_ENABLED: "true"
      IMPORT_FILES_LOCATIONS: /config/*.json
      IMPORT_VARSUBSTITUTION_ENABLED: "true"
      # Users created with add-user.sh and by portal invites are not in the realm files and must survive every release.
      IMPORT_MANAGED_USER: no-delete
      APP_DOMAIN: ${APP_DOMAIN}
      PORTAL_ADMIN_CLIENT_SECRET: ${PORTAL_ADMIN_CLIENT_SECRET}
      MAIL_ENABLED: ${MAIL_ENABLED}
      MAIL_HOST: ${MAIL_HOST}
      MAIL_PORT: ${MAIL_PORT}
      MAIL_FROM: ${MAIL_FROM}
      MAIL_FROM_NAME: ${MAIL_FROM_NAME}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
    volumes:
      - ./realms:/config:ro

  backend:
    image: ${IMAGE_PREFIX}-backend:${RELEASE}
    restart: unless-stopped
    logging: *logging
    mem_limit: 2500m
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/lifeplatform
      SPRING_DATASOURCE_PASSWORD: ${APP_DB_PASSWORD}
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: ${MINIO_ROOT_USER}
      MINIO_SECRET_KEY: ${MINIO_ROOT_PASSWORD}
      KEYCLOAK_ISSUER_CUSTOMERS: https://${AUTH_DOMAIN}/realms/customers
      KEYCLOAK_ISSUER_AGENTS: https://${AUTH_DOMAIN}/realms/agents
      KEYCLOAK_ISSUER_STAFF: https://${AUTH_DOMAIN}/realms/staff
      KEYCLOAK_ISSUER_REGULATORS: https://${AUTH_DOMAIN}/realms/regulators
      # The portal's user admin goes straight to Keycloak inside the network: Caddy blocks /admin publicly.
      KEYCLOAK_BASE_URL: http://keycloak:8080
      KEYCLOAK_PORTAL_ADMIN_SECRET: ${PORTAL_ADMIN_CLIENT_SECRET}
      PORTAL_URL: https://${APP_DOMAIN}/customers
      MOBILE_MONEY_GATEWAY_BASE_URL: http://mock-mobile-money:8080
      MOBILE_MONEY_CALLBACK_HMAC_SECRET: ${MOBILE_MONEY_CALLBACK_HMAC_SECRET}
      SMS_GATEWAY_BASE_URL: ${SMS_GATEWAY_BASE_URL}
      SMS_GATEWAY_AUTHORIZATION: ${SMS_GATEWAY_AUTHORIZATION}
      SMS_GATEWAY_SENDER_NAME: ${SMS_GATEWAY_SENDER_NAME}
      SMS_GATEWAY_LIVE: ${SMS_GATEWAY_LIVE}
      SMS_GATEWAY_ALLOWED_RECIPIENTS: ${SMS_GATEWAY_ALLOWED_RECIPIENTS}
      MAIL_ENABLED: ${MAIL_ENABLED}
      MAIL_HOST: ${MAIL_HOST}
      MAIL_PORT: ${MAIL_PORT}
      MAIL_USERNAME: ${MAIL_USERNAME}
      MAIL_PASSWORD: ${MAIL_PASSWORD}
      NOTIFICATION_FROM_ADDRESS: ${MAIL_FROM}
      EXTRA_CA_FILE: ${EXTRA_CA_FILE}
    volumes:
      # Read only by the rehearsal's EXTRA_CA_FILE; harmless in production.
      - caddydata:/caddy-data:ro
    depends_on:
      postgres: { condition: service_healthy }
      minio: { condition: service_healthy }
      keycloak: { condition: service_healthy }

  caddy:
    image: ${IMAGE_PREFIX}-web:${RELEASE}
    restart: unless-stopped
    logging: *logging
    environment:
      APP_DOMAIN: ${APP_DOMAIN}
      AUTH_DOMAIN: ${AUTH_DOMAIN}
      ACME_EMAIL: ${ACME_EMAIL}
      CADDY_GLOBAL_OPTIONS: ${CADDY_GLOBAL_OPTIONS}
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - caddydata:/data
      - caddyconfig:/config
    networks:
      default:
        # The backend fetches Keycloak's discovery document at https://AUTH_DOMAIN: inside the network that name is
        # Caddy, so the call stays on the box, the certificate is valid, and the token issuer matches exactly.
        aliases:
          - ${AUTH_DOMAIN}

volumes:
  pgdata:
  miniodata:
  caddydata:
  caddyconfig:
```

- [ ] **Step 3: Validate**

Run (repo root): `cp deploy/server/.env.example /tmp/check.env && sed -i 's/^RELEASE=$/RELEASE=check/' /tmp/check.env && docker compose --env-file /tmp/check.env --env-file deploy/server/versions.env -f deploy/server/compose.yml config -q && echo VALID`
Expected: `VALID` (warnings about empty variables are fine).

- [ ] **Step 4: Commit**

```bash
git add deploy/server/compose.yml deploy/server/.env.example
git commit -m "feat(deploy): the pilot server's compose stack and settings template"
```

### Task 14: Server-side scripts — release, smoke, backup, restore, status

**Files:**
- Create: `deploy/server/remote-release.sh`, `deploy/server/smoke.sh`, `deploy/server/backup.sh`, `deploy/server/restore.sh`, `deploy/server/status.sh`

**Interfaces:**
- Consumes: `scripts/migrate.sh server`, `scripts/configure-db.sh server` (Task 7), `add-user.sh` (Task 11), compose (Task 13).
- Produces: `remote-release.sh <version>`, `remote-release.sh --rollback <version>`; `backup.sh [label]` prints the backup directory; `restore.sh <backupDir> [--scratch]`.

- [ ] **Step 1: `remote-release.sh`**

```bash
#!/usr/bin/env bash
# Runs ON the server (2026-10-13, deployment pilot §8). Called by deploy/release.sh after it copied the files here.
# Stops at the first failure; until the backend restarts, the previous version keeps serving.
#
# Usage: ./remote-release.sh <version>            release
#        ./remote-release.sh --rollback <version> point the containers back at an earlier version (no migrations)
set -euo pipefail
cd "$(dirname "$0")"
DC="docker compose --env-file .env --env-file versions.env -f compose.yml"

wait_healthy() {
  local service="$1" i=0
  until [ "$($DC ps --format '{{.Health}}' "$service")" = "healthy" ]; do
    i=$((i + 1)); [ "$i" -gt 90 ] && { echo "$service did not become healthy" >&2; $DC logs --tail 80 "$service" >&2; exit 1; }
    sleep 2
  done
}

set_release() { sed -i "s/^RELEASE=.*/RELEASE=$1/" .env; }

if [ "${1:-}" = "--rollback" ]; then
  VERSION="${2:?version}"
  set_release "$VERSION"
  [ "${SKIP_PULL:-0}" = "1" ] || $DC pull keycloak backend caddy
  $DC up -d keycloak && wait_healthy keycloak
  $DC up -d backend caddy && wait_healthy backend
  ./smoke.sh
  echo "$VERSION" > RELEASED
  echo "Rolled back to $VERSION. Migrations were not undone; restore the pre-release backup if one must be."
  exit 0
fi

VERSION="${1:?version}"
if $DC ps --status running --services 2>/dev/null | grep -q '^postgres$'; then
  echo "Backup before $VERSION:"; ./backup.sh "pre-$VERSION"
fi
set_release "$VERSION"
[ "${SKIP_PULL:-0}" = "1" ] || $DC pull postgres minio mock-mobile-money keycloak backend caddy
$DC up -d postgres minio mock-mobile-money
wait_healthy postgres; wait_healthy minio
$DC run --rm minio-init
./scripts/migrate.sh server
./scripts/configure-db.sh server
$DC up -d keycloak && wait_healthy keycloak
$DC up -d caddy
$DC run --rm keycloak-config
if [ ! -f .first-admin-created ]; then
  ./add-user.sh staff staff.admin --roles ALL --home-branch "${FIRST_ADMIN_HOME_BRANCH:-DSM}" --first-name Platform --last-name Admin
  touch .first-admin-created
fi
$DC up -d backend && wait_healthy backend
./smoke.sh
echo "$VERSION" > RELEASED
echo "Released $VERSION."
```

- [ ] **Step 2: `smoke.sh`**

```bash
#!/usr/bin/env bash
# The release's last gate (2026-10-13): the public site, the API's door, the backend's own health, the issuer.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
K=""; [ -n "${CADDY_GLOBAL_OPTIONS:-}" ] && K="-k"   # rehearsal: Caddy's local CA
R="--resolve $APP_DOMAIN:443:127.0.0.1 --resolve $AUTH_DOMAIN:443:127.0.0.1"
fail() { echo "SMOKE FAILED: $1" >&2; exit 1; }

curl -s $K $R "https://$APP_DOMAIN/" | grep -q '<div id="root"' || fail "https://$APP_DOMAIN/ does not serve the console"
code=$(curl -s $K $R -o /dev/null -w '%{http_code}' "https://$APP_DOMAIN/api/policies")
[ "$code" = "401" ] || fail "/api without a token answered $code, expected 401"
$DC exec -T backend wget -qO- http://localhost:9090/actuator/health | grep -q '"status":"UP"' || fail "backend health is not UP"
curl -s $K $R "https://$AUTH_DOMAIN/realms/staff/.well-known/openid-configuration" \
  | grep -q "\"issuer\":\"https://$AUTH_DOMAIN/realms/staff\"" || fail "the staff realm does not report issuer https://$AUTH_DOMAIN/realms/staff"
code=$(curl -s $K $R -o /dev/null -w '%{http_code}' "https://$AUTH_DOMAIN/admin/")
[ "$code" = "404" ] || fail "the Keycloak admin console is reachable publicly ($code)"
echo "Smoke check passed."
```

- [ ] **Step 3: `backup.sh`**

```bash
#!/usr/bin/env bash
# Both databases and every document, then a copy off the server (2026-10-13, deployment pilot §4). Nightly from
# cron and before every release. Keeps 7 local copies; off-server keeps 14 daily and 8 weekly.
#
# Usage: ./backup.sh [label]     prints the backup directory on its last line
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a
DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
STAMP="$(date +%Y%m%d-%H%M%S)-${1:-nightly}"
DIR="$PWD/backups/$STAMP"
mkdir -p "$DIR/minio"

$DC exec -T postgres pg_dump -U postgres -Fc lifeplatform > "$DIR/lifeplatform.dump"
$DC exec -T postgres pg_dump -U postgres -Fc keycloak > "$DIR/keycloak.dump"
$DC run --rm -v "$DIR/minio:/backup" --entrypoint /bin/sh minio-init -c \
  "mc alias set local http://minio:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD >/dev/null && mc mirror --quiet local /backup"
echo "${RELEASE:-unknown}" > "$DIR/RELEASE"

ls -1d backups/*/ | sort | head -n -7 | xargs -r rm -rf

if [ -n "${BACKUP_S3_ENDPOINT:-}" ]; then
  rc() {
    docker run --rm -v "$PWD/backups:/data:ro" \
      -e RCLONE_CONFIG_OFF_TYPE=s3 -e RCLONE_CONFIG_OFF_PROVIDER=Other \
      -e RCLONE_CONFIG_OFF_ENDPOINT="$BACKUP_S3_ENDPOINT" \
      -e RCLONE_CONFIG_OFF_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" \
      -e RCLONE_CONFIG_OFF_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" \
      "rclone/rclone:$RCLONE_VERSION" "$@"
  }
  rc copy "/data/$STAMP" "off:$BACKUP_S3_BUCKET/daily/$STAMP"
  [ "$(date +%u)" = "7" ] && rc copy "/data/$STAMP" "off:$BACKUP_S3_BUCKET/weekly/$STAMP"
  rc delete --min-age 15d "off:$BACKUP_S3_BUCKET/daily"
  rc delete --min-age 57d "off:$BACKUP_S3_BUCKET/weekly"
else
  echo "WARNING: BACKUP_S3_ENDPOINT is empty -- this backup exists only on this server." >&2
fi
echo "$DIR"
```

- [ ] **Step 4: `restore.sh`**

```bash
#!/usr/bin/env bash
# Restores a backup (2026-10-13). --scratch restores the platform database into restore_check and reports counts,
# touching nothing live: the go-live and monthly restore test. Without it, the live databases and documents are
# replaced -- the backend and Keycloak are stopped first and started after.
#
# Usage: ./restore.sh <backups/STAMP> [--scratch]
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
DIR="${1:?backup directory}"; [ -f "$DIR/lifeplatform.dump" ] || { echo "$DIR has no lifeplatform.dump" >&2; exit 1; }
psql_() { $DC exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 "$@"; }

if [ "${2:-}" = "--scratch" ]; then
  psql_ -c "DROP DATABASE IF EXISTS restore_check" -c "CREATE DATABASE restore_check"
  $DC exec -T postgres pg_restore -U postgres -d restore_check --no-owner < "$DIR/lifeplatform.dump"
  psql_ -d restore_check -c "SELECT count(*) AS policies FROM policy.policy" -c "SELECT count(*) AS parties FROM party.party"
  psql_ -c "DROP DATABASE restore_check"
  echo "Scratch restore of $DIR succeeded."
  exit 0
fi

read -r -p "This REPLACES the live data with $DIR. Type RESTORE to continue: " answer
[ "$answer" = "RESTORE" ] || { echo "Cancelled."; exit 1; }
$DC stop backend keycloak
for db in lifeplatform keycloak; do
  $DC exec -T postgres pg_restore -U postgres -d "$db" --clean --if-exists < "$DIR/$db.dump"
done
$DC run --rm -v "$PWD/$DIR/minio:/backup:ro" --entrypoint /bin/sh minio-init -c \
  "mc alias set local http://minio:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD >/dev/null && mc mirror --overwrite --quiet /backup local"
$DC up -d keycloak backend
echo "Restored $DIR. Run ./smoke.sh once the backend is healthy."
```

- [ ] **Step 5: `status.sh`**

```bash
#!/usr/bin/env bash
# One look at the server (2026-10-13): containers, disk, the last backup's age, the deployed version, backend health.
set -uo pipefail
cd "$(dirname "$0")"
DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
echo "== version: $(cat RELEASED 2>/dev/null || echo none)"
$DC ps --format 'table {{.Service}}\t{{.Status}}'
echo "== disk"; df -h / | tail -1
last=$(ls -1d backups/*/ 2>/dev/null | sort | tail -1)
if [ -n "$last" ]; then
  age=$(( ( $(date +%s) - $(stat -c %Y "$last") ) / 3600 )); echo "== last backup: $last (${age}h ago)"
else echo "== last backup: NONE"; fi
echo "== backend health"; $DC exec -T backend wget -qO- http://localhost:9090/actuator/health; echo
```

- [ ] **Step 6: Syntax-check all five**

Run: `for f in deploy/server/*.sh; do bash -n "$f" && echo "ok $f"; done`
Expected: `ok` for each (seven files including `add-user.sh`).

- [ ] **Step 7: Commit**

```bash
git add deploy/server/*.sh
git commit -m "feat(deploy): server-side release, smoke check, backup, restore and status"
```

### Task 15: `release.sh`, `bootstrap-server.sh`, the runbook

**Files:**
- Create: `deploy/release.sh`, `deploy/release.env.example`, `deploy/bootstrap-server.sh`, `deploy/README.md`
- Modify: `.gitignore` (add `deploy/release.env`, `deploy/.stage/`, `deploy/.rehearsal/`)
- Modify: `backend/docs/07-infrastructure-architecture.md`

- [ ] **Step 1: `release.env.example`**

```bash
# Local, non-secret release settings. Copy to deploy/release.env (gitignored).
DEPLOY_HOST=deploy@203.0.113.10
# "local" = the dress rehearsal: run the server side in deploy/.rehearsal on this machine, no push, no SSH.
DEPLOY_MODE=remote
IMAGE_PREFIX=ghcr.io/OWNER/platform
# Must equal the server .env's AUTH_DOMAIN: it is compiled into the console.
AUTH_DOMAIN=auth.example.co.tz
```

- [ ] **Step 2: `release.sh`**

```bash
#!/usr/bin/env bash
# Ships a version (2026-10-13, deployment pilot §8). Runs on the developer's machine:
#   1. refuses a dirty tree or an existing tag, then tags HEAD
#   2. builds the four images and pushes them (skipped in the local rehearsal)
#   3. copies the server files over and runs remote-release.sh there
#
# Usage: deploy/release.sh <version>              e.g. v0.1.0
#        deploy/release.sh --rollback <version>
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f deploy/release.env ] || { echo "deploy/release.env is missing -- copy deploy/release.env.example" >&2; exit 1; }
set -a; . deploy/release.env; . deploy/server/versions.env; set +a

remote() {
  if [ "$DEPLOY_MODE" = "local" ]; then (cd deploy/.rehearsal && SKIP_PULL=1 bash "$@")
  else ssh "$DEPLOY_HOST" "cd ~/platform && $*"; fi
}

if [ "${1:-}" = "--rollback" ]; then remote ./remote-release.sh --rollback "${2:?version}"; exit 0; fi

VERSION="${1:?Usage: deploy/release.sh <version>}"
git diff --quiet && git diff --cached --quiet || { echo "Uncommitted changes -- commit first." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null && { echo "Tag $VERSION already exists." >&2; exit 1; }

docker build -t "$IMAGE_PREFIX-backend:$VERSION" -f backend/infra/app/Dockerfile backend
docker build -t "$IMAGE_PREFIX-postgres:$VERSION" backend/infra/postgres
docker build -t "$IMAGE_PREFIX-auth:$VERSION" --build-arg KEYCLOAK_VERSION="$KEYCLOAK_VERSION" -f deploy/auth/Dockerfile backend
docker build -t "$IMAGE_PREFIX-web:$VERSION" --build-arg AUTH_DOMAIN="$AUTH_DOMAIN" \
  --build-arg NODE_VERSION="$NODE_VERSION" --build-arg CADDY_VERSION="$CADDY_VERSION" -f deploy/web/Dockerfile .
if [ "$DEPLOY_MODE" != "local" ]; then
  for image in backend postgres auth web; do docker push "$IMAGE_PREFIX-$image:$VERSION"; done
fi
git tag "$VERSION"

STAGE=deploy/.stage; rm -rf "$STAGE"; mkdir -p "$STAGE/scripts"
cp deploy/server/compose.yml deploy/server/versions.env deploy/server/*.sh "$STAGE/"
cp backend/scripts/migrate.sh backend/scripts/configure-db.sh "$STAGE/scripts/"
cp -r backend/db-migrations "$STAGE/db-migrations"
cp -r backend/infra/postgres/init "$STAGE/postgres-init"
cp -r backend/mock-mobile-money "$STAGE/mock-mobile-money"
node deploy/keycloak/build-realms.mjs "$STAGE/realms"
find "$STAGE" -name '*.sh' -exec sed -i 's/\r$//' {} +

if [ "$DEPLOY_MODE" = "local" ]; then
  mkdir -p deploy/.rehearsal && cp -r "$STAGE"/. deploy/.rehearsal/
else
  tar czf - -C "$STAGE" . | ssh "$DEPLOY_HOST" "mkdir -p ~/platform && tar xzf - -C ~/platform && chmod +x ~/platform/*.sh ~/platform/scripts/*.sh"
fi
remote ./remote-release.sh "$VERSION"
echo "Released $VERSION. Push the tag when happy: git push origin $VERSION"
```

- [ ] **Step 3: `bootstrap-server.sh`**

```bash
#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server (2026-10-13, deployment pilot §8). Run as root:
#   scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER: && ssh root@SERVER bash bootstrap-server.sh
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }

apt-get update
apt-get install -y ca-certificates curl ufw unattended-upgrades openssl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
dpkg-reconfigure -f noninteractive unattended-upgrades

id deploy >/dev/null 2>&1 || adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
mkdir -p /home/deploy/.ssh && cp /root/.ssh/authorized_keys /home/deploy/.ssh/ && chown -R deploy:deploy /home/deploy/.ssh && chmod 700 /home/deploy/.ssh
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
systemctl reload ssh

ufw default deny incoming; ufw default allow outgoing
ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp
ufw --force enable

if [ ! -f /swapfile ]; then fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab; fi

P=/home/deploy/platform; mkdir -p "$P/backups"
if [ ! -f "$P/.env" ]; then
  cp /root/.env.example "$P/.env"
  secret() { openssl rand -base64 36 | tr -d '/+=' | cut -c1-32; }
  for key in POSTGRES_PASSWORD APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KC_ADMIN_PASSWORD MINIO_ROOT_PASSWORD PORTAL_ADMIN_CLIENT_SECRET MOBILE_MONEY_CALLBACK_HMAC_SECRET; do
    sed -i "s|^$key=.*|$key=$(secret)|" "$P/.env"
  done
  sed -i "s|^PLATFORM_TENANT_ID=.*|PLATFORM_TENANT_ID=$(cat /proc/sys/kernel/random/uuid)|" "$P/.env"
  chmod 600 "$P/.env"
fi
chown -R deploy:deploy "$P"
( crontab -u deploy -l 2>/dev/null | grep -v backup.sh; echo "30 2 * * * cd $P && ./backup.sh nightly >> backups/cron.log 2>&1" ) | crontab -u deploy -

echo "Done. Next: edit $P/.env (domains, ACME_EMAIL, IMAGE_PREFIX, SMS, backups), point DNS at this server,"
echo "log the deploy user in to the registry: su - deploy -c 'docker login ghcr.io' (a token with read:packages),"
echo "then run deploy/release.sh <version> from your machine."
```

- [ ] **Step 4: `deploy/README.md`** — the runbook, with these sections, each a short numbered procedure using the scripts above:
  1. *What runs where* (the §2 table, hostnames as `APP_DOMAIN`/`AUTH_DOMAIN`).
  2. *First deploy*: create the VPS (Ubuntu 24.04, 4 vCPU/8 GB/80 GB) → `bootstrap-server.sh` → edit `.env` → DNS `A` records for both hostnames → `docker login ghcr.io` as deploy → copy `release.env.example` → `deploy/release.sh v0.1.0` → note the printed `staff.admin` one-time password → sign in at `https://APP_DOMAIN/staff` and change it.
  3. *Release* / *Rollback* (`deploy/release.sh --rollback <v>`; migrations are not undone → restore).
  4. *Backups and restore*: nightly 02:30; `./restore.sh backups/<STAMP> --scratch` monthly; full restore.
  5. *Add a staff, agent or regulator login*: `ssh deploy@server 'cd platform && ./add-user.sh staff asha.juma --roles UNDERWRITER --home-branch DSM --first-name Asha --last-name Juma'`; agents need `--party-id`.
  6. *Keycloak admin console*: `ssh -L 8081:localhost:8081 deploy@server`, then `http://localhost:8081/admin` (user `admin`, `KC_ADMIN_PASSWORD` from `.env`).
  7. *Switch email on*: provider account, SPF/DKIM records, fill `MAIL_*`, `MAIL_ENABLED=true`, re-run the release (Keycloak SMTP and the app both follow).
  8. *SMS allowlist*: edit `SMS_GATEWAY_ALLOWED_RECIPIENTS`, then `docker compose ... up -d backend`.
  9. *Rename a hostname*: edit `APP_DOMAIN`/`AUTH_DOMAIN` in `.env` AND `AUTH_DOMAIN` in `release.env`, DNS, release a new version (the console is rebuilt with the new auth address; everyone signs in again once).
  10. *Rotate a secret*: which `.env` key, which services to restart; `PORTAL_ADMIN_CLIENT_SECRET` needs a release (keycloak-config applies it).
  11. *Go-live checklist* (spec §9).
  12. *Known pilot limits*: one backend instance; mobile money is the mock (its callbacks are signed with the mock's own secret, so mock-rail payments may not confirm — record premiums as cash/field receipts); no 2FA; no Grafana.

- [ ] **Step 5: Update `07-infrastructure-architecture.md`**: in §7 replace "**Still open:** production hosting, CDN/edge strategy, and production `redirectUris` for the portal..." with a paragraph pointing to `deploy/README.md` and the 2026-10-13 spec (one VPS, Caddy, generated realms); in §5 mark "Structured logging" and "Distributed tracing" as **not yet implemented** (no logback JSON config and no OpenTelemetry dependency exist as of 2026-10-13).

- [ ] **Step 6: `.gitignore`**: add `deploy/release.env`, `deploy/.stage/`, `deploy/.rehearsal/`.

- [ ] **Step 7: Syntax-check and commit**

Run: `bash -n deploy/release.sh && bash -n deploy/bootstrap-server.sh && echo ok`

```bash
git add deploy/release.sh deploy/release.env.example deploy/bootstrap-server.sh deploy/README.md .gitignore backend/docs/07-infrastructure-architecture.md
git commit -m "feat(deploy): release script, server bootstrap and the runbook"
```

### Task 16: The dress rehearsal

No new files unless a fix is needed. Everything runs on this machine with `DEPLOY_MODE=local`.

- [ ] **Step 1: Stop the dev stack's port users** — the rehearsal binds 80/443 and 127.0.0.1:8081 (dev Keycloak uses 8081): `cd backend/infra && docker compose stop keycloak`. Stop the dev backend and Vite.

- [ ] **Step 2: Rehearsal settings**

```bash
cp deploy/release.env.example deploy/release.env      # set DEPLOY_MODE=local, IMAGE_PREFIX=local/platform, AUTH_DOMAIN=auth.localhost
mkdir -p deploy/.rehearsal && cp deploy/server/.env.example deploy/.rehearsal/.env
```

In `deploy/.rehearsal/.env` set: `IMAGE_PREFIX=local/platform`, `APP_DOMAIN=app.localhost`, `AUTH_DOMAIN=auth.localhost`, `ACME_EMAIL=ops@example.tz`, `CADDY_GLOBAL_OPTIONS=local_certs`, `EXTRA_CA_FILE=/caddy-data/caddy/pki/authorities/local/root.crt`, a fresh UUID for `PLATFORM_TENANT_ID`, random values for every secret, the NextSMS values from `backend/.env` (`SMS_GATEWAY_ALLOWED_RECIPIENTS` in `255…` form — the user's own number), `MAIL_ENABLED=false`.

- [ ] **Step 3: Release twice**

Run: `deploy/release.sh v0.0.1-rehearsal` → expected ends `Smoke check passed.` and `Released v0.0.1-rehearsal.`, printing the `staff.admin` one-time password.
Then `git tag -d v0.0.1-rehearsal`, re-run the same command → expected: migrations report `Applied 0 migrations`, keycloak-config reports no changes needed, smoke passes. Delete the tag again afterwards.

- [ ] **Step 4: Walk the platform by hand** (browser; accept the local-CA warning or import `root.crt`)
  1. `https://app.localhost/` opens the customer login (no picker). `https://app.localhost/staff` → staff login → sign in as `staff.admin`, forced to change the password (policy: 10+ chars).
  2. `https://auth.localhost/admin/` → 404.
  3. Create a product version, a client with the user's own phone number, issue a policy (follow the existing e2e happy path by hand), record the first premium as a field receipt.
  4. Invite the client to the portal → staff screen shows "One-time password sent by SMS to +255…"; the phone receives it; the client's Messages show `••••••••`.
  5. Sign in at `https://app.localhost/customers` with the texted password → forced change → the policy appears; download the payment schedule PDF.
  6. `cd deploy/.rehearsal && ./add-user.sh staff test.underwriter --roles UNDERWRITER --home-branch DSM` → then re-run the release (Step 3 style): `test.underwriter` must still exist (`IMPORT_MANAGED_USER=no-delete`).
  7. `./backup.sh manual` → `./restore.sh backups/<STAMP> --scratch` → counts printed, `Scratch restore ... succeeded`.
  8. Build a second version (`deploy/release.sh v0.0.2-rehearsal`), then `deploy/release.sh --rollback v0.0.1-rehearsal` → smoke passes.
  9. `./status.sh` shows all services healthy and the last backup.

- [ ] **Step 5: Fix what broke.** Each fix is its own small commit with a test where the fix is in code. Known places most likely to need adjustment, check these first if a step fails: keycloak-config-cli env names (`IMPORT_FILES_LOCATIONS`, `IMPORT_VARSUBSTITUTION_ENABLED`, `IMPORT_MANAGED_USER`) against the pinned version's README; Keycloak 26 hostname/backchannel settings if the backend's portal admin calls fail with 401; the Caddy alias if the backend cannot fetch `https://AUTH_DOMAIN/.../openid-configuration`.

- [ ] **Step 6: Tear down and restore the dev stack**

```bash
cd deploy/.rehearsal && docker compose --env-file .env --env-file versions.env -f compose.yml down -v && cd ../..
rm -rf deploy/.rehearsal deploy/.stage
cd backend/infra && docker compose up -d keycloak
```

- [ ] **Step 7: Report** to the user: what passed, what was fixed (with commits), and the go-live checklist items still theirs (VPS purchase, DNS, registry token, NextSMS allowlist, backup bucket).

---

## Self-review (done while writing)

- Spec coverage: §2 → Tasks 12-13; §3 → Tasks 9-11 (+ refinement: generated realms); §4 → Tasks 7-8, 14; §5 → Tasks 1-3; §6 → Tasks 4-6; §7 → Tasks 11-12; §8 → Tasks 13-15; §9 → task tests + Task 16; §10 nothing; D8 → Task 11.
- Names used across tasks: `sendUnloggedSms`, `deliveryFor`, `PortalInvite(access, temporaryPassword, smsSentTo, notice)`, `Delivery.SMS_PASSWORD`, `communication.email.enabled`, `entryPath`/`SHOW_REALM_PICKER`, `migrate.sh server`, `configure-db.sh server`, `platform_default` network (compose `name: platform`) — consistent.
- The only intentionally variable values are the image pins, verified in Task 0.

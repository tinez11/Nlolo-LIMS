# Offer Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A customer with an unpaid offer is told it exists, reminded before it closes, told when cover starts, and told if it expires.

**Architecture:** `communication` gains its first Java. A `NotificationApi` renders a seeded template, resolves the party's channels, sends, and records one `notification_dispatch` row per attempt, deduped on `event_id`. Three `AFTER_COMMIT` listeners drive it from `policy.PolicyIssued`, `policy.PolicyActivated` and a new `policy.PolicyNotTakenUp`; a pg_cron sweep drives the reminder. SMS goes over `RestClient` to a WireMock gateway mirroring `MobileMoneyGatewayAdapter`; email goes over SMTP to Mailpit.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module directories), Postgres 16 with pg_cron, Testcontainers, JUnit 5, WireMock.

**Design spec:** `docs/superpowers/specs/2026-09-09-offer-notifications-design.md`

## Global Constraints

- **One new dependency, deliberately: `spring-boot-starter-mail`.** There is no way to speak SMTP without it, Mailpit is already in `infra/docker-compose.yml` for exactly this channel, and the user asked for email alongside SMS. It is a standard Boot starter. No other dependency may be added — in particular, no retry, templating or SMS-vendor library.
- **Never run Maven in Docker.** Use `./mvnw` on the host. Testcontainers networking breaks otherwise.
- **Never edit Java sources or `api/openapi/*.yaml` while a Maven build is running.** It produces `ClassNotFoundException` on anonymous inner classes and phantom failures across untouched modules.
- **`clean` fails while the dev backend holds `target/lifeplatform.jar`.** `rm -rf target/classes target/test-classes` gives the same full-recompile guarantee without stopping the server.
- **Never run Prettier.**
- **Migrations live in `backend/db-migrations/<module>/`.** Next free numbers: `communication/V2`, `refdata/V6`, `policy/V12`.
- **`db-migrations/_post-migration/*.sql` is NOT applied by `scripts/migrate.sh`.** Ops files, applied by hand, and the dev database needs them plus a restart before real-stack e2e reflects anything.
- **Every test class lists its migrations explicitly.** A new migration must be added to each `MigrationTestSupport.applyMigration(...)` call that needs it. Use a pattern-only node script, never a shell heredoc or backticks.
- **`communication`'s `allowedDependencies` are `party::api` and `refdata::api`.** Do not widen them. Everything else arrives as an event payload. `PartyApi.getPartyDetail(UUID)` is where `phoneNumber` and `email` come from; `PartyView` must not be widened.
- **Bean names collide.** `billing`, `distribution`, `reinsurance` and `regreporting` each declare a `PolicyEventListener`. Communication's must be `@Component("communicationPolicyEventListener")`.
- **`AFTER_COMMIT` + `PROPAGATION_REQUIRES_NEW`, always.** A plain `@Transactional` called from an AFTER_COMMIT callback silently joins the already-committed producer transaction and never commits. Confirmed empirically on this codebase.

---

## File Structure

**communication — the module's first Java**
- `communication/api/NotificationApi.java` (create) — the published interface, one method.
- `communication/api/NotificationChannel.java` (create) — SMS, EMAIL (the two that send).
- `communication/domain/NotificationTemplate.java`, `NotificationDispatch.java`, `ProcessedEvent.java` (create) — entities over the existing V1 tables.
- `communication/domain/TemplateRenderer.java` (create) — pure placeholder substitution, no dependencies.
- `communication/application/NotificationApiImpl.java` (create) — dedup, resolve, render, send, record.
- `communication/application/PolicyEventListener.java` (create) — the three policy events.
- `communication/infrastructure/*Repository.java` (create) — three Spring Data repositories.
- `communication/infrastructure/SmsGatewayAdapter.java` (create) — `RestClient`, mirrors `MobileMoneyGatewayAdapter`.
- `communication/infrastructure/EmailSenderAdapter.java` (create) — `JavaMailSender`.
- `communication/infrastructure/NotificationSender.java` (create) — the port both adapters implement.

**Migrations**
- `db-migrations/communication/V2__seed_offer_templates.sql` (create) — the four templates, sw and en, per channel.
- `db-migrations/refdata/V6__seed_offer_reminder_days.sql` (create) — `TZ_OFFER_REMINDER_DAYS`, 7.
- `db-migrations/_post-migration/configure-offer-reminder-sweep.sql` (create) — the pg_cron job.

**policy — the missing event**
- `policy/application/PolicyApiImpl.java` (modify) — publish `policy.PolicyNotTakenUp`.
- `policy/api/PolicyApi.java` (modify) — `expireOffer(String policyNumber)`.

**infra**
- `infra/docker-compose.yml` (modify) — `mock-sms-gateway`.
- `mock-sms-gateway/mappings/*.json` (create) — the stubs.
- `src/main/resources/application.yml` (modify) — SMTP and gateway URL.

**Docs**
- `api/asyncapi-events.yaml`, `docs/05-event-catalog.md` (modify).

**Console**
- `api/openapi/openapi-communication.yaml`, `communication/infrastructure/NotificationController.java` (create) — the read surface.
- `db-migrations/communication/V3__dispatch_policy_number.sql` (create) — what a message was about.
- `frontend/src/screens.tsx` (modify), `frontend/src/features/communications/*` (create) — the Communications section.

---

## Task 1: The sender port and its two adapters

**Files:**
- Create: `communication/infrastructure/NotificationSender.java`, `SmsGatewayAdapter.java`, `EmailSenderAdapter.java`, `communication/api/NotificationChannel.java`
- Modify: `pom.xml`, `infra/docker-compose.yml`, `src/main/resources/application.yml`
- Create: `mock-sms-gateway/mappings/send-sms.json`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/SmsGatewayAdapterTest.java`

**Interfaces:**
- Produces: `NotificationSender.send(NotificationChannel, String destination, String body)` returning a `SendResult(boolean sent, String detail)`; `NotificationChannel.{SMS, EMAIL}`.

- [ ] **Step 1: Add the mock gateway to compose**

Mirrors `mock-mobile-money` exactly — same image, same read-only mappings mount, one port up.

```yaml
  mock-sms-gateway:
    # Simulates a Tanzanian SMS aggregator so communication's ACL can be exercised without live
    # credentials. Same pattern, and the same reasoning, as mock-mobile-money above.
    image: wiremock/wiremock:3.5.4
    ports:
      - "8083:8080"
    volumes:
      - ../mock-sms-gateway:/home/wiremock:ro
    command: ["--global-response-templating"]
```

- [ ] **Step 2: Write the stub mapping**

`mock-sms-gateway/mappings/send-sms.json`:

```json
{
  "request": { "method": "POST", "urlPath": "/send" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "status": "ACCEPTED", "messageId": "SMS-{{randomValue type='UUID'}}" }
  }
}
```

- [ ] **Step 3: Add the mail starter and the config**

`pom.xml`, beside `spring-boot-starter-web`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>
```

`application.yml`:

```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}

communication:
  sms-gateway-url: ${SMS_GATEWAY_URL:http://localhost:8083}
  from-address: ${NOTIFICATION_FROM:no-reply@nlolo.co.tz}
```

- [ ] **Step 4: Write the port**

```java
package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;

/**
 * One way out of this module.
 *
 * <p>A port rather than two injected clients because the caller must not care which channel it
 * is using — {@code NotificationApiImpl} decides SMS or email from what the party actually has,
 * and a channel with no adapter is a configuration fact, not a branch in business logic.
 */
public interface NotificationSender {

    /** Whether this adapter handles the channel. */
    boolean supports(NotificationChannel channel);

    /**
     * @param destination a phone number for SMS, an email address for EMAIL
     * @return whether it left the platform, and why not when it did not. Never throws for a
     *     delivery failure: a failed send is a row to record, not an exception to unwind a
     *     policy transaction with.
     */
    SendResult send(String destination, String body);

    record SendResult(boolean sent, String detail) {
        public static SendResult ok() { return new SendResult(true, null); }
        public static SendResult failed(String detail) { return new SendResult(false, detail); }
    }
}
```

- [ ] **Step 5: Write the SMS adapter**

Read `payment/infrastructure/MobileMoneyGatewayAdapter.java` first and copy its `RestClient`
construction and timeout handling verbatim; only the URL, body and response shape differ.

```java
@Component
public class SmsGatewayAdapter implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayAdapter.class);
    private final RestClient restClient;

    public SmsGatewayAdapter(@Value("${communication.sms-gateway-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.SMS; }

    @Override
    public SendResult send(String destination, String body) {
        try {
            restClient.post().uri("/send")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("to", destination, "message", body))
                .retrieve().toBodilessEntity();
            return SendResult.ok();
        } catch (RestClientException e) {
            // Caught, not propagated. The caller is an AFTER_COMMIT listener reacting to a
            // policy that already exists; an unreachable aggregator must leave a FAILED row,
            // not tear down anything upstream.
            log.warn("SMS gateway refused a message to {}", destination, e);
            return SendResult.failed("SMS gateway: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Write the email adapter**

Same shape, `JavaMailSender`, `SimpleMailMessage`, catching `MailException`.

- [ ] **Step 7: Test the SMS adapter against WireMock**

Use the `wiremock-standalone` dependency already on the test classpath; copy the WireMock setup
from `regreporting/ProjectionEndToEndTest`. Two tests: a 200 returns `sent`, and a 500 returns
`failed` with the detail — the second is the one that matters, because it proves a dead gateway
cannot throw into a listener.

- [ ] **Step 8: Run and commit**

Run: `cd backend && rm -rf target/classes target/test-classes && ./mvnw test-compile && ./mvnw test -Dtest='SmsGatewayAdapterTest'`

```bash
git add backend/pom.xml backend/infra backend/mock-sms-gateway backend/src
git commit -m "feat(communication): a sender port, an SMS gateway ACL and an SMTP adapter"
```

---

## Task 2: Templates, rendering, and the dispatch record

**Files:**
- Create: `communication/domain/{NotificationTemplate,NotificationDispatch,ProcessedEvent,TemplateRenderer}.java`, three repositories, `db-migrations/communication/V2__seed_offer_templates.sql`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/TemplateRendererTest.java`

**Interfaces:**
- Produces: `TemplateRenderer.render(String bodyTemplate, Map<String, String> values)`; the three JPA entities; `NotificationTemplateRepository.findByTemplateKeyAndTenantIdAndChannelAndLanguage(...)`.

- [ ] **Step 1: Seed the templates**

Four keys × two channels × two languages. `{{placeholder}}` syntax, chosen because it matches
nothing else in these strings and needs no library.

```sql
-- db-migrations/communication/V2__seed_offer_templates.sql
-- The offer lifecycle, in the customer's own words.
--
-- Swahili first and English second per key: sw is the customer-facing default (see V1's own
-- comment on the language CHECK), en is what a back-office reader falls back to.
--
-- SMS bodies are deliberately short. A Tanzanian aggregator bills per 160-character segment,
-- and a two-segment reminder costs twice as much to say the same thing.
INSERT INTO communication.notification_template (template_key, tenant_id, channel, language, body_template) VALUES
  ('OFFER_MADE', '11111111-1111-1111-1111-111111111111', 'SMS', 'sw',
   'Ofa yako ya bima {{policyNumber}} iko tayari. Lipa {{premium}} kabla ya {{expiryDate}} ili ulinzi uanze.'),
  ('OFFER_MADE', '11111111-1111-1111-1111-111111111111', 'SMS', 'en',
   'Your cover offer {{policyNumber}} is ready. Pay {{premium}} by {{expiryDate}} to start it.'),
  ...
```

Write all sixteen rows. The tenant id is the seeded dev tenant; templates are per-tenant by
schema, and a second tenant seeding its own is a later concern.

- [ ] **Step 2: Write the renderer test first**

```java
@Test
void substitutesEveryPlaceholderItIsGiven() {
    assertThat(TemplateRenderer.render("Pay {{premium}} by {{expiryDate}}.",
        Map.of("premium", "TZS 50,000.00", "expiryDate", "2026-10-09")))
        .isEqualTo("Pay TZS 50,000.00 by 2026-10-09.");
}

/**
 * A placeholder nobody supplied is a template bug, and shipping "Pay {{premium}}" to a customer
 * is worse than not sending. It throws so the dispatch is recorded FAILED with a reason.
 */
@Test
void refusesToRenderAPlaceholderNobodySupplied() {
    assertThatThrownBy(() -> TemplateRenderer.render("Pay {{premium}}.", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("premium");
}
```

- [ ] **Step 3: Implement the renderer, the entities and the repositories**

`TemplateRenderer` is a static utility with no Spring in it: substitution is a pure function, and
keeping it that way is what makes the test above worth writing.

- [ ] **Step 4: Add the migration to the test classes that need it**

Only the communication tests need it so far. Use the pattern-only node script.

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='TemplateRendererTest'`

```bash
git add backend/db-migrations backend/src
git commit -m "feat(communication): the offer templates, and a renderer that refuses a hole"
```

---

## Task 3: NotificationApi — dedup, resolve, send, record

**Files:**
- Create: `communication/api/NotificationApi.java`, `communication/application/NotificationApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/NotificationApiIntegrationTest.java`

**Interfaces:**
- Consumes: `NotificationSender` (Task 1), `TemplateRenderer` and the repositories (Task 2).
- Produces: `NotificationApi.notify(UUID eventId, UUID partyId, String templateKey, Map<String, String> values)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void sendsAnSmsAndRecordsTheDispatch() { ... assert one SENT row, channel SMS ... }

/**
 * The reason processed_event exists. Everywhere else on this platform a redelivered event is
 * absorbed silently because the write is idempotent; here it is not, because a second SMS is a
 * second SMS on somebody's phone.
 */
@Test
void aRedeliveredEventSendsNothingASecondTime() {
    UUID eventId = UUID.randomUUID();
    notificationApi.notify(eventId, partyId, "OFFER_MADE", values);
    notificationApi.notify(eventId, partyId, "OFFER_MADE", values);
    assertThat(dispatchRepository.findByPartyId(partyId)).hasSize(1);
}

/**
 * Both contact fields are optional on a party (ContactInfo constrains the formats but requires
 * neither), so "nobody to tell" is a real state. It is recorded, because an unreachable customer
 * is an operational fact somebody needs to see, not an absence of work.
 */
@Test
void aPartyWithNoPhoneAndNoEmailGetsAFailedDispatchNamingWhy() { ... status FAILED ... }

@Test
void aPartyWithBothGetsBoth() { ... two rows, one SMS one EMAIL ... }
```

- [ ] **Step 2: Implement**

`notify` is `@Transactional`: the `processed_event` insert and the dispatch rows commit together,
so a crash mid-send cannot leave the event marked processed with nothing recorded.

Order matters: check `processed_event` first and return early; then resolve contacts; then per
channel render, send, and save the row. A send that fails saves `FAILED` and does **not** abort
the other channel — an unreachable phone must not suppress a working email.

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='NotificationApiIntegrationTest'`

```bash
git commit -m "feat(communication): notify, deduped on the event that caused it"
```

---

## Task 4: `policy.PolicyNotTakenUp`, the event nobody publishes

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyApi.expireOffer(String policyNumber)`; `policy.PolicyNotTakenUp` carrying `policyNumber`, `policyholderPartyId`, `expiredAt`.

The sweep sets `NOT_TAKEN_UP` with a raw `UPDATE` and publishes nothing, because in Project 1
there was no consumer. There is now. The sweep is SQL and cannot publish a Spring event, so this
adds the application-side path that can, mirroring `activateOnFirstPremium`'s shape exactly.

- [ ] **Step 1: Write the failing test**

```java
@Test
void expiringAnOfferPublishesPolicyNotTakenUp() { ... assert one audit row of that type ... }

@Test
void expiringSomethingThatIsNotAnOfferIsSilentAndPublishesNothing() { ... }
```

- [ ] **Step 2: Implement `expireOffer`**

Idempotent and silent on a non-`PROPOSED` policy, for the same reasons `activateOnFirstPremium`
is: redelivery and a raced sweep both land here legitimately.

- [ ] **Step 3: Point the sweep at it**

The pg_cron sweep stays the enforcer of the deadline; it is the reminder sweep in Task 6 that
calls `expireOffer` for policies it finds already past the window, so the event has a producer on
the application side. Record in `configure-offer-expiry-sweep.sql`'s header that the SQL sweep
and this method are two statements of one invariant, the same note `markNotTakenUp()` carries.

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest'`

```bash
git commit -m "feat(policy): PolicyNotTakenUp, so an expired offer can be told to somebody"
```

---

## Task 5: The three listeners

**Files:**
- Create: `communication/application/PolicyEventListener.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/OfferNotificationEndToEndTest.java`

- [ ] **Step 1: Write the end-to-end test first**

Issue a real policy through `PolicyApi`, assert an `OFFER_MADE` dispatch row exists. Collect the
premium, assert `COVER_STARTED`. Expire a third offer, assert `OFFER_EXPIRED`. Real producers,
real listeners, no fabricated envelopes.

- [ ] **Step 2: Write the listener**

`@Component("communicationPolicyEventListener")` — the name is not optional, see Global
Constraints. `AFTER_COMMIT`, `PROPAGATION_REQUIRES_NEW`, `TenantContext` save/set/restore, and a
catch-all that logs: a failed notification must never roll back a policy or a payment.

`policy.PolicyIssued` notifies **only when the policy is an offer**. A `MIGRATION` issuance is
already in force and both events fire together, so telling that customer to pay by a date to
start cover they already have would be wrong. The payload does not carry status, so the listener
reads it back — that is a `policy::api` call, which communication may not make. Instead, the
`PolicyIssued` payload gains a `status` field in this task; declare it in `asyncapi-events.yaml`.

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='OfferNotificationEndToEndTest'`

```bash
git commit -m "feat(communication): tell the customer their offer exists, started, or closed"
```

---

## Task 6: The reminder sweep

**Files:**
- Create: `db-migrations/refdata/V6__seed_offer_reminder_days.sql`, `db-migrations/_post-migration/configure-offer-reminder-sweep.sql`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/OfferReminderSweepTest.java`

- [ ] **Step 1: Seed the window**

`TZ_OFFER_REMINDER_DAYS`, `DEFAULT`, `7`, TZ — one reminder, a week before the 30-day offer
closes. User-approved 2026-09-09. Reference data for the same reason `TZ_OFFER_VALIDITY_DAYS` is.

- [ ] **Step 2: Write the sweep**

Selects `PROPOSED` policies where `created_at` falls in the reminder window and no `OFFER_CLOSING`
dispatch exists for them yet, and inserts a `PENDING` dispatch row per channel. It does **not**
send: SQL cannot call an SMS gateway. A small `@Scheduled` drain in `communication` sends
`PENDING` rows — the one place a Spring schedule is correct here, because it is a within-tenant
queue drain rather than a cross-tenant business-state transition.

- [ ] **Step 3: Test it from the file operations applies**

Load the function from `_post-migration`, not an inlined copy — the same discipline Project 1
used, and for the same reason the billing sweep shipped a broken `CALL` for three milestones.

Three tests: a policy inside the window gets a row; one outside does not; one already reminded
does not get a second.

- [ ] **Step 4: Run and commit**

```bash
git commit -m "feat(communication): remind a customer a week before their offer closes"
```

---

## Task 7: The read surface — templates and the outbox over HTTP

**Files:**
- Create: `api/openapi/openapi-communication.yaml`, `communication/infrastructure/NotificationController.java`, `communication/api/{NotificationTemplateView,NotificationDispatchView}.java`
- Create: `db-migrations/communication/V3__dispatch_policy_number.sql`
- Modify: `communication/api/NotificationApi.java`, `communication/application/NotificationApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/communication/CommunicationContractTest.java`

**Interfaces:**
- Produces: `GET /notifications/templates`, `PUT /notifications/templates/{templateKey}`, `GET /notifications/dispatches?partyId=&policyNumber=&status=`.

- [ ] **Step 1: Add `policy_number` to the dispatch**

```sql
-- db-migrations/communication/V3__dispatch_policy_number.sql
-- What this message was about.
--
-- Nullable on purpose: not every notification concerns a policy, and the ones that do should say
-- so in a column rather than leave a reader inferring it from the template key and a timestamp.
-- The policy detail page's Messages panel is the reason this exists -- "has THIS customer been
-- told about THIS policy" is the question the desk actually asks, and answering it from a global
-- outbox means knowing a party id and filtering by hand.
ALTER TABLE communication.notification_dispatch ADD COLUMN policy_number VARCHAR(20);
CREATE INDEX idx_notification_dispatch_policy ON communication.notification_dispatch (tenant_id, policy_number);
```

`NotificationApi.notify` gains a nullable `policyNumber` argument, and the three listeners pass
the one from their payload.

- [ ] **Step 2: Write the contract tests first**

Strict OpenAPI validation, the same discipline `PolicyContractTest` uses — an undeclared response
field is a hard failure.

```java
@Test
void listTemplatesMatchesTheOpenApiContract() { ... 200, isValid(SPEC_PATH) ... }

@Test
void editingATemplateBodyChangesWhatIsSentNext() {
    // The point of the screen: a typo in a customer's SMS must be fixable without a migration.
    // Asserts the NEXT send uses the new wording, not just that the row changed.
}

@Test
void aNonAdminCannotEditATemplate() { ... 403 ... }

@Test
void theTemplateKeySetIsNotEditable() {
    // No POST and no DELETE are declared. A key with no listener is dead text; a listener whose
    // key was deleted fails every send. The code that sends defines the set.
}

@Test
void dispatchesCanBeFilteredToOnePolicy() { ... }
```

- [ ] **Step 3: Write the spec, the views and the controller**

`PUT` accepts `{ "bodyTemplate": "..." }` and nothing else — channel, language and key are
identity, not content. Reject a body whose placeholders are not a subset of the ones the original
declared: renaming `{{premium}}` to `{{amount}}` would render a hole into a customer's message,
and `TemplateRenderer` throwing at send time is far too late.

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='CommunicationContractTest'`

```bash
git commit -m "feat(communication): read the outbox, and fix a typo without a migration"
```

---

## Task 8: The Communications section in the console

**Files:**
- Modify: `frontend/src/screens.tsx`, `frontend/src/api/types.ts`
- Create: `frontend/src/features/communications/{TemplatesPage,MessagesPage,MessagesPanel}.tsx`, `store/communicationsSlice.ts`
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx`
- Test: `frontend/src/features/communications/*.test.tsx`

- [ ] **Step 1: Regenerate the API types**

Run: `cd frontend && npm run generate:api`

- [ ] **Step 2: Add the nav group**

In `screens.tsx`'s staff group list, after `records`:

```tsx
    // Its own group rather than two items scattered into Configuration and Records. What the
    // platform says to customers is one operational area -- the wording and the evidence it was
    // sent are read together, usually by the same person answering the same complaint.
    { id: 'communications', label: 'Communications' },
```

Two screens: `notifications/templates` ("Message templates", icon `MessageSquare`) and
`notifications/messages` ("Messages sent", icon `Send`).

- [ ] **Step 3: Build the templates page**

A table of key/channel/language, and an edit form for the body. Show the placeholders the
template declares, so somebody editing knows which tokens are real — an editor who does not know
`{{expiryDate}}` exists will delete it.

- [ ] **Step 4: Build the outbox page**

Party, template, channel, status, time. `FAILED` rows show the reason. Status filter, and the
same URL round-tripping the other list pages use.

- [ ] **Step 5: The per-policy panel**

A `Messages` panel on `PolicyDetailPage`, reading dispatches filtered to that policy number.
Empty state says "Nothing sent about this policy yet" — an honest empty state, not a blank box
that reads as a loading failure.

- [ ] **Step 6: Run and commit**

Run: `cd frontend && npm run typecheck && npm run lint && npm test`

```bash
git commit -m "feat(console): a Communications section, and what the customer was told"
```

---

## Task 9: The written record, and end to end

**Files:**
- Modify: `api/asyncapi-events.yaml`, `docs/05-event-catalog.md`, `docs/04-api-contracts.md`
- Modify: `frontend/e2e/` — one spec

- [ ] **Step 1: Apply and restart**

Apply `communication/V2`, `refdata/V6` and both `_post-migration` files to the dev database by
hand, rebuild the jar, restart the backend, and bring up `mock-sms-gateway`.

- [ ] **Step 2: Correct the catalogue**

`policy.PolicyNotTakenUp` is new: producer policy, consumers communication, regreporting, audit.
`policy.PolicyIssued`'s payload gains `status`. `communication` stops being a documented
placeholder on four events and becomes a real consumer.

- [ ] **Step 3: The e2e**

Issue an offer through the console, assert the message lands in Mailpit's API
(`http://localhost:8025/api/v1/messages`) and that the SMS stub was called.

- [ ] **Step 4: Full suite and commit**

Run: `cd backend && ./mvnw test` then `cd frontend && npx playwright test`

```bash
git commit -m "docs(events): PolicyNotTakenUp, and communication as a real consumer"
```

---

## Out of scope

The other 27 events, inbound messages, delivery receipts, a preference centre, USSD and PUSH,
per-party language selection, and automatic retry of a failed send. Each is listener or adapter
work on the machinery this plan builds, except retry, which needs a backoff and dead-letter
decision of its own.

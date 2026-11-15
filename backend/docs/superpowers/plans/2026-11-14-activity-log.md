# Activity Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One admin page that answers "who did what, when, to what, and did it work", in plain words, plus a History tab on records. It replaces the Event journal.

**Architecture:** A servlet filter in the audit module records every write request (`POST`/`PUT`/`PATCH`/`DELETE`) that reaches a controller. Each record holds:
- who did it, the action label, the record acted on, and the request body (secrets removed);
- the outcome, including the refusal reason when there was one;
- a new action id, which the filter puts on the request thread.

The filter runs inside the security chain, so the signed-in user and the tenant are known. `DomainEventAuditListener` already writes every business event to `audit.audit_log` on that same thread after commit. It now stamps the action id on each event, which joins events to the action that caused them. A read API serves three things:
- the log, where events with no action appear as `System` lines;
- one opened line, showing what changed and what followed;
- a CSV download.

The console page and the History tabs read from that API.

**Tech Stack:** Spring Boot 3.3.5, Spring Modulith 1.2.5, PostgreSQL 16 (partitioned table, RLS), JdbcTemplate, Testcontainers + MockMvc + swagger-request-validator; React + zustand + openapi-typescript, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-11-14-activity-log-design.md`

## Global Constraints

- Who can see it: holders of `audit.view`. No new permission and no new role.
- Kept forever; never updated or deleted. `app_role` gets `INSERT, SELECT` only. RLS on `tenant_id` uses the platform's `NULLIF` predicate (audit/V2).
- Secrets are never stored: any field named like a password, OTP, PIN, sign-in/verification code, token, secret or authorization.
- Personal numbers (phone, national ID / ID number, account number, payee) are masked to their last 3 characters in the list and the CSV, and shown in full only in the opened line.
- Viewing is not recorded. Changes only.
- One page: the new log replaces "Event journal" at `/staff/audit-log`, in the same menu group (`records`).
- Words: short past-tense labels, drafted from the OpenAPI summaries and **reviewed by the user before merge**.
- Simple English in every user-facing string. No ids or enum names in the line text.
- Platform conventions apply:
  - host `./mvnw` with `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`; never Maven in Docker;
  - never run Prettier;
  - edit files with the Write/Edit tools;
  - one heavy run at a time;
  - stop the dev backend before backend tests;
  - logs go to the scratchpad only;
  - never `git add backend/docs` as a directory (two PDFs there must stay uncommitted).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Spec amendment (record in the spec in Task 1)

The spec says public writes are recorded as `Not signed in`. Routes outside a signed-in session are **not recorded** by the filter: `/public/**`, `/internal/**`, and the mobile-money callback. Those controllers set the tenant themselves and clear it before the filter's `finally` runs, so a row cannot pass RLS. These are machine or visitor endpoints, and their business events (an application received, a payment confirmed) still appear as `System` lines. The user must be told this at review.

---

## File structure

**Backend, audit module** (`backend/src/main/java/tz/co/nlolo/lifeplatform/audit/`)

| File | Responsibility |
|---|---|
| `application/ActivityContext.java` | Thread-local current action id; set and cleared by the filter, read by the event listener |
| `application/ActivityRedaction.java` | Drop secrets from a JSON body; mask personal numbers |
| `application/ActivityLabels.java` | Load `audit/activity-labels.tsv`; look up label + kind by `METHOD pattern` |
| `application/RecordRefs.java` | Path variables → record type + id; response body → readable record label and amount |
| `application/ActivityRecorder.java` | Insert one `audit.activity` row in its own transaction |
| `application/ActivityQueries.java` | Read side: list (actions ∪ standalone events), detail, CSV rows |
| `api/EventRefs.java` | Whitelisted refs + amount from an event payload (moved from dashboard's `RecentActivity`) |
| `infrastructure/ActivityRecordingFilter.java` | The servlet filter |
| `infrastructure/ActivityFilterConfig.java` | `FilterRegistrationBean`, ordered just inside Spring Security |
| `infrastructure/ActivityController.java` | `GET /activity`, `GET /activity/{activityId}`, `GET /activity.csv` |
| `infrastructure/DomainEventAuditListener.java` (modify) | Stamp `action_id` |
| `domain/AuditLogEntry.java` (modify) | `actionId` column |

**Other backend files**

- `backend/src/main/resources/audit/activity-labels.tsv` — `METHOD<TAB>pattern<TAB>kind<TAB>label`, one per write route.
- `backend/db-migrations/audit/V5__activity.sql`.
- `backend/db-migrations/_post-migration/configure-pg-partman.sql` (modify).
- `backend/api/openapi/openapi-audit.yaml` (modify).
- `backend/scripts/draft-activity-labels.mjs` — one-off drafting script, committed so it can be rerun.
- `dashboard/application/RecentActivity.java` (modify) — use `EventRefs`.

**Backend tests** (`backend/src/test/java/tz/co/nlolo/lifeplatform/audit/`)

- `ActivityRedactionTest`, `RecordRefsTest`, `ActivityLabelsCoverageTest`
- `ActivityRecordingIntegrationTest`, `ActivityContractTest`

**Frontend** (`frontend/src/`)

| File | Responsibility |
|---|---|
| `api/activity.ts` | Client for the three endpoints |
| `store/activityStore.ts` | List + detail resources |
| `features/audit/ActivityLogPage.tsx` | The page (replaces `AuditLogPage.tsx`, which is deleted) |
| `features/audit/ActivityLine.tsx` | One line + its opened detail |
| `features/audit/RecordHistory.tsx` | History tab body for any record |
| `features/audit/activityWords.ts` | Outcome, actor and change wording (pure, tested) |
| `screens.tsx`, `screens.test.ts` (modify) | Menu label "Activity log" |
| `features/policies/PolicyDetailPage.tsx`, `features/party/PartyDetailPage.tsx`, `features/claims/ClaimDetailPage.tsx`, agent / scheme / staff-user pages (modify) | History tab, gated on `audit.view` |
| `e2e/staff-activity-log.spec.ts` | End-to-end check |

---

### Task 1: Schema

**Files:**
- Create: `backend/db-migrations/audit/V5__activity.sql`
- Modify: `backend/db-migrations/_post-migration/configure-pg-partman.sql` (after the `audit.audit_log` block, around line 85)
- Modify: the spec, to add the amendment above under "Who sees it"

**Interfaces:**
- Produces:
  - table `audit.activity`;
  - column `audit.audit_log.action_id UUID NULL`;
  - index `idx_audit_log_action (action_id)`.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/audit/V5__activity.sql
-- 2026-11-14, activity log: who did what, when, to what, and whether it worked. One row per write request a
-- signed-in person (staff, agent or customer) made. Events raised by that request carry its id (audit_log.action_id),
-- so an admin sees what followed from it. Kept forever, never changed: app_role may insert and read only.

CREATE TABLE audit.activity (
    activity_id     UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor_subject   VARCHAR(100),               -- the token subject; null for System
    actor_name      VARCHAR(200),               -- display name at the time (TokenNames.displayName)
    actor_kind      VARCHAR(20) NOT NULL,       -- STAFF | AGENT | CUSTOMER
    http_method     VARCHAR(10) NOT NULL,
    route           VARCHAR(300) NOT NULL,      -- the matched pattern, e.g. /claims/{claimId}/approve
    label           VARCHAR(200) NOT NULL,
    kind            VARCHAR(30) NOT NULL,       -- claims | money | policies | people | products | settings | other
    record_type     VARCHAR(40),
    record_id       VARCHAR(100),
    record_label    VARCHAR(200),
    amount          NUMERIC(19,2),
    currency        VARCHAR(3),
    body            JSONB,                      -- as sent, secrets removed; personal numbers kept (masked on read)
    outcome         VARCHAR(10) NOT NULL,       -- DONE | REFUSED | FAILED
    status_code     INTEGER NOT NULL,
    reason          TEXT,                       -- the words the person saw, on REFUSED
    PRIMARY KEY (activity_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

DO $$
DECLARE
    month_start DATE := DATE '2026-11-01';
BEGIN
    WHILE month_start < DATE '2027-04-01' LOOP
        BEGIN
            EXECUTE format(
                'CREATE TABLE audit.%I PARTITION OF audit.activity FOR VALUES FROM (%L) TO (%L)',
                'activity_' || to_char(month_start, 'YYYY_MM'),
                month_start,
                (month_start + INTERVAL '1 month')::date);
        EXCEPTION
            WHEN invalid_object_definition OR duplicate_table THEN
                RAISE NOTICE 'activity already covers %, leaving it alone', month_start;
        END;
        month_start := (month_start + INTERVAL '1 month')::date;
    END LOOP;
END $$;

CREATE INDEX idx_activity_tenant_time ON audit.activity (tenant_id, occurred_at DESC);
CREATE INDEX idx_activity_actor ON audit.activity (tenant_id, actor_subject, occurred_at DESC);
CREATE INDEX idx_activity_record ON audit.activity (tenant_id, record_type, record_id, occurred_at DESC);

REVOKE UPDATE, DELETE ON audit.activity FROM app_role;
GRANT INSERT, SELECT ON audit.activity TO app_role;

ALTER TABLE audit.activity ENABLE ROW LEVEL SECURITY;
CREATE POLICY activity_tenant_isolation ON audit.activity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE audit.audit_log ADD COLUMN IF NOT EXISTS action_id UUID;
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit.audit_log (action_id);
```

Before writing the policy, check audit/V2's exact rewritten expression with `grep -n "NULLIF" backend/db-migrations/audit/V2__rls_fail_closed.sql`, and copy it character for character.

- [ ] **Step 2: Register with partman.** After the `audit.audit_log` block, add:

```sql
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'audit.activity') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'audit.activity',
        p_control => 'occurred_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2027-04-01'   -- audit/V5 hand-writes 2026-11 to 2027-03
);
END IF;
```

- [ ] **Step 3: Verify both paths.** The migration must apply on a bare database and on the dev database.

```bash
docker run -d --name act-mig -e POSTGRES_PASSWORD=x postgres:16; sleep 5
docker exec -i act-mig psql -U postgres -c "CREATE ROLE app_role;"
for f in V1__create_audit_schema V2__rls_fail_closed V3__q4_2026_partitions V5__activity; do
  docker exec -i act-mig psql -U postgres -v ON_ERROR_STOP=1 -q < backend/db-migrations/audit/$f.sql || echo FAIL $f; done
docker exec act-mig psql -U postgres -c "\d+ audit.activity" | grep -c activity_2026_11
docker rm -f act-mig
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < backend/db-migrations/audit/V5__activity.sql; echo EXIT $?
```

Expected: no `FAIL`, a count of 1, and `EXIT 0`.

- [ ] **Step 4: Commit**

```bash
git add backend/db-migrations/audit/V5__activity.sql backend/db-migrations/_post-migration/configure-pg-partman.sql backend/docs/superpowers/specs/2026-11-14-activity-log-design.md
git commit -m "feat(audit): activity table, and events can name the action that raised them"
```

---

### Task 2: Labels, and a guard that every write route has one

**Files:**
- Create: `backend/scripts/draft-activity-labels.mjs`
- Create: `backend/src/main/resources/audit/activity-labels.tsv`
- Create: `audit/application/ActivityLabels.java`
- Test: `audit/ActivityLabelsCoverageTest.java`

**Interfaces:**
- Produces:
  - `ActivityLabels.Label find(String method, String pattern)`, which returns null when the route is unknown;
  - `record Label(String kind, String text)`;
  - `static String key(String method, String pattern)`, which normalises every `{var}` to `{}`.

- [ ] **Step 1: Write the drafting script.** It reads every `backend/api/openapi/*.yaml` and, for each `post|put|patch|delete` operation, emits `METHOD<TAB>path<TAB>kind<TAB>label`.
  - **Kind** comes from the spec file name:
    - claims ← claims, benefitpayout
    - money ← billing, payment, policyloan, finaccounting, accumulation, unitlinked, annuity, bonus
    - policies ← policy, underwriting, omnichannel
    - people ← party, iam, distribution, organisation
    - products ← product, refdata, reinsurance
    - settings ← communication, document, regreporting, reporting, approvals, dashboard, audit
  - **Label** is the summary's first clause (cut at `,`, ` -- `, ` (`), with its first verb turned to past tense from a fixed map.
  - A verb missing from the map is left as written and printed with `CHECK`, so a person reviews it.

```js
// backend/scripts/draft-activity-labels.mjs -- usage: node scripts/draft-activity-labels.mjs > src/main/resources/audit/activity-labels.tsv
import fs from 'node:fs';
const dir = 'api/openapi/';
const KIND = { claims: 'claims', benefitpayout: 'claims', billing: 'money', payment: 'money', policyloan: 'money',
  finaccounting: 'money', accumulation: 'money', unitlinked: 'money', annuity: 'money', bonus: 'money',
  policy: 'policies', underwriting: 'policies', omnichannel: 'policies', party: 'people', iam: 'people',
  distribution: 'people', organisation: 'people', product: 'products', refdata: 'products', reinsurance: 'products' };
const PAST = { add: 'Added', approve: 'Approved', reject: 'Rejected', refuse: 'Refused', record: 'Recorded',
  create: 'Created', update: 'Updated', change: 'Changed', set: 'Set', delete: 'Deleted', remove: 'Removed',
  withdraw: 'Withdrew', cancel: 'Cancelled', register: 'Registered', request: 'Requested', submit: 'Submitted',
  decide: 'Decided', issue: 'Issued', propose: 'Proposed', publish: 'Published', confirm: 'Confirmed', pay: 'Paid',
  post: 'Posted', reverse: 'Reversed', upload: 'Uploaded', file: 'Filed', assign: 'Assigned', grant: 'Granted',
  revoke: 'Revoked', reset: 'Reset', send: 'Sent', resend: 'Resent', close: 'Closed', open: 'Opened', run: 'Ran',
  start: 'Started', stop: 'Stopped', end: 'Ended', mark: 'Marked', match: 'Matched', allocate: 'Allocated',
  waive: 'Waived', undo: 'Undid', correct: 'Corrected', amend: 'Amended', accept: 'Accepted', decline: 'Declined',
  activate: 'Activated', deactivate: 'Deactivated', suspend: 'Suspended', reinstate: 'Reinstated', lapse: 'Lapsed',
  surrender: 'Surrendered', import: 'Imported', price: 'Priced', quote: 'Quoted', review: 'Reviewed', sign: 'Signed',
  save: 'Saved', settle: 'Settled', assess: 'Assessed', invite: 'Invited', link: 'Linked', unlink: 'Unlinked',
  enrol: 'Enrolled', lock: 'Locked', unlock: 'Unlocked', vest: 'Vested', redirect: 'Redirected', switch: 'Switched',
  apply: 'Applied', generate: 'Generated', calculate: 'Calculated', move: 'Moved', return: 'Returned', give: 'Gave',
  take: 'Took', put: 'Put', make: 'Made', keep: 'Kept', note: 'Noted' };
const rows = [];
for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.yaml')).sort()) {
  const module = f.replace(/^openapi-|\.yaml$/g, '');
  const kind = KIND[module] ?? 'settings';
  const lines = fs.readFileSync(dir + f, 'utf8').split(/\r?\n/);
  let path = null;
  for (let i = 0; i < lines.length; i++) {
    const p = /^  (\/\S*):\s*$/.exec(lines[i]); if (p) { path = p[1]; continue; }
    const m = /^    (post|put|patch|delete):/.exec(lines[i]); if (!m || !path) continue;
    let summary = '';
    for (let j = i + 1; j < i + 8; j++) { const s = /^      summary:\s*(.+)$/.exec(lines[j] ?? ''); if (s) { summary = s[1].replace(/^['"]|['"]$/g, ''); break; } }
    const clause = summary.split(/,| -- | \(|; /)[0].trim();
    const [verb, ...rest] = clause.split(' ');
    const past = PAST[verb?.toLowerCase()];
    const label = past ? [past, ...rest].join(' ') : clause;
    rows.push([m[1].toUpperCase(), path, kind, label + (past ? '' : '\tCHECK')].join('\t'));
  }
}
console.log('# METHOD\tpattern\tkind\tlabel   -- drafted by scripts/draft-activity-labels.mjs, edited by hand, reviewed by the user');
for (const r of rows) console.log(r);
```

- [ ] **Step 2: Draft, then edit by hand.**

```bash
cd backend && node scripts/draft-activity-labels.mjs > src/main/resources/audit/activity-labels.tsv
grep -c . src/main/resources/audit/activity-labels.tsv; grep -c CHECK src/main/resources/audit/activity-labels.tsv
```

Expected: 275 lines (274 routes plus the header). Then edit every `CHECK` row into a past-tense label and remove the `CHECK` column. Re-read each label against its summary, and invent nothing beyond the summary's own words.

- [ ] **Step 3: Write `ActivityLabels`**

```java
package tz.co.nlolo.lifeplatform.audit.application;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** What each write action is called in the activity log (2026-11-14). One line per route in audit/activity-labels.tsv. */
@Component
public class ActivityLabels {

    public record Label(String kind, String text) {}

    private final Map<String, Label> labels = new HashMap<>();

    public ActivityLabels() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("audit/activity-labels.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length < 4) throw new IllegalStateException("activity-labels.tsv: needs 4 columns: " + line);
                labels.put(key(f[0], f[1]), new Label(f[2], f[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Label find(String method, String pattern) {
        return labels.get(key(method, pattern));
    }

    /** Variable names differ between the API description and the controllers ({id} vs {claimId}); the shape is what matters. */
    public static String key(String method, String pattern) {
        return method.toUpperCase() + " " + pattern.replaceAll("\\{[^}]*}", "{}");
    }
}
```

- [ ] **Step 4: Write the failing guard test.** It enumerates the routes the application actually serves.

```java
package tz.co.nlolo.lifeplatform.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Every write route a person can call has words in the activity log; a new one cannot ship unlabelled. */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ActivityLabelsCoverageTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final Set<RequestMethod> WRITES =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ActivityLabels labels;

    @Test
    void everyWriteRouteHasALabel() {
        Set<String> missing = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (!WRITES.contains(method)) continue;
                for (String pattern : info.getPatternValues()) {
                    if (tz.co.nlolo.lifeplatform.audit.infrastructure.ActivityRecordingFilter.unrecorded(pattern)) continue;
                    if (labels.find(method.name(), pattern) == null) missing.add(method + " " + pattern);
                }
            }
        });
        assertThat(missing).as("write routes with no line in audit/activity-labels.tsv").isEmpty();
    }
}
```

This test references `ActivityRecordingFilter.unrecorded(String)` from Task 4. Add that static method now, in a stub filter class with only this method, so the test compiles:

```java
public static boolean unrecorded(String pattern) {
    return pattern.startsWith("/public/") || pattern.startsWith("/internal/") || pattern.startsWith("/payments/callback");
}
```

Check the callback literal first: `grep -n CALLBACK_PATH backend/src/main/java/tz/co/nlolo/lifeplatform/payment/infrastructure/MobileMoneyHmacFilter.java`, and use its value.

- [ ] **Step 5: Run it.** It lists what the TSV is missing or names differently. Fix rows until it passes.

```bash
cd backend && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw -q test -Dtest=ActivityLabelsCoverageTest -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/labels.log" 2>&1; echo EXIT $?
```

Expected end state: `EXIT 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/scripts/draft-activity-labels.mjs backend/src/main/resources/audit/activity-labels.tsv backend/src/main/java/tz/co/nlolo/lifeplatform/audit backend/src/test/java/tz/co/nlolo/lifeplatform/audit/ActivityLabelsCoverageTest.java
git commit -m "feat(audit): a plain-words label for every write action, and a guard that none ships without one"
```

---

### Task 3: Redaction and record references (pure, unit-tested)

**Files:**
- Create: `audit/application/ActivityRedaction.java`
- Create: `audit/application/RecordRefs.java`
- Test: `audit/ActivityRedactionTest.java`
- Test: `audit/RecordRefsTest.java`

**Interfaces:**
- Produces:
  - `ActivityRedaction.withoutSecrets(JsonNode body) → JsonNode` (copy, secrets removed);
  - `ActivityRedaction.masked(JsonNode body) → JsonNode` (copy, personal numbers masked);
  - `ActivityRedaction.mask(String) → String`;
  - `RecordRefs.Ref of(Map<String,String> pathVariables, String pattern)`, where `record Ref(String type, String id)` and null means no record;
  - `RecordRefs.label(JsonNode responseBody) → String or null`;
  - `RecordRefs.amount(JsonNode requestBody, JsonNode responseBody) → Money or null`.

- [ ] **Step 1: Failing tests**

```java
package tz.co.nlolo.lifeplatform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRedaction;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRedactionTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(String s) throws Exception { return json.readTree(s); }

    @Test
    void secretsAreNeverKeptAtAnyDepth() throws Exception {
        JsonNode kept = ActivityRedaction.withoutSecrets(body(
            "{\"password\":\"x\",\"code\":\"123456\",\"signInCode\":\"9\",\"pin\":\"1\",\"accessToken\":\"t\","
                + "\"nested\":{\"newPassword\":\"y\",\"clientSecret\":\"s\"},\"productCode\":\"FUN-01\",\"amount\":5}"));
        assertThat(kept.toString()).doesNotContain("\"x\"", "123456", "accessToken", "newPassword", "clientSecret",
            "\"pin\"", "signInCode");
        assertThat(kept.get("productCode").asText()).as("a code that names a thing is not a secret").isEqualTo("FUN-01");
        assertThat(kept.get("amount").asInt()).isEqualTo(5);
    }

    @Test
    void personalNumbersAreMaskedToTheirLastThree() throws Exception {
        JsonNode masked = ActivityRedaction.masked(body(
            "{\"phoneNumber\":\"+255755418369\",\"nationalId\":\"19800101-12345-00001-23\",\"payeeRef\":\"0712345678\","
                + "\"accountNumber\":\"0150123456789\",\"fullName\":\"Juma K\"}"));
        assertThat(masked.get("phoneNumber").asText()).isEqualTo("••••369");
        assertThat(masked.get("nationalId").asText()).isEqualTo("••••-23");
        assertThat(masked.get("payeeRef").asText()).isEqualTo("••••678");
        assertThat(masked.get("accountNumber").asText()).isEqualTo("••••789");
        assertThat(masked.get("fullName").asText()).isEqualTo("Juma K");
    }
}
```

```java
package tz.co.nlolo.lifeplatform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.audit.application.RecordRefs;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordRefsTest {

    @Test
    void thePathVariableNamesTheRecord() {
        assertThat(RecordRefs.of(Map.of("policyNumber", "POL-1"), "/policies/{policyNumber}/lapse"))
            .isEqualTo(new RecordRefs.Ref("policy", "POL-1"));
        assertThat(RecordRefs.of(Map.of("claimId", "c-1"), "/claims/{claimId}/approve"))
            .isEqualTo(new RecordRefs.Ref("claim", "c-1"));
        assertThat(RecordRefs.of(Map.of("partyId", "p-1"), "/parties/{partyId}")).isEqualTo(new RecordRefs.Ref("client", "p-1"));
    }

    @Test
    void anUnknownVariableNamesItsFirstPathSegment() {
        assertThat(RecordRefs.of(Map.of("treatyId", "t-1"), "/treaties/{treatyId}/approve"))
            .isEqualTo(new RecordRefs.Ref("treaties", "t-1"));
        assertThat(RecordRefs.of(Map.of(), "/products")).isNull();
    }

    @Test
    void theResponseGivesTheRecordAReadableName() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(RecordRefs.label(json.readTree("{\"claimNumber\":\"CLM-0042\",\"policyNumber\":\"POL-1\"}")))
            .isEqualTo("CLM-0042");
        assertThat(RecordRefs.label(json.readTree("{\"fullName\":\"Juma K\"}"))).isEqualTo("Juma K");
        assertThat(RecordRefs.label(json.readTree("[1,2]"))).isNull();
    }
}
```

- [ ] **Step 2: Run them.** They should fail because the classes don't exist yet.

```bash
cd backend && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw -q test -Dtest='ActivityRedactionTest,RecordRefsTest' -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/t3.log" 2>&1; echo EXIT $?
```

Expected: `EXIT 1`, with a compilation error.

- [ ] **Step 3: Implement**

```java
package tz.co.nlolo.lifeplatform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** What the activity log never keeps, and what it shows only partly (2026-11-14). */
public final class ActivityRedaction {

    private ActivityRedaction() {}

    private static final Set<String> SECRET_NAMES = Set.of("password", "code", "otp", "pin", "token", "secret",
        "authorization", "signincode", "verificationcode", "resetcode");
    private static final String[] SECRET_ENDINGS = {"password", "token", "secret", "otp", "signincode", "pin"};
    private static final String[] PERSONAL = {"phone", "msisdn", "nationalid", "idnumber", "accountnumber", "payee"};

    static boolean secret(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(f)) return true;
        for (String end : SECRET_ENDINGS) if (f.endsWith(end)) return true;
        return false;
    }

    static boolean personal(String field) {
        String f = field.toLowerCase(Locale.ROOT);
        for (String p : PERSONAL) if (f.contains(p)) return true;
        return false;
    }

    public static JsonNode withoutSecrets(JsonNode body) {
        if (body == null) return null;
        JsonNode copy = body.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
                if (secret(it.next())) it.remove();
            }
            o.elements().forEachRemaining(ActivityRedaction::strip);
        } else if (node instanceof ArrayNode a) {
            a.elements().forEachRemaining(ActivityRedaction::strip);
        }
    }

    public static JsonNode masked(JsonNode body) {
        if (body == null) return null;
        JsonNode copy = body.deepCopy();
        mask(copy);
        return copy;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode o) {
            o.fieldNames().forEachRemaining(name -> {
                JsonNode v = o.get(name);
                if (personal(name) && v.isValueNode() && !v.isNull()) o.set(name, TextNode.valueOf(mask(v.asText())));
                else mask(v);
            });
        } else if (node instanceof ArrayNode a) {
            a.elements().forEachRemaining(ActivityRedaction::mask);
        }
    }

    public static String mask(String value) {
        if (value == null || value.length() <= 3) return "••••";
        return "••••" + value.substring(value.length() - 3);
    }
}
```

`o.fieldNames()` must not be modified while it is being iterated. `mask` only replaces values, which is safe for `ObjectNode`. If it isn't, collect the names into a list first.

```java
package tz.co.nlolo.lifeplatform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import tz.co.nlolo.lifeplatform.common.Money; // check the real package: grep -rn "^public record Money" backend/src/main/java

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which record a write acted on, and what to call it (2026-11-14). */
public final class RecordRefs {

    private RecordRefs() {}

    public record Ref(String type, String id) {}

    /** Path variable -> record type, most specific first. A route naming several (a claim on a policy) is about the last listed here that it carries. */
    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("policyNumber", "policy");
        TYPES.put("partyId", "client");
        TYPES.put("agentId", "agent");
        TYPES.put("schemeId", "scheme");
        TYPES.put("userId", "staff user");
        TYPES.put("staffUserId", "staff user");
        TYPES.put("productCode", "product");
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
        String first = pattern.replaceFirst("^/", "").split("/")[0];
        return new Ref(first, variables.values().iterator().next());
    }

    private static final List<String> LABEL_KEYS = List.of("claimNumber", "caseNumber", "receiptNumber", "policyNumber",
        "fullName", "displayName", "name");

    public static String label(JsonNode response) {
        if (response == null || !response.isObject()) return null;
        for (String key : LABEL_KEYS) {
            JsonNode v = response.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    /** The first {amount, currencyCode} money object, request before response; a bare "amount" number in TZS. */
    public static Money amount(JsonNode request, JsonNode response) {
        for (JsonNode body : new JsonNode[] {request, response}) {
            if (body == null || !body.isObject()) continue;
            for (String key : List.of("amount", "approvedAmount", "settledAmount", "sumAssured")) {
                JsonNode m = body.get(key);
                if (m == null || m.isNull()) continue;
                if (m.isObject() && m.hasNonNull("amount") && m.hasNonNull("currencyCode")) {
                    return Money.of(new BigDecimal(m.get("amount").asText()), m.get("currencyCode").asText());
                }
                if (m.isNumber() || m.isTextual() && m.asText().matches("-?\\d+(\\.\\d+)?")) {
                    String currency = body.hasNonNull("currency") ? body.get("currency").asText() : "TZS";
                    return Money.of(new BigDecimal(m.asText()), currency);
                }
            }
        }
        return null;
    }
}
```

`TYPES` iterates in insertion order and the last match wins. So `claimId` wins over `policyNumber` (a claim route), and `caseId` wins over `partyId`. Keep that order.

- [ ] **Step 4: Run.** Expected: `EXIT 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/audit/application/ActivityRedaction.java backend/src/main/java/tz/co/nlolo/lifeplatform/audit/application/RecordRefs.java backend/src/test/java/tz/co/nlolo/lifeplatform/audit/ActivityRedactionTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/audit/RecordRefsTest.java
git commit -m "feat(audit): secrets never kept, personal numbers masked, and each action names its record"
```

---

### Task 4: Recording

**Files:**
- Create: `audit/application/ActivityContext.java`
- Create: `audit/application/ActivityRecorder.java`
- Create: `audit/infrastructure/ActivityRecordingFilter.java` (replaces the Task 2 stub)
- Create: `audit/infrastructure/ActivityFilterConfig.java`
- Modify: `audit/infrastructure/DomainEventAuditListener.java`
- Modify: `audit/domain/AuditLogEntry.java`
- Test: `audit/ActivityRecordingIntegrationTest.java`

**Interfaces:**
- Consumes: `ActivityLabels.find`, `ActivityRedaction.withoutSecrets`, `RecordRefs.of/label/amount`, `TokenNames.displayName(Jwt)`, `TenantContext.getOrNull()`.
- Produces:
  - `ActivityContext.current() → UUID or null`;
  - `ActivityRecorder.record(ActivityRecorder.Row)`;
  - `audit_log.action_id` filled for events raised inside a recorded request.

- [ ] **Step 1: Failing integration test.**
  - It drives a real write through MockMvc, with filters on, as a signed-in staff member.
  - It picks a write whose service needs only the audit schema plus one module. A good candidate is organisation settings: `PUT /organisation/...`, migrated by `organisation/V*`. Find it with `grep -n "Mapping" backend/src/main/java/tz/co/nlolo/lifeplatform/organisation/infrastructure/OrganisationController.java`.
  - It copies the JWT post-processor from `OrganisationContractTest` (or that module's nearest contract test), including its tenant claim and roles.

```java
// assertions, after performing one successful and one refused request:
List<Map<String, Object>> rows = jdbc.queryForList(
    "SELECT actor_name, actor_kind, label, outcome, reason, record_type, body::text AS body FROM audit.activity ORDER BY occurred_at");
assertThat(rows).hasSize(2);
assertThat(rows.get(0)).containsEntry("actor_kind", "STAFF").containsEntry("outcome", "DONE");
assertThat(rows.get(0).get("label")).isNotNull();          // the TSV's words, not the route
assertThat(rows.get(0).get("actor_name")).isEqualTo("Asha Admin");   // the token's name claim in the test JWT
assertThat(rows.get(1)).containsEntry("outcome", "REFUSED");
assertThat((String) rows.get(1).get("reason")).isNotBlank().doesNotContain("Exception");
// events the successful request raised carry its id
Long joined = jdbc.queryForObject("SELECT count(*) FROM audit.audit_log a JOIN audit.activity v ON a.action_id = v.activity_id", Long.class);
assertThat(joined).isPositive();
```

The test database applies `audit/V1`, `V2`, `V3`, `V5` and the module's migrations through `MigrationTestSupport`. Add `.with(jwt().jwt(j -> j.claim("name", "Asha Admin")...))` so the name is known. If the chosen action raises no event, assert the join on a second action that does, or drop that assertion and cover the join in Step 6 with a direct test of `DomainEventAuditListener` plus `ActivityContext`.

- [ ] **Step 2: Run it.** Expected: it fails because `audit.activity` has no rows.

- [ ] **Step 3: Implement the context and the recorder**

```java
package tz.co.nlolo.lifeplatform.audit.application;

import java.util.UUID;

/** The action a request is, while it runs, so the events it raises can name it (2026-11-14). */
public final class ActivityContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private ActivityContext() {}
    public static void set(UUID actionId) { CURRENT.set(actionId); }
    public static UUID current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
```

```java
package tz.co.nlolo.lifeplatform.audit.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Writes one activity row, in a transaction of its own: whatever the request did, its record is kept. */
@Component
public class ActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ActivityRecorder.class);

    public record Row(UUID activityId, UUID tenantId, Instant occurredAt, String actorSubject, String actorName,
                      String actorKind, String method, String route, String label, String kind, String recordType,
                      String recordId, String recordLabel, BigDecimal amount, String currency, String bodyJson,
                      String outcome, int statusCode, String reason) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;

    public ActivityRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Row r) {
        try {
            own.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO audit.activity (activity_id, tenant_id, occurred_at, actor_subject, actor_name, actor_kind,
                  http_method, route, label, kind, record_type, record_id, record_label, amount, currency, body,
                  outcome, status_code, reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
                r.activityId(), r.tenantId(), Timestamp.from(r.occurredAt()), r.actorSubject(), r.actorName(),
                r.actorKind(), r.method(), r.route(), r.label(), r.kind(), r.recordType(), r.recordId(),
                r.recordLabel(), r.amount(), r.currency(), r.bodyJson(), r.outcome(), r.statusCode(), r.reason()));
        } catch (RuntimeException e) {
            // Never fails the person's request: it has already been answered. Loud, so a gap is seen.
            log.error("Could not record activity {} {} for tenant {}", r.method(), r.route(), r.tenantId(), e);
        }
    }
}
```

- [ ] **Step 4: Implement the filter and its registration**

```java
package tz.co.nlolo.lifeplatform.audit.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.TokenNames;
import tz.co.nlolo.lifeplatform.audit.application.ActivityContext;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRecorder;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRedaction;
import tz.co.nlolo.lifeplatform.audit.application.RecordRefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records every write a signed-in person makes (2026-11-14, activity log). Registered just inside Spring Security, so
 * the person and the tenant are known; the route and its variables are read after the controller has run.
 */
public class ActivityRecordingFilter extends OncePerRequestFilter {

    static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final int BODY_CAP = 64 * 1024;

    private final ActivityLabels labels;
    private final ActivityRecorder recorder;
    private final ObjectMapper json;

    public ActivityRecordingFilter(ActivityLabels labels, ActivityRecorder recorder, ObjectMapper json) {
        this.labels = labels;
        this.recorder = recorder;
        this.json = json;
    }

    public static boolean unrecorded(String pattern) {
        return pattern.startsWith("/public/") || pattern.startsWith("/internal/") || pattern.startsWith("/payments/callback");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITES.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID tenant = TenantContext.getOrNull();
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt) || tenant == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID actionId = UUID.randomUUID();
        Instant started = Instant.now();
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, BODY_CAP);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        ActivityContext.set(actionId);
        try {
            chain.doFilter(req, res);
        } finally {
            ActivityContext.clear();
            try {
                record(actionId, tenant, started, jwt, auth, req, res);
            } finally {
                res.copyBodyToResponse();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void record(UUID actionId, UUID tenant, Instant started, Jwt jwt, Authentication auth,
                        ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        String pattern = (String) req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null || unrecorded(pattern)) return;   // never reached a controller, or not a person's act
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        ActivityLabels.Label label = labels.find(req.getMethod(), pattern);
        JsonNode body = readJson(req.getContentAsByteArray(), req.getContentType());
        JsonNode answer = readJson(res.getContentAsByteArray(), res.getContentType());
        RecordRefs.Ref ref = RecordRefs.of(vars, pattern);
        var money = RecordRefs.amount(body, answer);
        int status = res.getStatus();
        String outcome = status < 400 ? "DONE" : status < 500 ? "REFUSED" : "FAILED";
        String reason = status >= 400 && answer != null && answer.hasNonNull("detail") ? answer.get("detail").asText() : null;
        recorder.record(new ActivityRecorder.Row(actionId, tenant, started, jwt.getSubject(), TokenNames.displayName(jwt),
            kindOf(auth), req.getMethod(), pattern,
            label == null ? req.getMethod() + " " + pattern : label.text(), label == null ? "other" : label.kind(),
            ref == null ? null : ref.type(), ref == null ? null : ref.id(), RecordRefs.label(answer),
            money == null ? null : money.amount(), money == null ? null : money.currencyCode(),
            body == null ? uploadNote(req) : ActivityRedaction.withoutSecrets(body).toString(),
            outcome, status, reason));
    }

    /** The workspace the request ran in, from the authorities the converter granted (ROLE_REALM_STAFF etc.). */
    private static String kindOf(Authentication auth) {
        for (var a : auth.getAuthorities()) {
            switch (a.getAuthority()) {
                case "ROLE_REALM_AGENT" -> { return "AGENT"; }
                case "ROLE_REALM_CUSTOMER" -> { return "CUSTOMER"; }
                default -> { }
            }
        }
        return "STAFF";
    }

    private JsonNode readJson(byte[] bytes, String contentType) {
        if (bytes.length == 0 || contentType == null || !contentType.contains("json")) return null;
        try {
            return json.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** A file upload: its name and size, never its content. */
    private String uploadNote(HttpServletRequest req) {
        String type = req.getContentType();
        if (type == null || !type.startsWith("multipart/")) return null;
        try {
            var files = json.createArrayNode();
            for (var part : req.getParts()) {
                if (part.getSubmittedFileName() != null) {
                    files.addObject().put("file", part.getSubmittedFileName()).put("bytes", part.getSize());
                }
            }
            return json.createObjectNode().set("files", files).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
```

Before relying on `kindOf`, check the realm role names: `grep -rn "ROLE_REALM_" backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/SecurityConfig.java backend/src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/Workspace.java`. Use the real names.

Check the `Money` accessors too: `grep -n "record Money" -A3 -r backend/src/main/java`. Use its real component names.

```java
package tz.co.nlolo.lifeplatform.audit.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.audit.application.ActivityLabels;
import tz.co.nlolo.lifeplatform.audit.application.ActivityRecorder;

/**
 * Just inside Spring Security's filter chain: a servlet filter ordered after it runs within its doFilter, so within
 * TenantContextFilter's try -- the tenant and the person are set, and the dispatcher has not yet run.
 */
@Configuration
class ActivityFilterConfig {

    @Bean
    FilterRegistrationBean<ActivityRecordingFilter> activityRecording(ActivityLabels labels, ActivityRecorder recorder,
                                                                      ObjectMapper json) {
        FilterRegistrationBean<ActivityRecordingFilter> bean =
            new FilterRegistrationBean<>(new ActivityRecordingFilter(labels, recorder, json));
        bean.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return bean;
    }
}
```

- [ ] **Step 5: Stamp events with their action.**
  - In `AuditLogEntry`, add `@Column(name = "action_id") private UUID actionId;` plus a constructor parameter after `payload`, and a `getActionId()`.
  - In `DomainEventAuditListener.onDomainEvent`, pass `tz.co.nlolo.lifeplatform.audit.application.ActivityContext.current()`.
  - Update the constructor's other callers: `grep -rn "new AuditLogEntry(" backend/src`.

- [ ] **Step 6: Run** the integration test, `DomainEventAuditListenerIntegrationTest`, `DurableEventsIntegrationTest` and `ModularityTests`. Expected: all pass.

If `ModularityTests` rejects the audit module using `TokenNames` (root package): `TokenNames` lives in the shared root package and other modules already use it, so that should not happen.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/audit backend/src/test/java/tz/co/nlolo/lifeplatform/audit/ActivityRecordingIntegrationTest.java
git commit -m "feat(audit): every write a person makes is recorded -- who, what, which record, the outcome -- and its events name it"
```

---

### Task 5: Read API

**Files:**
- Create: `audit/api/EventRefs.java` (moved from `RecentActivity`, which then calls it)
- Create: `audit/application/ActivityQueries.java`
- Create: `audit/infrastructure/ActivityController.java`
- Modify: `backend/api/openapi/openapi-audit.yaml`
- Modify: `dashboard/application/RecentActivity.java`
- Test: `audit/ActivityContractTest.java`

**Interfaces:**
- Produces:
  - `GET /activity?person&recordType&recordId&q&kind&outcome&from&to&page&pageSize` returns `ActivityPage { items: ActivityLine[], page: PageInfo }`;
  - `GET /activity/{activityId}` returns `ActivityDetail`;
  - `GET /activity.csv` with the same query returns `text/csv`, capped at 10,000 rows;
  - `GET /activity/people` returns `[{subject, name}]` for the person filter, and is the only list of people the page needs.
- Schemas, in `openapi-audit.yaml`:
  - `ActivityLine { id, occurredAt, actorName|null, actorKind (STAFF|AGENT|CUSTOMER|SYSTEM), label, kind, recordType|null, recordId|null, recordLabel|null, amount: Money|null, outcome (DONE|REFUSED|FAILED), reason|null, eventCount }`. A standalone event becomes `actorKind SYSTEM`, `label` = event type, `outcome DONE`, `id` = event id, `eventCount 0`.
  - `ActivityDetail { line: ActivityLine, changes: [{field, before|null, after}], events: [ActivityEvent], technical: { route, method, statusCode, body (full, secrets already removed) } }`.
  - `ActivityEvent { eventType, occurredAt, refs: {string: string}, amount: Money|null }`. This is the same shape as the dashboard's `ActivityItem`, so the console's `describeActivity` words it.

- [ ] **Step 1: Move the ref extraction.**
  - Create `audit/api/EventRefs.java` with `public static Map<String,String> refs(JsonNode payload)` and `public static Money amount(JsonNode payload)`. The bodies are the loops from `RecentActivity.item`, with `REF_KEYS` and `AMOUNT_KEYS` moving too.
  - Make `RecentActivity.item` call them. The dashboard already depends on `audit::api`, so the dependency still runs that way.
  - Run `RecentActivity`'s tests (`grep -rl RecentActivity backend/src/test`) to prove nothing changed.

- [ ] **Step 2: Write the OpenAPI** for the four operations and five schemas above, in `openapi-audit.yaml`, copying that file's existing `/audit-log` style (security, `ProblemDetail` refs, `PageInfo`). Write `required:` lists explicitly; per memory, openapi-typescript makes `default:` required.

- [ ] **Step 3: Failing contract test.** Copy `AccumulationContractTest`'s setup (MockMvc, `OpenApiValidationMatchers.openApi().isValid("api/openapi/openapi-audit.yaml")`, a JWT holding `audit.view`). Seed rows directly with `JdbcTemplate`:
- two activity rows for two people on one policy: one DONE, one REFUSED with a body carrying `phoneNumber`;
- one `audit_log` event joined to the DONE row;
- one standalone `audit_log` event.

Assert:
- `GET /activity` → 3 lines, newest first; the joined event is not its own line;
- `GET /activity?outcome=REFUSED` → 1;
- `GET /activity?recordType=policy&recordId=POL-1` → the 2 actions plus the standalone event, if its payload's `policyNumber` is `POL-1`;
- `GET /activity/{id}` of the DONE row → `events` has 1 item;
- `changes` lists its body fields;
- `technical.body.phoneNumber` is in full;
- the list's `reason` has no `Exception`;
- `GET /activity.csv` → a header plus 3 rows, and the phone masked;
- without `audit.view` → 403.

- [ ] **Step 4: Implement `ActivityQueries`** as native SQL over a `UNION ALL`, with the same no-null-binds rule as `AuditLogRepository.listEvents`: substitute wide-open values for absent filters.

```sql
SELECT * FROM (
  SELECT v.activity_id AS id, v.occurred_at, v.actor_subject, v.actor_name, v.actor_kind, v.label, v.kind,
         v.record_type, v.record_id, v.record_label, v.amount, v.currency, v.outcome, v.reason,
         (SELECT count(*) FROM audit.audit_log a WHERE a.action_id = v.activity_id) AS event_count
  FROM audit.activity v
  WHERE v.tenant_id = :tenant AND v.occurred_at BETWEEN :from AND :to
    AND (:person = '' OR v.actor_subject = :person)
    AND (:kind = '' OR v.kind = :kind)
    AND (:outcome = '' OR v.outcome = :outcome)
    AND (:recordType = '' OR (v.record_type = :recordType AND v.record_id = :recordId))
    AND (:q = '' OR v.record_label ILIKE :qLike OR v.record_id ILIKE :qLike OR v.label ILIKE :qLike)
  UNION ALL
  SELECT a.event_id, a.occurred_at, NULL, NULL, 'SYSTEM', a.event_type, :eventKindExpr, NULL, NULL,
         a.payload->>'policyNumber', NULL, NULL, 'DONE', NULL, 0
  FROM audit.audit_log a
  WHERE a.tenant_id = :tenant AND a.action_id IS NULL AND a.occurred_at BETWEEN :from AND :to
    AND a.event_type NOT IN ('finaccounting.GlPostingRecorded','finaccounting.PaaRevenueEarned','billing.PremiumInvoiceGenerated')
    AND (:person = '' OR :person = 'SYSTEM')
    AND (:outcome = '' OR :outcome = 'DONE')
    AND (:recordType = '' OR (:recordType = 'policy' AND a.payload->>'policyNumber' = :recordId)
                          OR (:recordType = 'claim' AND a.payload->>'claimId' = :recordId)
                          OR (:recordType = 'client' AND a.payload->>'partyId' = :recordId))
    AND (:q = '' OR a.payload::text ILIKE :qLike)
) lines ORDER BY occurred_at DESC LIMIT :limit OFFSET :offset
```

- `:eventKindExpr` is a `CASE split_part(a.event_type,'.',1) WHEN 'claims' THEN 'claims' ...` that mirrors the Task 2 kind map. Write it out literally; don't bind it.
- `NOISE` is the same list as `RecentActivity.NOISE`. Reference `EventRefs.NOISE` and move it there in Step 1.
- Use a second query of the same shape for `count(*)`.
- Read with `JdbcTemplate`, under RLS through the tenant GUC, like every other read.
- `:kind` filters standalone events by `:eventKindExpr`, so write that predicate too.
- The person dropdown passes the subject. `SYSTEM` is a pseudo-person.

Detail:
- load the row and its events (`WHERE action_id = :id ORDER BY occurred_at`), each mapped through `EventRefs`;
- `changes` comes from walking the body's top-level fields. For each field, `before` is the same field in the latest earlier DONE row with the same `record_type` and `record_id` that carried it (one query per opened line: `SELECT body FROM audit.activity WHERE ... AND occurred_at < :t AND outcome='DONE' ORDER BY occurred_at DESC LIMIT 20`, scanned in Java). Null means nothing earlier.
- A field whose value is an object or an array is shown as `after` = its compact JSON, cut to 200 characters.

The list and the CSV mask: run `ActivityRedaction.mask` on any field `personal()` would mask, plus `reason`'s free text as-is. The detail does not mask.

- [ ] **Step 5: Implement `ActivityController`.**
  - Gate every method with `@PreAuthorize("@access.can('audit.view')")`.
  - Default `from` = now − 7 days and `to` = now; cap `pageSize` at 100.
  - CSV follows `FileDownloadResponses` in the root package (`grep -n "public static" backend/src/main/java/tz/co/nlolo/lifeplatform/FileDownloadResponses.java`). Columns: `When, Who, Did, Record, Amount, Outcome, Reason`. Dates are Africa/Dar_es_Salaam civil time.

- [ ] **Step 6: Run** the contract test and the dashboard tests. Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add backend/api/openapi/openapi-audit.yaml backend/src/main/java/tz/co/nlolo/lifeplatform/audit backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/RecentActivity.java backend/src/test/java/tz/co/nlolo/lifeplatform/audit/ActivityContractTest.java
git commit -m "feat(audit): read the activity log -- filtered lines, one opened with what changed and what followed, and a CSV"
```

---

### Task 6: The page

**Files:**
- Regenerate: `frontend/src/types/api/audit.ts` (`npm run generate:api`)
- Create: `frontend/src/api/activity.ts`
- Create: `frontend/src/store/activityStore.ts`
- Create: `frontend/src/features/audit/activityWords.ts` (+ `.test.ts`)
- Create: `frontend/src/features/audit/ActivityLine.tsx`
- Create: `frontend/src/features/audit/ActivityLogPage.tsx` (+ `.test.tsx`)
- Delete: `frontend/src/features/audit/AuditLogPage.tsx`, `frontend/src/api/audit.ts`, `frontend/src/store/auditStore.ts`. Check first that nothing else imports them: `grep -rn "api/audit'\|auditStore\|AuditLogPage" frontend/src`.
- Modify: `frontend/src/screens.tsx:519-524` — label `Activity log`, element `<ActivityLogPage />`, path unchanged.
- Modify: the comment at `screens.tsx:311-317`, which no longer applies. Replace it with one line saying the group is "Records" and the page is gated on `audit.view` (`screens.tsx:390`).
- Modify: `frontend/src/screens.test.ts:149-159` — `'Activity log'`.

**Interfaces:**
- Consumes: the Task 5 endpoints and `describeActivity` (`features/dashboard/activity.ts:119`).
- Produces: `ActivityLine` and `RecordHistory` props, both used by Task 7.

- [ ] **Step 1: Pure wording, test first** (`activityWords.test.ts`)

```ts
import { describe, expect, it } from 'vitest';
import { actorWords, outcomeWords, changeWords } from './activityWords';

describe('activity words', () => {
  it('names who acted in plain words', () => {
    expect(actorWords({ actorName: 'Asha M.', actorKind: 'STAFF' })).toBe('Asha M. (staff)');
    expect(actorWords({ actorName: 'Juma K', actorKind: 'CUSTOMER' })).toBe('Juma K (customer)');
    expect(actorWords({ actorName: null, actorKind: 'SYSTEM' })).toBe('System');
    expect(actorWords({ actorName: null, actorKind: 'STAFF' })).toBe('Someone (staff)');
  });
  it('says the outcome, and why a refusal happened', () => {
    expect(outcomeWords({ outcome: 'DONE', reason: null })).toBe('Done');
    expect(outcomeWords({ outcome: 'REFUSED', reason: 'You cannot approve your own payout' }))
      .toBe('Refused: You cannot approve your own payout');
    expect(outcomeWords({ outcome: 'FAILED', reason: null })).toBe('Failed');
  });
  it('shows a change as before and after, or set to', () => {
    expect(changeWords({ field: 'premium', before: '45000', after: '52000' })).toBe('Premium: 45000 → 52000');
    expect(changeWords({ field: 'phoneNumber', before: null, after: '+255713000111' })).toBe('Phone number: set to +255713000111');
  });
});
```

Implement `activityWords.ts`:
- `actorWords`, from the kind map `STAFF→staff`, `AGENT→agent`, `CUSTOMER→customer`;
- `outcomeWords`;
- `changeWords`, which humanises the field name as in `activity.ts`'s `humanise` (camelCase split, first letter capitalised). Export a shared `humaniseField` rather than copying.

Run `npx vitest run src/features/audit/activityWords.test.ts`. Expected: fail, then pass.

- [ ] **Step 2: Client and store.**
  - `api/activity.ts` copies `api/audit.ts`'s shape: `listActivity(params)`, `getActivity(id)`, `listActivityPeople()`, `activityCsvUrl(params)`. The CSV goes through the same authenticated download helper other CSV exports use (`grep -rn "csv" frontend/src/api/*.ts | head`).
  - `store/activityStore.ts` copies `auditStore.ts` with three slots: `list`, `people`, `detail` (keyed by id).

- [ ] **Step 3: `ActivityLine.tsx`.**
  - One row: time (`formatInstant`), who (`actorWords`; a button that sets the person filter), the label, the record (a link: policy → `/staff/policies/:n`, claim → `/staff/claims/:id`, client → `/staff/parties/:id`, otherwise text), the amount (`formatMoney`; use the helper the dashboards use) and the outcome (`outcomeWords`, coloured with the status tokens `DataTable` already uses for states).
  - Opening the row expands below it and loads the detail:
    - **What changed**: one `changeWords` line per change;
    - **What followed**: one `describeActivity` line per event, with links;
    - **Technical detail**: a closed `<details>` showing route, status and pretty-printed JSON.
  - Copy the expandable-row pattern from the nearest existing page:

```bash
grep -rln "aria-expanded" frontend/src/features | head
```

- [ ] **Step 4: `ActivityLogPage.tsx`.**
  - `PageHeader` title `Activity log`, with a `CountLine` of matching lines.
  - Filters, held in URL search params like `AuditLogPage.update`:
    - person: a select from `/activity/people` plus `System`;
    - record: a text box that sends `q`;
    - kind: `FilterChip`s labelled `Claims, Money, Policies, People & access, Products & prices, Settings`;
    - outcome: chips for `Done, Refused, Failed`;
    - dates: two date inputs, defaulting to the last 7 days.
  - A `Download CSV` button.
  - Lines in `DataTable` or a list of `ActivityLine`, with a `Pager`.
  - Empty state: `Nothing matches these filters` plus `Clear filters`.
  - Drop the old disclaimer paragraph; the log now does say who.
  - Follow DESIGN.md: read `frontend/DESIGN.md`'s table and filter sections before styling, and use only existing tokens and components.
  - Test (`ActivityLogPage.test.tsx`): mock `api/activity`; render; assert a line reads `Asha M. (staff)`, its label, `Refused: …`; clicking the person sets `person=` in the URL; opening a line shows `What followed`.

- [ ] **Step 5: Run.**

```bash
cd frontend && npx vitest run src/features/audit src/screens.test.ts && npx tsc -b --noEmit && npx eslint src/features/audit src/api/activity.ts src/store/activityStore.ts src/screens.tsx
```

Expected: everything passes, no type errors, no lint errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/api/audit.ts frontend/src/api frontend/src/store/activityStore.ts frontend/src/features/audit frontend/src/screens.tsx frontend/src/screens.test.ts
git commit -m "feat(console): the Activity log replaces the Event journal -- who did what, in words, with what followed"
```

---

### Task 7: History tabs and "What they did"

**Files:**
- Create: `frontend/src/features/audit/RecordHistory.tsx` (+ `.test.tsx`)
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx` (the tab list built at lines 424-697)
- Modify: `frontend/src/features/party/PartyDetailPage.tsx` (`clientTabs`, lines 336-460)
- Modify: `frontend/src/features/claims/ClaimDetailPage.tsx` (`sections`, line 192)
- Modify: the agent detail page, the group/credit-life scheme pages (`features/policies/GroupSchemePage.tsx`, `CreditLifeSchemePage.tsx`) and `features/admin/UserDetailPage.tsx`

**Interfaces:**
- Consumes: `listActivity({ recordType, recordId })`, `ActivityLine`, `can('audit.view')` (the permission hook each page already uses).
- Produces: `<RecordHistory recordType="policy" recordId={policyNumber} />`.

- [ ] **Step 1: `RecordHistory`.** Loads `listActivity({recordType, recordId, from: 10 years ago})` and pages, rendering `ActivityLine` rows without the record column (it is this record). Test it against a mocked API: it renders lines and an empty state reading `Nothing recorded on this yet`.

- [ ] **Step 2: Add the tab** to each page's tab list, last, only when `can('audit.view')`:

```tsx
if (can('audit.view')) tabs.push({ value: 'history', label: 'History', content: <RecordHistory recordType="policy" recordId={policy.policyNumber} /> });
```

| Page | `recordType` | `recordId` |
|---|---|---|
| Policy | `policy` | policy number |
| Client | `client` | party id |
| Claim | `claim` | claim id |
| Agent | `agent` | agent id |
| Scheme | `scheme` | scheme id |
| Staff user | `staff user` | user id |

Each must match `RecordRefs.TYPES` in Task 3.

- [ ] **Step 3: "What they did".** On `UserDetailPage`, add a link to `/staff/audit-log?person=<subject>`. The subject must be the user's Keycloak id. Check what the page's view holds (`grep -n "subject\|keycloak" frontend/src/features/admin/UserDetailPage.tsx`). If the view does not carry it, the link filters by `q=<username>` instead. Say so in the commit message.

- [ ] **Step 4: Run** the affected page tests and add one assertion each: the History tab is present with `audit.view` and absent without it.

```bash
cd frontend && npx vitest run src/features/audit src/features/policies/PolicyDetailPage src/features/party/PartyDetailPage src/features/claims src/features/admin
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features
git commit -m "feat(console): History on policies, clients, claims, agents, schemes and staff -- for those who may read the activity log"
```

---

### Task 8: End to end, and the user's label review

**Files:**
- Create: `frontend/e2e/staff-activity-log.spec.ts`
- Modify: `frontend/e2e/staff-a11y.spec.ts:34`. `/staff/audit-log` stays; check that the a11y baseline in `e2e/a11y-baseline.json` has no entry tied to the old page's text.

- [ ] **Step 1: Apply V5 to dev and restart the backend.** Stop the old java on 8080 first, then restart with the SMS environment:

```bash
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < backend/db-migrations/audit/V5__activity.sql
```

- [ ] **Step 2: Write the e2e test** as the admin session (`auth-admin.setup.ts`):
  1. Make one harmless change through the UI, e.g. save Organisation settings unchanged.
  2. Open `/staff/audit-log`.
  3. Expect a line containing the admin's name and that action's label.
  4. Open it and expect `Technical detail`.
  5. Open a policy and expect a `History` tab.

  Use accessible names (`getByRole`) as in the neighbouring specs.

- [ ] **Step 3: Run that spec alone** with `--workers=2`, per the low-RAM note, and nothing else heavy running. Expected: pass.

- [ ] **Step 4: Label review.** Give the user `backend/src/main/resources/audit/activity-labels.tsv` as one readable list (label, then the screen it belongs to) and apply their corrections. Re-run `ActivityLabelsCoverageTest` after any edit.

- [ ] **Step 5: Commit, then the merge gates.**
  - Commit: `test(e2e): the activity log shows who did what`.
  - With the dev backend stopped, run the affected backend classes: `audit/*`, `DurableEventsIntegrationTest`, `ModularityTests`, the dashboard tests.
  - Restart the dev backend.
  - Run full vitest.
  - Merge `--no-ff` to main and push.
  - Per the user's cadence, the full backend suite and the full e2e run belong to the milestone, not this merge. Record that in memory.

---

## Self-review

**Spec coverage**

| Spec item | Task |
|---|---|
| Recorder on every write | 4 |
| Who | 4 (`TokenNames`, `kindOf`) |
| Action label | 2 |
| Record | 3, 4 |
| What was sent, secrets dropped | 3, 4 |
| Uploads: name and size only | 4 (`uploadNote`) |
| Outcome with reason | 4 |
| Events joined | 1 (column), 4 (stamp), 5 (detail) |
| What changed, before → after or "set to" | 5, 6 |
| Masking | 3, 5 |
| Kept forever, append-only, RLS | 1 |
| `audit.view` only | 5, 6, 7 |
| One page replacing the Event journal | 6 |
| Filters, CSV, opened line | 5, 6 |
| History tabs | 7 |
| "What they did" | 7 |
| Pre-change events as System lines | 5 (UNION branch with `action_id IS NULL`) |
| Label guard test | 2 |
| User label review | 8 |
| E2E rename impact | 6 (`screens.test.ts`), 8 (a11y spec) |

**Deviation:** public and machine routes are not recorded (Global Constraints amendment). The user is told at review.

**Checks the implementer must do in place, because the plan names but cannot see the exact value:**
- the realm role names (Task 4);
- the `Money` record's package and accessors (Tasks 3 and 4);
- the mobile-money callback path (Task 2);
- the RLS `NULLIF` expression text (Task 1);
- the user page's subject field (Task 7);
- the expandable-row component (Task 6);
- the CSV download helper (Task 6).

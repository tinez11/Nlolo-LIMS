# Analytics Plan 1 — Collections: Premiums to Follow Up + Finance Collections Band

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **Every frontend task invokes the `impeccable` skill** (project skill, `.claude/skills/impeccable`) before writing UI,
> with the mockup screen named in the task as the visual reference.

**Goal:** Staff and agents get one list of premiums to follow up (due soon / due today / overdue) with the client's
phone, what was already done, and actions (ask to pay, send reminder, record a call), downloadable; the finance
dashboard shows collections first (due, paid, collection rate, overdue, due today) by month / quarter / year.

**Architecture:** Facts stay with their owners. `billing` owns invoices, arrears, agent cash and a new
`collection_action` record (calls, reminders, payment requests). `party` gains a bulk phone lookup. `reporting`
composes the list (it already joins policy projection, client names, products and agent teams, and owns the exporter)
and serves it to staff and agents. `communication` sends the reminder SMS. `dashboard` adds the finance band from a
`reporting` figure. No module writes another's data; nothing is recomputed that a module already computes.

**Tech Stack:** Spring Boot 3 / Java 21 (host `./mvnw`, `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`), PostgreSQL migrations
under `backend/db-migrations/<module>/`, React + TypeScript + zustand + react-hook-form/zod, Vitest, Playwright.

**Source of the decisions:** `backend/docs/superpowers/specs/2026-11-11-analytics-prd-assessment.md`
(sections "Premiums to follow up — decided design", C1, A1, B1, decisions table). Mockup:
https://claude.ai/artifact/NCgMMcFxs4Xj1D2xsrVbXf — screen 2 (follow-up list), screen 5 (finance dashboard),
screen 6 (agent dashboard).

## Global Constraints

- Simple English on every screen; no codes as primary text (assessment, "Also decided").
- C1: collection rate = premium paid for premiums **due in the period** ÷ premium due in the period, measured now;
  waived premiums and offers not yet accepted are excluded; savings contributions are excluded from the rate.
- A promise to pay is a **note only** — it never suppresses reminders or dunning.
- Agents see **only their own book** (supervisors their team) — scope = `DistributionApi.resolveAgentTeam(partyId)`.
- Downloads of lists with names and phones are logged (the existing audit of exports; see Task 6 Step 6).
- B1: branch is a filter only. T1: no targets. No scheduled reports.
- Never run Prettier. Never run Maven in Docker. One heavy run (Maven / Vitest / Playwright) at a time.
- New migrations are applied to dev by hand with `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform`
  and added to every test migration list that loads the module's previous migration.
- After any change to a method signature: `./mvnw -B -o -q clean test-compile` (the IDE writes stub classes that
  make tests pass vacuously — check the test count rose).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Branch → `merge --no-ff` → push.

## What this plan does NOT do (later plans)

- Plan 2 — Reports foundation: drill-down links in report tables, the "What the figures mean" catalogue (K1),
  quarter as a period option on every report, "last updated" on every report.
- Plan 3 — Agent sales in money: premium sold (yearly), cover sold, "My sales" report with wide timeline.
- Plan 4 — Sales funnel (P1) and underwriting stage history + underwriting dashboard.
- Plan 5 — Management role and Management overview dashboard; policy movement; claims by stage.

## Known limits, stated in the product (not hidden)

- A mobile-money request waiting on the customer's phone is **shown** under "What was done" (from billing's own
  `collection_action` record), not hidden: billing cannot see payment's request status (payment depends on billing,
  not the reverse). Assessment said "left off"; this is the honest equivalent within the module rules.
- "Will lapse on" = arrears case opened + `DUNNING_ESCALATION_DAYS/LEVEL_5` days (the sweep's own rule).

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/db-migrations/billing/V20__collection_action.sql` (new) | Calls, reminders, payment requests per invoice |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/domain/CollectionAction.java` (new) | One action |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/infrastructure/CollectionActionRepository.java` (new) | Store |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/api/BillingApi.java` (modify) | `openPremiums`, `recordCollectionAction`, `sendPremiumReminder` |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/api/OpenPremiumView.java` (new) | One open premium with its follow-up facts |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/CollectionActions.java` (new) | Record call / reminder / request |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/BillingApiImpl.java` (modify) | `openPremiums` query; request logs an action |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/infrastructure/CollectionActionController.java` (new) | `POST /collections/actions` (staff + agents) |
| `backend/db-migrations/communication/V22__premium_reminder_template.sql` (new) | PREMIUM_REMINDER SMS + email, EN + SW |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/communication/application/ReceiptEventListener.java` (modify) | Send on `billing.PremiumReminderSent` |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/party/api/PartyApi.java` + `party/application/PartyApiImpl.java` (modify) | `phoneNumbers(Collection<UUID>)` |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/FollowUpQueue.java` (new) | Composes the list and the C1 figures |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/FollowUpRow.java`, `FollowUpPage.java`, `CollectionFigures.java` (new) | Views |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/ReportingApi.java` + `ReportingApiImpl.java` (modify) | Expose list, export, figures |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/infrastructure/FollowUpController.java` (new) | Staff + agent list and download |
| `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/FinanceDashboard.java` + `dashboard/api/FinanceDashboardView.java` (modify) | The collections band |
| `backend/api/openapi/openapi-billing.yaml`, `openapi-reporting.yaml`, `openapi-dashboard.yaml` (modify) | Contracts |
| `frontend/src/api/followUp.ts`, `frontend/src/store/followUpStore.ts` (new) | Calls + store |
| `frontend/src/features/collections/FollowUpPage.tsx` (+ `.test.tsx`, `recordCallForm.ts`) (new) | Staff page (mockup 2) |
| `frontend/src/features/distribution/MyFollowUpPage.tsx` (new) + agent dashboard panel (modify) | Agent (mockup 6) |
| `frontend/src/features/dashboard/FinanceDashboardPanel.tsx` (modify) | Band (mockup 5) |
| `frontend/src/screens.tsx`, `frontend/src/lazyPages.ts`, `frontend/src/navBadges.ts` (modify) | Navigation |
| `frontend/e2e/staff-follow-up.spec.ts` (new) | End to end |

---

### Task 1: billing — the collection action record

**Files:**
- Create: `backend/db-migrations/billing/V20__collection_action.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/domain/CollectionAction.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/infrastructure/CollectionActionRepository.java`
- Modify: every test migration list containing `"db-migrations/billing/V19__agent_cash.sql"` (13 files; find with
  `grep -rl V19__agent_cash backend/src/test`) — add `"db-migrations/billing/V20__collection_action.sql"` right after it.
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/billing/BillingApiIntegrationTest.java`

**Interfaces:**
- Produces: table `billing.collection_action(action_id, tenant_id, invoice_id, policy_number, kind, outcome,
  promised_on, note, recorded_by, recorded_at)`; `CollectionAction` with static factories `call(...)`,
  `reminder(...)`, `paymentRequest(...)`; repository `findByTenantIdAndInvoiceIdInOrderByRecordedAtDesc`.

- [ ] **Step 1: Write the migration**

```sql
-- What was done to collect a premium (2026-11-11, analytics plan 1): a call (its outcome, an optional promise to pay
-- and a note), a reminder SMS, or a mobile-money payment request. The record staff and agents read on "Premiums to
-- follow up". A promise is a note only: nothing here holds back reminders or dunning (the user, 2026-11-11).
CREATE TABLE billing.collection_action (
    action_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    invoice_id     UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    kind           VARCHAR(20) NOT NULL CHECK (kind IN ('CALL', 'REMINDER', 'PAYMENT_REQUEST')),
    outcome        VARCHAR(30) CHECK (outcome IS NULL OR outcome IN ('WILL_PAY', 'CANNOT_PAY_NOW', 'NO_ANSWER',
                                                                      'WRONG_NUMBER')),
    promised_on    DATE,
    note           VARCHAR(500),
    recorded_by    VARCHAR(100) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT collection_action_call_has_outcome CHECK ((kind = 'CALL') = (outcome IS NOT NULL)),
    CONSTRAINT collection_action_promise_on_call CHECK (promised_on IS NULL OR kind = 'CALL')
);
CREATE INDEX idx_collection_action_invoice ON billing.collection_action (tenant_id, invoice_id, recorded_at DESC);

ALTER TABLE billing.collection_action ENABLE ROW LEVEL SECURITY;
CREATE POLICY collection_action_tenant_isolation ON billing.collection_action
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON billing.collection_action TO app_role;
```

Check before writing: open `backend/db-migrations/billing/V16__deposit_reversal.sql` and copy its exact RLS/grant
lines (policy name style, `app_role`) if they differ from the above — the existing file is the authority.

- [ ] **Step 2: Write the entity and repository** — copy the field/constructor style of
  `billing/domain/DepositReversal.java` (JPA `@Entity`, `@Table(schema = "billing")`, protected no-arg constructor,
  getters only). Factories:

```java
public static CollectionAction call(UUID tenantId, UUID invoiceId, String policyNumber, String outcome,
                                    LocalDate promisedOn, String note, String by) { ... kind = "CALL" ... }
public static CollectionAction reminder(UUID tenantId, UUID invoiceId, String policyNumber, String by) { ... }
public static CollectionAction paymentRequest(UUID tenantId, UUID invoiceId, String policyNumber, String by) { ... }
```

Repository:

```java
public interface CollectionActionRepository extends JpaRepository<CollectionAction, UUID> {
    List<CollectionAction> findByTenantIdAndInvoiceIdInOrderByRecordedAtDesc(UUID tenantId, Collection<UUID> invoiceIds);
}
```

- [ ] **Step 3: Add V20 to the 13 test migration lists** (sed on the exact string, then `grep -c` must show 13).

- [ ] **Step 4: Apply V20 to dev** and confirm `\d billing.collection_action`.

- [ ] **Step 5: Compile** `./mvnw -B -o -q clean test-compile` — no errors.

- [ ] **Step 6: Commit** `feat(billing): collection_action — calls, reminders and payment requests per premium`.

---

### Task 2: billing — open premiums and the three actions

**Files:**
- Create: `billing/api/OpenPremiumView.java`, `billing/application/CollectionActions.java`,
  `billing/infrastructure/CollectionActionController.java`, `billing/api/CollectionActionRefusedException.java`
- Modify: `billing/api/BillingApi.java`, `billing/application/BillingApiImpl.java`,
  `billing/infrastructure/PremiumInvoiceRepository.java`, `billing/infrastructure/BillingExceptionHandler.java`,
  `backend/api/openapi/openapi-billing.yaml`
- Test: `billing/BillingApiIntegrationTest.java`, `billing/BillingContractTest.java`

**Interfaces:**
- Produces:

```java
/** One premium still owed (or about to be), with what was done about it. Money is what is OWED now. */
public record OpenPremiumView(UUID invoiceId, String policyNumber, LocalDate dueDate, BigDecimal owed, String currency,
                              String status, LocalDate lapseExpectedOn, UUID agentCashAgentId, BigDecimal agentCashHeld,
                              List<ActionView> actions) {
    public record ActionView(String kind, String outcome, LocalDate promisedOn, String note, String recordedBy,
                             Instant recordedAt) {}
}
// BillingApi
List<OpenPremiumView> openPremiums(LocalDate dueOnOrBefore);
OpenPremiumView.ActionView recordCall(UUID invoiceId, String outcome, LocalDate promisedOn, String note, String by);
OpenPremiumView.ActionView sendPremiumReminder(UUID invoiceId, String by);
```

- [ ] **Step 1: Failing test** in `BillingApiIntegrationTest` (copy the fixture style of
  `agentCashPaysThePremiumTheMomentTheAgentRecordsItPostedToDueFromAgents`):

```java
@Test
void openPremiumsListWhatIsOwedWithWhatWasDoneAndLeaveOutWhatTheBalanceOrAnAgentCovers() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "FOLLOW-UP-01");
    String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
    InvoiceView first = billingApi.listInvoices(policyNumber, null).get(0);

    TenantContext.set(tenantId);
    List<OpenPremiumView> open = billingApi.openPremiums(first.dueDate().plusDays(1));
    assertThat(open).extracting(OpenPremiumView::invoiceId).contains(first.invoiceId());

    TenantContext.set(tenantId);
    billingApi.recordCall(first.invoiceId(), "WILL_PAY", first.dueDate().plusDays(5), "Pays Friday", "staff-1");
    TenantContext.set(tenantId);
    assertThat(billingApi.openPremiums(first.dueDate().plusDays(1)))
        .filteredOn(p -> p.invoiceId().equals(first.invoiceId())).singleElement()
        .satisfies(p -> assertThat(p.actions()).first()
            .satisfies(a -> { assertThat(a.kind()).isEqualTo("CALL"); assertThat(a.outcome()).isEqualTo("WILL_PAY"); }));

    // Paid by an agent's cash: no longer owed, so no longer listed.
    TenantContext.set(tenantId);
    billingApi.captureFieldReceipt(UUID.randomUUID(), null, policyNumber, new BigDecimal("15000.00"), "TZS",
        "key-" + UUID.randomUUID(), Instant.now());
    TenantContext.set(tenantId);
    assertThat(billingApi.openPremiums(first.dueDate().plusDays(1)))
        .extracting(OpenPremiumView::invoiceId).doesNotContain(first.invoiceId());
}

@Test
void aCallNeedsAnOutcomeAndAPromiseIsOnlyForACall() {
    // recordCall with null outcome -> CollectionActionRefusedException "Say what happened on the call"
}
```

- [ ] **Step 2: Run** `./mvnw -B -o test -Dtest=BillingApiIntegrationTest` — expect compile failure / FAIL.

- [ ] **Step 3: Implement.**
  - `PremiumInvoiceRepository`: `List<PremiumInvoice> findByTenantIdAndStatusInAndDueDateLessThanEqualOrderByDueDateAsc(UUID tenantId, Collection<String> statuses, LocalDate dueOnOrBefore);`
  - `BillingApiImpl.openPremiums`: statuses `PAYABLE` (the existing constant: DUE, IN_GRACE, OVERDUE,
    PARTIALLY_PAID); `owed = invoice.balanceDue(creditedAgainst(invoice))`, skip `owed <= 0`; skip a not-yet-due
    invoice whose policy balance (`balanceEntryRepository.balanceOf`) ≥ owed (it will be paid on its due date by
    `BalanceAllocationDrain`); `agentCash*` from open field receipts on the policy
    (`fieldReceiptRepository` — add `findByTenantIdAndPolicyNumberAndStatusIn`) — an invoice is **left out** when
    open agent cash on its policy ≥ owed (savings/annuity cash waits for banking: decision 2026-11-11), otherwise
    listed with the held amount shown; `lapseExpectedOn` = open arrears case `openedAt` (Dar es Salaam date) +
    refdata `DUNNING_ESCALATION_DAYS`/`LEVEL_5` days (read it through `refdata::api` as the module already does —
    find the existing reader with `grep -rn DUNNING_ESCALATION billing`; if billing has none, read via
    `ReferenceDataApi` the same way `OVERDUE`/SLA codes are read in billing; never hard-code 30); actions from
    `CollectionActionRepository`, newest first, at most 5 per invoice.
  - `CollectionActions.recordCall`: refuse null/blank outcome ("Say what happened on the call"), outcome not in the
    four values, `promisedOn` before today, note > 500 chars; invoice must be open (`PAYABLE`) else "This premium is
    no longer owed".
  - `CollectionActions.sendReminder`: invoice open; save `CollectionAction.reminder`; publish
    `billing.PremiumReminderSent` with `{invoiceId, policyNumber, policyholderPartyId (policyApi.getPolicy),
    amount {amount, currencyCode}, dueDate}`. Refuse a second reminder for the same invoice on the same Dar es Salaam
    day ("A reminder was already sent today").
  - `BillingApiImpl.requestPaymentForInvoice`: after the existing event, save
    `CollectionAction.paymentRequest(..., by)` — add a `String requestedBy` overload; the controller passes
    `jwt.getSubject()`; the old signature delegates with `"system"`.
  - `CollectionActionController`:

```java
@PostMapping("/collections/actions/calls")
@PreAuthorize("hasRole('REALM_AGENTS') or @access.can('collections.manage')")
// body: {invoiceId, outcome, promisedOn?, note?}; an agent passes AgentBook.require(policyApi, policyNumberOf(invoice), jwt, auth)
@PostMapping("/collections/actions/reminders")
@PreAuthorize("hasRole('REALM_AGENTS') or @access.can('collections.manage')")
// body: {invoiceId}
```

  Copy the agent check exactly from `BillingSyncController.captureFieldReceipt`.
  - `openapi-billing.yaml`: add both paths and the `CollectionActionView` schema.

- [ ] **Step 4: Run** the two test classes — PASS; the class's test count rises by the new tests.
- [ ] **Step 5: Commit** `feat(billing): open premiums with what was done; record a call, send a reminder`.

---

### Task 3: communication — the reminder message

**Files:**
- Create: `backend/db-migrations/communication/V22__premium_reminder_template.sql`
- Modify: `communication/application/ReceiptEventListener.java`; the 10 test lists containing
  `V21__agent_cash_names_the_agent.sql` (add V22 after it); `CommunicationContractTest.SEEDED_TEMPLATES` (+4) and its
  `containsOnly(...)` key list (+ `PREMIUM_REMINDER`).

- [ ] **Step 1: Migration** — copy the INSERT … WHERE NOT EXISTS shape of `V21__agent_cash_names_the_agent.sql`.
  Wording (**to confirm with the user before merging — do not invent other wording**):
  - SMS en: `Reminder: the premium of {{amount}} for policy {{policyNumber}} was due on {{dueDate}}. Please pay to keep your cover.`
  - SMS sw: `Kumbusho: ada ya {{amount}} kwa bima {{policyNumber}} ilitakiwa kulipwa {{dueDate}}. Tafadhali lipa ili bima yako iendelee.`
  - EMAIL en/sw: the same sentence plus `If you have already paid, thank you — please ignore this message.` /
    `Kama umeshalipa, asante — tafadhali puuza ujumbe huu.`
- [ ] **Step 2: Listener** — in `ReceiptEventListener.onDomainEvent` add
  `case "billing.PremiumReminderSent" -> tell(envelope, payload, "PREMIUM_REMINDER", Map.of("policyNumber", ..., "amount", money(...), "dueDate", <dd MMM yyyy>));`
  (format the date with `java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT)`).
- [ ] **Step 3: Apply V22 to dev**; run `CommunicationContractTest` — PASS.
- [ ] **Step 4: Commit** `feat(communication): premium reminder SMS and email (EN, SW)`.

---

### Task 4: party — phones in one read

**Files:** `party/api/PartyApi.java`, `party/application/PartyApiImpl.java`,
`party/infrastructure/PartyRepository.java`; test `party/PartyContractTest.java` or the party integration test that
covers `displayNames` (find with `grep -rln displayNames backend/src/test`).

- [ ] **Step 1: Failing test** — two parties registered with phones; `phoneNumbers(List.of(a, b, unknown))` returns
  both phones, no entry for `unknown`.
- [ ] **Step 2: Implement** exactly like `displayNames` (`PartyApiImpl.java:471`): repository native query returning
  `(party_id, phone_number)` for the ids in the tenant.

```java
/** Phone numbers for these parties, in one read (2026-11-11, premiums to follow up). Absent when none is recorded. */
java.util.Map<UUID, String> phoneNumbers(java.util.Collection<UUID> partyIds);
```

- [ ] **Step 3: PASS; commit** `feat(party): phone numbers for many parties in one read`.

---

### Task 5: reporting — the follow-up list and the collection figures

**Files:**
- Create: `reporting/api/FollowUpRow.java`, `reporting/api/FollowUpPage.java`, `reporting/api/CollectionFigures.java`,
  `reporting/application/FollowUpQueue.java`, `reporting/infrastructure/FollowUpController.java`
- Modify: `reporting/api/ReportingApi.java`, `reporting/application/ReportingApiImpl.java`,
  `reporting/package-info.java` only if a dependency is missing (it has billing, party, distribution, product already),
  `backend/api/openapi/openapi-reporting.yaml`
- Test: new `reporting/FollowUpQueueIntegrationTest.java` (copy setup from `ReportQueriesIntegrationTest` +
  `ReportingTestMigrations`), contract test in the reporting contract test class.

**Interfaces:**
- Consumes: `BillingApi.openPremiums`, `PartyApi.displayNames`, `PartyApi.phoneNumbers`,
  `DistributionApi.resolveAgentTeam`, `ReportSql.LAST_CHANGE_CTE`, `reporting.report_policy`.
- Produces:

```java
public record FollowUpRow(UUID invoiceId, String policyNumber, String clientName, String clientPhone,
                          String productName, UUID agentId, String agentName, String branchCode, BigDecimal owed,
                          String currency, LocalDate dueDate, long daysLate, String lateBand, String policyStatus,
                          LocalDate lapseExpectedOn, BigDecimal agentCashHeld, String agentCashWith,
                          List<OpenPremiumView.ActionView> actions) {}
public record FollowUpPage(List<FollowUpRow> rows, long total, int page, int pageSize,
                           long dueToday, long dueSoon, long overdue, BigDecimal overdueOwed) {}
public record CollectionFigures(LocalDate from, LocalDate to, BigDecimal due, BigDecimal paid, String ratePercent,
                                long dueTodayCount, BigDecimal dueTodayOwed, long overdueCount, BigDecimal overdueOwed,
                                String currency) {}
// ReportingApi
FollowUpPage followUp(FollowUpQuery q);          // staff: any agent
FollowUpPage followUpForAgentParty(UUID agentPartyId, FollowUpQuery q);  // agent: own team only
ReportResult followUpReport(FollowUpQuery q, UUID agentPartyIdOrNull);   // for Excel/PDF via ReportExporter
CollectionFigures collectionFigures(LocalDate from, LocalDate to, LocalDate today);
record FollowUpQuery(String view /* DUE_SOON|DUE_TODAY|OVERDUE */, int soonDays /* 7|14|30 */, String lateBand,
                     UUID agentId, String branchCode, UUID productId, String search, int page, int pageSize) {}
```

- [ ] **Step 1: Failing tests** in `FollowUpQueueIntegrationTest` — use a `@MockitoBean BillingApi` (the reporting
  tests already isolate other modules this way; check `ReportQueriesIntegrationTest` imports first and copy) returning
  three `OpenPremiumView`s (due in 3 days, due today, 20 days late) for policies seeded into `report_policy` via the
  existing event fixtures (`events.sell(...)`); assert:
  - DUE_SOON(7) returns only the 3-day row; DUE_TODAY only today's; OVERDUE only the 20-day row with
    `lateBand = "15–30 days"`, `daysLate = 20`;
  - a policy with no activation (offer) is left out;
  - `followUpForAgentParty` returns only rows whose `report_policy.agent_id` is in the team;
  - `collectionFigures(from, to)` over invoices due in the period: `due`, `paid = due − owed`, rate one decimal.
    For the paid side, `BillingApi` needs the period's invoices including PAID ones: add
    `List<InvoiceDueView> invoicesDueBetween(LocalDate from, LocalDate to)` to billing (amount, owed, status,
    policyNumber, currency) in this task — WAIVED excluded; savings-type policies excluded via
    `BillingApi.isSavingsType`.
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement `FollowUpQueue`** — one SQL over `report_policy` + `LAST_CHANGE_CTE` for the policies in the
  billing rows (`policy_number = ANY(?)`), to get product, agent, branch, holder, latest status, activated_on; drop
  rows with `activated_on IS NULL`; names via `PartyApi.displayNames` (clients and agents' parties — agent name:
  copy how `CommissionsReport` resolves agent names); phones via `phoneNumbers`; late bands exactly:
  `1–7 days`, `8–14 days`, `15–30 days`, `31–60 days`, `61–90 days`, `More than 90 days`; sort OVERDUE by daysLate
  desc, DUE views by due date asc; page in memory (lists are hundreds, not millions — add a comment saying so);
  `followUpReport` builds one `ReportSection` with columns Client, Phone, Policy, Product, Agent, Owed (MONEY), Due
  date (DATE), Days late (COUNT), What was done (TEXT, newest action in words: "Called 8 Nov — promised 12 Nov",
  "Reminder sent 3 Nov", "Payment request sent 5 Nov").
- [ ] **Step 4: Controller**

```java
@GetMapping("/collections/follow-up")                 @PreAuthorize("@access.canAny('collections.view','collections.manage')")
@GetMapping("/collections/follow-up/export")          @PreAuthorize("@access.canAny('collections.view','collections.manage')")
@GetMapping("/agents/me/follow-up")                   @PreAuthorize("hasRole('REALM_AGENTS')")   // party from jwt claim party_id
@GetMapping("/agents/me/follow-up/export")            @PreAuthorize("hasRole('REALM_AGENTS')")
@GetMapping("/collections/figures")                   @PreAuthorize("@access.canAny('collections.view','reports.view')")
```

  Export uses `ReportExporter.export(result, format, preparedBy)` and `FileDownloadResponses.fileResponse` exactly as
  `ReportController.export` does. Agent party: `jwt.getClaimAsString("party_id")`, refused 403 when absent (copy
  `AgentBook.agentParty`'s message).
- [ ] **Step 5: OpenAPI** — add the five paths and schemas to `openapi-reporting.yaml`.
- [ ] **Step 6: Export logging** — check whether `ReportController.export` writes an audit entry
  (`grep -rn audit backend/src/main/java/tz/co/nlolo/lifeplatform/reporting`). If it does, call the same thing; if it
  does not, publish `reporting.ListDownloaded {list, format, rows, by}` (the audit module records every envelope —
  `DomainEventAuditListener`) from both export endpoints. Do not build a new audit store.
- [ ] **Step 7: Run tests — PASS; ModularityTests — PASS. Commit**
  `feat(reporting): premiums to follow up for staff and agents, with download; collection figures (C1)`.

---

### Task 6: dashboard — collections first on the finance dashboard

**Files:** `dashboard/application/FinanceDashboard.java`, `dashboard/api/FinanceDashboardView.java`,
`backend/api/openapi/openapi-dashboard.yaml` (find the finance view schema), its test
(`grep -rln FinanceDashboard backend/src/test`).

- [ ] **Step 1: Failing test** — the view carries `collections` = `CollectionFigures` for the dashboard period
  (`period.from()` first day … `period.to()` last day, capped at today).
- [ ] **Step 2: Implement** — add one component `CollectionFigures collections` (append at the end of the record to
  keep the existing JSON order), read with `Safely.read("collection figures", () -> reporting.collectionFigures(...))`.
- [ ] **Step 3: PASS; commit** `feat(dashboard): finance dashboard carries the period's collection figures`.

---

### Task 7: frontend — API, types, store

**Files:** `frontend/src/api/followUp.ts`, `frontend/src/store/followUpStore.ts`, regenerate types
(`npm run generate:api`), `frontend/src/store/followUpStore.test.ts`.

- [ ] **Step 1:** `npm run generate:api`; the new paths appear in `src/types/api/reporting.ts` / `billing.ts`.
- [ ] **Step 2:** `followUp.ts` — `listFollowUp(query)`, `listMyFollowUp(query)`, `followUpExportUrl(query, format)`,
  `myFollowUpExportUrl(...)`, `recordCall(body)`, `sendReminder(invoiceId)`, `collectionFigures(from, to)`; download
  the same way the reports page downloads (find `export` in `frontend/src/features/reporting` and copy — files come
  from the API with auth, never a plain link).
- [ ] **Step 3:** `followUpStore.ts` with `track(...)` slices `list`, `acting` keyed by invoiceId — copy
  `agentCashStore.ts`.
- [ ] **Step 4:** store test (copy `billingStore` test shape from git history `git show 0e663e70^:frontend/src/store/billingStore.test.ts`):
  after `recordCall` succeeds the list reloads.
- [ ] **Step 5:** `npx tsc --noEmit`, eslint on the files, vitest on the store — PASS. Commit.

---

### Task 8: frontend — "Premiums to follow up" page (staff)

**Design:** invoke the `impeccable` skill (`Skill impeccable`, arguments: "build the Premiums to follow up page —
match mockup screen 2 at https://claude.ai/artifact/NCgMMcFxs4Xj1D2xsrVbXf; follow DESIGN.md; simple English; reuse
DataTable, FilterChip, PageHeader, FormField, StatusBadge, PartyName"). The mockup is the visual reference; DESIGN.md
and existing components win where they differ.

**Files:** `frontend/src/features/collections/FollowUpPage.tsx`, `frontend/src/features/collections/recordCallForm.ts`,
`frontend/src/features/collections/FollowUpPage.test.tsx`, `frontend/src/screens.tsx` (entry under group
`collections`, label "Premiums to follow up", path `follow-up`, an unused lucide icon), `frontend/src/lazyPages.ts`,
`STAFF_PERMISSION` map (find with `grep -rn STAFF_PERMISSION frontend/src`) → `collections.view`.

Content (from the decided design — nothing more):
- Header "Premiums to follow up", one-line description from the mockup; Download Excel / PDF.
- Figures: Due today, Due in the next 7 days, Overdue (count · amount), Paid this month, Collection rate (with its
  one-line meaning).
- Tabs Due soon (7/14/30 select) · Due today · Overdue; filters How late, Agent, Branch, Product; search.
- Rows: client name + tappable phone (`tel:` link), policy + product, agent, owed, due date or "N days late" chip,
  "Will lapse on …", "Agent cash with … (TZS …)" when shown, What was done (newest two), actions **Ask to pay**
  (navigate to `/staff/receipts/new?policy=…&method=MOBILE_MONEY` — the existing Deposit page; do not build a new
  request flow), **Send reminder**, **Record a call** (inline form: What happened select with the four outcomes in
  words — "Spoke — will pay", "Spoke — cannot pay now", "No answer", "Wrong number"; Promised to pay on (optional);
  Note; the sentence "A promise is a note only: reminders and letters still go."). Action buttons show only with
  `collections.manage`.
- Empty states in words ("Nobody owes a premium due today").

- [ ] **Step 1:** Write `FollowUpPage.test.tsx` first (mock `@/api/followUp`): renders a row with name, phone link,
  "20 days late"; Record a call submits `{invoiceId, outcome: 'WILL_PAY', promisedOn, note}`; Send reminder calls the
  API once and disables while running. Run — FAIL.
- [ ] **Step 2:** Invoke impeccable; build the page. Run test — PASS; `tsc`, eslint — clean.
- [ ] **Step 3:** `screens.test.ts` — run; fix only the nav-order/icon assertions the new entry legitimately changes.
- [ ] **Step 4:** Commit `feat(collections): premiums to follow up page`.

---

### Task 9: frontend — finance dashboard: collections first

**Design:** invoke `impeccable` — "rework the top of FinanceDashboardPanel to match mockup screen 5: a Collections
section first with This month / This quarter / This year, then Waiting for you to approve".

**Files:** `frontend/src/features/dashboard/FinanceDashboardPanel.tsx` (+ its test).

- [ ] **Step 1:** Test first: with `collections` in the view, the first section heading is "Collections" and shows
  Premiums due, Premiums paid, Collection rate, Overdue now, Due today; the switch sets the dashboard range
  (this month = current month; this quarter = the quarter's first month to now; this year = January to now) via the
  existing range mechanism (`RangePicker`/`range.ts` — reuse, do not add a second range state).
- [ ] **Step 2:** Build; keep every existing panel below it; approvals block stays as it is today (no new
  "waiting for you" logic in this plan — that is Plan 5).
- [ ] **Step 3:** Tests, tsc, eslint — PASS. Commit `feat(dashboard): finance dashboard opens on collections`.

---

### Task 10: frontend — agents: their customers to follow up

**Design:** invoke `impeccable` — "agent follow-up list and dashboard panel, mockup screen 6".

**Files:** `frontend/src/features/distribution/MyFollowUpPage.tsx` (+ test), `AgentDashboardPanel.tsx` (panel
"Your customers to follow up" showing the first 5 with a link), `screens.tsx` agent entry (group `my-business`, label
"Customers to follow up", unused icon), `lazyPages.ts`.

- [ ] **Step 1:** Test first: list from `listMyFollowUp`; Download Excel / Download PDF to print call the agent export;
  **no** "Ask to pay" button for agents (decided); Record cash links to `/agents/cash?policy=…`; Record a call works.
- [ ] **Step 2:** Build; supervisors get the "Me / My team" switch only if `resolveAgentTeam` returns more than the
  agent (the endpoint already scopes; the switch filters `agentId`).
- [ ] **Step 3:** Tests, tsc, eslint — PASS. Commit `feat(agents): customers to follow up, with download`.

---

### Task 11: end to end and merge

**Files:** `frontend/e2e/staff-follow-up.spec.ts` (new).

- [ ] **Step 1:** Spec (copy auth/session setup from `staff-field-receipts.spec.ts`): finance opens Collections →
  Premiums to follow up; Overdue tab shows the heading and either rows or the empty sentence; Record a call on the
  first row (when one exists) shows it under "What was done"; Download Excel returns a file (use
  `page.waitForEvent('download')`). A claims assessor gets the access panel.
- [ ] **Step 2:** Backend affected tests (billing, reporting, party, communication, dashboard, ModularityTests) — stop
  the dev backend first, `clean test-compile`, run, restart the backend.
- [ ] **Step 3:** Vitest on changed folders; then Playwright, `--workers=1`, MCP browser closed:
  `staff-follow-up`, `staff-field-receipts`, `staff-billing`, `agents-my-book`, `agents-my-profile`, dashboards spec
  (`grep -l "Dashboard" frontend/e2e/*.spec.ts` → the finance one). A failure: re-run that spec alone before
  treating it as a regression.
- [ ] **Step 4:** Walk it on dev in the browser as staff.finance and agent.senior (SMS only to 255755418369).
- [ ] **Step 5:** Merge `--no-ff` to main, push, update memory.

---

## Self-review

- Spec coverage: list (Task 5/8), actions (2/3/8), promise as note (2/8), agents own + download (5/10), finance band
  first with month/quarter/year (6/9), C1 (5), simple English (8/9/10 content lists), branch as filter (5/8),
  downloads logged (5 Step 6). Not covered here by design: drill-down, catalogue, agent sales, funnel, underwriting,
  management — Plans 2–5.
- Open item before merge: reminder wording (Task 3) is a proposal — ask the user.
- Type names used consistently: `OpenPremiumView`, `FollowUpRow`, `FollowUpPage`, `CollectionFigures`,
  `FollowUpQuery`, `CollectionAction`.

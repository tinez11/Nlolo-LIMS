# M3 — Policy Core Implementation Plan (`policy`, `policyloan`)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `policy` module (the platform's hub aggregate — policy lifecycle state machine, beneficiaries, endorsements, coverage, surrender quoting, and the loan-value reservation protocol) and the `policyloan` module (loan origination, repayment, and the ledger of loan transactions), both with real JPA persistence, REST APIs contract-tested against their existing OpenAPI specs, and a synchronous reserve/confirm/release protocol between the two modules that closes a genuine cross-module race condition (Module-Architecture-B1). The Camunda-orchestrated surrender/maturity loan-netting choreography is explicitly deferred wholesale — no workflow engine is adopted this milestone.

**Architecture:** Two new Spring Modulith modules on top of M1's (`party`, `document`, `audit`, `refdata`) and M2's (`product`, `underwriting`) foundations. `policy` depends on `underwriting`, `product`, `party`, `document`, `refdata` (all already-declared `allowedDependencies`, unchanged by this plan). `policyloan` depends on `policy`, `refdata` (also already declared). The two modules are built together deliberately (per `docs/08-implementation-roadmap.md`'s M3 framing) because `policyloan.originateLoan()`'s synchronous call into `policy.reserveLoanValue`/`confirmReservation`/`releaseReservation` needs both sides present to test meaningfully — this is the single most architecturally significant mechanism in this milestone (**Module-Architecture-B1**, disambiguated below from a different, unrelated "B1" in `docs/03-aggregate-design.md`'s revision log, which is the `TZ_REINSTATEMENT_WINDOW_MONTHS` refdata parameter, not a race condition).

Camunda 7 CE is EOL (unpatched since October 2025); Camunda 8 requires a paid licence and its job-worker thread-pool model would risk a fail-open cross-tenant leak against this platform's `TenantContext` (a servlet-filter-populated `ThreadLocal`, not propagated across worker threads by any framework guarantee). No workflow engine is adopted in M3 — that choice is deferred to a later spike. Concretely, this means:
- `POST /policies/{policyNumber}/surrender` and `GET /policies/{policyNumber}/processes/{processInstanceId}` are real, routable, correctly-secured endpoints that return `501 Not Implemented` with an RFC 7807 body (`errorCode=CHOREOGRAPHY_NOT_IMPLEMENTED`), not 404s and not omitted endpoints (Task 4).
- The six choreography-only events (`PolicySurrenderInitiated`, `PolicyMaturityInitiated`, `PolicySurrendered`, `SurrenderPayoutRequested`, `MaturityPayoutRequested`, `LoanSettledForPayout`) are not published anywhere in this plan.
- The `SURRENDERED` and `MATURED` statuses remain in the DB `CHECK` constraint and the `PolicyStatus` enum (other code — `PolicyView`, `getCoverageStatus`, search filters — must be able to represent a policy that reaches them via a later milestone's choreography), but **no code path in this plan transitions a policy into either status**. `LAPSED → SURRENDERED` (Po4, the aggregate design's *simpler*, non-choreography surrender path for a lapsed policy with positive residual cash value) is likewise not implemented in M3: it is reachable only through the same `POST /policies/{policyNumber}/surrender` endpoint that 501s, and its only observable event, `PolicySurrendered`, is on the wholesale-deferred list — implementing the transition without ever being able to signal it externally would be dead code. **Flagged as a judgment call** (see Self-Review Notes) — the roadmap's own acceptance-criteria wording ("Active → Suspended → Lapsed → Reinstated/Surrendered ... under test") reads as if `SURRENDERED` should be exercised; this plan concludes that's superseded by the explicit Camunda-deferral decision and the wholesale event deferral, but flags it for confirmation rather than silently picking a side.

**Tech Stack:** Same as M1/M2 — Spring Boot 3.3.5, Spring Modulith 1.2.5, Spring Data JPA (Hibernate 6, using `@JdbcTypeCode(SqlTypes.JSON)` for the two JSONB columns this plan introduces mappings for), Spring Security (multi-issuer JWT), PostgreSQL 16 with Row-Level Security, `swagger-request-validator-mockmvc` for contract tests, Testcontainers. **No new Maven dependencies.**

## Global Constraints

- **Base package `tz.co.nlolo.lifeplatform`.** `policy`'s and `policyloan`'s root `package-info.java` files already declare the correct `allowedDependencies` (`policy` → `{underwriting::api, product::api, party::api, document::api, refdata::api}`; `policyloan` → `{policy::api, refdata::api}`) — **do not modify either root `package-info.java`.** The one exception, made explicit in Task 1: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/api/package-info.java` is a bare `package` statement missing the `@org.springframework.modulith.NamedInterface("api")` annotation every sibling module's `api` package carries (`policy/api`, `party/api`, `product/api`, `underwriting/api` all have it). This is a pre-existing stub defect, not a design change — fix it as literally the first step of Task 1.
- **Build/test commands.** This machine has no system `JAVA_HOME`. Every `mvnw` invocation in this plan must be preceded by:
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ```
  Then run `./mvnw` directly (no Docker wrapper — Docker Desktop is running locally and Testcontainers talks to it directly). Example: `export JAVA_HOME="..." && ./mvnw -B -q test -Dtest=PolicyApiIntegrationTest`.
- **Tenant isolation is defense-in-depth on EVERY tenant-scoped table, not just the aggregate root.** `db-migrations/policy/V1__create_policy_schema.sql` currently `ENABLE ROW LEVEL SECURITY`s only `policy.policy` (1 of 7 tenant-scoped tables) and has **zero** `GRANT` statements anywhere in the file; `db-migrations/policyloan/V1__create_policyloan_schema.sql` RLS's only `policyloan.policy_loan` (1 of 4) and likewise has zero grants — the exact bug class M1's and M2's final whole-branch reviews each found and fixed for every other schema (`party`, `document`, `audit`, `refdata`, `product`, `underwriting`). Task 1 and Task 6 fix both proactively: every remaining tenant-scoped table gets `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + a `USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)` policy, and both schemas get the exact 3-line `GRANT` block already established in `party`/`product`/`underwriting`/`refdata`'s migrations:
  ```sql
  GRANT USAGE ON SCHEMA <schema> TO app_role;
  GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA <schema> TO app_role;
  ALTER DEFAULT PRIVILEGES IN SCHEMA <schema> GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
  ```
  `policyloan.loan_transaction` is **partitioned** (`PARTITION BY RANGE (occurred_at)`, two pre-created monthly partitions) — Postgres propagates `ENABLE ROW LEVEL SECURITY` and `GRANT`s on a partitioned parent to all its partitions automatically (partitions inherit the parent's RLS policies and privileges; there is nothing extra to do per-partition), so RLS/`GRANT` are applied once to `policyloan.loan_transaction` itself, not to each `..._2026_08`/`..._2026_09` child. The existing `REVOKE UPDATE, DELETE ON policyloan.loan_transaction FROM app_role` (append-only ledger enforcement) **must be moved to after** the new blanket `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policyloan TO app_role` statement — ordering matters: a `REVOKE` followed by a later broader `GRANT` would silently re-grant what was just revoked, undoing the append-only guarantee. Task 6 places the `REVOKE` as the last statement in the file.
- **`app_role` privilege and RLS tests must be extended, not just the migrations.** `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java` and `src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java` (root package) already prove `app_role` can reach `party`/`product`/`underwriting`/`audit` through the application's own `DataSource` and that RLS genuinely isolates tenants for `party`/`product`/`underwriting` — both files carry an explicit javadoc history of this exact bug class reaching M1's and M2's final reviews. Task 1 and Task 6 add the equivalent proof for `policy` and `policyloan` respectively, following the established pattern exactly (read both files before editing — the pattern is: bootstrap `app_role` to a real `LOGIN` role via `ALTER ROLE` in `@BeforeAll`, point `@DynamicPropertySource`'s `spring.datasource.username`/`password` at it (privileges test) or `SET ROLE app_role` on a raw JDBC connection (RLS test), then exercise a real business operation).
- **The underwriting-decision auto-issuance trigger requires two small, additive fixes to the already-merged M2 `underwriting` module** — discovered by reading the actual code, not stated in any design doc:
  1. `underwriting.api.UnderwritingCaseView` does not expose `productVersionId`, `sumAssuredAmount`, or `sumAssuredCurrency` (only `caseId`, `applicantPartyId`, `productId`, `status`, `referralStatus`, decision fields). `policy.issuePolicy`'s auto-issuance path needs all three from the decided case (it cannot re-derive them from the `UnderwritingDecisionMade` event payload, which per `api/asyncapi-events.yaml` carries only `caseId, outcome, loadingPercent, decidedAt` — this is exactly the insufficiency the brief's gap analysis (§9 item 10) already established, resolving in favor of "yes, `policy` must call `UnderwritingApi.getCase(caseId)`"; the gap is that `getCase`'s return type doesn't carry enough fields either). Task 2 adds the three fields to the record (additive — no existing caller passes them positionally except `UnderwritingApiImpl.toView`, which Task 2 also updates).
  2. `underwriting` currently publishes **zero** domain events at all (confirmed — no `ApplicationEventPublisher` anywhere in the module). `openapi-policy.yaml`'s own description says normal issuance is "system-triggered by consuming `UnderwritingDecisionMade` internally" — but nothing produces that event today. Task 2 adds one `ApplicationEventPublisher.publishEvent(DomainEventEnvelope.of("underwriting.UnderwritingDecisionMade", ...))` call inside `UnderwritingApiImpl.submitAssessment`, fired exactly when a case transitions to `DECIDED`, matching the already-defined `UnderwritingDecisionMadePayload` schema (`caseId, outcome, loadingPercent, decidedAt`) in `api/asyncapi-events.yaml:370-374`. **Flagged as a judgment call** — modifying an already-merged M2 module is not one of the pre-made decisions; this plan concludes it is necessary and in the spirit of "fix the gap now, proactively" established elsewhere in this same brief (the migration-grants and `policyloan/api` `NamedInterface` fixes), rather than leaving `policy`'s primary issuance path with no real producer to react to. See Self-Review Notes.
- **`product.api.ProductSnapshotView` needs two additive fields for the same reason.** It currently exposes `productId, productVersionId, effectiveDate, ifrsMeasurementModel, gracePeriodDays, maxLoanToValuePercent` — no `category` and no way to read `product_version.surrender_charge_schedule` (the column exists in `db-migrations/product/V1__create_product_schema.sql:39` but is not mapped by the `ProductVersion` entity or exposed by any `ProductApi` method). `policy` needs `category` to gate `SUSPENDED` eligibility (see below) and needs the surrender-charge schedule to compute `quoteSurrenderValue`. Task 1 adds `ProductCategory category` and `String surrenderChargeScheduleJson` (raw JSON text, parsed defensively downstream — see Task 2) to the record, maps the column on `ProductVersion`, and populates both in `ProductApiImpl.getActiveSnapshot`.
- **Numeric/structural placeholders, flagged exactly like `refdata`'s existing four.** Per the established convention (`db-migrations/refdata/V1__create_refdata_schema.sql`'s `TZ_CONTESTABILITY_MONTHS` etc., each commented `-- PLACEHOLDER, pending ...`):
  - **Loan interest rate** has no source anywhere in the design docs or schema. Task 6 adds `db-migrations/refdata/V2__seed_policy_loan_parameters.sql` (a **new** migration file — `V1` is already-applied elsewhere and must not be edited) seeding `TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE` / code `DEFAULT` / jurisdiction `TZ` / value `12.0` (percent per annum), commented `-- PLACEHOLDER, pending Actuarial sign-off`.
  - **Which product categories support `SUSPENDED`** is explicitly flagged-not-resolved in `docs/03-aggregate-design.md` ("working assumption is group life/education savings group schemes ... implementer must build this as configurable, not hardcode"). The same new migration file seeds a `POLICY_SUSPENSION_ELIGIBLE_CATEGORIES` code set with two rows (`GROUP_LIFE`, `EDUCATION_SAVINGS`), each commented `-- PLACEHOLDER, pending Product sign-off`, read via the existing `ReferenceDataApi.getCodes(String codeSetKey)` (no `refdata` code change needed — the method already exists and already returns exactly this shape).
  - **`product.product_version.surrender_charge_schedule` JSONB's shape is undefined anywhere.** This plan defines it explicitly (Task 2): a JSON array of duration-band objects, `[{"minMonths": 0, "maxMonths": 12, "chargePercent": 100.0}, {"minMonths": 12, "maxMonths": 36, "chargePercent": 20.0}, {"minMonths": 36, "maxMonths": null, "chargePercent": 0.0}]` — `minMonths` inclusive, `maxMonths` exclusive-or-null-for-unbounded, `chargePercent` applied against cash value for a policy whose duration-in-force (issue date to quote date, in whole months) falls in that band. A `null`/missing/empty schedule means **zero charge**, not a crash or a 500 — parsed defensively inside a `try/catch` that falls back to zero on any parse failure, with a code comment marking the shape itself as "a plan-level decision pending Actuarial confirmation, not a confirmed contractual schedule."
  - Every one of the above carries an explicit code comment saying so at its point of use — never a silent guess.
- **Value types stay raw, matching every other module's convention — no new wrapper classes.** `docs/02-module-architecture.md`'s pseudocode `PolicyApi` interface uses conceptual types (`PolicyNumber`, `Money`, `UnderwritingCaseId`, `ReservationId`) that don't exist anywhere in the actual codebase — `party`, `product`, and `underwriting` all use raw `UUID`/`String`/`BigDecimal`+`String currency` pairs instead (e.g. `UnderwritingApi.openCase(..., BigDecimal sumAssuredAmount, String sumAssuredCurrency, ...)`, not a `Money` object). This plan follows that established convention: `PolicyApi`/`PolicyLoanApi` methods take `String policyNumber` (matching `PolicyNumberRef`'s pattern-string OpenAPI type), `UUID underwritingCaseId`, `UUID reservationId`, and `BigDecimal amount, String currency` pairs for money — never a `Money`, `PolicyNumber`, or `ReservationId` wrapper type.
- **Money JSON never round-trips through `BigDecimal`'s binary representation carelessly.** `openapi-common.yaml#/components/schemas/Money.amount` is a decimal *string*, matching `OpenCaseRequest.Money`'s existing pattern (`@Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount`). Every request DTO in Tasks 4 and 7 defines its own nested `Money` record mirroring that exact pattern (no shared `Money` class exists in the codebase to reuse — each module that needs one defines its own nested copy, matching `underwriting.infrastructure.OpenCaseRequest.Money`'s precedent).
- **Event payloads follow the `party` precedent, with one narrow, necessary deviation.** Per `PartyApiImpl`'s established pattern: constructor-inject `ApplicationEventPublisher`, publish inside `@Transactional` methods via `eventPublisher.publishEvent(DomainEventEnvelope.of("<module>.<EventType>", tenantId, payload))`, payload as an untyped map, `sequenceNumber` left `null` (still no ordering-sensitive consumer in this plan's scope). **Deviation:** `Map.of(...)` throws `NullPointerException` on any `null` value, and several payloads in this plan have a genuinely nullable field (`PolicyIssued.agentOfRecordId`, `LoanDisbursementRequested.idempotencyKey`) — for exactly those two events, this plan builds the payload with a mutable `LinkedHashMap` and `put`s the optional key only when non-null (an absent key, not a null value) instead of `Map.of(...)`. Every other event payload in this plan uses `Map.of(...)` directly, unchanged from the precedent, since none of their fields are ever null. **Flagged as a judgment call** in Self-Review Notes.
- **`loan_encumbrance_amount` is updated synchronously, not via async event consumption.** `policy.policy_account`'s column comment describes it as "updated only by consuming `policyloan`'s `LoanOriginated`/`LoanRepaid`/`LoanSettledForPayout` events" — but `policyloan.confirmReservation(reservationId)` is already a direct synchronous call from `policyloan` into `policy`'s own transaction (that is the entire point of the reserve/confirm/release protocol). This plan updates `loan_encumbrance_amount` directly inside `PolicyApiImpl.confirmReservation`, in the same transaction, rather than adding a separate `@TransactionalEventListener` for `policyloan.LoanOriginated` that would introduce an async lag the synchronous call doesn't need. `LoanOriginated` is still published (for `audit` and any future consumer) — `policy` simply doesn't listen to its own encumbrance-relevant copy of information it already got synchronously. **Scope limitation, stated explicitly, not silently:** this plan does **not** add a `policy`-side listener for `LoanRepaid`/`LoanSettledForPayout`, so `loan_encumbrance_amount` only ever increases (at `confirmReservation`) and never decreases on repayment in M3 — a customer who fully repays a loan will not see restored available loan value until a later milestone adds that consumption path. This is the safe direction (it can only under-state, never over-state, available loan value), and is noted in Self-Review Notes.
- **`recordRepayment` treats every repayment as immediately confirmed — a stated simplification, not the real design.** Per Module Architecture, the real trigger for `LoanRepaid` is consuming `payment.PaymentConfirmed` — `payment` is M5, not built. `openapi-policyloan.yaml`'s repayment endpoint response description ("pending payment gateway confirmation") implies a two-phase pending→confirmed flow this plan does not build. `PolicyLoanApiImpl.recordRepayment` instead posts the `LoanTransaction` and publishes `LoanRepaid` in the same call, with a code comment stating this is a placeholder until M5's real gateway confirmation exists. A `PolicyLoanApi.markDisbursed(UUID loanId)` internal-only method (not part of `openapi-policyloan.yaml` — same "internal-only" convention as `ProductApi.resolveRatingMultiplier`) exists purely as a test seam to move a loan from `DISBURSEMENT_REQUESTED` to `DISBURSED` (the real trigger, consuming `payment.DisbursementCompleted`, doesn't exist either) so repayment's success path is testable without M5.
- **Module-specific `@RestControllerAdvice` classes MUST carry `@Order(Ordered.HIGHEST_PRECEDENCE)`.** The root `GlobalExceptionHandler` is `@Order(Ordered.LOWEST_PRECEDENCE)`; Spring resolves `@ExceptionHandler` by first-matching-advice-bean-wins, not merged-by-specificity across beans — omitting `@Order` on `PolicyExceptionHandler`/`PolicyLoanExceptionHandler` would silently let `GlobalExceptionHandler`'s catch-all shadow them depending on classpath-scan order (the exact regression M1's final review caught for `PartyExceptionHandler`).
- **State-machine violations throw a policy-owned exception type directly from the domain entity, not a bare JDK exception.** `ProductDefinition`/`Party`'s existing domain entities throw bare `IllegalArgumentException`/`IllegalStateException` for invariant violations, relying on `GlobalExceptionHandler`'s generic 400 mapping — that convention does not fit here, because state-machine violations need the OpenAPI's `409 Conflict` semantics specifically, and a module-scoped advice cannot safely add a blanket handler for a generic JDK exception type (e.g. `IllegalStateException` is also thrown by `TenantContext.get()`'s fail-loud missing-tenant guard — an unrestricted, `HIGHEST_PRECEDENCE` advice mapping `IllegalStateException` to 409 app-wide would misclassify that unrelated failure for every module, not just `policy`). `policy.domain.Policy` therefore imports and throws `policy.api.InvalidPolicyStateException` directly — a same-module import (domain → api within `policy`), not a cross-module Spring Modulith dependency, so `allowedDependencies` is unaffected. **Flagged as a judgment call**, since it deviates from `product`/`party`'s domain-throws-bare-JDK-exception convention; see Self-Review Notes for the reasoning.
- **Controllers derive audit fields from `@AuthenticationPrincipal Jwt jwt` → `jwt.getSubject()`**, never a hardcoded string. Request DTOs carry Bean Validation matching each OpenAPI schema's `required` list, with `@Valid` on every `@RequestBody`.
- **Service methods read tenant via `TenantContext.get()` per-call** (never cached/injected as a field or constructor parameter), and every mutating method is `@Transactional`. **Concurrency tests must call `TenantContext.set(tenantId)` inside each spawned thread/task, not only on the main test thread** — `TenantContext` is a `ThreadLocal` and does not propagate to child threads; Task 3's and Task 8's genuinely concurrent tests set it explicitly inside every `Callable`/`Runnable` they submit to an `ExecutorService`.
- **Idempotency-Key accepted, not enforced** — same deliberate scope cut as M2's `underwriting` (`openapi-policy.yaml`'s endorsement/surrender endpoints and `openapi-policyloan.yaml`'s originate/repayment endpoints all reference the shared `IdempotencyKey` header parameter; controllers accept it as an optional `@RequestHeader` but do nothing with it yet — real dedup lands with `payment`'s idempotency work in M5).
- **Contract tests validate against the OpenAPI files via `swagger-request-validator-mockmvc`**, following `ProductContractTest`/`UnderwritingContractTest`'s idiom exactly (`@Testcontainers` + `@AutoConfigureMockMvc` + `SecurityMockMvcRequestPostProcessors.jwt()` + `OpenApiValidationMatchers.openApi().isValid(SPEC_PATH)`). **Every test must be falsifiable** — no asserting against empty result sets; every test populates real data first through the real HTTP/API surface (this codebase has a documented recurring vacuous-test problem; three were caught in M2's own final review).
- **`api/openapi/openapi-policy.yaml`'s `ManualIssueRequest` schema has a literal duplicate-key defect** (two sibling `required:` arrays at lines ~276 and ~288 — the second, longer one is what YAML parsers actually honor, but this is an authoring bug, not a real second constraint). Task 4 merges them into one array: `required: [underwritingCaseId, policyholderPartyId, productVersionId, sumAssured, agentOfRecordId, reasonForManualIssue]`.
- **Beneficiary exactly-one-of/shares-sum-to-100 is hand-written application validation, not a generated contract check.** `openapi-policy.yaml`'s `BeneficiaryInput` schema only declares `required: [type, sharePercent]` — the exactly-one-of(`partyId`,`freeformDesignee`) and sum-to-100 invariants are prose-only in the spec and enforced by a DB `CHECK` constraint at the single-row level (which cannot express the cross-row sum invariant at all). Task 2 hand-writes both checks in `PolicyApiImpl.replaceBeneficiaries`; Task 5 hand-writes contract tests asserting `422` for each violation, since a schema-generated test would never catch either.
- **Customer object-level authorization mirrors `PartyController`'s established `party_id`-claim idiom exactly**, not `PartyApiImpl.findPartyOrThrow`'s tenant-equality idiom (that one is for tenant scoping, already handled uniformly by RLS + `TenantContext` for `policy`). `GET /policies/{policyNumber}` checks, when the caller is `ROLE_REALM_CUSTOMERS`, that `jwt.getClaimAsString("party_id")` equals the policy's `policyholderPartyId`; same "not found" `404`/`PolicyNotFoundException` either way is used for "doesn't exist" and "exists but isn't yours" at the **service** layer (anti-enumeration, matching `PartyApiImpl.findPartyOrThrow`'s framing), but the customer-vs-not-your-policy check itself is a `403` at the **controller** layer (matching `PartyController.getParty`'s exact structure — realm-membership check via `Authentication.getAuthorities()`, then claim comparison, then `AccessDeniedException` on mismatch). Agent/agency-hierarchy scoping remains deferred (no agent data model exists yet — same cut M1/M2 already made).
- **Out of scope for M3, stated explicitly, not silently dropped:**
  - Maturity processing/trigger (no scheduled/external trigger is specified anywhere, and it's entangled with the deferred choreography — `PolicyMatured` is never published in this plan).
  - `ACTIVE`/`LAPSED`/`SUSPENDED` → `SURRENDERED`/`MATURED` transitions of any kind (deferred choreography — see Architecture section above).
  - Anything requiring the `payment` module (M5) beyond publish-only events — `LoanDisbursementRequested` is published but nothing consumes it; loans legitimately rest at `DISBURSEMENT_REQUESTED` (or, via the `markDisbursed` test seam, `DISBURSED`/`REPAYING`) in every test in this plan.
  - `billing`-driven lapse (M4) — `lapsePolicy` exists as a directly-callable, fully-tested state transition; nothing in this plan calls it automatically from a grace-period or dunning-escalation timer.
  - A true cross-tenant `@Scheduled` background sweep for expired loan-value reservations. See Task 3's own note — the platform's RLS design fails closed (a connection with no `TenantContext` sees zero rows on every RLS-protected table), so a global timer thread with no tenant loop has nothing to iterate without either a tenant-directory table (doesn't exist) or an RLS-bypassing role (the platform's strongest existing security invariant explicitly forbids `app_role` ever having `BYPASSRLS`). This plan implements the TTL sweep as an opportunistic, per-tenant sweep executed at the start of every `reserveLoanValue` call (correct, RLS-safe, and satisfies the "crash case self-heals" requirement, just not on a fixed wall-clock timer). **Flagged as a judgment call** in Self-Review Notes.

---

### Task 1: `policy` migration, domain entities, repositories, and the two proactive M2-module fixes it depends on

**Files:**
- Modify: `db-migrations/policy/V1__create_policy_schema.sql` (new columns on `policy.policy`; RLS on the remaining 6 tenant-scoped tables; `app_role` grants)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductSnapshotView.java` (add `category`, `surrenderChargeScheduleJson`)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java` (map the `surrender_charge_schedule` column)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java` (populate the two new fields in `getActiveSnapshot`)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/api/InvalidPolicyStateException.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/domain/{Policy,PolicyAccount,Endorsement,Beneficiary,Coverage,LoanValueReservation}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/{PolicyRepository,PolicyAccountRepository,EndorsementRepository,BeneficiaryRepository,CoverageRepository,LoanValueReservationRepository}.java`
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java` (add a `policy`-schema round trip)
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java` (add a `policy.policy` tenant-isolation proof)

**Interfaces:**
- Consumes: `TenantContext.get()` (M1), `MigrationTestSupport.applyMigration` (M1, test-only), the modified `ProductApi.getActiveSnapshot` (this task).
- Produces: the six domain entities and six repositories Task 2 and Task 3 build `PolicyApiImpl` on top of. Exact repository method signatures other tasks depend on:
  ```java
  public interface PolicyRepository extends JpaRepository<Policy, String> {
      Optional<Policy> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
      Page<Policy> findByTenantIdAndPolicyholderPartyId(UUID tenantId, UUID policyholderPartyId, Pageable pageable);
      Page<Policy> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
      Page<Policy> findByTenantId(UUID tenantId, Pageable pageable);
  }
  public interface PolicyAccountRepository extends JpaRepository<PolicyAccount, String> {
      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("SELECT pa FROM PolicyAccount pa WHERE pa.policyNumber = :policyNumber")
      Optional<PolicyAccount> lockByPolicyNumber(String policyNumber);
  }
  public interface LoanValueReservationRepository extends JpaRepository<LoanValueReservation, UUID> {
      Optional<LoanValueReservation> findByReservationIdAndTenantId(UUID reservationId, UUID tenantId);
      java.math.BigDecimal sumReservedAmountForPolicy(String policyNumber, UUID tenantId); // native aggregate, see Step 3
      int expireStaleReservations(String policyNumber, UUID tenantId, java.time.Instant now); // bulk update, see Step 3
  }
  ```

- [ ] **Step 1: Fix `policyloan/api/package-info.java`'s missing `@NamedInterface` (one line, do this first — decision 6)**

Read the current file first (`src/main/java/tz/co/nlolo/lifeplatform/policyloan/api/package-info.java` — currently a bare `package tz.co.nlolo.lifeplatform.policyloan.api;` statement), then replace its entire contents:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.policyloan.api;
```

- [ ] **Step 2: Extend `db-migrations/policy/V1__create_policy_schema.sql`**

Read the current file first to confirm exact current structure (123 lines, RLS/grants section at the end), then append after the final `CREATE POLICY policy_tenant_isolation ...` block:

```sql
-- New columns needed by the policy lifecycle state machine (Deliverable 3 Rev 2 §3) --
-- absent from the original schema, which only carried the `status` enum itself.
ALTER TABLE policy.policy ADD COLUMN product_category VARCHAR(30);
-- Captured at issuance from product.ProductSnapshotView.category() (see this task's
-- product.api changes below) so SUSPENDED-eligibility can be checked against
-- POLICY_SUSPENSION_ELIGIBLE_CATEGORIES (db-migrations/refdata/V2, Task 6) without a
-- synchronous call into product on every suspend attempt.
ALTER TABLE policy.policy ADD COLUMN suspended_at TIMESTAMPTZ;
ALTER TABLE policy.policy ADD COLUMN suspension_reason VARCHAR(255);
ALTER TABLE policy.policy ADD COLUMN lapsed_at TIMESTAMPTZ;

-- Defense-in-depth RLS (Global Constraints) -- policy.policy already had it; the
-- remaining 6 tenant-scoped tables in this schema did not.
ALTER TABLE policy.policy_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_account_tenant_isolation ON policy.policy_account
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policy.fund_holding ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_holding_tenant_isolation ON policy.fund_holding
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policy.endorsement ENABLE ROW LEVEL SECURITY;
CREATE POLICY endorsement_tenant_isolation ON policy.endorsement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policy.beneficiary ENABLE ROW LEVEL SECURITY;
CREATE POLICY beneficiary_tenant_isolation ON policy.beneficiary
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policy.coverage ENABLE ROW LEVEL SECURITY;
CREATE POLICY coverage_tenant_isolation ON policy.coverage
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policy.loan_value_reservation ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_value_reservation_tenant_isolation ON policy.loan_value_reservation
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role) has no access to this schema at all
-- and every request against it fails with "permission denied for schema policy"
-- (the exact bug M1's/M2's final whole-branch reviews found and fixed for every other
-- schema -- fixed here from the start instead of waiting for a third review to catch it).
GRANT USAGE ON SCHEMA policy TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA policy GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
```

`policy.fund_holding` gets RLS/grants here for completeness (every tenant-scoped table, no exceptions) but no JPA entity is created for it in this plan — nothing in M3's scope writes fund units (unit-linked fund transactions are out of scope; no design doc requires them for M3), so mapping an entity nothing uses would be dead code. Flagged, not silent.

- [ ] **Step 3: Extend `product.api.ProductSnapshotView` and `product.domain.ProductVersion`**

Read `src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductSnapshotView.java` first, then replace its entire contents:

```java
package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * M3 addition: `category` and `surrenderChargeScheduleJson` were not exposed here in M2
 * because nothing needed them yet. `policy.issuePolicy` needs `category` to gate SUSPENDED
 * eligibility (Deliverable 3 Rev 2 §3's flagged, unresolved "which product categories
 * support SUSPENDED" item); `policy.quoteSurrenderValue` needs the raw surrender-charge
 * JSON to compute an early-surrender penalty. Both are additive -- no existing caller of
 * this record breaks.
 */
public record ProductSnapshotView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                                   IfrsMeasurementModel ifrsMeasurementModel, int gracePeriodDays, BigDecimal maxLoanToValuePercent,
                                   ProductCategory category, String surrenderChargeScheduleJson) {}
```

Read `src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java` next, then add the missing column mapping and getter (insert after the `maxLoanToValuePercent` field/getter pair, and thread it through the constructor):

```java
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "surrender_charge_schedule", columnDefinition = "jsonb")
    private String surrenderChargeScheduleJson;

    public String getSurrenderChargeScheduleJson() { return surrenderChargeScheduleJson; }
    public void setSurrenderChargeScheduleJson(String surrenderChargeScheduleJson) { this.surrenderChargeScheduleJson = surrenderChargeScheduleJson; }
```

`surrender_charge_schedule` is never set by any M2 or M3 code path (no endpoint accepts it as input yet — `publishVersion`'s request shape is unchanged by this plan) so a plain setter, not a constructor parameter, is enough; every `ProductVersion` row will have `NULL` here until a future milestone adds a way to author it, and `quoteSurrenderValue` (Task 2) treats `NULL` as "no schedule, zero charge" per Global Constraints.

- [ ] **Step 4: Update `ProductApiImpl.getActiveSnapshot`**

Read `src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java` first, then replace the `getActiveSnapshot` method body:

```java
    @Override
    public ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.get();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductDefinition definition = productDefinitionRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel()),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson());
    }
```

- [ ] **Step 5: Write the `policy` domain entities**

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * State machine per docs/03-aggregate-design.md §3 (verbatim graph in this plan's header
 * Architecture note). Guard methods throw InvalidPolicyStateException (policy.api) directly
 * rather than a bare IllegalStateException -- see Global Constraints for why an unrestricted,
 * HIGHEST_PRECEDENCE module advice cannot safely catch a generic JDK exception type here
 * (TenantContext.get()'s fail-loud guard also throws IllegalStateException, for an unrelated
 * reason, and would be misclassified as a 409 app-wide if this class threw the same type).
 *
 * SURRENDERED and MATURED are valid enum values (the DB CHECK and every view type must be
 * able to represent a policy that reaches them via a later milestone) but no method on this
 * class transitions into either -- the surrender/maturity choreography is deferred wholesale
 * (see plan header). There is deliberately no surrender()/matureTo() method here.
 */
@Entity
@Table(name = "policy", schema = "policy")
public class Policy {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policyholder_party_id", nullable = false)
    private UUID policyholderPartyId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "product_category")
    private String productCategory;

    @Column(name = "agent_of_record_id")
    private UUID agentOfRecordId;

    @Column(nullable = false)
    private String status = "PROPOSED";

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency = "TZS";

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Policy() {}

    public Policy(String policyNumber, UUID tenantId, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                  String productCategory, UUID agentOfRecordId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String createdBy) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.policyholderPartyId = policyholderPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.productCategory = productCategory;
        this.agentOfRecordId = agentOfRecordId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.createdBy = createdBy;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPolicyholderPartyId() { return policyholderPartyId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getProductCategory() { return productCategory; }
    public UUID getAgentOfRecordId() { return agentOfRecordId; }
    public String getStatus() { return status; }
    public LocalDate getIssueDate() { return issueDate; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public Instant getLapsedAt() { return lapsedAt; }

    public void activate(LocalDate issueDate) {
        if (!"PROPOSED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " cannot be issued from status " + status);
        }
        this.status = "ACTIVE";
        this.issueDate = issueDate;
    }

    public void suspend(String reason) {
        if (!"ACTIVE".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE to be SUSPENDED (current: " + status + ")");
        }
        this.status = "SUSPENDED";
        this.suspendedAt = Instant.now();
        this.suspensionReason = reason;
    }

    public void resume() {
        if (!"SUSPENDED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be SUSPENDED to resume (current: " + status + ")");
        }
        this.status = "ACTIVE";
        this.suspendedAt = null;
        this.suspensionReason = null;
    }

    public void lapse() {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE or SUSPENDED to LAPSE (current: " + status + ")");
        }
        this.status = "LAPSED";
        this.lapsedAt = Instant.now();
    }

    /** LAPSED -> REINSTATED directly (not a second write to ACTIVE) -- a reinstated policy stays
     * labeled REINSTATED going forward, distinguishing it for audit/actuarial purposes from a
     * policy that was continuously ACTIVE. isInForce() treats both as equivalent for coverage
     * purposes. Window-eligibility (TZ_REINSTATEMENT_WINDOW_MONTHS) is checked by the caller
     * (PolicyApiImpl, Task 2) before this is invoked, using lapsedAt exposed above. */
    public void reinstate() {
        if (!"LAPSED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be LAPSED to be REINSTATED (current: " + status + ")");
        }
        this.status = "REINSTATED";
    }

    public boolean isInForce() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "policy_account", schema = "policy")
public class PolicyAccount {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private java.util.UUID tenantId;

    @Column(name = "cash_value_amount", nullable = false)
    private BigDecimal cashValueAmount = BigDecimal.ZERO;

    @Column(name = "cash_value_currency", nullable = false)
    private String cashValueCurrency = "TZS";

    // Non-authoritative projection (see Global Constraints: updated synchronously inside
    // PolicyApiImpl.confirmReservation, not via async event consumption of LoanOriginated).
    @Column(name = "loan_encumbrance_amount", nullable = false)
    private BigDecimal loanEncumbranceAmount = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PolicyAccount() {}

    public PolicyAccount(String policyNumber, java.util.UUID tenantId, BigDecimal cashValueAmount, String cashValueCurrency) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.cashValueAmount = cashValueAmount;
        this.cashValueCurrency = cashValueCurrency;
    }

    public String getPolicyNumber() { return policyNumber; }
    public java.util.UUID getTenantId() { return tenantId; }
    public BigDecimal getCashValueAmount() { return cashValueAmount; }
    public String getCashValueCurrency() { return cashValueCurrency; }
    public BigDecimal getLoanEncumbranceAmount() { return loanEncumbranceAmount; }

    public BigDecimal availableLoanValue(BigDecimal currentlyReserved) {
        // Deliberate M3 simplification (Global Constraints): available value is cash value net
        // of confirmed encumbrance and currently-RESERVED holds -- NOT further capped by
        // product_version.max_loan_to_value_percent, which would require issuePolicy to persist
        // that percentage onto PolicyAccount/Policy and is not required to prove the
        // Module-Architecture-B1 race-condition fix, this milestone's actual acceptance
        // criterion. Flagged, not silently dropped -- a future milestone can apply the LTV cap
        // as a further multiplier on cashValueAmount before this subtraction.
        return cashValueAmount.subtract(loanEncumbranceAmount).subtract(currentlyReserved);
    }

    public void increaseEncumbrance(BigDecimal amount) {
        this.loanEncumbranceAmount = this.loanEncumbranceAmount.add(amount);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "endorsement", schema = "policy")
public class Endorsement {

    @Id
    @Column(name = "endorsement_id")
    private UUID endorsementId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "endorsement_type", nullable = false)
    private String endorsementType;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Endorsement() {}

    public Endorsement(UUID tenantId, String policyNumber, String endorsementType, LocalDate effectiveDate,
                        Map<String, Object> changes, String approvedBy) {
        this.endorsementId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.endorsementType = endorsementType;
        this.effectiveDate = effectiveDate;
        this.changes = changes;
        this.approvedBy = approvedBy;
    }

    public UUID getEndorsementId() { return endorsementId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getEndorsementType() { return endorsementType; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public Map<String, Object> getChanges() { return changes; }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiary", schema = "policy")
public class Beneficiary {

    @Id
    @Column(name = "beneficiary_id")
    private UUID beneficiaryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "beneficiary_type", nullable = false)
    private String beneficiaryType;

    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "freeform_designee")
    private String freeformDesignee;

    @Column(name = "share_percent", nullable = false)
    private BigDecimal sharePercent;

    @Column(nullable = false)
    private boolean revocable = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Beneficiary() {}

    // Exactly-one-of(partyId, freeformDesignee) is validated by the caller (PolicyApiImpl,
    // Task 2) BEFORE construction -- this constructor trusts its inputs, matching the DB's own
    // chk_beneficiary_exactly_one_designation CHECK as a second, independent layer, not the
    // only layer (Global Constraints: hand-written application validation, not schema-only).
    public Beneficiary(UUID tenantId, String policyNumber, String beneficiaryType, UUID partyId,
                        String freeformDesignee, BigDecimal sharePercent, boolean revocable) {
        this.beneficiaryId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.beneficiaryType = beneficiaryType;
        this.partyId = partyId;
        this.freeformDesignee = freeformDesignee;
        this.sharePercent = sharePercent;
        this.revocable = revocable;
    }

    public UUID getBeneficiaryId() { return beneficiaryId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getBeneficiaryType() { return beneficiaryType; }
    public UUID getPartyId() { return partyId; }
    public String getFreeformDesignee() { return freeformDesignee; }
    public BigDecimal getSharePercent() { return sharePercent; }
    public boolean isRevocable() { return revocable; }
    public boolean isActive() { return active; }

    public void deactivate() { this.active = false; }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coverage", schema = "policy")
public class Coverage {

    @Id
    @Column(name = "coverage_id")
    private UUID coverageId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "benefit_type", nullable = false)
    private String benefitType;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency = "TZS";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Coverage() {}

    public Coverage(UUID tenantId, String policyNumber, String benefitType, BigDecimal sumAssuredAmount, String sumAssuredCurrency) {
        this.coverageId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.benefitType = benefitType;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
    }

    public UUID getCoverageId() { return coverageId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getBenefitType() { return benefitType; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public boolean isActive() { return active; }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Module-Architecture-B1 fix (docs/02-module-architecture.md §3.4/§3.5 -- see plan header
 * for the full disambiguation from the unrelated Aggregate-Design-doc "B1"). Status lifecycle:
 * RESERVED -(confirmReservation)-> CONFIRMED, RESERVED -(releaseReservation)-> RELEASED,
 * RESERVED -(TTL sweep, Task 3)-> EXPIRED. CONFIRMED/RELEASED/EXPIRED are all terminal.
 */
@Entity
@Table(name = "loan_value_reservation", schema = "policy")
public class LoanValueReservation {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(nullable = false)
    private String status = "RESERVED";

    @Column(name = "ttl_expires_at", nullable = false)
    private Instant ttlExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoanValueReservation() {}

    public LoanValueReservation(UUID tenantId, String policyNumber, BigDecimal amount, String currency, Instant ttlExpiresAt) {
        this.reservationId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.ttlExpiresAt = ttlExpiresAt;
    }

    public UUID getReservationId() { return reservationId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getTtlExpiresAt() { return ttlExpiresAt; }

    public void confirm() { this.status = "CONFIRMED"; }
    public void release() { this.status = "RELEASED"; }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 409 Conflict by policy.infrastructure.PolicyExceptionHandler -- see Global
 * Constraints for why this is a dedicated type rather than a bare IllegalStateException. */
public class InvalidPolicyStateException extends RuntimeException {
    public InvalidPolicyStateException(String message) {
        super(message);
    }
}
```

- [ ] **Step 6: Write the repositories**

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, String> {
    Optional<Policy> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
    Page<Policy> findByTenantIdAndPolicyholderPartyId(UUID tenantId, UUID policyholderPartyId, Pageable pageable);
    Page<Policy> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Page<Policy> findByTenantId(UUID tenantId, Pageable pageable);
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PolicyAccountRepository extends JpaRepository<PolicyAccount, String> {
    /** PESSIMISTIC_WRITE is the Module-Architecture-B1 fix's actual lock -- it serializes
     * concurrent reserveLoanValue calls against the SAME policy_account row within policy's
     * own transaction (never held across a module boundary), closing the check-then-act race
     * two concurrent loan requests would otherwise hit. See Task 3. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pa FROM PolicyAccount pa WHERE pa.policyNumber = :policyNumber")
    Optional<PolicyAccount> lockByPolicyNumber(String policyNumber);
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Endorsement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndorsementRepository extends JpaRepository<Endorsement, java.util.UUID> {
    List<Endorsement> findByPolicyNumberOrderByEffectiveDateDesc(String policyNumber);
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
    List<Beneficiary> findByPolicyNumberAndActiveTrue(String policyNumber);
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverageRepository extends JpaRepository<Coverage, UUID> {
    List<Coverage> findByPolicyNumberAndActiveTrue(String policyNumber);
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.LoanValueReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanValueReservationRepository extends JpaRepository<LoanValueReservation, UUID> {
    Optional<LoanValueReservation> findByReservationIdAndTenantId(UUID reservationId, UUID tenantId);

    List<LoanValueReservation> findByPolicyNumberAndTenantIdAndStatus(String policyNumber, UUID tenantId, String status);

    /** Opportunistic TTL sweep (Global Constraints/Task 3) -- runs inside the caller's own
     * TenantContext, so it is RLS-safe (sees only that tenant's rows) unlike a true cross-tenant
     * @Scheduled job would be. Called at the start of every reserveLoanValue before computing
     * availability, so a crashed prior reservation self-heals on next access. */
    @Modifying
    @Query("UPDATE LoanValueReservation r SET r.status = 'EXPIRED' " +
           "WHERE r.policyNumber = :policyNumber AND r.tenantId = :tenantId AND r.status = 'RESERVED' AND r.ttlExpiresAt < :now")
    int expireStaleReservations(String policyNumber, UUID tenantId, Instant now);
}
```

- [ ] **Step 7: Extend `AppRolePrivilegesIntegrationTest.java`**

Read the current file first. Add `"db-migrations/policy/V1__create_policy_schema.sql"` to the `@BeforeAll`'s `MigrationTestSupport.applyMigration(...)` call (after the `underwriting` line), then add a new `@Autowired private PolicyApi policyApi;` field (import `tz.co.nlolo.lifeplatform.policy.api.PolicyApi` and the other `policy.api` types this test needs) and this test method:

```java
    /**
     * M3 addition: proves app_role can write and read through policy.policy/policy_account
     * via the app's own DataSource -- issuePolicy persists both tables in one transaction, and
     * getPolicy reads them back, exercising INSERT+SELECT on both new grants together.
     */
    @Test
    void appRoleCanIssueAndReadAPolicyThroughTheApplicationsOwnDataSource() {
        TenantContext.set(UUID.randomUUID());

        PartyView policyholder = partyApi.registerIndividual("App Role Policy Applicant", LocalDate.of(1988, 6, 1),
            "+255713000002", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("APP-ROLE-POLICY", "App Role Policy Product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        UnderwritingCaseView opened = underwritingApi.openCase(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new java.math.BigDecimal("10"), "underwriter1");

        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
            new java.math.BigDecimal("1000000"), "TZS", null, "MONTHLY", java.util.List.of(), "App role smoke test");
        PolicyView issued = policyApi.issuePolicy(opened.caseId(), request, "test-staff");
        assertThat(issued.policyNumber()).isNotNull();

        PolicyView fetched = policyApi.getPolicy(issued.policyNumber());
        assertThat(fetched.status()).isEqualTo(PolicyStatus.ACTIVE);
    }
```

Add the corresponding imports (`tz.co.nlolo.lifeplatform.policy.api.*`) at the top of the file alongside the existing `product.api`/`underwriting.api` imports.

- [ ] **Step 8: Extend `RowLevelSecurityIntegrationTest.java`**

Read the current file first. Add `"db-migrations/policy/V1__create_policy_schema.sql"` to the `@BeforeAll`'s migration list and its own `GRANT USAGE ON SCHEMA policy TO app_role; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policy TO app_role;` pair (mirroring the existing `product`/`underwriting` redundant-but-explicit grants in that same `@BeforeAll`), add `@Autowired private PolicyApi policyApi;`, and this test method (uses the same `openCaseForCurrentTenant` private helper already in the file, extended one step further):

```java
    /**
     * M3 addition: proves policy_tenant_isolation actually isolates tenants for policy.policy,
     * not merely that the CREATE POLICY statement parses -- same proof shape as the existing
     * product/underwriting tests in this class.
     */
    @Test
    void policyIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-POLICY-A", "3");
        underwritingApi.submitAssessment(caseIdA, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedA = underwritingApi.getCase(caseIdA);
        String policyNumberA = policyApi.issuePolicy(caseIdA,
            new PolicyApi.IssueRequest(decidedA.applicantPartyId(), decidedA.productId(), decidedA.productVersionId(),
                new java.math.BigDecimal("1000000"), "TZS", null, "MONTHLY", java.util.List.of(), "RLS test"),
            "test-staff").policyNumber();

        TenantContext.set(tenantB);
        UUID caseIdB = openCaseForCurrentTenant("RLS-POLICY-B", "4");
        underwritingApi.submitAssessment(caseIdB, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedB = underwritingApi.getCase(caseIdB);
        policyApi.issuePolicy(caseIdB,
            new PolicyApi.IssueRequest(decidedB.applicantPartyId(), decidedB.productId(), decidedB.productVersionId(),
                new java.math.BigDecimal("2000000"), "TZS", null, "MONTHLY", java.util.List.of(), "RLS test"),
            "test-staff");

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM policy.policy")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT policy_number FROM policy.policy")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(policyNumberA);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }
```

Note this test relies on `issuePolicy`, `UnderwritingApi.getCase` returning `productVersionId`/case fields, and `UnderwritingDecisionMade` auto-publication all existing by the time this file compiles — since `RowLevelSecurityIntegrationTest` is edited again structurally in no later task, run this file's compile/test step only after Task 2 lands (or write the method now and defer running it — either is fine; the plan's Task 9 runs the full suite regardless).

- [ ] **Step 9: Compile**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
```

Expected: `BUILD SUCCESS`. (The two test-file edits in Steps 7–8 reference `PolicyApi`/`issuePolicy`, which don't exist until Task 2 — compile `src/main` only at this checkpoint; defer compiling `src/test` for those two files until Task 2 lands, or write them now as dead code the compiler will catch once Task 2's types exist. Either ordering is fine since this is a single continuous implementation effort, not separate releases.)

- [ ] **Step 10: Commit**

```bash
git add db-migrations/policy src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductSnapshotView.java \
  src/main/java/tz/co/nlolo/lifeplatform/product/domain/ProductVersion.java \
  src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java \
  src/main/java/tz/co/nlolo/lifeplatform/policy/domain src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure \
  src/main/java/tz/co/nlolo/lifeplatform/policy/api/InvalidPolicyStateException.java \
  src/main/java/tz/co/nlolo/lifeplatform/policyloan/api/package-info.java \
  src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java \
  src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java
git commit -m "feat: policy migration, domain entities, and proactive product/policyloan fixes"
```

---

### Task 2: `policy` application layer — issuance, lifecycle state machine, beneficiaries, endorsements, surrender quoting, events

**Files:**
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/UnderwritingCaseView.java` (add `productVersionId`, `sumAssuredAmount`, `sumAssuredCurrency`)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java` (thread the new fields through `toView`; publish `underwriting.UnderwritingDecisionMade` on decision)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java` (add the combined party+status search query)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/api/{PolicyStatus,BeneficiaryType,PolicyView,BeneficiaryView,CoverageStatusView,SurrenderQuoteView,PolicyNotFoundException,BeneficiaryValidationException,InsufficientLoanValueException,ReservationNotFoundException,PolicyApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/application/{PolicyApiImpl,UnderwritingDecisionEventListener}.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `PartyApi.getParty`, `ProductApi.getActiveSnapshot` (extended, Task 1), `UnderwritingApi.getCase`/`submitAssessment` (extended, this task), `ReferenceDataApi.getCodes`/`getValue`, all six Task 1 repositories.
- Produces: `PolicyApi` — Task 3 extends `PolicyApiImpl` (same file) with the reserve/confirm/release methods' bodies; Task 4's REST layer and Task 6's `PolicyLoanApiImpl` both consume the finished interface. Exact signature every later task must match exactly:
  ```java
  package tz.co.nlolo.lifeplatform.policy.api;

  public interface PolicyApi {
      record BeneficiaryInput(BeneficiaryType type, java.util.UUID partyId, String freeformDesignee, java.math.BigDecimal sharePercent, boolean revocable) {}
      record IssueRequest(java.util.UUID policyholderPartyId, java.util.UUID productId, java.util.UUID productVersionId,
                           java.math.BigDecimal sumAssuredAmount, String sumAssuredCurrency, java.util.UUID agentOfRecordId,
                           String premiumFrequency, java.util.List<BeneficiaryInput> beneficiaries, String reasonForManualIssue) {}
      record EndorsementInput(String endorsementType, java.time.LocalDate effectiveDate, java.util.Map<String, Object> changes) {}

      PolicyView issuePolicy(java.util.UUID underwritingCaseId, IssueRequest request, String issuedBy);
      PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy);
      void replaceBeneficiaries(String policyNumber, java.util.List<BeneficiaryInput> beneficiaries, String changedBy);
      SurrenderQuoteView quoteSurrenderValue(String policyNumber);
      PolicyView getPolicy(String policyNumber);
      org.springframework.data.domain.Page<PolicyView> searchPolicies(java.util.UUID policyholderPartyId, PolicyStatus status, org.springframework.data.domain.Pageable pageable);
      CoverageStatusView getCoverageStatus(String policyNumber, java.time.LocalDate asOf);
      boolean isPolicyInForce(String policyNumber, java.time.LocalDate asOf);

      // Module-Architecture-B1 fix -- bodies land in this task; Task 3 adds the TTL sweep call.
      java.util.UUID reserveLoanValue(String policyNumber, java.math.BigDecimal amount, String currency, java.time.Duration ttl);
      void confirmReservation(java.util.UUID reservationId);
      void releaseReservation(java.util.UUID reservationId);

      void suspendPolicy(String policyNumber, String reason, String suspendedBy);
      void resumeSuspendedPolicy(String policyNumber, String resumedBy);
      void lapsePolicy(String policyNumber, String lapsedBy);
      void reinstatePolicy(String policyNumber, String reinstatedBy);
  }
  ```
  `PolicyLoanApiImpl` (Task 6) calls exactly `reserveLoanValue`/`confirmReservation`/`releaseReservation`/`isPolicyInForce` — no other `PolicyApi` method.

- [ ] **Step 1: Extend `underwriting.api.UnderwritingCaseView`**

Read the current file first, then replace its contents:

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * M3 addition: productVersionId/sumAssuredAmount/sumAssuredCurrency were not exposed here in
 * M2 because nothing needed them yet -- UnderwritingCase (the domain entity) has always stored
 * all three. policy.application.UnderwritingDecisionEventListener needs all three from a
 * decided case to issue a policy; the UnderwritingDecisionMade event payload alone
 * (caseId, outcome, loadingPercent, decidedAt per api/asyncapi-events.yaml) does not carry
 * them. Additive -- the sole existing construction site (UnderwritingApiImpl.toView) is
 * updated in the same commit as this file.
 */
public record UnderwritingCaseView(UUID caseId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                                    UnderwritingCaseStatus status, ReferralStatus referralStatus, DecisionOutcome decisionOutcome,
                                    BigDecimal decisionLoadingPercent, String decisionDeclineReason, Instant decisionDecidedAt,
                                    BigDecimal sumAssuredAmount, String sumAssuredCurrency) {}
```

- [ ] **Step 2: Update `UnderwritingApiImpl` — thread the new fields through, and publish `UnderwritingDecisionMade`**

Read the current file first. Add two fields/constructor params (`ApplicationEventPublisher eventPublisher`) and the corresponding imports (`org.springframework.context.ApplicationEventPublisher`, `tz.co.nlolo.lifeplatform.DomainEventEnvelope`, `java.util.LinkedHashMap`, `java.util.Map`):

```java
    private final ApplicationEventPublisher eventPublisher;

    public UnderwritingApiImpl(UnderwritingCaseRepository underwritingCaseRepository, RiskAssessmentRepository riskAssessmentRepository,
                                PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi, RulesEnginePort rulesEnginePort,
                                ApplicationEventPublisher eventPublisher) {
        this.underwritingCaseRepository = underwritingCaseRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.rulesEnginePort = rulesEnginePort;
        this.eventPublisher = eventPublisher;
    }
```

Replace `submitAssessment`'s body (insert the event publish immediately before `return toView(underwritingCase);`, after the existing `underwritingCaseRepository.save(underwritingCase);` line):

```java
    @Override
    @Transactional
    public UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);
        if (UnderwritingCaseStatus.DECIDED.name().equals(underwritingCase.getStatus())) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        underwritingCase.markInReview();

        RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
        riskAssessmentRepository.save(assessment);

        decideIfPossible(underwritingCase);
        underwritingCaseRepository.save(underwritingCase);

        // M3 addition: the ONLY producer of underwriting.UnderwritingDecisionMade anywhere in
        // the codebase -- policy.application.UnderwritingDecisionEventListener is this event's
        // sole consumer and has nothing to react to without this call (see plan Global
        // Constraints -- underwriting published zero domain events before this task).
        if (UnderwritingCaseStatus.DECIDED.name().equals(underwritingCase.getStatus())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId);
            payload.put("outcome", underwritingCase.getDecisionOutcome());
            // loadingPercent is genuinely nullable (only set when outcome=LOADED per the
            // chk_loading_only_when_loaded DB CHECK) -- Map.of(...) would throw NPE here for
            // every other outcome, hence the mutable map (Global Constraints).
            payload.put("loadingPercent", underwritingCase.getDecisionLoadingPercent());
            payload.put("decidedAt", underwritingCase.getDecisionDecidedAt().toString());
            eventPublisher.publishEvent(DomainEventEnvelope.of("underwriting.UnderwritingDecisionMade", tenantId, payload));
        }
        return toView(underwritingCase);
    }
```

Replace `toView`:

```java
    private UnderwritingCaseView toView(UnderwritingCase c) {
        return new UnderwritingCaseView(c.getCaseId(), c.getApplicantPartyId(), c.getProductId(), c.getProductVersionId(),
            UnderwritingCaseStatus.valueOf(c.getStatus()), ReferralStatus.valueOf(c.getReferralStatus()),
            c.getDecisionOutcome() != null ? DecisionOutcome.valueOf(c.getDecisionOutcome()) : null,
            c.getDecisionLoadingPercent(), c.getDecisionDeclineReason(), c.getDecisionDecidedAt(),
            c.getSumAssuredAmount(), c.getSumAssuredCurrency());
    }
```

- [ ] **Step 3: Add the combined search query to `PolicyRepository`**

Read `src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java` (Task 1) first, then add this method to the interface (both `policyholderPartyId` and `status` are independent optional query params on `GET /policies` per `openapi-policy.yaml` — the three single-filter methods Task 1 wrote don't cover "both given" case):

```java
    Page<Policy> findByTenantIdAndPolicyholderPartyIdAndStatus(UUID tenantId, UUID policyholderPartyId, String status, Pageable pageable);
```

- [ ] **Step 4: Write the `policy.api` enums, views, and exceptions**

```java
package tz.co.nlolo.lifeplatform.policy.api;

public enum PolicyStatus { PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED }
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

public enum BeneficiaryType { PARTY, FREEFORM }
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

public record BeneficiaryView(UUID beneficiaryId, BeneficiaryType type, UUID partyId, String freeformDesignee,
                               java.math.BigDecimal sharePercent, boolean revocable) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyView(String policyNumber, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                          UUID agentOfRecordId, PolicyStatus status, LocalDate issueDate,
                          BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                          BigDecimal cashValueAmount, String cashValueCurrency,
                          List<BeneficiaryView> beneficiaries) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CoverageStatusView(String policyNumber, LocalDate asOf, List<CoverageStatusView.ActiveCoverageView> activeCoverages) {
    public record ActiveCoverageView(BenefitType benefitType, BigDecimal sumAssuredAmount, String sumAssuredCurrency) {}
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.Instant;

public record SurrenderQuoteView(String policyNumber, BigDecimal quotedValueAmount, String quotedValueCurrency, Instant quotedAt) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String policyNumber) {
        super("No policy found for policyNumber " + policyNumber);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 422 Unprocessable Entity -- exactly-one-of(partyId, freeformDesignee) or
 * shares-sum-to-100 violated (Global Constraints: hand-written, not schema-generated). */
public class BeneficiaryValidationException extends RuntimeException {
    public BeneficiaryValidationException(String message) {
        super(message);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 409 Conflict -- Module-Architecture-B1's reserve step found insufficient
 * available loan value (Task 3). */
public class InsufficientLoanValueException extends RuntimeException {
    public InsufficientLoanValueException(String message) {
        super(message);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(UUID reservationId) {
        super("No loan value reservation found for id " + reservationId);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PolicyApi {

    record BeneficiaryInput(BeneficiaryType type, UUID partyId, String freeformDesignee, BigDecimal sharePercent, boolean revocable) {}

    record IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                         BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId,
                         String premiumFrequency, List<BeneficiaryInput> beneficiaries, String reasonForManualIssue) {}

    record EndorsementInput(String endorsementType, LocalDate effectiveDate, Map<String, Object> changes) {}

    PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy);
    PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy);
    void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy);
    SurrenderQuoteView quoteSurrenderValue(String policyNumber);
    PolicyView getPolicy(String policyNumber);
    Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Pageable pageable);
    CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf);
    boolean isPolicyInForce(String policyNumber, LocalDate asOf);

    UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl);
    void confirmReservation(UUID reservationId);
    void releaseReservation(UUID reservationId);

    void suspendPolicy(String policyNumber, String reason, String suspendedBy);
    void resumeSuspendedPolicy(String policyNumber, String resumedBy);
    void lapsePolicy(String policyNumber, String lapsedBy);
    void reinstatePolicy(String policyNumber, String reinstatedBy);
}
```

- [ ] **Step 5: Write `PolicyApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.*;
import tz.co.nlolo.lifeplatform.policy.infrastructure.*;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyApiImpl implements PolicyApi {

    private static final Logger log = LoggerFactory.getLogger(PolicyApiImpl.class);

    private final PolicyRepository policyRepository;
    private final PolicyAccountRepository policyAccountRepository;
    private final EndorsementRepository endorsementRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final CoverageRepository coverageRepository;
    private final LoanValueReservationRepository loanValueReservationRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PolicyApiImpl(PolicyRepository policyRepository, PolicyAccountRepository policyAccountRepository,
                          EndorsementRepository endorsementRepository, BeneficiaryRepository beneficiaryRepository,
                          CoverageRepository coverageRepository, LoanValueReservationRepository loanValueReservationRepository,
                          PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi,
                          ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.policyAccountRepository = policyAccountRepository;
        this.endorsementRepository = endorsementRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.coverageRepository = coverageRepository;
        this.loanValueReservationRepository = loanValueReservationRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy) {
        UUID tenantId = TenantContext.get();
        partyApi.getParty(request.policyholderPartyId()); // existence check -- PartyNotFoundException propagates as-is
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(request.productId(), LocalDate.now());

        // Placeholder generation scheme (flagged): policy.policy's own column comment describes
        // a "tenant/product/year/sequence, human-meaningful for USSD/call-center lookup"
        // business key -- no sequence generator or product-code lookup is wired here. This is
        // pattern-valid (^[A-Z0-9-]{6,20}$) and unique enough for M3; a later milestone can
        // replace the generation strategy without changing this method's signature.
        String policyNumber = "POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<Beneficiary> beneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, request.beneficiaries());

        Policy policy = new Policy(policyNumber, tenantId, request.policyholderPartyId(), request.productId(), request.productVersionId(),
            snapshot.category().name(), request.agentOfRecordId(), request.sumAssuredAmount(), request.sumAssuredCurrency(), issuedBy);
        policy.activate(LocalDate.now());
        policyRepository.save(policy);

        policyAccountRepository.save(new PolicyAccount(policyNumber, tenantId, BigDecimal.ZERO, request.sumAssuredCurrency()));

        // Only a DEATH coverage row is created at issuance -- ProductApi does not expose the
        // full benefit schedule list back to callers (publishVersion accepts one at authoring
        // time, but no getter returns it), so a Coverage row per BenefitScheduleEntry isn't
        // buildable without a further ProductApi change this plan does not make. Flagged.
        coverageRepository.save(new Coverage(tenantId, policyNumber, BenefitType.DEATH.name(), request.sumAssuredAmount(), request.sumAssuredCurrency()));

        beneficiaryRepository.saveAll(beneficiaries);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", request.policyholderPartyId());
        payload.put("productId", request.productId());
        payload.put("productVersionId", request.productVersionId());
        payload.put("sumAssured", Map.of("amount", request.sumAssuredAmount().toPlainString(), "currencyCode", request.sumAssuredCurrency()));
        payload.put("issueDate", policy.getIssueDate().toString());
        payload.put("agentOfRecordId", request.agentOfRecordId()); // nullable -- see Global Constraints
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload));

        return toView(policy);
    }

    @Override
    @Transactional
    public PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        if (!policy.isInForce()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be in force to apply an endorsement (current: " + policy.getStatus() + ")");
        }
        Endorsement endorsement = new Endorsement(tenantId, policyNumber, request.endorsementType(), request.effectiveDate(), request.changes(), appliedBy);
        endorsementRepository.save(endorsement);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyEndorsed", tenantId,
            Map.of("policyNumber", policyNumber, "endorsementType", request.endorsementType(), "effectiveDate", request.effectiveDate().toString())));
        return toView(policy);
    }

    @Override
    @Transactional
    public void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        List<Beneficiary> newBeneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, beneficiaries);

        List<Beneficiary> existing = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policyNumber);
        existing.forEach(Beneficiary::deactivate);
        beneficiaryRepository.saveAll(existing);
        beneficiaryRepository.saveAll(newBeneficiaries);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.BeneficiaryChanged", tenantId,
            Map.of("policyNumber", policyNumber, "changedAt", Instant.now().toString())));
    }

    @Override
    public SurrenderQuoteView quoteSurrenderValue(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.findById(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(policy.getProductId(), LocalDate.now());

        BigDecimal chargePercent = resolveSurrenderChargePercent(snapshot.surrenderChargeScheduleJson(), policy.getIssueDate());
        BigDecimal charge = account.getCashValueAmount().multiply(chargePercent).divide(new BigDecimal("100"));
        BigDecimal quotedValue = account.getCashValueAmount().subtract(charge);
        Instant quotedAt = Instant.now();

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.SurrenderValueCalculated", tenantId,
            Map.of("policyNumber", policyNumber,
                   "quotedValue", Map.of("amount", quotedValue.toPlainString(), "currencyCode", account.getCashValueCurrency()),
                   "quotedAt", quotedAt.toString())));

        return new SurrenderQuoteView(policyNumber, quotedValue, account.getCashValueCurrency(), quotedAt);
    }

    /**
     * Duration-band -> charge% shape (Global Constraints -- a plan-level decision pending
     * Actuarial confirmation, not a confirmed contractual schedule): a JSON array of
     * {"minMonths": int, "maxMonths": int-or-absent, "chargePercent": number} objects,
     * minMonths inclusive, maxMonths exclusive (absent/null = unbounded). A missing, blank, or
     * unparseable schedule means ZERO charge -- this must never throw out to the caller.
     */
    private BigDecimal resolveSurrenderChargePercent(String scheduleJson, LocalDate issueDate) {
        if (scheduleJson == null || scheduleJson.isBlank() || issueDate == null) {
            return BigDecimal.ZERO;
        }
        try {
            long monthsInForce = Period.between(issueDate, LocalDate.now()).toTotalMonths();
            JsonNode bands = objectMapper.readTree(scheduleJson);
            for (JsonNode band : bands) {
                long minMonths = band.path("minMonths").asLong(0);
                long maxMonths = band.hasNonNull("maxMonths") ? band.path("maxMonths").asLong() : Long.MAX_VALUE;
                if (monthsInForce >= minMonths && monthsInForce < maxMonths) {
                    return new BigDecimal(band.path("chargePercent").asText("0"));
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("Unparseable surrender_charge_schedule for a policy issued {} -- treating as zero charge", issueDate, e);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public PolicyView getPolicy(String policyNumber) {
        return toView(findPolicyOrThrow(policyNumber, TenantContext.get()));
    }

    @Override
    public Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Policy> page;
        if (policyholderPartyId != null && status != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyIdAndStatus(tenantId, policyholderPartyId, status.name(), pageable);
        } else if (policyholderPartyId != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyId(tenantId, policyholderPartyId, pageable);
        } else if (status != null) {
            page = policyRepository.findByTenantIdAndStatus(tenantId, status.name(), pageable);
        } else {
            page = policyRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toView);
    }

    @Override
    public CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        List<CoverageStatusView.ActiveCoverageView> coverages = coverageRepository.findByPolicyNumberAndActiveTrue(policyNumber).stream()
            .filter(c -> !"SURRENDER".equals(c.getBenefitType())) // openapi-policy.yaml's CoverageStatusView enum excludes SURRENDER
            .map(c -> new CoverageStatusView.ActiveCoverageView(BenefitType.valueOf(c.getBenefitType()), c.getSumAssuredAmount(), c.getSumAssuredCurrency()))
            .toList();
        return new CoverageStatusView(policyNumber, effectiveAsOf, coverages);
    }

    @Override
    public boolean isPolicyInForce(String policyNumber, LocalDate asOf) {
        // asOf is accepted (matches the OpenAPI query param and Po3's signature) but not
        // otherwise consulted -- this is a pure "is this policy currently ACTIVE-or-REINSTATED"
        // status read, not a date-bounded coverage-window computation (that's
        // getCoverageStatus's job, which separately filters `active` Coverage rows). Flagged.
        return findPolicyOrThrow(policyNumber, TenantContext.get()).isInForce();
    }

    @Override
    @Transactional
    public UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));

        BigDecimal currentlyReserved = loanValueReservationRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "RESERVED")
            .stream().map(LoanValueReservation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = account.availableLoanValue(currentlyReserved);
        if (amount.compareTo(available) > 0) {
            throw new InsufficientLoanValueException(
                "Requested " + amount + " " + currency + " exceeds available loan value " + available + " for policy " + policyNumber);
        }

        LoanValueReservation reservation = new LoanValueReservation(tenantId, policyNumber, amount, currency, Instant.now().plus(ttl));
        loanValueReservationRepository.save(reservation);
        return reservation.getReservationId();
    }

    @Override
    @Transactional
    public void confirmReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        LoanValueReservation reservation = loanValueReservationRepository.findByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!"RESERVED".equals(reservation.getStatus())) {
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is " + reservation.getStatus() + ", not RESERVED -- cannot confirm");
        }
        // Encumbrance updated synchronously here, not via async LoanOriginated consumption --
        // see Global Constraints.
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(reservation.getPolicyNumber())
            .orElseThrow(() -> new PolicyNotFoundException(reservation.getPolicyNumber()));
        account.increaseEncumbrance(reservation.getAmount());
        policyAccountRepository.save(account);

        reservation.confirm();
        loanValueReservationRepository.save(reservation);
    }

    @Override
    @Transactional
    public void releaseReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        LoanValueReservation reservation = loanValueReservationRepository.findByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if ("CONFIRMED".equals(reservation.getStatus())) {
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is already CONFIRMED -- cannot release a confirmed reservation");
        }
        if ("RESERVED".equals(reservation.getStatus())) {
            reservation.release();
            loanValueReservationRepository.save(reservation);
        }
        // Already RELEASED or EXPIRED -- idempotent no-op, so a caller retrying after a network
        // timeout on a first, actually-successful release doesn't get a spurious error.
    }

    @Override
    @Transactional
    public void suspendPolicy(String policyNumber, String reason, String suspendedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Deliverable 3 Rev 2 §3's flagged, unresolved "which product categories support
        // SUSPENDED" item, resolved here as a configurable refdata code set (Task 6's
        // db-migrations/refdata/V2) rather than a hardcoded category list.
        List<ReferenceCodeView> eligibleCategories = referenceDataApi.getCodes("POLICY_SUSPENSION_ELIGIBLE_CATEGORIES");
        boolean eligible = eligibleCategories.stream().anyMatch(c -> c.code().equals(policy.getProductCategory()));
        if (!eligible) {
            throw new InvalidPolicyStateException("Product category " + policy.getProductCategory() + " is not eligible for SUSPENDED status");
        }
        policy.suspend(reason);
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicySuspended", tenantId,
            Map.of("policyNumber", policyNumber, "suspendedAt", policy.getSuspendedAt().toString(), "reason", reason)));
    }

    @Override
    @Transactional
    public void resumeSuspendedPolicy(String policyNumber, String resumedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.resume();
        policyRepository.save(policy);
        // No dedicated "PolicyResumed" event exists in the event catalog -- docs/05-event-
        // catalog.md only says billing "resumes on the reverse transition" in prose, naming no
        // event. Nothing published here; billing's M4 consumption is out of scope regardless.
    }

    @Override
    @Transactional
    public void lapsePolicy(String policyNumber, String lapsedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.lapse();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyLapsed", tenantId,
            Map.of("policyNumber", policyNumber, "lapsedAt", policy.getLapsedAt().toString())));
    }

    @Override
    @Transactional
    public void reinstatePolicy(String policyNumber, String reinstatedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        int windowMonths = Integer.parseInt(referenceDataApi.getValue("TZ_REINSTATEMENT_WINDOW_MONTHS", "TZ"));
        long monthsSinceLapse = Period.between(policy.getLapsedAt().atZone(ZoneOffset.UTC).toLocalDate(), LocalDate.now()).toTotalMonths();
        if (monthsSinceLapse > windowMonths) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " lapsed " + monthsSinceLapse
                + " months ago, exceeding the " + windowMonths + "-month reinstatement window (TZ_REINSTATEMENT_WINDOW_MONTHS, a PLACEHOLDER pending B1 sign-off)");
        }
        policy.reinstate();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyReinstated", tenantId,
            Map.of("policyNumber", policyNumber, "reinstatedAt", Instant.now().toString())));
    }

    private List<Beneficiary> validateAndBuildBeneficiaries(UUID tenantId, String policyNumber, List<BeneficiaryInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        BigDecimal totalShare = BigDecimal.ZERO;
        List<Beneficiary> built = new ArrayList<>();
        for (BeneficiaryInput input : inputs) {
            boolean hasParty = input.partyId() != null;
            boolean hasFreeform = input.freeformDesignee() != null && !input.freeformDesignee().isBlank();
            if (hasParty == hasFreeform) { // both true or both false -- neither is valid
                throw new BeneficiaryValidationException("Each beneficiary must have exactly one of partyId or freeformDesignee, not both or neither");
            }
            if (input.type() == BeneficiaryType.PARTY && !hasParty) {
                throw new BeneficiaryValidationException("Beneficiary type PARTY requires partyId");
            }
            if (input.type() == BeneficiaryType.FREEFORM && !hasFreeform) {
                throw new BeneficiaryValidationException("Beneficiary type FREEFORM requires freeformDesignee");
            }
            totalShare = totalShare.add(input.sharePercent());
            built.add(new Beneficiary(tenantId, policyNumber, input.type().name(), input.partyId(), input.freeformDesignee(),
                input.sharePercent(), input.revocable()));
        }
        if (totalShare.compareTo(new BigDecimal("100")) != 0) {
            throw new BeneficiaryValidationException("Beneficiary shares must sum to 100, got " + totalShare);
        }
        return built;
    }

    private Policy findPolicyOrThrow(String policyNumber, UUID tenantId) {
        return policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    private PolicyView toView(Policy policy) {
        PolicyAccount account = policyAccountRepository.findById(policy.getPolicyNumber()).orElse(null);
        List<BeneficiaryView> beneficiaryViews = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policy.getPolicyNumber()).stream()
            .map(b -> new BeneficiaryView(b.getBeneficiaryId(), BeneficiaryType.valueOf(b.getBeneficiaryType()), b.getPartyId(),
                b.getFreeformDesignee(), b.getSharePercent(), b.isRevocable()))
            .toList();
        return new PolicyView(policy.getPolicyNumber(), policy.getPolicyholderPartyId(), policy.getProductId(), policy.getProductVersionId(),
            policy.getAgentOfRecordId(), PolicyStatus.valueOf(policy.getStatus()), policy.getIssueDate(),
            policy.getSumAssuredAmount(), policy.getSumAssuredCurrency(),
            account != null ? account.getCashValueAmount() : BigDecimal.ZERO,
            account != null ? account.getCashValueCurrency() : policy.getSumAssuredCurrency(),
            beneficiaryViews);
    }
}
```

- [ ] **Step 6: Write `UnderwritingDecisionEventListener.java`**

```java
package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auto-issuance trigger -- openapi-policy.yaml's own description: "normal issuance is
 * system-triggered by consuming UnderwritingDecisionMade internally." AFTER_COMMIT, mirroring
 * audit.DomainEventAuditListener's established pattern: underwriting's decision must actually
 * be durable before policy acts on it.
 *
 * The event payload alone (caseId, outcome, loadingPercent, decidedAt) is NOT enough to issue a
 * policy -- it carries none of applicantPartyId/productId/productVersionId/sumAssured. This
 * listener calls UnderwritingApi.getCase(caseId) synchronously to pull the full decided case
 * (made possible by this task's UnderwritingCaseView extension), exercising the
 * policy -> underwriting allowedDependencies edge the design docs provision but never spell out
 * a concrete use for.
 */
@Component
public class UnderwritingDecisionEventListener {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingDecisionEventListener.class);

    private final UnderwritingApi underwritingApi;
    private final PolicyApi policyApi;

    public UnderwritingDecisionEventListener(UnderwritingApi underwritingApi, PolicyApi policyApi) {
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"underwriting.UnderwritingDecisionMade".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        Object outcome = payload.get("outcome");
        // Per docs/03-aggregate-design.md: ACCEPT and LOADED (rated-up-but-accepted) both
        // result in issuance; DECLINED/POSTPONED never do.
        if (!DecisionOutcome.ACCEPT.name().equals(outcome) && !"LOADED".equals(outcome)) {
            return;
        }
        UUID caseId = (UUID) payload.get("caseId");
        TenantContext.set(envelope.tenantId());
        try {
            UnderwritingCaseView decidedCase = underwritingApi.getCase(caseId);
            // agentOfRecordId/premiumFrequency/beneficiaries aren't part of an UnderwritingCase
            // at all -- no agent-of-record or premium-frequency field exists on that aggregate,
            // and beneficiary designation happens post-issuance via PUT .../beneficiaries. This
            // defaults them for the automatic path; POST /policies/manual-issue lets staff set
            // all three explicitly for the exception path.
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
                decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                null, "MONTHLY", List.of(), "Automatic issuance on underwriting decision " + outcome);
            policyApi.issuePolicy(caseId, request, "system:underwriting-decision-listener");
        } catch (Exception e) {
            // AFTER_COMMIT -- underwriting's own transaction already committed; there is
            // nothing left to roll back here. audit.DomainEventAuditListener has already
            // durably recorded the raw UnderwritingDecisionMade event regardless of whether
            // this listener succeeds, so the decision itself is never lost -- only automatic
            // issuance needs a manual retry (via /policies/manual-issue) if this path fails. No
            // dead-letter queue is built for this listener specifically in M3.
            log.error("Automatic policy issuance failed for underwriting case {}", caseId, e);
        } finally {
            TenantContext.clear();
        }
    }
}
```

- [ ] **Step 7: Write `PolicyApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyApi policyApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Policy Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            "+25571300" + Math.abs(productCode.hashCode() % 10000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Policy Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, List<PolicyApi.BeneficiaryInput> beneficiaries) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "MONTHLY", beneficiaries, "Direct issuance test");
        return policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
    }

    @Test
    void issuePolicyActivatesImmediatelyAndPublishesPolicyIssued() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ISSUE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        PolicyView view = policyApi.getPolicy(policyNumber);
        assertEquals(PolicyStatus.ACTIVE, view.status());
        assertEquals(fixture.applicantId(), view.policyholderPartyId());
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void endToEndAutoIssuanceFiresFromARealUnderwritingDecision() {
        // Proves the real producer -> consumer path: underwritingApi.submitAssessment publishes
        // underwriting.UnderwritingDecisionMade for real, and
        // policy.application.UnderwritingDecisionEventListener consumes it and issues a policy
        // -- not a fabricated event injected directly into the publisher.
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-AUTO-01");
        UnderwritingCaseView opened = underwritingApi.openCase(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", "agent1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TenantContext.set(tenantId);
            var results = policyApi.searchPolicies(fixture.applicantId(), null, PageRequest.of(0, 10));
            assertThat(results.getContent()).hasSize(1);
            assertThat(results.getContent().get(0).status()).isEqualTo(PolicyStatus.ACTIVE);
        });
    }

    @Test
    void replaceBeneficiariesRejectsBothPartyIdAndFreeformDesignee() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(BeneficiaryValidationException.class, () -> policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.PARTY, fixture.applicantId(), "estate", new BigDecimal("100"), true)),
            "test-agent"));
    }

    @Test
    void replaceBeneficiariesRejectsSharesNotSummingTo100() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-02");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(BeneficiaryValidationException.class, () -> policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.FREEFORM, null, "estate", new BigDecimal("60"), true)),
            "test-agent"));
    }

    @Test
    void replaceBeneficiariesAcceptsAValidExactlyOneOfSetSummingTo100() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-BENE-03");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        policyApi.replaceBeneficiaries(policyNumber,
            List.of(new PolicyApi.BeneficiaryInput(BeneficiaryType.PARTY, fixture.applicantId(), null, new BigDecimal("60"), true),
                    new PolicyApi.BeneficiaryInput(BeneficiaryType.FREEFORM, null, "estate", new BigDecimal("40"), true)),
            "test-agent");
        PolicyView view = policyApi.getPolicy(policyNumber);
        assertEquals(2, view.beneficiaries().size());
    }

    @Test
    void suspendRejectsAProductCategoryNotOnTheEligibleList() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SUSP-01"); // TERM_LIFE -- not on POLICY_SUSPENSION_ELIGIBLE_CATEGORIES
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        assertThrows(InvalidPolicyStateException.class, () -> policyApi.suspendPolicy(policyNumber, "admin hold", "test-staff"));
    }

    @Test
    void suspendAndResumeRoundTripForAnEligibleCategory() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Group Scheme Member", LocalDate.of(1990, 1, 1), "+255713099001", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("POLICY-SUSP-02", "Group Life Product", ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        Fixture fixture = new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.suspendPolicy(policyNumber, "SACCO group non-payment", "test-staff");
        assertEquals(PolicyStatus.SUSPENDED, policyApi.getPolicy(policyNumber).status());
        assertFalse(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));

        policyApi.resumeSuspendedPolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.ACTIVE, policyApi.getPolicy(policyNumber).status());
    }

    @Test
    void lapseAndReinstateWithinTheWindowSucceeds() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-LAPSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.LAPSED, policyApi.getPolicy(policyNumber).status());

        // TZ_REINSTATEMENT_WINDOW_MONTHS is seeded 12 (PLACEHOLDER) -- lapsedAt is "now," so
        // reinstatement must succeed immediately.
        policyApi.reinstatePolicy(policyNumber, "test-staff");
        assertEquals(PolicyStatus.REINSTATED, policyApi.getPolicy(policyNumber).status());
        assertTrue(policyApi.isPolicyInForce(policyNumber, LocalDate.now()));
    }

    @Test
    void endorsementIsRejectedWhenPolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-ENDORSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");

        assertThrows(InvalidPolicyStateException.class, () -> policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput("SUM_ASSURED_CHANGE", LocalDate.now(), java.util.Map.of("newSumAssured", "2000000")), "test-agent"));
    }

    @Test
    void quoteSurrenderValueDoesNotCrashWhenNoScheduleIsConfigured() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "POLICY-SURR-01");
        String policyNumber = issueDirectly(tenantId, fixture, List.of());
        TenantContext.set(tenantId);
        SurrenderQuoteView quote = policyApi.quoteSurrenderValue(policyNumber);
        assertEquals(0, BigDecimal.ZERO.compareTo(quote.quotedValueAmount())); // zero cash value, zero charge -- zero quote, no exception
    }
}
```

Note: this test file uses `org.awaitility.Awaitility` for the one genuinely-async assertion (`AFTER_COMMIT` event listener timing) — **`awaitility` is not currently a declared dependency; confirm before running this step.** If absent, replace the one `Awaitility.await()...` block with a short bounded retry loop (`for` + `Thread.sleep(100)` up to ~5s) instead of adding a new Maven dependency, per Global Constraints' "no new dependencies" rule — the assertion content is unchanged either way.

- [ ] **Step 8: Compile and run**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
./mvnw -B -q test -Dtest=PolicyApiIntegrationTest
./mvnw -B -q test -Dtest=UnderwritingApiIntegrationTest # unchanged behavior, confirm no regression from the ApplicationEventPublisher constructor param
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/underwriting src/main/java/tz/co/nlolo/lifeplatform/policy/api \
  src/main/java/tz/co/nlolo/lifeplatform/policy/application src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java \
  src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "feat: policy issuance, lifecycle state machine, and underwriting auto-issuance trigger"
```

---

### Task 3: Module-Architecture-B1 — the TTL sweep, and a genuine concurrency proof

**Files:**
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (add the opportunistic sweep call to `reserveLoanValue`)
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policy/ModuleArchitectureB1ConcurrencyTest.java`

**Interfaces:**
- Consumes: `LoanValueReservationRepository.expireStaleReservations` (Task 1), `PolicyApi.reserveLoanValue`/`InsufficientLoanValueException` (Task 2).
- Produces: nothing new — this task hardens an existing method and proves it. Task 6/8's `PolicyLoanApiImpl` and its end-to-end race test consume `reserveLoanValue`/`confirmReservation`/`releaseReservation` exactly as Task 2 already defined them; this task changes no signature.

- [ ] **Step 1: Add the opportunistic sweep call to `reserveLoanValue`**

Read the current `reserveLoanValue` method in `PolicyApiImpl.java` (Task 2) first, then insert one line as the very first statement in the method body, immediately after `UUID tenantId = TenantContext.get();` and before `findPolicyOrThrow(policyNumber, tenantId);`:

```java
    @Override
    @Transactional
    public UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl) {
        UUID tenantId = TenantContext.get();
        // Opportunistic TTL sweep (Global Constraints -- a true cross-tenant @Scheduled sweep
        // is architecturally incompatible with this platform's fail-closed RLS design, since a
        // background thread with no TenantContext sees zero rows on every RLS-protected table
        // and there is no tenant-directory table to iterate). Runs inside the CALLER's own
        // TenantContext, so it is RLS-safe and expires only this tenant's stale RESERVED rows
        // for this policy -- self-healing the crash case (reserved, then crashed before
        // confirm/release) on the next real access instead of on a fixed wall-clock timer.
        loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, Instant.now());
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));

        BigDecimal currentlyReserved = loanValueReservationRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "RESERVED")
            .stream().map(LoanValueReservation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = account.availableLoanValue(currentlyReserved);
        if (amount.compareTo(available) > 0) {
            throw new InsufficientLoanValueException(
                "Requested " + amount + " " + currency + " exceeds available loan value " + available + " for policy " + policyNumber);
        }

        LoanValueReservation reservation = new LoanValueReservation(tenantId, policyNumber, amount, currency, Instant.now().plus(ttl));
        loanValueReservationRepository.save(reservation);
        return reservation.getReservationId();
    }
```

- [ ] **Step 2: Write `ModuleArchitectureB1ConcurrencyTest.java`**

```java
package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-Architecture-B1 (docs/02-module-architecture.md §3.4/§3.5) -- the loan-origination
 * race condition, NOT the Aggregate-Design-doc's unrelated "B1" (TZ_REINSTATEMENT_WINDOW_MONTHS
 * -- see this plan's header disambiguation). Proves the specific race the reserve/confirm/
 * release protocol closes: two concurrent reserveLoanValue calls against the SAME policy, each
 * individually affordable but jointly exceeding available cash value, must never both succeed.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ModuleArchitectureB1ConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** issuePolicy starts cash value at zero (Task 2 -- real cash value only accrues via
     * premium payment, billing, M4, out of scope). This test needs a real available balance to
     * race against, so it seeds policy_account.cash_value_amount directly via JDBC right after
     * issuance -- a test-only shortcut, not a new production code path. */
    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue) throws SQLException {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Concurrency Test Applicant", LocalDate.of(1990, 1, 1), "+255713099999", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("B1-RACE-" + UUID.randomUUID().toString().substring(0, 6), "Race Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", null, "MONTHLY", List.of(), "Concurrency test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            statement.executeUpdate();
        }
        return policyNumber;
    }

    @Test
    void concurrentReservationsAgainstTheSamePolicyNeverJointlyOverdraw() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000");
        String policyNumber = issuePolicyWithCashValue(tenantId, cashValue);
        BigDecimal eachRequest = new BigDecimal("700000"); // two of these (1,400,000) exceed cashValue; one alone (700,000) fits

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<UUID>> tasks = List.of(
            () -> attemptReservation(tenantId, policyNumber, eachRequest, barrier),
            () -> attemptReservation(tenantId, policyNumber, eachRequest, barrier));
        List<Future<UUID>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        int successCount = 0;
        int failureCount = 0;
        for (Future<UUID> future : futures) {
            try {
                if (future.get() != null) successCount++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof InsufficientLoanValueException) {
                    failureCount++;
                } else {
                    throw e;
                }
            }
        }
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        // Confirms the DB row itself, not just the Java-level return values: exactly one
        // RESERVED reservation exists, summing to eachRequest -- the race genuinely never let
        // both writes land.
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM policy.loan_value_reservation WHERE policy_number = ? AND status = 'RESERVED'")) {
            statement.setString(1, policyNumber);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
                assertThat(resultSet.getBigDecimal(2)).isEqualByComparingTo(eachRequest);
            }
        }
    }

    /** TenantContext is a ThreadLocal -- MUST be set inside the task running on the
     * ExecutorService's own worker thread, not only on the test's main thread (Global
     * Constraints) -- otherwise every reserveLoanValue call inside the Callable would throw
     * TenantContext's fail-loud IllegalStateException instead of racing at all. */
    private UUID attemptReservation(UUID tenantId, String policyNumber, BigDecimal amount, CyclicBarrier barrier) throws Exception {
        TenantContext.set(tenantId);
        try {
            barrier.await(5, TimeUnit.SECONDS); // maximizes the actual race window
            return policyApi.reserveLoanValue(policyNumber, amount, "TZS", Duration.ofMinutes(15));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void expiredReservationSelfHealsOnNextReserveLoanValueCall() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);

        // A reservation with a TTL already in the past -- simulates the crash case
        // (originateLoan reserved, then crashed before confirm/release ran).
        UUID staleReservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("900000"), "TZS", Duration.ofMillis(1));
        Thread.sleep(50);

        // Without Step 1's sweep, this would still see 900,000 "RESERVED" and reject a fresh
        // 900,000 request as exceeding the 1,000,000 available. The opportunistic sweep at the
        // top of reserveLoanValue expires the stale row first, making room -- proving the
        // "crash case self-heals on next access" requirement without a cross-tenant
        // @Scheduled job (Global Constraints).
        UUID newReservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("900000"), "TZS", Duration.ofMinutes(15));
        assertThat(newReservationId).isNotEqualTo(staleReservationId);
    }
}
```

- [ ] **Step 3: Compile and run**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
./mvnw -B -q test -Dtest=ModuleArchitectureB1ConcurrencyTest
```

Expected: `BUILD SUCCESS`, both tests pass, every run — this is a genuine race, not a timing-dependent flaky assertion: the `PESSIMISTIC_WRITE` lock on `policy_account` (Task 1) serializes the two threads' read-then-write sequences deterministically regardless of which acquires the lock first, so exactly one always wins and exactly one always loses, run after run.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java \
  src/test/java/tz/co/nlolo/lifeplatform/policy/ModuleArchitectureB1ConcurrencyTest.java
git commit -m "feat: Module-Architecture-B1 TTL sweep and concurrency proof"
```

---

### Task 4: `policy` REST layer, including the two 501 endpoints

**Files:**
- Modify: `api/openapi/openapi-policy.yaml` (merge `ManualIssueRequest`'s duplicate `required` arrays)
- Modify: `src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java` and `.../product/application/ProductApiImpl.java` (add `getSnapshotByVersionId`, needed by manual issuance — `ManualIssueRequest` supplies `productVersionId` only, never a bare `productId`)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/{PolicyController,PolicyExceptionHandler,MoneyDto,BeneficiaryInputDto,ManualIssueRequestDto,EndorsementRequestDto,PolicyResponseDto,CoverageStatusResponseDto,PolicySearchResponse}.java`

**Interfaces:**
- Consumes: `PolicyApi` (Tasks 2–3), `ProductApi.getSnapshotByVersionId` (this task), `ROLE_REALM_*`/`ROLE_UNDERWRITER`-style authorities (M1's `SecurityConfig`), `jwt.getClaimAsString("party_id")` (M1's established object-level idiom).
- Produces: the 10 HTTP endpoints `api/openapi/openapi-policy.yaml` declares — Task 5's contract tests and Task 6's `PolicyLoanApiImpl` (which calls `PolicyApi` directly, never through HTTP) both build on this.

**Important wire-shape note, read before writing any DTO below:** `openapi-policy.yaml`'s `PolicyView` schema nests `sumAssured`/`cashValue` as `Money` **objects** (`{amount, currencyCode}`), and its `CoverageStatusView` schema nests each `activeCoverages[].sumAssured` the same way — but `policy.api.PolicyView`/`CoverageStatusView` (Task 2) are **flattened** to `sumAssuredAmount`/`sumAssuredCurrency` pairs, matching every other module's internal-API-layer convention (Global Constraints: "no shared `Money` class — flattened amount/currency pairs"). Returning either service-layer record directly from a controller would fail contract validation (wrong JSON shape). This task adds a translation DTO for each (`PolicyResponseDto`, `CoverageStatusResponseDto`) that every controller method nests through — internal callers (`PolicyLoanApiImpl`, every test in this plan) use the flattened `policy.api` records directly and never see either DTO. `openapi-policyloan.yaml`'s `LoanView` has the identical nesting — Task 7 applies the same pattern there.

- [ ] **Step 1: Merge `ManualIssueRequest`'s duplicate `required` key**

In `api/openapi/openapi-policy.yaml`, find:

```yaml
    ManualIssueRequest:
      type: object
      required: [underwritingCaseId, policyholderPartyId, productVersionId, sumAssured, agentOfRecordId]
      properties:
        underwritingCaseId: { type: string, format: uuid }
        policyholderPartyId: { $ref: 'openapi-common.yaml#/components/schemas/PartyRef' }
        productVersionId: { type: string, format: uuid }
        sumAssured: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        agentOfRecordId: { type: string, format: uuid, nullable: true }
        premiumFrequency: { type: string, enum: [MONTHLY, QUARTERLY, ANNUALLY] }
        beneficiaries:
          type: array
          items: { $ref: '#/components/schemas/BeneficiaryInput' }
        reasonForManualIssue: { type: string, description: "Required — feeds audit." }
      required: [underwritingCaseId, policyholderPartyId, productVersionId, sumAssured, agentOfRecordId, reasonForManualIssue]
```

Replace with the single, correct `required` array (keep the properties block identical, just remove the first, superseded `required` line):

```yaml
    ManualIssueRequest:
      type: object
      properties:
        underwritingCaseId: { type: string, format: uuid }
        policyholderPartyId: { $ref: 'openapi-common.yaml#/components/schemas/PartyRef' }
        productVersionId: { type: string, format: uuid }
        sumAssured: { $ref: 'openapi-common.yaml#/components/schemas/Money' }
        agentOfRecordId: { type: string, format: uuid, nullable: true }
        premiumFrequency: { type: string, enum: [MONTHLY, QUARTERLY, ANNUALLY] }
        beneficiaries:
          type: array
          items: { $ref: '#/components/schemas/BeneficiaryInput' }
        reasonForManualIssue: { type: string, description: "Required — feeds audit." }
      required: [underwritingCaseId, policyholderPartyId, productVersionId, sumAssured, agentOfRecordId, reasonForManualIssue]
```

- [ ] **Step 2: Add `ProductApi.getSnapshotByVersionId`**

Read `product.api.ProductApi.java` first, then add this method to the interface:

```java
    /**
     * M3 addition: manual policy issuance (openapi-policy.yaml's ManualIssueRequest) supplies
     * only productVersionId, never a bare productId -- PolicyApi.IssueRequest needs both.
     * Internal-only, not part of openapi-product.yaml (same convention as
     * resolveRatingMultiplier).
     */
    ProductSnapshotView getSnapshotByVersionId(UUID productVersionId);
```

Read `product.application.ProductApiImpl.java` next, then add the implementation:

```java
    @Override
    public ProductSnapshotView getSnapshotByVersionId(UUID productVersionId) {
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
        ProductDefinition definition = productDefinitionRepository.findById(version.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(version.getProductId()));
        return new ProductSnapshotView(version.getProductId(), productVersionId, version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel()), version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson());
    }
```

- [ ] **Step 3: Write the request/response DTOs**

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record BeneficiaryInputDto(
    @NotNull BeneficiaryType type,
    UUID partyId,
    String freeformDesignee,
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal sharePercent,
    Boolean revocable) {

    public tz.co.nlolo.lifeplatform.policy.api.PolicyApi.BeneficiaryInput toApiInput() {
        return new tz.co.nlolo.lifeplatform.policy.api.PolicyApi.BeneficiaryInput(
            type, partyId, freeformDesignee, sharePercent, revocable == null || revocable);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ManualIssueRequestDto(
    @NotNull UUID underwritingCaseId,
    @NotNull UUID policyholderPartyId,
    @NotNull UUID productVersionId,
    @NotNull @Valid MoneyDto sumAssured,
    @NotNull UUID agentOfRecordId,
    String premiumFrequency,
    List<@Valid BeneficiaryInputDto> beneficiaries,
    @NotBlank String reasonForManualIssue) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Map;

public record EndorsementRequestDto(@NotBlank String endorsementType, @NotNull LocalDate effectiveDate, @NotNull Map<String, Object> changes) {}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** See this task's "Important wire-shape note" above. */
public record PolicyResponseDto(String policyNumber, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                                 UUID agentOfRecordId, PolicyStatus status, LocalDate issueDate,
                                 MoneyDto sumAssured, MoneyDto cashValue, List<BeneficiaryView> beneficiaries) {

    public static PolicyResponseDto from(PolicyView view) {
        return new PolicyResponseDto(view.policyNumber(), view.policyholderPartyId(), view.productId(), view.productVersionId(),
            view.agentOfRecordId(), view.status(), view.issueDate(),
            new MoneyDto(view.sumAssuredAmount().toPlainString(), view.sumAssuredCurrency()),
            new MoneyDto(view.cashValueAmount().toPlainString(), view.cashValueCurrency()),
            view.beneficiaries());
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.CoverageStatusView;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.time.LocalDate;
import java.util.List;

public record CoverageStatusResponseDto(String policyNumber, LocalDate asOf, List<CoverageStatusResponseDto.ActiveCoverageDto> activeCoverages) {
    public record ActiveCoverageDto(BenefitType benefitType, MoneyDto sumAssured) {}

    public static CoverageStatusResponseDto from(CoverageStatusView view) {
        return new CoverageStatusResponseDto(view.policyNumber(), view.asOf(),
            view.activeCoverages().stream()
                .map(c -> new ActiveCoverageDto(c.benefitType(), new MoneyDto(c.sumAssuredAmount().toPlainString(), c.sumAssuredCurrency())))
                .toList());
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import org.springframework.data.domain.Page;

import java.util.List;

public record PolicySearchResponse(List<PolicyResponseDto> items, PolicySearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static PolicySearchResponse from(Page<PolicyView> springPage) {
        return new PolicySearchResponse(
            springPage.getContent().stream().map(PolicyResponseDto::from).toList(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
```

- [ ] **Step 4: Write `PolicyController.java`**

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class PolicyController {

    private final PolicyApi policyApi;
    private final ProductApi productApi;

    public PolicyController(PolicyApi policyApi, ProductApi productApi) {
        this.policyApi = policyApi;
        this.productApi = productApi;
    }

    @PostMapping("/policies/manual-issue")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> manualIssue(@Valid @RequestBody ManualIssueRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(request.productVersionId());
        List<PolicyApi.BeneficiaryInput> beneficiaries = request.beneficiaries() != null
            ? request.beneficiaries().stream().map(BeneficiaryInputDto::toApiInput).toList() : List.of();
        PolicyApi.IssueRequest issueRequest = new PolicyApi.IssueRequest(request.policyholderPartyId(), snapshot.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(),
            request.agentOfRecordId(), request.premiumFrequency(), beneficiaries, request.reasonForManualIssue());
        PolicyView view = policyApi.issuePolicy(request.underwritingCaseId(), issueRequest, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponseDto.from(view));
    }

    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> getPolicy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        PolicyView view = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(view, jwt, authentication);
        return ResponseEntity.ok(PolicyResponseDto.from(view));
    }

    @GetMapping("/policies")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicySearchResponse> searchPolicies(
            @RequestParam(required = false) UUID policyholderPartyId,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<PolicyView> result = policyApi.searchPolicies(policyholderPartyId, status, PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(PolicySearchResponse.from(result));
    }

    @PostMapping("/policies/{policyNumber}/endorsements")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> applyEndorsement(@PathVariable String policyNumber, @Valid @RequestBody EndorsementRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        // Idempotency-Key accepted, not enforced (Global Constraints).
        PolicyView view = policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput(request.endorsementType(), request.effectiveDate(), request.changes()), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponseDto.from(view));
    }

    @PutMapping("/policies/{policyNumber}/beneficiaries")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Void> replaceBeneficiaries(@PathVariable String policyNumber,
            @RequestBody List<@Valid BeneficiaryInputDto> beneficiaries, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        policyApi.replaceBeneficiaries(policyNumber, beneficiaries.stream().map(BeneficiaryInputDto::toApiInput).toList(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/policies/{policyNumber}/surrender-value")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Map<String, Object>> getSurrenderValue(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        SurrenderQuoteView quote = policyApi.quoteSurrenderValue(policyNumber);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyNumber", quote.policyNumber());
        body.put("quotedValue", Map.of("amount", quote.quotedValueAmount().toPlainString(), "currencyCode", quote.quotedValueCurrency()));
        body.put("quotedAt", quote.quotedAt().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * DEFERRED CHOREOGRAPHY (plan header) -- Camunda 7 is EOL, Camunda 8 needs a paid licence
     * and a cross-tenant leak review against this platform's ThreadLocal TenantContext. Real,
     * routable, correctly-secured (matches openapi-policy.yaml's customersAuth/agentsAuth/
     * staffAuth triad exactly) endpoint that returns 501, not 404 and not omitted.
     */
    @PostMapping("/policies/{policyNumber}/surrender")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProblemDetail> surrenderPolicy(@PathVariable String policyNumber,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        return notImplementedChoreography();
    }

    /** Same deferred-choreography seam as surrenderPolicy above -- nothing to poll without a
     * process engine. */
    @GetMapping("/policies/{policyNumber}/processes/{processInstanceId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProblemDetail> getProcessStatus(@PathVariable String policyNumber, @PathVariable String processInstanceId) {
        return notImplementedChoreography();
    }

    @GetMapping("/policies/{policyNumber}/coverage-status")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<CoverageStatusResponseDto> getCoverageStatus(@PathVariable String policyNumber, @RequestParam(required = false) LocalDate asOf) {
        return ResponseEntity.ok(CoverageStatusResponseDto.from(policyApi.getCoverageStatus(policyNumber, asOf)));
    }

    @GetMapping("/policies/{policyNumber}/in-force")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Map<String, Boolean>> isInForce(@PathVariable String policyNumber, @RequestParam(required = false) LocalDate asOf) {
        return ResponseEntity.ok(Map.of("inForce", policyApi.isPolicyInForce(policyNumber, asOf)));
    }

    /**
     * Object-level authorization (Global Constraints) -- mirrors PartyController.getParty's
     * exact structure: realm-membership check via Authentication.getAuthorities(), then
     * jwt.getClaimAsString("party_id") vs. the resource's own party id, then a real 403
     * (AccessDeniedException), NOT a disguised 404 -- that anti-enumeration disguise is for
     * cross-TENANT mismatches only (already handled by findPolicyOrThrow/RLS), a different
     * scoping dimension from this same-tenant ownership check.
     */
    private void enforceCustomerOwnPolicyOnly(PolicyView view, Jwt jwt, Authentication authentication) {
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(view.policyholderPartyId().toString())) {
                throw new AccessDeniedException("Access denied: customer may only access their own policy");
            }
        }
    }

    private ResponseEntity<ProblemDetail> notImplementedChoreography() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED,
            "Surrender/maturity loan-netting choreography is deferred until a workflow engine is "
            + "chosen (Camunda 7 is EOL; Camunda 8 needs a paid licence and a cross-tenant leak "
            + "review against this platform's ThreadLocal TenantContext) -- this endpoint will be "
            + "implemented alongside that engine, per docs/08-implementation-roadmap.md's M3 framing.");
        problem.setProperty("errorCode", "CHOREOGRAPHY_NOT_IMPLEMENTED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
```

- [ ] **Step 5: Write `PolicyExceptionHandler.java`**

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryValidationException;
import tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- Spring resolves @ExceptionHandler methods by
 * first-matching-ADVICE-BEAN-wins, not merged-by-specificity across beans. Without this,
 * GlobalExceptionHandler's catch-all could shadow these domain-specific mappings depending on
 * classpath-scan order (the exact regression M1's final review found and fixed for
 * PartyExceptionHandler -- do not repeat it here).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PolicyExceptionHandler {

    @ExceptionHandler(PolicyNotFoundException.class)
    public ProblemDetail handleNotFound(PolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "POLICY_NOT_FOUND");
    }

    @ExceptionHandler(InvalidPolicyStateException.class)
    public ProblemDetail handleInvalidState(InvalidPolicyStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "INVALID_POLICY_STATE");
    }

    @ExceptionHandler(BeneficiaryValidationException.class)
    public ProblemDetail handleBeneficiaryValidation(BeneficiaryValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "BENEFICIARY_VALIDATION_FAILED");
    }

    @ExceptionHandler(InsufficientLoanValueException.class)
    public ProblemDetail handleInsufficientLoanValue(InsufficientLoanValueException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "INSUFFICIENT_LOAN_VALUE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 6: Compile**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add api/openapi/openapi-policy.yaml src/main/java/tz/co/nlolo/lifeplatform/product/api/ProductApi.java \
  src/main/java/tz/co/nlolo/lifeplatform/product/application/ProductApiImpl.java \
  src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure
git commit -m "feat: policy REST layer, including the 501 choreography stubs"
```

---

### Task 5: `policy` contract tests

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`

**Interfaces:**
- Consumes: the full `policy` REST surface (Task 4), `api/openapi/openapi-policy.yaml` (fixed, Task 4 Step 1).
- Produces: nothing new — this is the falsifiability gate for Tasks 2–4.

- [ ] **Step 1: Write `PolicyContractTest.java`**

```java
package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-policy.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql");
    }

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record IssuedPolicy(String policyNumber, UUID policyholderPartyId) {}

    private UUID registerApplicant(UUID tenantId, String phoneSuffix) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Policy Contract Applicant","dateOfBirth":"1988-03-15","contactInfo":{"phoneNumber":"+25571234%s"}}
                    """.formatted(phoneSuffix)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    private ProductFixture publishProduct(UUID tenantId, String code) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Policy Contract Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(code)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new ProductFixture(UUID.fromString(productId), UUID.fromString(JsonPath.read(snapshotResponse, "$.productVersionId")));
    }

    private UUID openUnderwritingCase(UUID tenantId, UUID applicantId, ProductFixture product) throws Exception {
        String response = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, product.productId(), product.productVersionId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.caseId"));
    }

    private IssuedPolicy manualIssue(UUID tenantId, String productCode) throws Exception {
        UUID applicantId = registerApplicant(tenantId, String.valueOf(Math.abs(productCode.hashCode() % 10000)));
        ProductFixture product = publishProduct(tenantId, productCode);
        UUID caseId = openUnderwritingCase(tenantId, applicantId, product);

        String response = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Contract test manual issuance"}
                    """.formatted(caseId, applicantId, product.productVersionId(), UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        return new IssuedPolicy(JsonPath.read(response, "$.policyNumber"), applicantId);
    }

    @Test
    void manualIssueRejectsNonStaffCaller() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyMatchesOpenApiContractForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-01");

        mockMvc.perform(get("/policies/" + issued.policyNumber())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.sumAssured.amount").value("1000000.00"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getPolicyRejectsCustomerReadingSomeoneElsesPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-02");

        mockMvc.perform(get("/policies/" + issued.policyNumber())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyForNonexistentPolicyReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/policies/NOSUCHPOLICY1")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    @Test
    void searchPoliciesMatchesOpenApiContractAndFindsTheSeededPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-03");

        mockMvc.perform(get("/policies")
                .queryParam("policyholderPartyId", issued.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + issued.policyNumber() + "')]").exists());
    }

    @Test
    void applyEndorsementMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-04");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/endorsements")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"endorsementType":"ADDRESS_CHANGE","effectiveDate":"2026-01-15","changes":{"newAddress":"Dar es Salaam"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void replaceBeneficiariesAcceptsAValidSetAndReturnsOk() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-05");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"FREEFORM","freeformDesignee":"estate","sharePercent":100,"revocable":true}]
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void replaceBeneficiariesRejectsBothPartyIdAndFreeformDesigneeWith422() throws Exception {
        // Hand-written contract test (Global Constraints -- decision 13): openapi-policy.yaml's
        // BeneficiaryInput schema only declares required:[type, sharePercent], so a
        // schema-generated test would never send (or reject) both fields populated at once.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-06");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"PARTY","partyId":"%s","freeformDesignee":"estate","sharePercent":100,"revocable":true}]
                    """.formatted(issued.policyholderPartyId())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_VALIDATION_FAILED"));
    }

    @Test
    void replaceBeneficiariesRejectsSharesNotSummingTo100With422() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-07");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"FREEFORM","freeformDesignee":"estate","sharePercent":60,"revocable":true}]
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_VALIDATION_FAILED"));
    }

    @Test
    void surrenderValueMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-08");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/surrender-value")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void surrenderPolicyReturns501WithChoreographyNotImplemented() throws Exception {
        // Decision 2: a real, routable, correctly-secured endpoint that returns 501, not 404
        // and not omitted -- this is the falsifiable proof of that decision, not prose.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-09");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/surrender")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.errorCode").value("CHOREOGRAPHY_NOT_IMPLEMENTED"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void getProcessStatusReturns501WithChoreographyNotImplemented() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-10");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/processes/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.errorCode").value("CHOREOGRAPHY_NOT_IMPLEMENTED"));
    }

    @Test
    void coverageStatusMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-11");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/coverage-status")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.activeCoverages[0].benefitType").value("DEATH"));
    }

    @Test
    void inForceMatchesOpenApiContractAndReturnsTrueForANewlyIssuedPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-12");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/in-force")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.inForce").value(true));
    }
}
```

- [ ] **Step 2: Compile and run**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q test -Dtest=PolicyContractTest
```

Expected: `BUILD SUCCESS`, all 15 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java
git commit -m "test: policy contract tests, including the 501 choreography-stub proofs"
```

---

### Task 6: `policyloan` migration, domain, `PolicyLoanApi`, and its implementation consuming reserve/confirm/release

**Files:**
- Modify: `db-migrations/policyloan/V1__create_policyloan_schema.sql` (RLS on the remaining 3 tenant-scoped tables; `app_role` grants; move the append-only `REVOKE` to after the grants)
- Create: `db-migrations/refdata/V2__seed_policy_loan_parameters.sql`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/domain/{PolicyLoan,LoanInterestTerm,LoanTransaction}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/infrastructure/{PolicyLoanRepository,LoanInterestTermRepository,LoanTransactionRepository}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/api/{LoanStatus,LoanView,LoanNotFoundException,LoanNotEligibleException,PolicyLoanApi}.java`
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/application/PolicyLoanApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policyloan/PolicyLoanApiIntegrationTest.java`
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java` (add a `policyloan`-schema round trip)
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java` (add a `policyloan.policy_loan` tenant-isolation proof)

**Interfaces:**
- Consumes: `PolicyApi.isPolicyInForce`/`reserveLoanValue`/`confirmReservation`/`releaseReservation` (Tasks 2–3 — the **only** `PolicyApi` methods this module calls, per `policyloan`'s `allowedDependencies`), `ReferenceDataApi.getValue` (M1, extended data via Task 6's own migration).
- Produces: `PolicyLoanApi` — Task 7's REST layer and Task 8's contract/race tests consume it. Exact signature:
  ```java
  package tz.co.nlolo.lifeplatform.policyloan.api;

  public interface PolicyLoanApi {
      LoanView originateLoan(String policyNumber, java.math.BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy);
      LoanView getLoan(java.util.UUID loanId);
      java.util.List<LoanView> listLoansForPolicy(String policyNumber);
      LoanView recordRepayment(java.util.UUID loanId, java.math.BigDecimal amount, String currency, String paymentReference, String recordedBy);

      // Internal-only test seams (not part of openapi-policyloan.yaml) standing in for
      // payment.DisbursementCompleted (M5) and a future billing-driven forced-lapse (M4).
      LoanView markDisbursed(java.util.UUID loanId);
      LoanView triggerForcedLapse(java.util.UUID loanId, String reason);
  }
  ```

- [ ] **Step 1: Extend `db-migrations/policyloan/V1__create_policyloan_schema.sql`**

Read the current file first (81 lines). Replace everything from `CREATE INDEX idx_loan_transaction_tenant ...` (currently line 76) through the end of the file with:

```sql
CREATE INDEX idx_loan_transaction_loan ON policyloan.loan_transaction (loan_id, occurred_at);
CREATE INDEX idx_loan_transaction_tenant ON policyloan.loan_transaction (tenant_id);

-- Defense-in-depth RLS (Global Constraints) -- policy_loan already had it; the remaining 3
-- tenant-scoped tables in this schema did not.
ALTER TABLE policyloan.policy_loan ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_loan_tenant_isolation ON policyloan.policy_loan
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.loan_interest_term ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_interest_term_tenant_isolation ON policyloan.loan_interest_term
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.repayment_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY repayment_schedule_tenant_isolation ON policyloan.repayment_schedule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- loan_transaction is PARTITIONED (PARTITION BY RANGE on occurred_at, two pre-created monthly
-- partitions). Postgres propagates ENABLE ROW LEVEL SECURITY and GRANTs on a partitioned
-- PARENT to every existing and future partition automatically -- applied once here, nothing
-- extra needed per-partition.
ALTER TABLE policyloan.loan_transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_transaction_tenant_isolation ON policyloan.loan_transaction
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- see product/V1's identical comment (Global Constraints). Applied
-- BEFORE the REVOKE below -- this ordering is load-bearing: a REVOKE followed by a LATER,
-- broader GRANT would silently re-grant what was just revoked, undoing the append-only
-- ledger guarantee the REVOKE exists to enforce.
GRANT USAGE ON SCHEMA policyloan TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policyloan TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA policyloan GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- Append-only ledger (Deliverable 3 Rev 2 §4) -- MUST come after the GRANT block immediately
-- above, not before it (see this step's own note). Moved here from its original position
-- right after the loan_transaction indexes, where it preceded any GRANT at all and was
-- therefore a no-op against a role with zero privileges in the first place.
REVOKE UPDATE, DELETE ON policyloan.loan_transaction FROM app_role;
```

`policyloan.repayment_schedule` gets RLS/grants here for completeness (every tenant-scoped table) but no JPA entity is created for it in this plan — nothing in M3's scope populates a fixed installment schedule (L2's own comment: "optional -- present only for products with fixed installment terms"; no product in this plan's test fixtures configures one). Flagged, not silent.

- [ ] **Step 2: Write `db-migrations/refdata/V2__seed_policy_loan_parameters.sql`**

```sql
-- V2: new refdata parameters M3 needs that have no source anywhere in Phase 0's design docs
-- or schema (brief §8/§9 item 9). V1 is already applied in every real deployment and must
-- never be edited directly -- this is a NEW file, following the exact pattern V1 established.

INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE', 'DEFAULT', 'Policy loan annual interest rate (percent)', '12.0', 'TZ'), -- PLACEHOLDER, pending Actuarial sign-off
    ('POLICY_SUSPENSION_ELIGIBLE_CATEGORIES', 'GROUP_LIFE', 'Product category eligible for SUSPENDED status (non-arrears hold)', 'true', 'TZ'), -- PLACEHOLDER, pending Product sign-off (Deliverable 3 Rev 2 Section 3 working assumption)
    ('POLICY_SUSPENSION_ELIGIBLE_CATEGORIES', 'EDUCATION_SAVINGS', 'Product category eligible for SUSPENDED status (non-arrears hold)', 'true', 'TZ'); -- PLACEHOLDER, same

COMMENT ON TABLE refdata.reference_code_set IS
    'Global, non-tenant-scoped reference and regulatory parameter data. Seed values are PLACEHOLDERS pending Legal/Compliance/Product/Actuarial sign-off -- do not go live on these numbers without explicit confirmation. (Comment re-stated by V2 -- COMMENT ON TABLE is idempotent and always reflects the latest migration to set it; V1 already carries an equivalent comment for its own four rows.)';
```

No new `GRANT` block is needed — `refdata`'s schema-level grants (from `V1`) already cover every table in the schema, including future rows inserted into an existing table; only new *tables* would need a new grant.

- [ ] **Step 3: Write the `policyloan` domain entities**

```java
package tz.co.nlolo.lifeplatform.policyloan.domain;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotEligibleException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_loan", schema = "policyloan")
public class PolicyLoan {

    @Id
    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "principal_amount", nullable = false)
    private BigDecimal principalAmount;

    @Column(name = "principal_currency", nullable = false)
    private String principalCurrency = "TZS";

    @Column(nullable = false)
    private String status = "RESERVED_PENDING_ORIGINATION";

    @Column(name = "originated_at")
    private Instant originatedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected PolicyLoan() {}

    public PolicyLoan(UUID tenantId, String policyNumber, BigDecimal principalAmount, String principalCurrency, String createdBy) {
        this.loanId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.principalAmount = principalAmount;
        this.principalCurrency = principalCurrency;
        this.createdBy = createdBy;
    }

    public UUID getLoanId() { return loanId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public String getPrincipalCurrency() { return principalCurrency; }
    public String getStatus() { return status; }
    public Instant getOriginatedAt() { return originatedAt; }

    public void markOriginated() {
        if (!"RESERVED_PENDING_ORIGINATION".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be RESERVED_PENDING_ORIGINATION to originate (current: " + status + ")");
        }
        this.status = "ORIGINATED";
        this.originatedAt = Instant.now();
    }

    public void markDisbursementRequested() {
        if (!"ORIGINATED".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be ORIGINATED before disbursement can be requested (current: " + status + ")");
        }
        this.status = "DISBURSEMENT_REQUESTED";
    }

    public void markDisbursed() {
        if (!"DISBURSEMENT_REQUESTED".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSEMENT_REQUESTED to mark disbursed (current: " + status + ")");
        }
        this.status = "DISBURSED";
    }

    /** Idempotent on repeated repayments -- only the DISBURSED -> REPAYING edge is a real
     * transition; a loan already REPAYING stays REPAYING. */
    public void markRepaying() {
        if ("DISBURSED".equals(status)) {
            this.status = "REPAYING";
        }
    }

    public void markSettled() {
        this.status = "SETTLED";
    }

    public void markForcedLapseTriggered() {
        if (!"DISBURSED".equals(status) && !"REPAYING".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to force-lapse (current: " + status + ")");
        }
        this.status = "FORCED_LAPSE_TRIGGERED";
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Deliverable 3 Rev 2, L1: effective-dated rather than a single immutable field, so
 * "locked at origination" vs "floating" is a data question once B2 is finally decided. */
@Entity
@Table(name = "loan_interest_term", schema = "policyloan")
public class LoanInterestTerm {

    @Id
    @Column(name = "loan_interest_term_id")
    private UUID loanInterestTermId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoanInterestTerm() {}

    public LoanInterestTerm(UUID tenantId, UUID loanId, BigDecimal rate, LocalDate effectiveFrom) {
        this.loanInterestTermId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.rate = rate;
        this.effectiveFrom = effectiveFrom;
    }

    public UUID getLoanId() { return loanId; }
    public BigDecimal getRate() { return rate; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only ledger (Deliverable 3 Rev 2 §4) -- authoritative for outstanding balance, never
 * the schedule. Composite PK mirrors the partitioned table's own PRIMARY KEY(loan_transaction_id,
 * occurred_at) exactly -- the table's own comment: "partition key must be part of the PK." */
@Entity
@Table(name = "loan_transaction", schema = "policyloan")
@IdClass(LoanTransaction.LoanTransactionId.class)
public class LoanTransaction {

    @Id
    @Column(name = "loan_transaction_id")
    private UUID loanTransactionId;

    @Id
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column
    private String reference;

    protected LoanTransaction() {}

    public LoanTransaction(UUID tenantId, UUID loanId, String transactionType, BigDecimal amount, String currency, String reference) {
        this.loanTransactionId = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
    }

    public UUID getLoanTransactionId() { return loanTransactionId; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getLoanId() { return loanId; }
    public String getTransactionType() { return transactionType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }

    public static class LoanTransactionId implements Serializable {
        private UUID loanTransactionId;
        private Instant occurredAt;

        public LoanTransactionId() {}
        public LoanTransactionId(UUID loanTransactionId, Instant occurredAt) {
            this.loanTransactionId = loanTransactionId;
            this.occurredAt = occurredAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LoanTransactionId that)) return false;
            return Objects.equals(loanTransactionId, that.loanTransactionId) && Objects.equals(occurredAt, that.occurredAt);
        }

        @Override
        public int hashCode() { return Objects.hash(loanTransactionId, occurredAt); }
    }
}
```

- [ ] **Step 4: Write the repositories**

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyLoanRepository extends JpaRepository<PolicyLoan, UUID> {
    Optional<PolicyLoan> findByLoanIdAndTenantId(UUID loanId, UUID tenantId);
    List<PolicyLoan> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.LoanInterestTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanInterestTermRepository extends JpaRepository<LoanInterestTerm, UUID> {
    List<LoanInterestTerm> findByLoanIdOrderByEffectiveFromDesc(UUID loanId);
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanTransactionRepository extends JpaRepository<LoanTransaction, LoanTransaction.LoanTransactionId> {
    List<LoanTransaction> findByLoanIdOrderByOccurredAt(UUID loanId);
}
```

- [ ] **Step 5: Write the `policyloan.api` types**

```java
package tz.co.nlolo.lifeplatform.policyloan.api;

public enum LoanStatus { RESERVED_PENDING_ORIGINATION, ORIGINATED, DISBURSEMENT_REQUESTED, DISBURSED, REPAYING, SETTLED, FORCED_LAPSE_TRIGGERED }
```

```java
package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanView(UUID loanId, String policyNumber, BigDecimal principalAmount, String principalCurrency,
                        BigDecimal outstandingBalance, String outstandingCurrency, BigDecimal currentInterestRate, LoanStatus status) {}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.api;

import java.util.UUID;

public class LoanNotFoundException extends RuntimeException {
    public LoanNotFoundException(UUID loanId) {
        super("No loan found for id " + loanId);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.api;

/** Mapped to 409 Conflict -- a loan-status guard was violated (e.g. originating against a
 * policy that isn't in force, or repaying a loan that was never disbursed). */
public class LoanNotEligibleException extends RuntimeException {
    public LoanNotEligibleException(String message) {
        super(message);
    }
}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PolicyLoanApi {
    LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy);
    LoanView getLoan(UUID loanId);
    List<LoanView> listLoansForPolicy(String policyNumber);
    LoanView recordRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference, String recordedBy);

    LoanView markDisbursed(UUID loanId);
    LoanView triggerForcedLapse(UUID loanId, String reason);
}
```

- [ ] **Step 6: Write `PolicyLoanApiImpl.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanInterestTerm;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanInterestTermRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanTransactionRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.PolicyLoanRepository;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyLoanApiImpl implements PolicyLoanApi {

    /** Module-Architecture-B1's TTL -- the same value passed by every Task 8 test. Not
     * externalized as a refdata parameter in M3 -- it governs an internal protocol timing, not
     * a business/statutory value, unlike TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE. */
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final PolicyLoanRepository policyLoanRepository;
    private final LoanInterestTermRepository loanInterestTermRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final PolicyApi policyApi;
    private final ReferenceDataApi referenceDataApi;
    private final ApplicationEventPublisher eventPublisher;

    public PolicyLoanApiImpl(PolicyLoanRepository policyLoanRepository, LoanInterestTermRepository loanInterestTermRepository,
                              LoanTransactionRepository loanTransactionRepository, PolicyApi policyApi,
                              ReferenceDataApi referenceDataApi, ApplicationEventPublisher eventPublisher) {
        this.policyLoanRepository = policyLoanRepository;
        this.loanInterestTermRepository = loanInterestTermRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.policyApi = policyApi;
        this.referenceDataApi = referenceDataApi;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy) {
        UUID tenantId = TenantContext.get();
        if (!policyApi.isPolicyInForce(policyNumber, LocalDate.now())) {
            throw new LoanNotEligibleException("Policy " + policyNumber + " must be in force to originate a loan");
        }

        // Module-Architecture-B1's reserve leg -- created/checked inside policy's OWN
        // transaction (policy.PolicyApiImpl.reserveLoanValue), never a lock held across this
        // module boundary. InsufficientLoanValueException (policy.api) propagates as-is --
        // policy.infrastructure.PolicyExceptionHandler maps it to 409 application-wide, so
        // policyloan does not need its own duplicate mapping for a policy-owned exception type.
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, requestedAmount, currency, RESERVATION_TTL);
        BigDecimal rate = new BigDecimal(referenceDataApi.getValue("TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE", "TZ"));
        PolicyLoan loan = new PolicyLoan(tenantId, policyNumber, requestedAmount, currency, originatedBy);
        try {
            policyLoanRepository.save(loan);
            loanInterestTermRepository.save(new LoanInterestTerm(tenantId, loan.getLoanId(), rate, LocalDate.now()));
            loan.markOriginated();
            policyLoanRepository.save(loan);
        } catch (RuntimeException e) {
            // Module-Architecture-B1's release leg -- persistence failed in policyloan's OWN
            // transaction/schema after the reservation already succeeded in policy's; releasing
            // immediately here (rather than leaving it to the TTL sweep) frees the hold right
            // away instead of making a legitimate concurrent borrower wait out the full TTL.
            policyApi.releaseReservation(reservationId);
            throw e;
        }
        policyApi.confirmReservation(reservationId);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanOriginated", tenantId,
            Map.of("loanId", loan.getLoanId(), "policyNumber", policyNumber,
                   "principalAmount", Map.of("amount", requestedAmount.toPlainString(), "currencyCode", currency),
                   "interestRate", rate, "originatedAt", loan.getOriginatedAt().toString())));

        // Disbursement is requested (event published) but never actually executed in M3 --
        // payment (its consumer) is M5. Loans legitimately rest at DISBURSEMENT_REQUESTED here
        // (Global Constraints) unless a test calls markDisbursed as a stand-in.
        loan.markDisbursementRequested();
        policyLoanRepository.save(loan);

        Map<String, Object> disbursementPayload = new LinkedHashMap<>();
        disbursementPayload.put("loanId", loan.getLoanId());
        disbursementPayload.put("payeeRef", payeeRef);
        disbursementPayload.put("amount", Map.of("amount", requestedAmount.toPlainString(), "currencyCode", currency));
        // idempotencyKey isn't a parameter on this method (the header is accepted, not
        // enforced, at the controller -- Global Constraints) -- omitted entirely here rather
        // than put as a null value.
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursementRequested", tenantId, disbursementPayload));

        return toView(loan);
    }

    @Override
    public LoanView getLoan(UUID loanId) {
        return toView(findLoanOrThrow(loanId, TenantContext.get()));
    }

    @Override
    public List<LoanView> listLoansForPolicy(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public LoanView recordRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference, String recordedBy) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to accept a repayment (current: " + loan.getStatus() + ")");
        }
        // M3 simplification (Global Constraints): every repayment is treated as immediately
        // confirmed. The real trigger per Module Architecture is consuming
        // payment.PaymentConfirmed (M5, not built) -- this makes the success path testable now.
        loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "REPAYMENT", amount, currency, paymentReference));
        loan.markRepaying();
        BigDecimal outstanding = computeOutstandingBalance(loan);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            loan.markSettled();
        }
        policyLoanRepository.save(loan);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
            Map.of("loanId", loanId, "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "repaidAt", Instant.now().toString(),
                   "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(), "currencyCode", loan.getPrincipalCurrency()))));

        return toView(loan);
    }

    @Override
    @Transactional
    public LoanView markDisbursed(UUID loanId) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.markDisbursed();
        loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "DISBURSEMENT", loan.getPrincipalAmount(), loan.getPrincipalCurrency(), "test-seam-disbursement"));
        policyLoanRepository.save(loan);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursed", tenantId,
            Map.of("loanId", loanId, "disbursedAt", Instant.now().toString())));
        return toView(loan);
    }

    @Override
    @Transactional
    public LoanView triggerForcedLapse(UUID loanId, String reason) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.markForcedLapseTriggered();
        policyLoanRepository.save(loan);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanForcedLapseTriggered", tenantId,
            Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "triggeredAt", Instant.now().toString())));
        return toView(loan);
    }

    private BigDecimal computeOutstandingBalance(PolicyLoan loan) {
        BigDecimal balance = loan.getPrincipalAmount();
        for (LoanTransaction transaction : loanTransactionRepository.findByLoanIdOrderByOccurredAt(loan.getLoanId())) {
            switch (transaction.getTransactionType()) {
                case "REPAYMENT", "SETTLEMENT" -> balance = balance.subtract(transaction.getAmount());
                case "INTEREST_ACCRUAL" -> balance = balance.add(transaction.getAmount());
                default -> { /* DISBURSEMENT/REVERSAL: already reflected in the starting principalAmount above, or unused in M3's scope */ }
            }
        }
        return balance;
    }

    private PolicyLoan findLoanOrThrow(UUID loanId, UUID tenantId) {
        return policyLoanRepository.findByLoanIdAndTenantId(loanId, tenantId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }

    private LoanView toView(PolicyLoan loan) {
        BigDecimal outstanding = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
        BigDecimal currentRate = loanInterestTermRepository.findByLoanIdOrderByEffectiveFromDesc(loan.getLoanId()).stream()
            .findFirst().map(LoanInterestTerm::getRate).orElse(BigDecimal.ZERO);
        return new LoanView(loan.getLoanId(), loan.getPolicyNumber(), loan.getPrincipalAmount(), loan.getPrincipalCurrency(),
            outstanding, loan.getPrincipalCurrency(), currentRate, LoanStatus.valueOf(loan.getStatus()));
    }
}
```

- [ ] **Step 7: Write `PolicyLoanApiIntegrationTest.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyLoanApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi policyLoanApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Loan Test Applicant", LocalDate.of(1990, 1, 1), "+255713098" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Loan Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", null, "MONTHLY", List.of(), "Loan test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            statement.executeUpdate();
        }
        return policyNumber;
    }

    @Test
    void originateLoanReservesConfirmsAndRestsAtDisbursementRequested() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-ORIGINATE-01");
        TenantContext.set(tenantId);

        LoanView loan = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        assertEquals(LoanStatus.DISBURSEMENT_REQUESTED, loan.status());
        assertEquals(0, new BigDecimal("500000").compareTo(loan.principalAmount()));
        assertEquals(0, new BigDecimal("12.0").compareTo(loan.currentInterestRate())); // TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE, PLACEHOLDER
    }

    @Test
    void originateLoanRejectsAnAmountExceedingAvailableLoanValue() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("100000"), "LOAN-ORIGINATE-02");
        TenantContext.set(tenantId);

        assertThrows(tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException.class, () ->
            policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent"));
    }

    @Test
    void originateLoanRejectsAPolicyThatIsNotInForce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-ORIGINATE-03");
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");

        assertThrows(LoanNotEligibleException.class, () ->
            policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent"));
    }

    @Test
    void repaymentReducesOutstandingBalanceAndSettlesAtZero() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-REPAY-01");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId()); // test seam standing in for payment.DisbursementCompleted

        LoanView afterFirstRepayment = policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("200000"), "TZS", "PAY-REF-01", "test-agent");
        assertEquals(LoanStatus.REPAYING, afterFirstRepayment.status());
        assertEquals(0, new BigDecimal("300000").compareTo(afterFirstRepayment.outstandingBalance()));

        LoanView afterFullRepayment = policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("300000"), "TZS", "PAY-REF-02", "test-agent");
        assertEquals(LoanStatus.SETTLED, afterFullRepayment.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(afterFullRepayment.outstandingBalance()));
    }

    @Test
    void recordRepaymentRejectsALoanStillAtDisbursementRequested() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-REPAY-02");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");

        // Never called markDisbursed -- loan legitimately rests at DISBURSEMENT_REQUESTED
        // (Global Constraints: payment, its real trigger, is M5).
        assertThrows(LoanNotEligibleException.class, () ->
            policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("100000"), "TZS", "PAY-REF-03", "test-agent"));
    }

    @Test
    void triggerForcedLapsePublishesAndTransitionsFromRepaying() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-FORCELAPSE-01");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId());

        LoanView forced = policyLoanApi.triggerForcedLapse(originated.loanId(), "cash value exhausted");
        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, forced.status());
    }

    @Test
    void listLoansForPolicyReturnsEveryLoanAgainstThatPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("2000000"), "LOAN-LIST-01");
        TenantContext.set(tenantId);
        policyLoanApi.originateLoan(policyNumber, new BigDecimal("100000"), "TZS", "MPESA-0712345678", "test-agent");

        List<LoanView> loans = policyLoanApi.listLoansForPolicy(policyNumber);
        assertThat(loans).hasSize(1);
        assertThat(loans.get(0).policyNumber()).isEqualTo(policyNumber);
    }
}
```

- [ ] **Step 8: Extend `AppRolePrivilegesIntegrationTest.java`**

Read the current file (as extended by Task 1) first. Add `"db-migrations/refdata/V2__seed_policy_loan_parameters.sql"` and `"db-migrations/policyloan/V1__create_policyloan_schema.sql"` to the `@BeforeAll` migration list, add `@Autowired private PolicyLoanApi policyLoanApi;`, and this test:

```java
    /**
     * M3 addition: proves app_role can write and read through policyloan.policy_loan/
     * loan_interest_term via the app's own DataSource, exercising the full
     * Module-Architecture-B1 reserve/confirm/release round trip -- confirmReservation writes
     * through policy.policy_account's grants (already proven above) AND policyloan's own new
     * grants in the same call.
     */
    @Test
    void appRoleCanOriginateALoanThroughTheApplicationsOwnDataSource() {
        TenantContext.set(UUID.randomUUID());

        PartyView policyholder = partyApi.registerIndividual("App Role Loan Applicant", LocalDate.of(1988, 6, 1), "+255713000003", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("APP-ROLE-LOAN", "App Role Loan Product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, java.time.LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", java.math.BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", java.math.BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), java.time.LocalDate.now());
        PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(),
            new PolicyApi.IssueRequest(policyholder.partyId(), product.productId(), snapshot.productVersionId(),
                new java.math.BigDecimal("1000000"), "TZS", null, "MONTHLY", java.util.List.of(), "App role loan smoke test"),
            "test-staff");

        LoanView loan = policyLoanApi.originateLoan(issued.policyNumber(), new java.math.BigDecimal("100000"), "TZS", "MPESA-0700000000", "test-agent");
        assertThat(loan.loanId()).isNotNull();

        LoanView fetched = policyLoanApi.getLoan(loan.loanId());
        assertThat(fetched.status()).isEqualTo(LoanStatus.DISBURSEMENT_REQUESTED);
    }
```

Add `tz.co.nlolo.lifeplatform.policyloan.api.*` to the file's imports.

- [ ] **Step 9: Extend `RowLevelSecurityIntegrationTest.java`**

Read the current file (as extended by Task 1) first. Add `"db-migrations/refdata/V2__seed_policy_loan_parameters.sql"` and `"db-migrations/policyloan/V1__create_policyloan_schema.sql"` to the `@BeforeAll` migration list, its own `GRANT USAGE ON SCHEMA policyloan TO app_role; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policyloan TO app_role;` pair, `@Autowired private PolicyLoanApi policyLoanApi;`, and:

```java
    /**
     * M3 addition: proves policy_loan_tenant_isolation actually isolates tenants for
     * policyloan.policy_loan, not merely that the CREATE POLICY statement parses.
     */
    @Test
    void policyLoanIsTenantIsolatedUnderRls() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID caseIdA = openCaseForCurrentTenant("RLS-LOAN-A", "5");
        underwritingApi.submitAssessment(caseIdA, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedA = underwritingApi.getCase(caseIdA);
        String policyNumberA = policyApi.issuePolicy(caseIdA,
            new PolicyApi.IssueRequest(decidedA.applicantPartyId(), decidedA.productId(), decidedA.productVersionId(),
                new java.math.BigDecimal("1000000"), "TZS", null, "MONTHLY", java.util.List.of(), "RLS test"),
            "test-staff").policyNumber();
        String loanIdA = policyLoanApi.originateLoan(policyNumberA, new java.math.BigDecimal("100000"), "TZS", "MPESA-0700000001", "test-agent").loanId().toString();

        TenantContext.set(tenantB);
        UUID caseIdB = openCaseForCurrentTenant("RLS-LOAN-B", "6");
        underwritingApi.submitAssessment(caseIdB, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.MEDICAL, "ok", new java.math.BigDecimal("10"), "underwriter1");
        UnderwritingCaseView decidedB = underwritingApi.getCase(caseIdB);
        String policyNumberB = policyApi.issuePolicy(caseIdB,
            new PolicyApi.IssueRequest(decidedB.applicantPartyId(), decidedB.productId(), decidedB.productVersionId(),
                new java.math.BigDecimal("2000000"), "TZS", null, "MONTHLY", java.util.List.of(), "RLS test"),
            "test-staff").policyNumber();
        policyLoanApi.originateLoan(policyNumberB, new java.math.BigDecimal("200000"), "TZS", "MPESA-0700000002", "test-agent");

        try (Connection superuserConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuserConnection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM policyloan.policy_loan")) {
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(2);
        }

        try (Connection restrictedConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = restrictedConnection.createStatement()) {
            statement.execute("SET ROLE app_role");
            statement.execute("SET app.current_tenant_id = '" + tenantA + "'");
            try (ResultSet resultSet = statement.executeQuery("SELECT loan_id FROM policyloan.policy_loan")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(loanIdA);
                assertThat(resultSet.next()).isFalse();
            }
        }
    }
```

Note this reuses the `PolicyApi`-based imports/helper `openCaseForCurrentTenant` Task 1 already added — no further edits to that helper are needed.

- [ ] **Step 10: Compile and run**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
./mvnw -B -q test -Dtest=PolicyLoanApiIntegrationTest
./mvnw -B -q test -Dtest=AppRolePrivilegesIntegrationTest
./mvnw -B -q test -Dtest=RowLevelSecurityIntegrationTest
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 11: Commit**

```bash
git add db-migrations/policyloan db-migrations/refdata/V2__seed_policy_loan_parameters.sql \
  src/main/java/tz/co/nlolo/lifeplatform/policyloan/domain src/main/java/tz/co/nlolo/lifeplatform/policyloan/infrastructure \
  src/main/java/tz/co/nlolo/lifeplatform/policyloan/api src/main/java/tz/co/nlolo/lifeplatform/policyloan/application \
  src/test/java/tz/co/nlolo/lifeplatform/policyloan/PolicyLoanApiIntegrationTest.java \
  src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java \
  src/test/java/tz/co/nlolo/lifeplatform/RowLevelSecurityIntegrationTest.java
git commit -m "feat: policyloan migration, domain, and PolicyLoanApi consuming reserve/confirm/release"
```

---

### Task 7: `policyloan` REST layer

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/policyloan/infrastructure/{MoneyDto,OriginateLoanRequestDto,RepaymentRequestDto,LoanResponseDto,PolicyLoanController,PolicyLoanExceptionHandler}.java`

**Interfaces:**
- Consumes: `PolicyLoanApi` (Task 6), `PolicyApi.getPolicy` (Tasks 2–3, for the customer object-level check — `policyloan → policy` is the module's one allowed synchronous edge, already exercised for the reserve/confirm/release protocol; this is a second, independent use of the same edge).
- Produces: the 4 HTTP endpoints `api/openapi/openapi-policyloan.yaml` declares — Task 8's contract and race tests build on this.

**Same wire-shape note as Task 4:** `openapi-policyloan.yaml`'s `LoanView` schema nests `principalAmount`/`outstandingBalance` as `Money` objects; `policyloan.api.LoanView` (Task 6) is flattened. `LoanResponseDto` in this task is the translation layer, exactly mirroring Task 4's `PolicyResponseDto`.

**Object-level authorization, not explicitly spelled out in `openapi-policyloan.yaml` but added here for consistency with `policy`'s own customer scoping (Global Constraints):** a customer may only originate/list/read/repay loans against a policy where `policyholderPartyId` matches their token's `party_id` claim. This reuses `PolicyApi.getPolicy` (already an allowed `policyloan → policy` call) rather than duplicating ownership data into `policyloan`'s own schema.

- [ ] **Step 1: Write the DTOs**

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OriginateLoanRequestDto(@NotNull @Valid MoneyDto requestedAmount, @NotBlank String payeeRef) {}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RepaymentRequestDto(@NotNull @Valid MoneyDto amount, @NotBlank String paymentReference) {}
```

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanResponseDto(UUID loanId, String policyNumber, MoneyDto principalAmount, MoneyDto outstandingBalance,
                               BigDecimal currentInterestRate, LoanStatus status) {

    public static LoanResponseDto from(LoanView view) {
        return new LoanResponseDto(view.loanId(), view.policyNumber(),
            new MoneyDto(view.principalAmount().toPlainString(), view.principalCurrency()),
            new MoneyDto(view.outstandingBalance().toPlainString(), view.outstandingCurrency()),
            view.currentInterestRate(), view.status());
    }
}
```

- [ ] **Step 2: Write `PolicyLoanController.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
public class PolicyLoanController {

    private final PolicyLoanApi policyLoanApi;
    private final PolicyApi policyApi;

    public PolicyLoanController(PolicyLoanApi policyLoanApi, PolicyApi policyApi) {
        this.policyLoanApi = policyLoanApi;
        this.policyApi = policyApi;
    }

    @PostMapping("/policies/{policyNumber}/loans")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> originateLoan(@PathVariable String policyNumber, @Valid @RequestBody OriginateLoanRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        // Idempotency-Key accepted, not enforced (Global Constraints).
        enforceCustomerOwnPolicyOnly(policyNumber, jwt, authentication);
        LoanView loan = policyLoanApi.originateLoan(policyNumber, new BigDecimal(request.requestedAmount().amount()),
            request.requestedAmount().currencyCode(), request.payeeRef(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LoanResponseDto.from(loan));
    }

    @GetMapping("/policies/{policyNumber}/loans")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<LoanResponseDto>> listLoans(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyNumber, jwt, authentication);
        return ResponseEntity.ok(policyLoanApi.listLoansForPolicy(policyNumber).stream().map(LoanResponseDto::from).toList());
    }

    @GetMapping("/loans/{loanId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> getLoan(@PathVariable UUID loanId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        LoanView loan = policyLoanApi.getLoan(loanId);
        enforceCustomerOwnPolicyOnly(loan.policyNumber(), jwt, authentication);
        return ResponseEntity.ok(LoanResponseDto.from(loan));
    }

    @PostMapping("/loans/{loanId}/repayments")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> recordRepayment(@PathVariable UUID loanId, @Valid @RequestBody RepaymentRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        LoanView existing = policyLoanApi.getLoan(loanId);
        enforceCustomerOwnPolicyOnly(existing.policyNumber(), jwt, authentication);
        LoanView loan = policyLoanApi.recordRepayment(loanId, new BigDecimal(request.amount().amount()), request.amount().currencyCode(),
            request.paymentReference(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LoanResponseDto.from(loan));
    }

    /** Mirrors PolicyController.enforceCustomerOwnPolicyOnly exactly (Global Constraints) --
     * reuses PolicyApi.getPolicy rather than duplicating policyholderPartyId into policyloan's
     * own schema. PolicyNotFoundException (404) propagates as-is if the policy doesn't exist or
     * isn't in this caller's tenant. */
    private void enforceCustomerOwnPolicyOnly(String policyNumber, Jwt jwt, Authentication authentication) {
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            PolicyView policy = policyApi.getPolicy(policyNumber);
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(policy.policyholderPartyId().toString())) {
                throw new AccessDeniedException("Access denied: customer may only access loans against their own policy");
            }
        }
    }
}
```

- [ ] **Step 3: Write `PolicyLoanExceptionHandler.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotEligibleException;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/** @Order(HIGHEST_PRECEDENCE) required -- see PolicyExceptionHandler's identical note (Task 4). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PolicyLoanExceptionHandler {

    @ExceptionHandler(LoanNotFoundException.class)
    public ProblemDetail handleNotFound(LoanNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "LOAN_NOT_FOUND");
    }

    @ExceptionHandler(LoanNotEligibleException.class)
    public ProblemDetail handleNotEligible(LoanNotEligibleException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "LOAN_NOT_ELIGIBLE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
```

- [ ] **Step 4: Compile**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/policyloan/infrastructure
git commit -m "feat: policyloan REST layer"
```

---

### Task 8: `policyloan` contract tests, plus the end-to-end Module-Architecture-B1 race test over real HTTP

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policyloan/PolicyLoanContractTest.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/policyloan/ModuleArchitectureB1EndToEndRaceTest.java`

**Interfaces:**
- Consumes: the full `policyloan` REST surface (Task 7), `api/openapi/openapi-policyloan.yaml`, the full `policy`+`underwriting`+`product`+`party` REST surfaces (for building fixtures over real HTTP).
- Produces: nothing new — this is the falsifiability gate for Tasks 6–7, and the end-to-end proof that Module-Architecture-B1 holds through the real HTTP boundary, not only when called directly against `PolicyApi`/`PolicyLoanApi` as Java objects (Task 3's test proves the mechanism; this task proves the wiring around it).

- [ ] **Step 1: Write `PolicyLoanContractTest.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyLoanContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-policyloan.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PolicyLoanApi policyLoanApi; // used only to reach markDisbursed (an internal test seam, not HTTP-exposed) as setup for the repayment test

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID tenantId, String policyNumber, UUID policyholderPartyId) {}

    private Fixture issuePolicy(String productCode) throws Exception {
        UUID tenantId = UUID.randomUUID();
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Loan Contract Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+25571310%s"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID applicantId = UUID.fromString(JsonPath.read(applicantResponse, "$.partyId"));

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Loan Contract Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Loan contract test issuance"}
                    """.formatted(caseId, applicantId, productVersionId, UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = 1000000 WHERE policy_number = ?")) {
            statement.setString(1, policyNumber);
            statement.executeUpdate();
        }
        return new Fixture(tenantId, policyNumber, applicantId);
    }

    @Test
    void originateLoanMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicy("LOAN-CONTRACT-01");
        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("DISBURSEMENT_REQUESTED"));
    }

    @Test
    void originateLoanRejectsAnAmountExceedingAvailableWith409() throws Exception {
        Fixture fixture = issuePolicy("LOAN-CONTRACT-02");
        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"5000000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_LOAN_VALUE"));
    }

    @Test
    void originateLoanRejectsCustomerActingOnSomeoneElsesPolicy() throws Exception {
        Fixture fixture = issuePolicy("LOAN-CONTRACT-03");
        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString()).claim("party_id", UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void listLoansMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicy("LOAN-CONTRACT-04");
        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isAccepted());

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].policyNumber").value(fixture.policyNumber()));
    }

    @Test
    void getLoanMatchesOpenApiContractAndRecordRepaymentMatchesAfterMarkingDisbursed() throws Exception {
        Fixture fixture = issuePolicy("LOAN-CONTRACT-05");
        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));

        mockMvc.perform(get("/loans/" + loanId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // markDisbursed is an internal test seam (not part of openapi-policyloan.yaml -- Global
        // Constraints), standing in for consuming payment.DisbursementCompleted (M5, not built).
        TenantContext.set(fixture.tenantId());
        policyLoanApi.markDisbursed(loanId);
        TenantContext.clear();

        mockMvc.perform(post("/loans/" + loanId + "/repayments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"50000.00","currencyCode":"TZS"},"paymentReference":"PAY-CONTRACT-01"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REPAYING"));
    }

    @Test
    void getLoanForNonexistentLoanReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/loans/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: Write `ModuleArchitectureB1EndToEndRaceTest.java`**

```java
package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3 proves Module-Architecture-B1's race-condition fix by calling PolicyApi directly, on
 * threads the test itself controls TenantContext for. This test proves the SAME guarantee holds
 * through the real HTTP boundary -- TenantContextFilter sets TenantContext per-request from
 * each dispatched MockMvc call's own JWT claim, on the thread that call itself runs on, so no
 * manual TenantContext.set() is needed here; the wiring end-to-end (controller -> PolicyLoanApi
 * -> PolicyApi.reserveLoanValue's PESSIMISTIC_WRITE lock) is what's under test.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ModuleArchitectureB1EndToEndRaceTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql");
    }

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private String issuePolicyWithCashValueViaHttp(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"E2E Race Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+25571311%s"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String applicantId = JsonPath.read(applicantResponse, "$.partyId");

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"E2E Race Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"%s","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"E2E race test issuance"}
                    """.formatted(caseId, applicantId, productVersionId, cashValue.toPlainString(), UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            statement.executeUpdate();
        }
        return policyNumber;
    }

    @Test
    void concurrentLoanOriginationsAgainstTheSamePolicyNeverJointlyOverdrawOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValueViaHttp(tenantId, new BigDecimal("1000000"), "E2E-B1-RACE-01");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<Integer>> tasks = List.of(
            () -> attemptOriginateOverHttp(tenantId, policyNumber, barrier),
            () -> attemptOriginateOverHttp(tenantId, policyNumber, barrier));
        List<Future<Integer>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        List<Integer> statuses = futures.stream().map(ModuleArchitectureB1EndToEndRaceTest::getStatus).toList();
        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
    }

    private int attemptOriginateOverHttp(UUID tenantId, String policyNumber, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS); // maximizes the actual race window across both dispatched requests
        MvcResult result = mockMvc.perform(post("/policies/" + policyNumber + "/loans")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"700000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andReturn();
        return result.getResponse().getStatus();
    }

    private static int getStatus(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 3: Compile and run**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q test -Dtest=PolicyLoanContractTest
./mvnw -B -q test -Dtest=ModuleArchitectureB1EndToEndRaceTest
```

Expected: `BUILD SUCCESS`, all tests pass, every run (same deterministic-locking reasoning as Task 3's test).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform/policyloan/PolicyLoanContractTest.java \
  src/test/java/tz/co/nlolo/lifeplatform/policyloan/ModuleArchitectureB1EndToEndRaceTest.java
git commit -m "test: policyloan contract tests and the end-to-end Module-Architecture-B1 race proof"
```

---

### Task 9: Full verification

**Files:** none — this task runs the whole suite and closes out any drift found across Tasks 1–8. It is not optional scaffolding; it is where a genuinely cross-cutting mistake (a missed import, a stale migration path in a test's `@BeforeAll`, a forgotten `@Order`) actually surfaces, since no earlier task compiles or runs the entire module pair together.

- [ ] **Step 1: Full build**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -q clean verify
```

Expected: `BUILD SUCCESS`. This runs every test class in the repository, including `ModularityTests` (`ApplicationModules.of(Application.class).verify()` — confirms `policy`/`policyloan`'s `allowedDependencies` are still exactly what Task 1 confirmed unmodified, and that `policyloan.api`'s new `@NamedInterface("api")` annotation, added in Task 1 Step 1, doesn't introduce a cycle), `NoCircularDependencyTest`, and `NoCrossModuleJoinTest`.

- [ ] **Step 2: Confirm no stray dependency was introduced**

```bash
./mvnw -B -q test -Dtest=ModularityTests
```

Expected: `BUILD SUCCESS`. If this fails, the most likely cause is an accidental import of a non-`api` package across `policy`/`policyloan`/`product`/`underwriting` boundaries (e.g. `policy.infrastructure` importing `underwriting.infrastructure` instead of `underwriting.api`) — grep the failing module's source for the reported illegal package and fix the import to go through that module's `api` package instead.

- [ ] **Step 3: Confirm the `awaitility` question from Task 2 was actually resolved**

Task 2's `PolicyApiIntegrationTest` uses `org.awaitility.Awaitility`, flagged there as needing a dependency check. Confirm the outcome now, not later:

```bash
grep -r "awaitility" pom.xml
```

If absent, `PolicyApiIntegrationTest.endToEndAutoIssuanceFiresFromARealUnderwritingDecision` must have been rewritten to a bounded manual retry loop instead (per Task 2's own instruction — no new Maven dependency). Confirm the actual file matches whichever path was taken; do not leave a compile error here as a surprise for Task 9's build to catch for the first time.

- [ ] **Step 4: Mirror the CI `db-migration-validation` job locally for every new/changed migration**

The CI gate (`.github/workflows/ci-cd.yml`) only checks `app_role`'s own role attributes — it does **not** check that any given schema's migration actually grants `app_role` access or enables RLS per-table (the exact gap that let the `policy`/`policyloan` migrations ship with only 1-of-7 and 1-of-4 tables RLS'd and zero grants in the first place, per this plan's header). Run every migration file this plan touched, in dependency order, against a fresh local Postgres, as the flow the CI job takes:

```bash
psql -h localhost -U postgres -c "CREATE ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD 'local_app_password';"
psql -h localhost -U postgres -f db-migrations/party/V1__create_party_schema.sql
psql -h localhost -U postgres -f db-migrations/product/V1__create_product_schema.sql
psql -h localhost -U postgres -f db-migrations/underwriting/V1__create_underwriting_schema.sql
psql -h localhost -U postgres -f db-migrations/refdata/V1__create_refdata_schema.sql
psql -h localhost -U postgres -f db-migrations/refdata/V2__seed_policy_loan_parameters.sql
psql -h localhost -U postgres -f db-migrations/policy/V1__create_policy_schema.sql
psql -h localhost -U postgres -f db-migrations/policyloan/V1__create_policyloan_schema.sql
psql -h localhost -U postgres -tA -c "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'app_role';" # must print 'f'
psql -h localhost -U postgres -tA -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname IN ('policy','policyloan') AND rowsecurity = false;" # must print nothing -- every table in both schemas must have RLS enabled
psql -h localhost -U postgres -tA -c "SELECT has_schema_privilege('app_role', 'policy', 'USAGE'), has_schema_privilege('app_role', 'policyloan', 'USAGE');" # must print 't  t'
```

Expected: the `rowsecurity = false` query returns **zero rows** (every tenant-scoped table in both schemas has RLS enabled — including `policy.fund_holding` and `policyloan.repayment_schedule`, which carry RLS/grants despite having no JPA entity in this plan), and both `has_schema_privilege` checks return `t`. If Testcontainers is preferred over a local Postgres instance for this step, run it via a throwaway JUnit test using `MigrationTestSupport.applyMigration` with the same file list and assert the same three queries through a raw JDBC connection — either is acceptable; the point is running this check explicitly rather than trusting the CI gate to catch a grants/RLS gap it is not written to catch.

- [ ] **Step 5: Confirm the acceptance criteria this milestone actually claims, one by one**

Re-read `docs/08-implementation-roadmap.md`'s M3 acceptance criteria against what was actually built, and confirm each:

| Acceptance criterion (as scoped by this plan's header) | Where it's proven |
|---|---|
| Full policy lifecycle state machine (`PROPOSED→ACTIVE`, `ACTIVE↔SUSPENDED`, `ACTIVE/SUSPENDED→LAPSED`, `LAPSED→REINSTATED`) under test | `PolicyApiIntegrationTest` (Task 2) |
| Loan reserve/confirm/release integration test covering Module-Architecture-B1's race condition | `ModuleArchitectureB1ConcurrencyTest` (Task 3, direct API) and `ModuleArchitectureB1EndToEndRaceTest` (Task 8, real HTTP) |
| Surrender/maturity Camunda process instance running end-to-end | **Explicitly and deliberately not built** — deferred wholesale per this plan's header; `PolicyContractTest.surrenderPolicyReturns501WithChoreographyNotImplemented`/`getProcessStatusReturns501WithChoreographyNotImplemented` (Task 5) are the falsifiable proof of the deferral decision itself, not of a working choreography |

- [ ] **Step 6: Scan for leftover placeholder language that was never actually flagged**

```bash
grep -rn "TODO\|FIXME\|not yet implemented\|coming soon" src/main/java/tz/co/nlolo/lifeplatform/policy src/main/java/tz/co/nlolo/lifeplatform/policyloan
```

Expected: no matches. Every genuine placeholder in this plan (loan interest rate, suspension-eligible categories, surrender-charge schedule shape, the policy-number generation scheme, the reserve/confirm/release TTL sweep design, `recordRepayment`'s immediate-confirmation simplification) is flagged with a specific, named code comment explaining *what* is a placeholder and *why* — never a bare `TODO`.

- [ ] **Step 7: Final commit**

```bash
git add -A
git status # confirm nothing unexpected is staged
git commit -m "chore: M3 full verification pass" --allow-empty
```

(`--allow-empty` only matters if Steps 1–6 found nothing to fix; if they did, the fix itself should already have been committed as part of whichever task's work it corrected, not bundled anonymously into this step.)

---

## Self-Review Notes

Every task above maps to a specific brief citation; this section covers the checks the brief itself asked for — spec-to-task coverage, placeholder language, and cross-task signature consistency — plus every judgment call this plan made that was **not** one of the 14 pre-made decisions, flagged explicitly for confirmation.

**Spec-to-task coverage check (brief section → task):**
- §2.1 state machine, Po2 beneficiary typing, Po3 `isPolicyInForce`, Po4 lapsed-surrender scope cut → Tasks 1–2 (decisions 7, 13; header Architecture note for Po4).
- §2.2 `LoanInterestTerm`/`RepaymentSchedule`/no-`PARTIALLY_REPAID` → Task 6.
- §2.3 Module-Architecture-B1/A2 disambiguation → used verbatim as the disambiguated name throughout Tasks 3, 6, 8 (decision 3).
- §3.1/§3.4 `PolicyApi` shape, B1 mechanism, deferred choreography → Tasks 2–4 (decisions 1, 2, 7, 10).
- §3.2/§3.5 `PolicyLoanApi` shape (reverse-engineered from the OpenAPI, per the brief's own note that no Java signature exists) → Task 6.
- §4 both OpenAPI files, all 14 endpoints → Tasks 4, 5, 7, 8; the duplicate-`required`-key fix → Task 4 Step 1 (decision 12).
- §5 both migrations' RLS/grants gaps, partitioned-table nuance, `REVOKE` ordering → Tasks 1, 6 (decision 4).
- §6 all five consumed APIs, including the two gaps this plan found by reading the actual code (`UnderwritingCaseView`'s missing fields, `ProductSnapshotView`'s missing fields) → Tasks 1, 2, 4 (decision 8; two flagged judgment calls below).
- §7 event catalog, in-scope/deferred split → every event listed "fully in-scope" or "in-scope but cross-milestone-dependent" is published somewhere in Tasks 2, 6; every "DEFERRED" event is published nowhere (grep confirms this at Task 9 Step 6's discretion, though the grep there targets placeholder language, not event names specifically — a `grep -rn "PolicySurrenderInitiated\|PolicyMaturityInitiated\|PolicySurrendered\|SurrenderPayoutRequested\|MaturityPayoutRequested\|LoanSettledForPayout" src/main/java/tz/co/nlolo/lifeplatform/policy src/main/java/tz/co/nlolo/lifeplatform/policyloan` returning zero matches is the more direct proof; worth running once at the end of Task 9).
- §8 refdata placeholders → Task 6's `V2` migration (decision 9); grace period explicitly *not* mis-sourced as a refdata key (Task 1/2, reads `product.product_version.grace_period_days` — actually, note: this plan does not end up consulting grace period at all, since billing-driven lapse is out of scope and `lapsePolicy` is a direct call with no grace-period computation inside `policy` itself. This is consistent with the scope cut, not an oversight — flagged for visibility since the brief's §8 discussion of grace period could otherwise read as unaddressed).
- §9 all 14 numbered gaps — each is either resolved by one of the 14 pre-made decisions (1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13) or by an explicit judgment call below (5, 10, 14, plus the two new code-level gaps this plan found).

**Placeholder-language scan:** every numeric/structural placeholder carries a named comment (loan interest rate, suspension-eligible categories, surrender-charge schedule shape and parsing, policy-number generation, the TTL-sweep redesign, `recordRepayment`'s simplification, the DEATH-only coverage-row simplification, the "no LTV cap" simplification, the "encumbrance never decreases" simplification) — none are bare guesses. No task contains "similar to Task N" or "add appropriate validation" — every validation/business rule is spelled out as literal code in the task that needs it.

**Cross-task signature consistency check:** `PolicyApi`'s full interface is declared once, verbatim, in Task 2's "Produces" block, and every later task (3's modification, 4's controller, 6's `PolicyLoanApiImpl`, Task 9's table) calls exactly those method names/signatures — no task introduces a same-purpose method under a different name. `PolicyLoanApi` likewise declared once in Task 6 and consumed unchanged by Tasks 7–8. `UnderwritingCaseView`'s extended field list (Task 2 Step 1) is consumed identically by `UnderwritingDecisionEventListener` (Task 2 Step 6) and `PolicyController.manualIssue`'s equivalent data path (Task 4, via `getSnapshotByVersionId`, a different but consistent source for the same `productId`/`productVersionId` need on the manual path).

**Judgment calls made in this plan that were NOT among the 14 pre-made decisions — flagged for explicit confirmation:**

1. **Modifying two already-merged M2 modules (`underwriting`, `product`) rather than treating them as frozen.** Three separate additive changes were made: `underwriting.api.UnderwritingCaseView` gained three fields and `UnderwritingApiImpl` gained its first-ever event publish (Task 2); `product.api.ProductSnapshotView` gained two fields, `ProductVersion` gained a JSONB-mapped column, and `ProductApi` gained a second new method `getSnapshotByVersionId` (Tasks 1 and 4). All three were discovered by reading the actual M2 code, not by any design-doc gap the brief itself named at this level of specificity. Every change is additive (new fields/methods only, no existing signature altered) and each is justified inline, but this is still real scope beyond "build `policy`/`policyloan`" — confirm this is acceptable before treating M2 as closed.
2. **`UnderwritingDecisionMade` is now actually published.** Without Judgment Call 1, `policy`'s entire "system-triggered by consuming `UnderwritingDecisionMade` internally" issuance path (the documented common case, not the exception path) would have no real producer anywhere in the codebase, making `UnderwritingDecisionEventListener` untestable end-to-end. This plan concluded publishing it now is necessary, not optional — but it's a behavior change to `underwriting`'s public contract (a new event on the wire) that a stricter "M2 is frozen" reading would forbid.
3. **`SURRENDERED` is not reachable by any code path in this plan**, including the `LAPSED→SURRENDERED` (Po4) path the aggregate design describes as a *simpler*, non-choreography transition. The roadmap's acceptance-criteria wording ("Active → Suspended → Lapsed → Reinstated/Surrendered ... under test") reads as if `SURRENDERED` should be exercised. This plan concludes the wholesale deferral of `PolicySurrendered` (decision 2's event list) makes any `SURRENDERED`-reaching transition unobservable and therefore not worth building dead code for — but this is this plan's inference, not a stated decision, and Po4 specifically was never mentioned in the 14 decisions.
4. **The TTL sweep is an opportunistic, per-call, per-tenant sweep, not a true `@Scheduled` cross-tenant background job.** The brief's own quoted mechanism ("A background sweep in `policy` auto-releases any reservation whose TTL expired") reads as a literal timer thread. This plan concludes a true cross-tenant timer is architecturally incompatible with the platform's fail-closed RLS design (no tenant-directory table exists to iterate; an RLS-bypassing role is the platform's most guarded security invariant) and substitutes a per-tenant, per-call sweep that achieves the same self-healing effect without either gap — but this is a real, debatable design substitution, not a literal implementation of the quoted mechanism.
5. **`Map.of(...)` is not used for three event payloads, not the two originally anticipated.** `PolicyIssued` (`agentOfRecordId`) and `LoanDisbursementRequested` (`idempotencyKey`, handled by omitting the key entirely rather than a null value) were flagged in Global Constraints; `underwriting.UnderwritingDecisionMade` (`loadingPercent`, genuinely nullable per the `chk_loading_only_when_loaded` DB CHECK) needed the same treatment and was decided during Task 2's own drafting. The Global Constraints bullet on this point should be read as covering all three, not literally two.
6. **Object-level customer authorization on `policyloan`'s four endpoints** is this plan's own extension, not stated in `openapi-policyloan.yaml` (which specifies only realm-level security for all four). Decision 11 only covers `policy`'s `GET /policies/{policyNumber}`. Extending the same pattern to `policyloan` (reusing `PolicyApi.getPolicy` for the ownership check) is a reasonable, consistent safety improvement, but it is an addition beyond what any decision or the OpenAPI spec itself requires.
7. **`policy.policy`'s status-versus-404-versus-403 split.** An earlier draft of this plan's Global Constraints described customer-ownership mismatches as using "the same 404 either way" anti-enumeration disguise `PartyApiImpl.findPartyOrThrow` uses for cross-tenant mismatches. On closer reading of the actual `PartyController.getParty` code, that disguise is real only for the tenant dimension (via `findPartyOrThrow`); the customer's-own-`party_id` check in the controller is a genuine, undisguised `403`, confirmed by `PartyContractTest.getPartyRejectsCustomerReadingSomeoneElsesRecord`'s own assertion (`status().isForbidden()`, not `isNotFound()`). This plan's actual controller code (Task 4) implements the correct behavior (403 for ownership, 404 only for cross-tenant/nonexistent) — the note is here in case the Global Constraints section's prose was read before this correction was made.

**Environment substitution already made per this plan's own instructions:** the Global Constraints' build/test commands use `export JAVA_HOME=... && ./mvnw` directly (no Docker wrapper), per this task's environment note — a deliberate departure from M2's plan, which used a Docker-wrapped `mvnw` for a different environment. If this turns out to be the wrong assumption for whoever executes this plan, substitute the working command form and note the substitution in the task report, per the same latitude M0–M2 used.


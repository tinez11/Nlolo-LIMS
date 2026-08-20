# M10 — Regulatory Reporting (TIRA): Design

**Milestone:** M10, the last on `docs/08-implementation-roadmap.md`'s M0→M10 order (`:183`).
**Module:** `regreporting`.
**Status entering M10:** the module is empty — five `package-info.java` files, one `V1` migration, zero Java, zero tests.
**Blocked on:** C2 (TIRA's return catalog/format), owned by Compliance/Regulatory (`docs/02-module-architecture.md:198`).

---

## 1. This is still a regulatory-reporting module

M10 delivers what the roadmap says can proceed (`:183`): "the reporting read-model infrastructure and a generic report-scheduling mechanism, built against a placeholder return format that's easy to swap." It does **not** invent TIRA's return catalog.

That is the same discipline M9 applied to C1 — build the structure, defer the domain content, mark the boundary in the schema itself, never guess — with one deliberate improvement. M9's final whole-branch review found its own spec claiming the chart of accounts "live[s] in `chart_of_account` as data, so replacing them is a seed change, not a code change" when every code was a compiled `Map.of(...)` constant. M10 does not repeat that: §5 states exactly which half of the return format is data and which is code, in those terms, and the data half is genuinely a seeded table.

**What an operator should expect from M10's output:** correct, queryable, per-period figures for policies in force, new business, claims, premium collected, and reinsurance ceded — assembled into one placeholder return whose every line code and label is invented and flagged. Not a TIRA-compliant submission.

---

## 2. Context findings that shaped this design

Each of these was verified against the code, not assumed.

**`regreporting/V1` has never been applied by any test.** `grep -rn "regreporting" src/test` returns exactly two hits: a prose comment in `ClaimSettlementEndToEndTest:553` and an ArchUnit module-name string list in `NoCrossModuleJoinTest:16`. Neither applies the migration. This is the identical blind spot `finaccounting` had entering M9, and it is why V1's defect set below went unnoticed.

**V1 carries the recurring V1 defect set — the seventh consecutive module.** No RLS on either table, no `GRANT` to `app_role` anywhere in the schema, no audit columns, no optimistic locking. `docs/06-database-schema.md:25` requires `tenant_id` indexed with an RLS policy on every tenant-scoped table; both V1 tables carry `tenant_id NOT NULL` and neither has RLS or a policy.

**V1's own header cites a document section that does not exist.** Line 1 reads `-- Module: regreporting (Regulatory Reporting / TIRA) -- Deliverable 3 §10 (CQRS read-model only)`. §10 of `docs/03-aggregate-design.md` (`:179`) is "Thin/Generic Modules — unchanged from Rev 1 (`iam`, `document`, `refdata`, `omnichannel`)", which does not mention `regreporting`, and no CQRS read-model section for `regreporting` exists anywhere in that document. The design intent the comment describes is sound and this spec adopts it; the citation is false and V2's header corrects it.

**No policy event except `PolicyIssued` carries `productId`.** `PolicyIssuedPayload` (`api/asyncapi-events.yaml:421`) carries `policyNumber`, `policyholderPartyId`, `productId`, `productVersionId`, `sumAssured`, `issueDate`, `premium`, `premiumFrequency`, `agentOfRecordId`. `PolicyLapsedPayload` carries `policyNumber` + `lapsedAt`; `PolicyReinstatedPayload` carries `policyNumber` + `reinstatedAt`; `PolicyMatured`/`PolicySurrendered` are similarly minimal. V1's `policy_in_force_summary.product_id UUID NOT NULL` is therefore **unfillable from events alone** — which is what forces the dimension table in §4.

**Only `ClaimRegistered` carries `claimType`.** `ClaimRegisteredPayload` has it (enum `DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY`); `ClaimApproved` carries `claimId`/`policyNumber`/`approvedAmount`, `ClaimRejected` carries `claimId`/`reason`, `ClaimSettled` carries `claimId`/`policyNumber`/`settledAmount`/`settledAt`. Same conclusion, same fix.

**`policy.PolicySurrendered` does not mean a policyholder surrender.** Its own schema description states it fires for a settled DEATH/DISABILITY/CRITICAL_ILLNESS claim via `PolicyApiImpl.terminateForSettledClaim`, because the policyholder-initiated surrender choreography (`PolicySurrenderInitiated → … → SurrenderPayoutRequested`) was never implemented. Reporting it under a "surrenders" line would be quietly false.

**`finaccounting.GlPostingRecorded` carries no account codes.** Its payload (`:764`) is `postingId` (the journal entry id), `groupId` (always null, C1-blocked), `period`, `amount` (the entry's DR total), `postingType` (the source event name). `FinaccountingApi` exposes only `listJournalEntries`, `getJournalEntry`, `listChartOfAccounts` — no trial-balance or account-balance query. So **no financial statement is derivable from the GL as it stands**, and financial figures in M10 come from business events instead. `docs/05-event-catalog.md:30` names `regreporting` as a consumer of this event; M10 declines it (see §9).

**`finaccounting.CsmRolledForward` is declared but has never been published.** M9 built no producer for it — IFRS 17 measurement is C1-blocked. Nothing in M10 may depend on it.

**`REALM_REGULATORS` has existed since M1 and no endpoint has ever used it.** `SecurityConfig` maps the regulators issuer to a synthetic `ROLE_REALM_REGULATORS` authority; regulators carry no specific role names (only staff does). M10 is the realm's first real use.

**Regulators are necessarily tenant-scoped.** `TenantContextFilter` rejects *any* authenticated request whose token lacks a parseable `tenant_id` claim with a 403 (`TENANT_CLAIM_MISSING`), regulator tokens included. A cross-tenant regulator view would require deliberately defeating the platform's central isolation control.

**There is no `COMPLIANCE_OFFICER` role.** `docs/04-api-contracts.md:44` defines the staff vocabulary as `UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`, `FINANCE_OFFICER`, `CUSTOMER_SERVICE_REP`, `ADMIN`. Confirmed against every `hasRole(...)` in `src/main/java`.

**An OpenAPI draft already exists, and cannot be loaded by tooling.** `api/openapi/openapi-regreporting-document-refdata.yaml` holds three complete OpenAPI documents separated by `---`, with a header instructing that they be split into separate files before tooling use. `openApi().isValid()` and `ParseOptions.setResolve(true)` both need one document per file.

---

## 3. Approved decisions

Five decisions, each chosen by the user from options with stated trade-offs.

1. **Events-only projections.** `allowedDependencies` stays exactly `{ refdata::api }`. Nothing is added. All business facts arrive as domain events. Honours `docs/02-module-architecture.md:177` and keeps D3's eventual reporting-DB separation open.
2. **Generic line-item returns.** A return is a header plus N `return_line` rows. Which lines a return type contains is a definition; the values come from named metric readers over the projections.
3. **Structured rows, synchronous generation.** `POST` returns `201` with the finished return. No `document` dependency, no rendered artifact, no `GENERATING → READY` state machine. `document_ref` stays nullable and unwritten pending C2.
4. **Movement rows plus cumulative sum** for stock metrics. No scheduled close job anywhere in M10.
5. **Four fact tables and two dimension tables** (§4).

---

## 4. Architecture

### Dependencies

`regreporting`'s `package-info.java` keeps `@ApplicationModule(allowedDependencies = { "refdata::api" })` unchanged. `ModularityTests` enforces it. `regreporting` consumes events from `policy`, `claims`, `billing`, and `reinsurance` while calling none of their APIs — the same relationship `docs/02-module-architecture.md:183` describes for the broad fan-in consumers.

### Two dimension tables

Both exist because the attributes needed to attribute a movement arrive on one event and the movement itself arrives on another. This is the pattern `distribution` (M7) and `reinsurance` (M8) each already established with their own `policy_projection` tables, for the identical `allowedDependencies` constraint.

- **`policy_dimension`** — `(tenant_id, policy_number)` → `product_id`, `sum_assured_amount`, `sum_assured_currency`, `issue_date`. Written from `policy.PolicyIssued`.
- **`claim_dimension`** — `(tenant_id, claim_id)` → `claim_type`, `policy_number`. Written from `claims.ClaimRegistered`.

### Four fact tables, storing movements

Every fact table stores **signed movements for the period in which the event occurred**, never an absolute balance. A stock metric for period P is the cumulative sum of all movements where `period <= P`.

| Table | Key | Measures | Fed by |
|---|---|---|---|
| `policy_movement` | `(tenant_id, period, product_id)` | `policy_count_delta`, `sum_assured_delta` + currency | `PolicyIssued` (+1), `PolicyReinstated` (+1), `PolicyLapsed` (−1), `PolicyMatured` (−1), `PolicySurrendered` (−1, claim-driven) |
| `claims_movement` | `(tenant_id, period, claim_type)` | `registered_count`, `approved_count`, `rejected_count`, `settled_count`, `approved_amount`, `settled_amount` + currency | `ClaimRegistered`, `ClaimApproved`, `ClaimRejected`, `ClaimSettled` |
| `premium_movement` | `(tenant_id, period, product_id)` | `collected_amount` + currency | `billing.PremiumCollected` |
| `reinsurance_movement` | `(tenant_id, period)` | `ceded_risk_amount`, `ceded_premium_amount` + currency | `reinsurance.CessionRecorded` |

**Why movements rather than snapshots.** A running counter answers "how many policies are in force *now*"; a return generated in Q4 for Q3 must answer "how many were in force at the end of Q3". Cumulative-sum-over-movements answers both, keeps every historical period correct permanently with no scheduled job, and puts a late-arriving event in its own period instead of corrupting the present. It is the same reasoning `finaccounting` already applies to its ledger: direction carries the sign, balances are derived.

**`PolicySuspended` produces no movement, deliberately.** A suspended policy is still in force — it is not paying premiums, which is a different fact. Recorded so the omission reads as a decision.

**`reinsurance_movement` is deliberately not attributed by product.** `reinsurance.CessionRecorded` is published from `reinsurance`'s own transaction reacting to `PolicyIssued`, so its arrival is not ordered against `regreporting`'s own `PolicyIssued` listener (`docs/05-event-catalog.md:62` documents that ordering across fan-in consumers is not guaranteed). Keying it by product would introduce a race where a cession could arrive before the dimension row it needs. Keying it by tenant and period alone removes the dependency entirely.

**A missing dimension row must never drop a figure.** For `premium_movement` the dimension will normally exist (an invoice is generated and collected well after issuance), but if a lookup misses, the movement is attributed to an explicit `UNKNOWN` sentinel `product_id` rather than skipped or crashed. A financial figure silently vanishing is strictly worse than one landing in a visibly-unattributed bucket. The same rule applies to `claims_movement` when a `claim_dimension` row is absent.

### Listener mechanics

Four listeners in `regreporting.application`, one per producing module, each following the platform's established shape exactly (copied from `reinsurance.application.PolicyEventListener`): `@TransactionalEventListener(phase = AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` (a plain `@Transactional` from an AFTER_COMMIT callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project), `TenantContext` save/set/**restore** rather than an unconditional clear, and a catch-all that logs and increments an alertable failure counter.

Bean names must be explicit — `PolicyEventListener`, `ClaimEventListener`, and `PaymentEventListener` are each already declared by more than one module, and this platform has lost a task to a Spring bean-name collision.

Projection writes are **upserts** keyed on the fact table's unique key, accumulating into that period's row.

---

## 5. What is data and what is code — stated precisely

This section exists because M9's spec got the equivalent claim wrong and its final review caught it.

**The line composition of a return is DATA.** Two seeded tables:
- `return_definition` — `(tenant_id, return_type)` → `label`, `description`, `period_kind` (`QUARTERLY` or `ANNUAL`, matching V1's `period VARCHAR(10)` comment "`'YYYY-Qn'` or `'YYYY'`"). `period_kind` is what §9's last bullet relies on to reject a period whose format does not match the return type — without it, an annual and a quarterly period could be cumulative-summed together, which would be silently wrong.
- `return_definition_line` — `(tenant_id, return_type, line_no)` → `line_code`, `label`, `metric_name`, `unit`

**The computation of a metric is CODE.** A registry of named `MetricReader`s, each resolving one `metric_name` against the projections (e.g. `POLICIES_IN_FORCE` cumulative-sums `policy_movement.policy_count_delta` where `period <= P`).

The consequence, in plain terms: **re-shaping a return out of metrics that already exist is a seed change. Asking for a number nobody computes yet is a Java change.** Replacing the placeholder catalog with TIRA's real one is the former to the extent TIRA asks for figures M10 already computes, and the latter for anything genuinely new. Neither this spec nor any code comment may claim the format is "just data".

**M10 seeds exactly one definition**, `QUARTERLY_PRUDENTIAL`, with lines drawn from all four fact tables so every projection is exercised end to end. Every line code and label is an **invented placeholder pending C2** and carries a comment saying so.

---

## 6. Schema — `regreporting/V2`

`V1` is immutable: `scripts/migrate.sh` applies every `V*.sql` per module in version order, so V1 has been applied to real deployments regardless of no test covering it.

`V2` does the following.

1. **Grants and RLS across the schema.** `GRANT USAGE ON SCHEMA`, `GRANT SELECT/INSERT/UPDATE/DELETE ON ALL TABLES`, `ALTER DEFAULT PRIVILEGES`, then `ENABLE ROW LEVEL SECURITY` + a tenant-isolation policy of the exact platform shape (`USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)`) on every table, existing and new. V1 granted `app_role` nothing and enabled RLS nowhere.
2. **Corrects V1's false citation** in its own header comment (§2).
3. **Renames `policy_in_force_summary` → `policy_movement`** and repurposes its measures as deltas. The rename is not cosmetic: under the movement model the name `..._in_force_summary` would actively misdescribe the contents, and a reader trusting it would compute in-force figures wrongly. Its existing unique index on `(tenant_id, period, product_id)` is already the correct grain for movements and is retained.
4. **Creates** `policy_dimension`, `claim_dimension`, `claims_movement`, `premium_movement`, `reinsurance_movement`, `return_line`, `return_definition`, `return_definition_line`.
5. **Adds audit columns and tenant indexes** where missing, per `docs/06-database-schema.md:25`.
6. **Adds money guards.** All amounts are `NUMERIC(19,2)` + `CHAR(3)` per `:32`. Movement deltas are deliberately **signed** (a lapse is negative), so they take no positivity CHECK — unlike `finaccounting.gl_posting.amount`, where direction carries the sign. Counts are `INTEGER` and likewise signed. This difference is called out in the migration so nobody "fixes" it into a positivity constraint later.
7. **Widens nothing without checking.** Every new `VARCHAR` is sized against its CHECK vocabulary in the same migration — M7 shipped a CHECK admitting a 16-character value into a `VARCHAR(15)`, making a whole payout path unwritable while every test stayed green.

**One inherited wart, documented rather than papered over.** `regulatory_return.status` is `VARCHAR(15)` with `CHECK (status IN ('GENERATING','READY'))`. Under synchronous generation (§7) `GENERATING` is unreachable — every return is complete when it is created. The CHECK is left as-is because dropping an unreachable enum value is not worth a migration, and V2 records that `READY` is the only value ever written. Note for whoever adds submission states after C2: `SUBMISSION_FAILED` is 17 characters and would need the column widened in the same migration that adds it.

**`document_ref` stays nullable and is never written.** Rendering a submission artifact requires knowing TIRA's file format, which is exactly what C2 has not told us. Inventing one would be the invented-content trap this milestone is structured to avoid.

---

## 7. Return generation

`RegreportingApi` exposes:

- `RegulatoryReturnView generateReturn(String returnType, String period, String generatedBy)`
- `List<RegulatoryReturnView> listReturns(String period)`
- `RegulatoryReturnView getReturn(UUID returnId)` — including its lines

`generateReturn` loads the `return_definition_line` rows for the type, resolves each line's `metric_name` through the `MetricReader` registry for the requested period, and writes one `return_line` row per line, all in one transaction, returning `201` with the finished return.

**Why synchronous, against the draft spec's `202`.** The draft models generation as long-running with a `GENERATING → READY` state machine and a polling `GET`. With projections maintained incrementally, generation is a handful of aggregate queries over pre-summarised rows — milliseconds. A state machine, a polling endpoint, and an unreachable status value for millisecond work is speculative complexity. The deviation is deliberate and recorded here rather than made silently; if C2 later brings heavy file rendering, reintroducing async is a contained change behind the same API.

**Idempotency and regeneration.** `(tenant_id, return_type, period)` is unique on `regulatory_return`. Regenerating an existing return replaces its lines within one transaction rather than accumulating duplicates or failing — a return is a derived artifact and re-deriving it must be safe. `generated_at` and `generated_by` are updated on regeneration.

**Unknown return type or malformed period** is a `FinaccountingValidationException`-equivalent 422 (`RegreportingValidationException`), not a 500 — a caller asking for a return type nobody seeded is a client error.

---

## 8. REST surface and testing

### Spec file

`api/openapi/openapi-regreporting.yaml` is extracted as its own document and the `regreporting` block is removed from `openapi-regreporting-document-refdata.yaml`, which keeps `document` and `refdata`. This is what that file's own header has asked for since it was written, and it is a hard prerequisite: `openApi().isValid()` and `ParseOptions.setResolve(true)` cannot load a multi-document file.

The spec states plainly in its own `description` that the return catalog is a placeholder pending C2, so an API consumer is not left believing the line codes are TIRA's.

### Endpoints

| Endpoint | Gate |
|---|---|
| `POST /regulatory-returns` | `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))` |
| `GET /regulatory-returns` (filter by `period`) | staff as above, **or** `hasRole('REALM_REGULATORS')` |
| `GET /regulatory-returns/{returnId}` | staff as above, **or** `hasRole('REALM_REGULATORS')` |

The staff gate is a **decision, not a spec quote** — no `COMPLIANCE_OFFICER` role exists (§2), and `FINANCE_OFFICER`/`ADMIN` is the same choice M7, M8, and M9 each recorded for the same reason.

The regulator gate is `REALM_REGULATORS`' first use on the platform, and is **read-only and tenant-scoped**: regulators get no `POST`, and `TenantContextFilter` guarantees they see exactly one tenant. Cross-tenant regulatory access is explicitly out of scope (§9).

Money on the wire is a decimal string via `MoneyDto`, never a JSON number.

### Tests

- `CumulativeMetricTest` — pure unit: cumulative-sum semantics, including that movements in periods *after* the requested one are excluded.
- `ReturnGeneratorTest` — pure unit: a definition's lines map to metric readers in order; an unknown metric name fails loudly rather than silently emitting a null line.
- `RegreportingApiIntegrationTest` — real Postgres as `app_role`: projections upsert correctly, generation writes lines, regeneration replaces rather than duplicates, cross-tenant reads 404 under real RLS, unknown return type 422.
- `ProjectionEndToEndTest` — drives the **real** producer chains (issue a real policy, settle a real claim, collect a real premium, record a real cession) and asserts the dimension and movement rows, not hand-published envelopes.
- **`HistoricalPeriodReturnTest` — the test that proves decision 4 actually works.** Build movements across three consecutive periods, generate a return for the *earliest*, and assert it reports that period's figures and not the latest. Without this the entire movements-versus-snapshot decision is unverified, and a regression to a running counter would pass every other test in this list.
- **`MissingDimensionTest`** — a movement whose dimension row is absent lands under the `UNKNOWN` sentinel and is still counted, rather than vanishing.
- `RegreportingContractTest` — one test per reachable status, `openApi().isValid(...)` paired with `SpecTypeConformance.matchesDeclaredTypes(...)` on every decimal-carrying response (`isValid` alone does not enforce primitive JSON types — measured on this platform). Includes a **regulator token reading successfully** and a regulator `POST` being refused, plus agent/customer 403s.
- `RegreportingSpecParsesTest` — container-free spec load with `setResolve(true)`, proving the extraction in §8 produced a genuinely loadable document.
- `regreporting` added to `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest` — the 7th module in each, and the first automated coverage this schema has ever had.
- An alertable counter `lifeplatform_regreporting_event_processing_failed_total`, its rule in `observability/alert-rules.yml`, and its registration in `AlertRuleMetricProducerTest` — a metric with no rule, or a rule with no producer, is silent.

---

## 9. Flagged for sign-off, and deliberate deferrals

**Invented — needs Compliance/Regulatory sign-off before production use (C2).** Every item carries a comment saying so:
- The entire return catalog: every return type, line code, line label, and ordering
- The `QUARTERLY_PRUDENTIAL` placeholder definition in its entirety
- Which metrics a real TIRA return actually requires

**Blocked on C2, not guessed:**
- TIRA's submission channel and file format, and therefore `regulatory_return.document_ref`, which stays nullable and unwritten
- Any submission lifecycle beyond `READY` (`SUBMITTED`, `ACCEPTED`, `REJECTED`) — including the `VARCHAR(15)` widening those would need

**Deferred deliberately, recorded so the next reader does not rediscover them:**
- **A GL control-total line.** `docs/05-event-catalog.md:30` names `regreporting` as a consumer of `finaccounting.GlPostingRecorded`, and M10 does not consume it. The event carries no account codes (§2), so it can only support a coarse "total value posted this period" reconciliation figure, not a trial balance. Worth adding once either the event is enriched with its legs' account codes or `finaccounting` exposes a balance query — both of which are that module's changes to make, not this one's.
- **`finaccounting.CsmRolledForward`** is declared in the catalog and has no producer (C1). Nothing here depends on it.
- **`policy.PolicySurrendered` is a claim-driven termination, not a policyholder surrender**, per its own schema description. M10 counts it as a termination and does not label it a surrender anywhere. A true-surrender line is impossible until the upstream choreography exists.
- **Cross-tenant regulatory access.** `TenantContextFilter` 403s any token without a `tenant_id` claim, so a TIRA-wide multi-insurer view would require defeating the platform's central tenant-isolation control. Out of scope by design; TIRA aggregating across insurers is TIRA's concern, not this platform's.
- **D3's reporting-database separation** remains a Phase 1/2 item (`docs/02-module-architecture.md:22`). This design does not foreclose it — the projections already live in their own schema, fed only by events, with no synchronous coupling to any transactional module.
- **Commission and policy-loan projections** (`distribution.CommissionPaid`, `policyloan.LoanDisbursed`/`LoanRepaid`) are not built. Both are plausible return inputs; neither is justified before C2 says so.
- **No period-locking.** Nothing prevents a movement landing in a period whose return has already been generated; regeneration is the remedy. Period-close semantics are deferred platform-wide (the same gap `finaccounting` recorded in M9).
- **`period` is a string** (`YYYY-Qn` or `YYYY`), matching V1. No calendar arithmetic is performed on it beyond lexical comparison, which is sound for cumulative sums because both formats sort correctly within their own kind — but a return mixing annual and quarterly periods in one cumulative sum would be wrong, so `ReturnDefinition` fixes the period *kind* per return type and the API rejects a mismatched period format.

---

## 10. Acceptance criterion mapping

`docs/08-implementation-roadmap.md:183` — the milestone "[c]annot be finalized until open item C2 (TIRA's actual return catalog/format) is resolved", and names what can proceed: "the reporting read-model infrastructure and a generic report-scheduling mechanism, built against a placeholder return format that's easy to swap."

- **Reporting read-model infrastructure** → two dimension tables and four movement fact tables, maintained by four event listeners, each proven against a real producer chain by `ProjectionEndToEndTest`
- **Generic report-generation mechanism** → `return_definition`/`return_definition_line` as data plus a `MetricReader` registry as code, with the boundary between them stated exactly (§5)
- **Placeholder return format that's easy to swap** → one seeded `QUARTERLY_PRUDENTIAL` definition; re-shaping a return from existing metrics is a seed change, and §5 says plainly what still requires code
- **Correct for historical periods** → movements plus cumulative sum, proven by `HistoricalPeriodReturnTest`, with no scheduled close job to fail
- **Explicitly NOT claimed:** a TIRA-compliant return. Every line code and label is invented and flagged, no submission path exists, and `document_ref` is unwritten. M10's honest Definition of Done is "the reporting infrastructure works and is exercised end to end; the return catalog awaits C2."

---

## 11. What "scheduling" means here, and why there is no scheduler

The roadmap's phrase is "a generic report-**scheduling** mechanism". M10 delivers generation-on-demand, not a scheduler, and that is a deliberate reading rather than an oversight.

The platform's two existing scheduled mechanisms (`billing.sweep_billing_state()` and the commission close, both pg_cron + `SECURITY DEFINER`) exist because something *must* happen at a deadline without a human: dunning escalates, a commission period closes. A regulatory return has no such property — it is produced when a compliance officer files it, and because §4's movements make every historical period permanently reproducible, generating it late costs nothing and produces the identical figures.

Adding pg_cron here would mean inventing a filing calendar that C2 has not given us, and a mis-timed automatic filing is a worse failure than a manual one. When C2 supplies real filing deadlines, a scheduled trigger over this same `generateReturn` call is a small, contained addition — the mechanism it would drive is what M10 builds.

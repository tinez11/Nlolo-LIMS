# IFRS 17 I5a — Engine Period Cycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> This user prefers inline execution with a solo final review (no subagents).

**Goal:** Run month-end steps 6 and 7 for a period:
- extract the actual cash flows per IFRS 17 group;
- load the engine's results (P-01…P-17) as maker-checker ENGINE_RUN journals through 9160;
- reconcile each group's ledger figures to the engine's, and block the period lock on an unexplained difference.

**Architecture:**
- Everything lives in `finaccounting`, under `application/engine` and `domain/engine`.
- Pure pieces carry the logic: the extract builder, the results parser and validator, the run poster's netting, and the
  reconciler. JDBC stores and a service wire them together.
- Reinsurance groups (`RI-<treaty>-<year>`) come from a reference→group map that finaccounting keeps from reinsurance
  events, backfilled by migration. Posted ledger lines are immutable, so existing postings cannot be re-tagged.
- Policy facts for the extract come from a finaccounting-owned `policy_snapshot`, fed by policy events. `policy::api`
  is not an option: finaccounting → policy → distribution → finaccounting would be a module cycle.

**Tech stack:** Spring Boot 3 / Java 21, JdbcTemplate, Apache POI (XSSF), commons-csv, Spring Modulith, Postgres 16
(RLS, app_role), Testcontainers, React 18 + Vite + Zustand, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-06-ifrs17-i5a-engine-period-cycle-design.md` (d11f4def).

## Global Constraints

- **Period.** Both the extract and the results need the period CLOSING. The extract is refused while
  `UnpostedEvents.openUpTo(tenant, period) > 0`. A replacement is allowed only while the period is not LOCKED.
- **Approval.** A FINANCE_APPROVER who is not the uploader approves. Approval needs a sign-off reference (non-blank,
  ≤ 200 characters) and an `ACTUARIAL_REPORT` document.
- **Rounding.** A difference of up to TZS 1.00 per figure per group is rounding. Anything larger is an exception, and
  it blocks `lockPeriod` until it is accepted.
- **Accounts by group kind.**
  - Policy groups: 2110, 2111, 2112, 2113, 2121, 2123, 2124, 2132, 2143, 22xx (posting accounts), 4110–4150, 5130,
    5300, 5400, 5410, 5500, 7110, 7120.
  - Reinsurance groups: 1410, 1411, 1412, 1413, 1436, 6110, 6120, 6130.
  - Entries P-01…P-17 only.
- **Reconciliation figures.**
  - Policy groups: LRC = 21xx posting accounts except 2190; LIC = 22xx; CSM = 2112.
  - Reinsurance groups: ARC = 1410–1413; AIC = 1420; RI CSM = 1412.
- **RI group key:** `"RI-" + treatyId.toString().substring(0, 8).toUpperCase() + "-" + treaty.effectiveFrom.getYear()`
  (16 characters).
- **Results upload:** XLSX only, with sheets Header, Journal and Closing. This deviates from the spec's "or CSVs": the
  template is a workbook, and three linked CSVs invite a mismatched set. Extract downloads are XLSX, or CSV per sheet.
- **Document types** (document V9): `IFRS17_EXTRACT`, `IFRS17_RESULTS`, `ACTUARIAL_REPORT`. Owners are
  `extract:{id}` and `enginerun:{id}`, both under 50 characters.
- **Migrations:** finaccounting V15 (engine tables, the policy snapshot, the RI reference map) and document V9. Append
  them to the test lists with `node scripts/dev/append-test-migration.mjs <module>/<file>`.
- **Bean names** must be unique app-wide: `StatementStore` exists in reinsurance, and `Statements` in unitlinked.
  Prefix new beans with `Engine`.
- **Testing discipline.** `-Dtest` takes fully qualified names when a simple name could collide. Never run Maven
  while the dev backend runs from the same worktree. No Prettier.

---

### Task 1: Reinsurance groups — key, events, reference map

**Files:**
- Create: `reinsurance/domain/ReinsuranceGroupKey.java` (pure)
- Modify: `reinsurance/application/BordereauJob.java` (payload `reinsuranceGroup`)
- Modify: `reinsurance/application/ClaimEventListener.java` (`publishRecoveryCalculated` adds `reinsuranceGroup`)
- Modify: `reinsurance/application/ReinsuranceStatements.java` (`StatementApproved` adds `reinsuranceGroup`)
- Create: `backend/db-migrations/finaccounting/V15__engine_period_cycle.sql`. This is section A: table
  `reinsurance_group_ref`, plus a backfill from `reinsurance.bordereau`, `claim_recovery` and `statement`, guarded with
  `to_regclass`.
- Create: `finaccounting/application/engine/EngineReinsuranceGroups.java` (an `@Component` listener and a JDBC store)
- Test: `reinsurance/ReinsuranceGroupKeyTest.java`; `finaccounting/application/engine/EngineReinsuranceGroupsIntegrationTest.java`

**Interfaces:**
- `ReinsuranceGroupKey.of(UUID treatyId, LocalDate effectiveFrom) -> String`
- Table `finaccounting.reinsurance_group_ref(tenant_id, reference_type, reference, group_key, PRIMARY KEY (tenant_id,
  reference_type, reference))`, where reference_type is BORDEREAU, RECOVERY or STATEMENT.
- `EngineReinsuranceGroups.groupOf(tenantId, referenceType, reference) -> Optional<String>`; `groups(tenantId) -> Set<String>`.

- [ ] **Step 1: Failing tests.**
  - `ReinsuranceGroupKeyTest`: `of(UUID.fromString("ab12cd34-…"), 2026-09-28)` gives `"RI-AB12CD34-2026"`.
  - The integration test publishes `reinsurance.BordereauPosted`, `RecoveryCalculated` and `StatementApproved`, each
    with `reinsuranceGroup`. It asserts that `groupOf` returns the group for each reference.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
  - The key class.
  - The three publishers each add `"reinsuranceGroup", ReinsuranceGroupKey.of(treaty.getTreatyId(),
    treaty.getEffectiveFrom())`. In `ClaimEventListener`, load the treaty by `recovery.getTreatyId()`.
  - `EngineReinsuranceGroups` is an `@TransactionalEventListener(AFTER_COMMIT)` with `withTenant` and a
    `REQUIRES_NEW` transaction, as `PolicyClassificationEventListener` does. It upserts with
    `INSERT … ON CONFLICT DO NOTHING`.
  - V15 section A, as the backfill:

    ```sql
    INSERT … SELECT b.tenant_id, 'BORDEREAU', b.bordereau_id::text,
      'RI-' || upper(substr(t.treaty_id::text,1,8)) || '-' || extract(year from t.effective_from)::int
      FROM reinsurance.bordereau b JOIN reinsurance.reinsurance_treaty t USING (treaty_id)
    ```

    Do the same for `claim_recovery` (reference `recovery_id`) and `statement` (reference `statement_id`), with
    `ON CONFLICT DO NOTHING`, all inside `IF to_regclass('reinsurance.statement') IS NOT NULL`.
  - Enable RLS with a tenant policy, and grant SELECT and INSERT to app_role.
- [ ] **Step 4: Run them and see them pass.** Also run `BordereauIntegrationTest`, `StatementIntegrationTest` and
  `RecoveryEndToEndTest` (fully qualified), which assert the payloads.
- [ ] **Step 5: Commit** — `feat(ifrs17): I5a -- reinsurance groups per treaty and year, mapped from bordereau, recovery and statement`

### Task 2: Policy snapshot for the extract

**Files:**
- V15 section B: table `finaccounting.policy_snapshot(tenant_id, policy_number PK, issue_date, sum_assured, premium,
  premium_frequency, currency, status, updated_at)`, with a guarded backfill from `policy.policy`.
- Create: `finaccounting/application/engine/EnginePolicySnapshots.java`. It listens to:
  - `policy.PolicyActivated`: insert or update with the sum assured, premium, frequency, issue date and ACTIVE;
  - `policy.PolicyLapsed`, `PolicySurrendered`, `PolicyMatured`, `PolicyExpired` and `PolicyCancelledFreeLook`: the
    matching status;
  - `policy.PolicyReinstated`: ACTIVE;
  - `policy.PolicyMadePaidUp`: PAID_UP.
- Test: `finaccounting/application/engine/EnginePolicySnapshotsIntegrationTest.java`

**Interfaces:**
- `EnginePolicySnapshots.inForce(tenantId) -> List<Snapshot(policyNumber, issueDate, sumAssured, premium, frequency,
  currency, status)>`.
- In force means ACTIVE, REINSTATED or PAID_UP.

- [ ] **Steps 1–5:** TDD as in Task 1. The test publishes `PolicyActivated` and then `PolicyLapsed`, and asserts the
  status and `inForce` membership. Commit:
  `feat(ifrs17): I5a -- finaccounting's own policy snapshot (no policy::api: it would be a module cycle)`.

### Task 3: The extract (step 6)

**Files:**
- V15 section C: table `engine_extract(extract_id, tenant_id, period, number, created_by, created_at, document_ref,
  groups TEXT[], cash_flow_rows, balance_rows, policy_rows, UNIQUE (tenant_id, period, number))`.
- Document V9: the three new types, following the V8 pattern.
- Modify: `document/api/DocumentType.java`.
- Create: `finaccounting/domain/engine/ExtractBuilder.java` (pure: rows to the three sheets).
- Create: `finaccounting/domain/engine/ExtractWorkbook.java` (POI XLSX and CSV writers).
- Create: `finaccounting/application/engine/EngineExtracts.java` (queries, numbering, storage).
- Test: `ExtractBuilderTest` (pure); `finaccounting/application/engine/EngineExtractIntegrationTest.java`

**Queries** (period `p`, tenant `t`):
- **Cash flows, policy groups:**

  ```sql
  SELECT ifrs17_group, movement_type, account_code, direction, sum(amount)
    FROM finaccounting.gl_posting
   WHERE tenant_id = t AND period = p AND ifrs17_group IS NOT NULL AND ifrs17_group NOT LIKE 'RI-%'
     AND (account_code LIKE '21%' OR account_code LIKE '22%')
   GROUP BY 1, 2, 3, 4
  ```
- **Cash flows, RI groups:** postings on 1430, 1431, 1436 and 1420 joined to `reinsurance_group_ref` on
  `(reference_type, reference)`, grouped by `group_key`.
- **Balances:** the same two sources up to and including `p` (closing) and before `p` (opening), net Dr − Cr per
  account. Policy groups cover the 21xx and 22xx posting accounts; RI groups cover 14xx.
- **Policies:** `policy_snapshot` (in force) joined to `policy_classification`, for the group and model. VFA fund value
  is the net of 2131 by `policy_number` to the end of `p`; the investment component is the net of 2124 by
  `policy_number`.
- **Groups extracted:** the union of the groups on all three sheets.

**Interfaces:**
- `EngineExtracts.create(tenantId, period, by) -> ExtractView(extractId, period, number, groups, rowCounts, documentRef,
  createdBy, createdAt)`. It throws `EngineStateException` (409) when the period is not CLOSING or has unposted
  events.
- `EngineExtracts.render(extractId, format, sheet) -> byte[]`. Rendering is re-run from the stored rows, kept as JSONB
  in an `engine_extract_row` table (V15 section C), so a download is reproducible after later postings.

- [ ] **Steps:**
  - Failing tests:
    - `ExtractBuilderTest`: three sheets from given rows; groups union; headers exactly
      `group,movement,account,side,amount,currency`.
    - Integration:
      - post invoices for two classified policies plus a bordereau; start closing;
      - create an extract;
      - assert the cash-flow rows (including `RI-…`), the balances, the policies sheet, number 1, then number 2 on a
        second create;
      - refused while OPEN; refused with an unposted event.
  - Implement, then run until green. Commit: `feat(ifrs17): I5a -- the period extract for the engine (step 6)`.

### Task 4: Results template, parser and validator (pure)

**Files:**
- Create: `finaccounting/domain/engine/EngineResults.java` (records: `Header`, `Line(group, entry, account, side,
  amount, movement, note, row)` and `Closing(group, lrc, lic, csm, arc, aic, riCsm, row)`).
- Create: `finaccounting/domain/engine/EngineResultsParser.java` (POI XSSF; named sheets; every row's errors collected).
- Create: `finaccounting/domain/engine/EngineResultsValidator.java`.
- Create: `finaccounting/domain/engine/EngineResultsTemplate.java` (writes the blank template with a worked example).
- Test: `finaccounting/EngineResultsParserTest.java`, `finaccounting/EngineResultsValidatorTest.java`

**Validator input:**
- the parsed results;
- the extract's groups;
- the period status;
- known run references;
- the chart's posting accounts;
- per group, whether it is a policy or a reinsurance group (prefix `RI-`).

Output: `List<String>` errors. Each error names the sheet and row, for example
`Journal row 7: TERM-GMM-2026-REM P-08 account 4110 is not one P-entries post for a policy group`.

**Rules:**
- period CLOSING;
- extract named;
- reference new;
- every group in the extract;
- every policy group of the extract has a Closing row;
- entry in P-01…P-17;
- account in the set for the group's kind;
- side DR or CR;
- amount > 0 with at most 2 decimals;
- each group balanced;
- Closing columns filled to match the group kind.

- [ ] **Steps:** failing tests first — a valid workbook built in the test with POI; then one test per rule, each
  asserting its exact message; then "every error at once" with five faults in one file. Implement, then run until
  green. Commit: `feat(ifrs17): I5a -- the engine results template, parser and validator`.

### Task 5: Engine runs — upload, approval, posting through 9160, replacement

**Files:**
- V15 section D: tables `engine_run(run_id, tenant_id, period, extract_id, engine_reference UNIQUE per tenant,
  engine_name, status CHECK (UPLOADED, VALIDATED, REJECTED, POSTED, REPLACED), errors TEXT[], results_document_ref,
  uploaded_by, uploaded_at, decided_by, decided_at, decision_reason, signoff_reference, report_document_ref,
  replaces_run_id, version, CHECK (decided_by IS NULL OR decided_by <> uploaded_by))`.
  - `engine_run_line(run_id, row_no, group_key, entry, account_code, direction, amount, movement, note)`.
  - `engine_run_closing(run_id, group_key, lrc, lic, csm, arc, aic, ri_csm)`.
  - Unique index: one POSTED run per (tenant, period).
- Create: `finaccounting/api/EngineRunApi.java` and its view records.
- Create: `finaccounting/application/engine/EngineRunStore.java` and `EngineRuns.java` (the service).
- Create: `finaccounting/domain/engine/EngineRunJournals.java` (pure: lines to one balanced journal per group, each line
  counter-posted to 9160).
- Test: `EngineRunJournalsTest` (pure); `finaccounting/application/engine/EngineRunIntegrationTest.java`

**Interfaces:**
- `upload(bytes, fileName, by)` stores the file as IFRS17_RESULTS, then parses and validates it. It returns VALIDATED,
  or REJECTED with the errors, and never throws for content.
- `approve(runId, signOffReference, reportRef, approver)` checks the run is VALIDATED and the approver is not the
  uploader, and validates again. Then, in one transaction:
  - if a run is POSTED for the period, post a full reversal of each of its journals
    (`JournalEntry … .withSource(ENGINE_RUN).fromEngineRun(newRunId).reversing(old)`, legs swapped) and mark it
    REPLACED;
  - post one ENGINE_RUN journal per group (source event `ifrs17.EngineRun`, source ref `{runId}:{group}`, dimensions:
    group, movement, reference type `ENGINE_RUN`, reference the run id), with the 9160 counter-legs;
  - assert the run's 9160 net is zero (sum over its journals), otherwise throw (rollback);
  - set POSTED.
- `reject(runId, reason, by)`.
- `list(period, status)`, `get(runId)`.
- `EngineRunJournals.of(tenantId, runId, period, lines) -> List<JournalEntry>`. For each line it emits the engine leg
  and a 9160 leg on the opposite side, for the same amount and group.

- [ ] **Steps:**
  - Failing tests:
    - Pure: per-group journals balance; 9160 nets to zero per journal.
    - Integration:
      - an upload with errors is REJECTED and keeps its errors;
      - a fixed upload is VALIDATED;
      - approval by the uploader is refused;
      - approval without a sign-off reference is refused;
      - an approval posts ENGINE_RUN journals with `engine_run_id`, the 9160 net is 0, and every line has its group;
      - a second run for the period, approved, reverses the first in full: the net of all its accounts is 0 before
        the new lines, and the old run is REPLACED;
      - a LOCKED period refuses the approval;
      - a second tenant sees nothing.
  - Implement, then run until green. Commit:
    `feat(ifrs17): I5a -- engine runs: upload, maker-checker approval, posting through 9160, replacement`.

### Task 6: Reconciliation, exceptions and the lock gate

**Files:**
- V15 section E: table `engine_reconciliation(run_id, group_key, figure, ledger, engine, difference, status CHECK
  (AGREED, EXCEPTION, EXPLAINED, ACCEPTED), explanation, explained_by, accepted_by, CHECK (accepted_by IS NULL OR
  accepted_by <> explained_by))`.
- Create: `finaccounting/domain/engine/EngineReconciler.java` (pure: ledger and engine figures in, rows out, using the
  TZS 1.00 rounding).
- Modify: `EngineRuns.approve` to reconcile right after posting.
- Modify: `AccountingPeriods.lock` to refuse with `"Engine run <ref>: <group> <figure> differs by <diff> TZS; explain
  and accept it, or post a replacement run"`.
- Test: `EngineReconcilerTest`; extend `EngineRunIntegrationTest`.

**Ledger figures** per group, at the end of the period: sum Dr − Cr, sign-flipped to credit-positive for liabilities.
- Policy groups use `ifrs17_group = key`.
- RI groups use `ifrs17_group = key` (engine runs) OR `(reference_type, reference)` in `reinsurance_group_ref` for the
  key.

**Interfaces:** `explain(runId, group, figure, text, by)`, `accept(runId, group, figure, by)` (not the explainer).

- [ ] **Steps:**
  - Failing tests:
    - Pure: a difference of 0.99 is AGREED; 1.01 is an EXCEPTION.
    - Integration:
      - results whose closing CSM is 5.00 above the ledger: lock is refused with that message;
      - explain, then accept by the same person: refused; accept by a second person: lock succeeds;
      - matching results lock first time.
  - Implement, then run until green. Commit: `feat(ifrs17): I5a -- reconcile ledger to engine; an unexplained difference blocks the lock`.

### Task 7: HTTP, OpenAPI, contract

**Files:**
- Create: `finaccounting/infrastructure/EngineController.java`.
- Modify: `FinaccountingExceptionHandler` (`ENGINE_STATE` 409, `ENGINE_RUN_NOT_FOUND` 404).
- Modify: `finaccounting/package-info.java` if `document::api` is not already there.
- Modify: `openapi-finaccounting.yaml`.
- Test: `FinaccountingContractTest` (new method); `EngineControllerTest` (owner lengths).

**Endpoints:**
- Extracts:
  - `POST /ifrs17/periods/{period}/extracts`
  - `GET /ifrs17/periods/{period}/extracts`
  - `GET /ifrs17/extracts/{id}`
  - `GET /ifrs17/extracts/{id}/download?format=xlsx|csv&sheet=cash-flows|balances|policies` (binary)
- Template: `GET /ifrs17/results-template` (xlsx)
- Runs:
  - `POST /ifrs17/engine-runs` (multipart `file`)
  - `GET /ifrs17/engine-runs?period=&status=`
  - `GET /ifrs17/engine-runs/{id}`
  - `POST …/{id}/approval` (multipart: `signOffReference` and `report`; APPROVER)
  - `POST …/{id}/rejection` (`{reason}`; APPROVER)
- Exceptions:
  - `POST …/{id}/exceptions/{group}/{figure}/explanation` (`{text}`)
  - `POST …/{id}/exceptions/{group}/{figure}/acceptance` (APPROVER)

Roles: everything else needs FINANCE (FINANCE_OFFICER or ADMIN); reads allow FINANCE or APPROVER.

**Approval order:** check the run is editable, then store the report, then approve.

- [ ] **Steps:**
  - Failing contract test:
    - an approval by a finance officer without FINANCE_APPROVER gets 403;
    - an approval by an approver gets 200 and is valid against the spec;
    - the run's response includes `reconciliation`;
    - an extract created while the period is OPEN gets 409 `ENGINE_STATE`.
  - Implement, then run until green, including `ModularityTests`. Commit:
    `feat(ifrs17): I5a -- engine cycle endpoints and OpenAPI`.

### Task 8: Console — Finance → "IFRS 17 engine"

**Files:**
- `frontend/src/api/finaccounting.ts` (engine functions)
- `frontend/src/api/types.ts` (`EngineRunView`, `ExtractView`, … prefixed `Engine*`)
- `frontend/src/store/engineStore.ts`
- `frontend/src/features/finance/enginePeriod.ts` with its test (pure helpers: reconciliation status labels, the
  "can approve" rule)
- `frontend/src/features/finance/EnginePage.tsx` (a period picker, then extracts, runs and reconciliation)
- `frontend/src/features/finance/EngineRunPage.tsx` (the per-group journal, errors, approval form, exceptions)
- `frontend/src/lazyPages.ts`, `frontend/src/screens.tsx` (finance group, "IFRS 17 engine")

**Accessible names for e2e:**
- buttons: "Create extract", "Download extract", "Upload results", "Approve and post", "Reject", "Explain",
  "Accept explanation";
- inputs: "Results file" and "Actuary's report";
- the "Sign-off reference" label;
- tables: "Engine runs" and "Reconciliation".

- [ ] **Steps:** pure helper test first, then the pages. Verify with
  `npm run -s generate:api && npm run -s typecheck && npm run -s lint && npx vitest run src/features/finance src/store`.
  Commit: `feat(console): I5a -- the IFRS 17 engine period page`.

### Task 9: e2e, dev stack, gate, merge

- [ ] **Spec** `frontend/e2e/staff-ifrs17-engine.spec.ts`:
  - finance opens a period, starts closing it (Accounting periods page) and creates an extract;
  - it downloads the template, builds a results workbook in the test (`exceljs` is not a dependency: use the
    template from the API, fill it with SheetJS if present, otherwise generate the XLSX in the backend fixture via
    `GET /ifrs17/extracts/{id}/download` plus a test-only builder). The plan's fallback is a small results XLSX checked
    into `frontend/e2e/fixtures/`, produced once by `EngineResultsTemplate` and kept in step with the groups the spec
    creates in its own fresh period;
  - it uploads, and the approver approves with a sign-off reference and a report PDF;
  - the run shows Posted and the reconciliation shows Agreed.
  
  Choose the period with care: a past dev period with no postings, made CLOSING by the spec, so dev months stay
  untouched. Decide which while writing the spec, checking `accounting_period` in dev.
- [ ] **Dev:** apply finaccounting V15 and document V9. Restart the backend and Vite from `.worktrees/ifrs17-i5a`.
- [ ] **Gate:**
  - affected classes (changed tests, plus every class in `finaccounting/` and `reinsurance/`) with
    `./mvnw -B -o clean test` (dev backend stopped);
  - the console checks;
  - the full e2e suite.
- [ ] **Merge:**

  ```bash
  git merge --no-ff ifrs17-i5a -m "Merge IFRS 17 I5a: the engine period cycle"
  git push origin main && git push origin ifrs17-i5a
  ```
  
  Then update memory and point to I5b (P-19).

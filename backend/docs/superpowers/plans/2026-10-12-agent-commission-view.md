# Agent Commission View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This user's brief overrides that default: implement directly in this session, no subagents, one solo whole-branch review at the end.**

**Goal:** A "My commission" view — totals, month-by-month statements and per-line detail with client, product, type, amount and date — for an agent, a supervisor viewing their downline, and staff on the agent detail page.

**Architecture:** Distribution stores each policy's holder on its own `policy_projection` (V7) and enriches accrual lines with client name, product name and a Dar es Salaam accrual date through `party::api`/`product::api`. A new agents-realm `GET /agents/me/team` lists the caller and their downline. The frontend derives the headline totals from the existing statements list and renders one shared `AgentCommission` component on a new agent screen and on the staff `AgentDetailPage`.

**Tech Stack:** Spring Boot 3 + Spring Modulith, JPA, Postgres 16 (Testcontainers), MockMvc + OpenAPI validator; React + TypeScript, zustand, react-router, vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-12-agent-commission-view-design.md`

## Global Constraints

- Worktree: `.worktrees/agent-commission`, branch `agent-commission`. All commands run from inside it.
- Access rules are enforced server-side. An agent reads only themselves and their downline; a downline agent never sees a supervisor's figures; a statement paired with the wrong agent stays 404 (`listAccruals` IDOR guard untouched).
- Cross-module reads only through `party::api` and `product::api` (already in distribution's `allowedDependencies`). Distribution must never call `policy`.
- "This month"/"this year"/`accruedOn` use the civil day in `Africa/Dar_es_Salaam`, never UTC or the browser zone.
- Totals are net of clawbacks. `PAYOUT_FAILED` counts as awaiting payout. "Paid out" is all time. Currencies are never mixed.
- Money displays through `formatMoney` and the `Money` type; frontend sums use BigInt cents, never JS numbers.
- No OpenAPI field gets a `default:` (openapi-typescript would make it required). `frontend/src/types/api/` is generated and git-ignored — regenerate, never hand-edit.
- Lint bans calling setState directly inside a `useEffect` body. Reset per-agent state by remounting with `key`.
- Follow DESIGN.md and existing components (`PageHeader`, `Panel`, `Field`, `DataTable`, `StatusBadge`, `EmptyState`). Restyle nothing else. No change to `components/`.
- Never run Prettier. Never run Maven in Docker; host `./mvnw` only. Run `./mvnw clean test-compile` after any signature change. Stop the dev backend before any Maven `clean`.
- Do not touch payout logic, commission calculation/accrual rules, Keycloak realm files, the login theme, other modules' main code, `.env`, package or lock files.
- Scope additions agreed with the spec: the one-line V7 entry in the 13 test classes that list distribution migrations (6 live outside `distribution/`), and one entry in `frontend/src/lazyPages.ts` for the new page.
- Do not run the full backend suite (~80 min) or the full e2e suite without asking.
- After each task print: `✅ <what was completed>` plus the command and the result that proves it, including the number of test classes run.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## File Structure

Backend (`backend/`):
- Create `db-migrations/distribution/V7__projection_policyholder.sql` — column + guarded backfill.
- Modify `src/main/java/tz/co/nlolo/lifeplatform/distribution/domain/PolicyProjection.java` — `policyholderPartyId`.
- Modify `.../distribution/domain/CommissionAccrual.java` — `civilDayOf`, `getAccruedOn`.
- Modify `.../distribution/application/PolicyEventListener.java` — store the holder.
- Modify `.../distribution/infrastructure/PolicyProjectionRepository.java` — batch lookup.
- Modify `.../distribution/api/CommissionAccrualView.java` — five fields.
- Create `.../distribution/api/AgentTeamMemberView.java`.
- Modify `.../distribution/api/DistributionApi.java` — `listTeam`.
- Modify `.../distribution/application/DistributionApiImpl.java` — enrichment, `listTeam`.
- Modify `.../distribution/infrastructure/CommissionAccrualResponseDto.java` — five fields.
- Create `.../distribution/infrastructure/AgentTeamMemberDto.java`.
- Modify `.../distribution/infrastructure/AgentController.java` — `GET /agents/me/team`.
- Modify `api/openapi/openapi-distribution.yaml`.
- Tests: create `src/test/java/tz/co/nlolo/lifeplatform/distribution/CommissionAccrualCivilDayTest.java`; modify `ClawbackIntegrationTest.java`, `DistributionContractTest.java`; add the V7 line to the 13 migration-listing classes.

Frontend (`frontend/`):
- Modify `src/api/distribution.ts` — `AgentTeamMember`, `getOwnTeam`.
- Modify `src/store/distributionStore.ts` — `ownTeam`.
- Create `src/features/distribution/commissionView.ts` + `commissionView.test.ts` — pure functions.
- Create `src/features/distribution/PayoutForm.tsx` — moved verbatim out of `CommissionStatementsPanel.tsx`.
- Create `src/features/distribution/AgentCommission.tsx` — the shared view.
- Delete `src/features/distribution/CommissionStatementsPanel.tsx`.
- Create `src/features/distribution/MyCommissionPage.tsx`.
- Modify `AgentDetailPage.tsx`, `AgentProfilePage.tsx`, `src/screens.tsx`, `src/lazyPages.ts`.
- e2e: create `agents-my-commission.spec.ts`, `agents-commission-team-picker.spec.ts`, `staff-agent-commission.spec.ts`; modify `agents-my-profile.spec.ts`, `staff-distribution.spec.ts`.

---

### Task 1: Store each policy's holder on distribution's projection (V7)

**Files:**
- Create: `backend/db-migrations/distribution/V7__projection_policyholder.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/domain/PolicyProjection.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/application/PolicyEventListener.java:164-181`
- Modify (one line each): the 13 test classes listing `db-migrations/distribution/V6__commission_withholding.sql`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/ClawbackIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyProjection(UUID tenantId, String policyNumber, UUID agentId, UUID productId, BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate, UUID policyholderPartyId)` and `UUID PolicyProjection.getPolicyholderPartyId()`.

- [ ] **Step 1: Write the migration**

`backend/db-migrations/distribution/V7__projection_policyholder.sql`:

```sql
-- db-migrations/distribution/V7__projection_policyholder.sql
-- Agent commission view (spec 2026-10-12, D1): who holds each policy, so a commission line can name its client.
-- distribution may not call policy (policy depends on distribution::api), so the holder is kept on distribution's own
-- projection, taken from policy.PolicyActivated, which has always carried policyholderPartyId.
-- Re-runnable: migrate.sh is not idempotent, so neither statement fails or rewrites anything on a second run.

ALTER TABLE distribution.policy_projection ADD COLUMN IF NOT EXISTS policyholder_party_id UUID;

-- Backfill from policy's own table. Guarded because a schema built without policy (two distribution-only test classes)
-- has nothing to read; migrate.sh applies policy before distribution, so every real database is backfilled.
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NOT NULL THEN
        UPDATE distribution.policy_projection pp
           SET policyholder_party_id = p.policyholder_party_id
          FROM policy.policy p
         WHERE p.tenant_id = pp.tenant_id
           AND p.policy_number = pp.policy_number
           AND pp.policyholder_party_id IS NULL;
    END IF;
END $$;
```

- [ ] **Step 2: Add V7 to every class that lists distribution's migrations**

Run: `grep -rl "db-migrations/distribution/V6__commission_withholding.sql" backend/src/test` — expect these 13:
`AgentBookOfBusinessScopingTest`, `AppRolePrivilegesIntegrationTest`, `RowLevelSecurityIntegrationTest`, `distribution/ClawbackIntegrationTest`, `distribution/CommissionAccrualEndToEndTest`, `distribution/CommissionCloseSweepPsqlTest`, `distribution/CommissionPayoutEndToEndTest`, `distribution/CreditLifeCommissionTest`, `distribution/DistributionApiIntegrationTest`, `distribution/DistributionContractTest`, `finaccounting/ClaimAndCommissionPostingEndToEndTest`, `policy/PolicyApiIntegrationTest`, `policy/PolicyCustomerScopingTest`.

In each, with the Edit tool, directly after the line `"db-migrations/distribution/V6__commission_withholding.sql",` insert (same indentation):

```java
            "db-migrations/distribution/V7__projection_policyholder.sql",
```

If V6 is the last element of its array (line ends `.sql");` or `.sql"`), add the comma to V6 and put V7 last with the original terminator. Re-run the grep for V7 and confirm 13 files.

- [ ] **Step 3: Write the failing test**

Append to `ClawbackIntegrationTest` (it runs the app as `app_role`, so the read goes through RLS):

```java
    /** Agent commission view (V7): the projection keeps who holds the policy, from PolicyActivated, and V7 backfills
     * a row written before the column existed -- re-applied here against a row whose holder has been cleared, which
     * is exactly the state every pre-V7 row is in. */
    @Test
    void theProjectionRecordsThePolicyholderAndV7BackfillsARowThatLacksIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "HOLDER");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "HOLDER-01");

        TenantContext.set(tenantId);
        UUID holder = policyApi.getPolicy(policyNumber).policyholderPartyId();
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).orElseThrow()
            .getPolicyholderPartyId()).isEqualTo(holder);

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement clear = connection.prepareStatement(
                 "UPDATE distribution.policy_projection SET policyholder_party_id = NULL "
                     + "WHERE tenant_id = ? AND policy_number = ?")) {
            clear.setObject(1, tenantId);
            clear.setString(2, policyNumber);
            assertThat(clear.executeUpdate()).isEqualTo(1);
        }
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).orElseThrow()
            .getPolicyholderPartyId()).isNull();

        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/distribution/V7__projection_policyholder.sql");

        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).orElseThrow()
            .getPolicyholderPartyId()).isEqualTo(holder);
    }
```

- [ ] **Step 4: Run it to see it fail**

Run (from `backend/`): `./mvnw -q test-compile` — Expected: compilation FAILS with `cannot find symbol ... getPolicyholderPartyId()`.

- [ ] **Step 5: Map the column and set it at activation**

In `PolicyProjection.java`, after the `lapsedAt` field:

```java
    /** Who holds the policy (V7) -- for a credit-life or group scheme, the lender or association. Null only on a
     * row written before V7 that the backfill could not match. */
    @Column(name = "policyholder_party_id")
    private UUID policyholderPartyId;
```

Replace the public constructor's signature and add the assignment (keep the existing body lines):

```java
    public PolicyProjection(UUID tenantId, String policyNumber, UUID agentId, UUID productId,
                             BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate,
                             UUID policyholderPartyId) {
        // ...existing assignments unchanged...
        this.policyholderPartyId = policyholderPartyId;
    }
```

Add beside the other getters:

```java
    public UUID getPolicyholderPartyId() { return policyholderPartyId; }
```

In `PolicyEventListener.handlePolicyActivated`, after `UUID agentOfRecordId = (UUID) payload.get("agentOfRecordId");`:

```java
        UUID policyholderPartyId = (UUID) payload.get("policyholderPartyId");
```

and change the save to:

```java
            policyProjectionRepository.save(new PolicyProjection(tenantId, policyNumber, agentOfRecordId,
                productId, premiumAmount, premiumCurrency, issueDate, policyholderPartyId));
```

- [ ] **Step 6: Compile clean and run the test**

Stop the dev backend if it is running. Run: `./mvnw -q clean test-compile` — Expected: BUILD SUCCESS, no output.
Run: `./mvnw test -Dtest=ClawbackIntegrationTest` — Expected: `Tests run: 6, Failures: 0, Errors: 0` for `ClawbackIntegrationTest` (5 existing + 1), `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add backend/db-migrations/distribution/V7__projection_policyholder.sql backend/src/main/java/tz/co/nlolo/lifeplatform/distribution backend/src/test/java
git commit -m "feat(distribution): keep each policy's holder on the commission projection (V7)"
```

---

### Task 2: Client, product and accrual date on every commission line

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/domain/CommissionAccrual.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/api/CommissionAccrualView.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/PolicyProjectionRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/application/DistributionApiImpl.java` (constructor, `listAccrualsForPolicy` ~403, `listAccruals` ~617, `toAccrualView` ~724)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/CommissionAccrualResponseDto.java`
- Modify: `backend/api/openapi/openapi-distribution.yaml` (`CommissionAccrualView` schema)
- Create test: `backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/CommissionAccrualCivilDayTest.java`
- Modify tests: `ClawbackIntegrationTest.java`, `DistributionContractTest.java`

**Interfaces:**
- Consumes: `PolicyProjection.getPolicyholderPartyId()` (Task 1).
- Produces: `CommissionAccrualView(UUID accrualId, UUID agentId, UUID statementId, String policyNumber, TierType tierType, BigDecimal amount, String currency, String period, String sourceRef, UUID reversesAccrualId, UUID clientPartyId, String clientName, UUID productId, String productName, LocalDate accruedOn)`; JSON fields `clientPartyId`, `clientName`, `productId`, `productName` (omitted when null), `accruedOn` (`YYYY-MM-DD`, always present).

- [ ] **Step 1: Write the failing unit test for the civil day**

`backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/CommissionAccrualCivilDayTest.java`:

```java
package tz.co.nlolo.lifeplatform.distribution;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** A commission line is dated on the Dar es Salaam day it was booked (UTC+3, no DST), never the UTC one. */
class CommissionAccrualCivilDayTest {

    @Test
    void anEarlyMorningLineInDarEsSalaamTakesTheDarDateNotTheUtcOne() {
        // 01:30 in Dar es Salaam is 22:30 UTC the evening before.
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-11T22:30:00Z")))
            .isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void theDarDayEndsAtTwentyOneHundredUtc() {
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-12T20:59:59Z")))
            .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-12T21:00:00Z")))
            .isEqualTo(LocalDate.of(2026, 10, 13));
    }
}
```

- [ ] **Step 2: Write the failing integration assertions**

In `DistributionContractTest.listAccrualsReturns200WithTheLineItems`, append to the `mockMvc.perform(...)` chain:

```java
            .andExpect(jsonPath("$[0].clientName").value("Distribution Contract Holder ACCR-200"))
            .andExpect(jsonPath("$[0].clientPartyId").isString())
            .andExpect(jsonPath("$[0].productName").value("Distribution Contract Product DIST-CT-ACCR"))
            .andExpect(jsonPath("$[0].productId").value(productId.toString()))
            .andExpect(jsonPath("$[0].accruedOn").value(
                LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toString()));
```

Append to `ClawbackIntegrationTest`:

```java
    /** Agent commission view: a clawback line names the same client and product as the line it reverses, carries a
     * negative amount, and is dated -- read through the API the screen uses, not the repository. */
    @Test
    void aClawbackLineCarriesTheClientProductAndDateOfTheLineItReverses() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "ENRICH");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "ENRICH-01");
        policyApi.lapsePolicy(policyNumber, "test-staff");

        TenantContext.set(tenantId);
        UUID statementId = originalFirstYearAccrual(tenantId, policyNumber).getStatementId();
        List<CommissionAccrualView> lines = distributionApi.listAccruals(statementId);

        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.clientName()).isEqualTo("Clawback IT Policyholder ENRICH-01");
            assertThat(line.productName()).isEqualTo("Clawback IT Product ENRICH");
            assertThat(line.productId()).isEqualTo(fixture.productId());
            assertThat(line.accruedOn()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")));
        });
        assertThat(lines).filteredOn(line -> line.reversesAccrualId() != null).singleElement()
            .satisfies(clawback -> assertThat(clawback.amount()).isEqualByComparingTo("-10000.00"));
    }
```

In `ClawbackIntegrationTest.aLapseForAPolicyWithNoProjectionRowIsASilentNoOp`, append at the end of the method:

```java
        // With no projection row there is no client or product to name -- nulls, not an error. The date still stands.
        assertThat(distributionApi.listAccrualsForPolicy(policyNumber)).singleElement().satisfies(line -> {
            assertThat(line.clientPartyId()).isNull();
            assertThat(line.clientName()).isNull();
            assertThat(line.productId()).isNull();
            assertThat(line.productName()).isNull();
            assertThat(line.accruedOn()).isNotNull();
        });
```

Add `import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;` to `ClawbackIntegrationTest`.

- [ ] **Step 3: Run to see it fail**

Run: `./mvnw -q test-compile` — Expected: FAIL, `cannot find symbol` for `civilDayOf`, `clientName()`, `productName()`.

- [ ] **Step 4: Implement**

`CommissionAccrual.java` — add imports `java.time.LocalDate` and `java.time.ZoneId`, then beside `getCreatedAt()`:

```java
    /** The civil zone every commission date on this platform is read in. */
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    /** The Dar es Salaam day an instant falls on. Static so the boundary can be tested without a persisted row. */
    public static LocalDate civilDayOf(Instant instant) {
        return LocalDate.ofInstant(instant, CIVIL_ZONE);
    }

    /** The day this line was booked, in Dar es Salaam -- not the UTC day {@link #getCreatedAt()} would give. */
    public LocalDate getAccruedOn() { return civilDayOf(createdAt); }
```

`CommissionAccrualView.java` (keep its imports; add `java.time.LocalDate`):

```java
/** Read view of {@code distribution.domain.CommissionAccrual} -- the per-event line items a
 * {@link CommissionStatementView} totals. {@code reversesAccrualId} is non-null exactly for a
 * clawback row (M7 user decision 2).
 *
 * <p>Agent commission view: {@code clientPartyId}/{@code clientName} name the policy's holder and
 * {@code productId}/{@code productName} its product, all from distribution's own policy projection --
 * null when no projection row exists for the policy, or a name cannot be resolved. {@code accruedOn}
 * is the Dar es Salaam day the line was booked. */
public record CommissionAccrualView(UUID accrualId, UUID agentId, UUID statementId, String policyNumber,
                                     TierType tierType, BigDecimal amount, String currency, String period,
                                     String sourceRef, UUID reversesAccrualId,
                                     UUID clientPartyId, String clientName, UUID productId, String productName,
                                     LocalDate accruedOn) {}
```

`PolicyProjectionRepository.java` — add (import `java.util.Collection`, `java.util.List`):

```java
    List<PolicyProjection> findByTenantIdAndPolicyNumberIn(UUID tenantId, Collection<String> policyNumbers);
```

`DistributionApiImpl.java`:
- Add a constructor parameter `PolicyProjectionRepository policyProjectionRepository` (directly after `CommissionAccrualRepository commissionAccrualRepository`), a `private final PolicyProjectionRepository policyProjectionRepository;` field, and its assignment. Imports: `tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjection`, `tz.co.nlolo.lifeplatform.distribution.infrastructure.PolicyProjectionRepository`, `tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException`, `tz.co.nlolo.lifeplatform.product.api.ProductNotFoundException`, `java.util.HashMap`, `java.util.Map`, `java.util.Optional`, `java.util.Set`, `java.util.function.Function`, `java.util.stream.Collectors`.
- `listAccrualsForPolicy` body becomes:

```java
        UUID tenantId = TenantContext.get();
        return toAccrualViews(tenantId,
            commissionAccrualRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber));
```

- `listAccruals`: replace the final `return commissionAccrualRepository.findByStatementIdAndTenantId(...).stream().map(this::toAccrualView).toList();` with:

```java
        return toAccrualViews(tenantId, commissionAccrualRepository.findByStatementIdAndTenantId(statementId, tenantId));
```

- Replace `toAccrualView` with:

```java
    /**
     * Agent commission view: each line names its client and product. One projection query for the call's policy
     * numbers, then one party read per distinct holder and one product read per distinct product -- a month of lines
     * on three policies costs three party reads, not one per line. Names come through party::api and product::api;
     * distribution never reads policy.
     */
    private List<CommissionAccrualView> toAccrualViews(UUID tenantId, List<CommissionAccrual> accruals) {
        Set<String> policyNumbers = accruals.stream().map(CommissionAccrual::getPolicyNumber).collect(Collectors.toSet());
        Map<String, PolicyProjection> projections = policyNumbers.isEmpty() ? Map.of()
            : policyProjectionRepository.findByTenantIdAndPolicyNumberIn(tenantId, policyNumbers).stream()
                .collect(Collectors.toMap(PolicyProjection::getPolicyNumber, Function.identity()));
        Map<UUID, Optional<String>> clientNames = new HashMap<>();
        Map<UUID, Optional<String>> productNames = new HashMap<>();
        return accruals.stream().map(accrual -> {
            PolicyProjection projection = projections.get(accrual.getPolicyNumber());
            UUID clientPartyId = projection == null ? null : projection.getPolicyholderPartyId();
            UUID productId = projection == null ? null : projection.getProductId();
            String clientName = clientPartyId == null ? null
                : clientNames.computeIfAbsent(clientPartyId, this::partyName).orElse(null);
            String productName = productId == null ? null
                : productNames.computeIfAbsent(productId, this::productName).orElse(null);
            return new CommissionAccrualView(accrual.getAccrualId(), accrual.getAgentId(), accrual.getStatementId(),
                accrual.getPolicyNumber(), accrual.getTierType(), accrual.getAmount(), accrual.getCurrency(),
                accrual.getPeriod(), accrual.getSourceRef(), accrual.getReversesAccrualId(),
                clientPartyId, clientName, productId, productName, accrual.getAccruedOn());
        }).toList();
    }

    /** Empty, not an exception: a line whose holder cannot be read still shows its amount. */
    private Optional<String> partyName(UUID partyId) {
        try {
            return Optional.ofNullable(partyApi.getParty(partyId).displayName());
        } catch (PartyNotFoundException e) {
            return Optional.empty();
        }
    }

    private Optional<String> productName(UUID productId) {
        try {
            return Optional.ofNullable(productApi.getProduct(productId).productName());
        } catch (ProductNotFoundException e) {
            return Optional.empty();
        }
    }
```

If `grep -n "toAccrualView" DistributionApiImpl.java` shows any other caller, route it through `toAccrualViews(tenantId, List.of(accrual)).get(0)`.

`CommissionAccrualResponseDto.java` (add `java.time.LocalDate` import):

```java
/** {@code reversesAccrualId} is non-null exactly for a clawback row, and such a row's
 * {@code amount} is negative -- see {@link MoneyDto} on why this module's money DTO carries no
 * positivity constraint. Client and product are null when the policy has no projection row. */
public record CommissionAccrualResponseDto(UUID accrualId, UUID agentId, UUID statementId, String policyNumber,
                                            TierType tierType, MoneyDto amount, String period,
                                            String sourceRef, UUID reversesAccrualId,
                                            UUID clientPartyId, String clientName, UUID productId, String productName,
                                            LocalDate accruedOn) {

    public static CommissionAccrualResponseDto from(CommissionAccrualView view) {
        return new CommissionAccrualResponseDto(view.accrualId(), view.agentId(), view.statementId(),
            view.policyNumber(), view.tierType(), new MoneyDto(view.amount().toPlainString(), view.currency()),
            view.period(), view.sourceRef(), view.reversesAccrualId(),
            view.clientPartyId(), view.clientName(), view.productId(), view.productName(), view.accruedOn());
    }
}
```

`openapi-distribution.yaml`, schema `CommissionAccrualView`: change `required` to
`[accrualId, agentId, statementId, policyNumber, tierType, amount, period, sourceRef, accruedOn]` and append after `reversesAccrualId`:

```yaml
        clientPartyId:
          type: [string, "null"]
          format: uuid
          description: The holder of the policy this line was earned on. Null only when distribution never saw the policy issued.
        clientName:
          type: [string, "null"]
          description: The holder's display name -- for a credit-life or group scheme, the lender or association. Null when it cannot be resolved.
        productId:
          type: [string, "null"]
          format: uuid
        productName:
          type: [string, "null"]
        accruedOn:
          type: string
          format: date
          description: The day this line was booked, in Africa/Dar_es_Salaam.
```

- [ ] **Step 5: Compile clean and run the three classes**

Run: `./mvnw -q clean test-compile` — Expected: BUILD SUCCESS.
Run: `./mvnw test -Dtest='CommissionAccrualCivilDayTest,ClawbackIntegrationTest,DistributionContractTest'` — Expected: all three classes reported in the surefire summary, `Failures: 0, Errors: 0`, `BUILD SUCCESS`. Confirm `CommissionAccrualCivilDayTest` shows `Tests run: 2` and `ClawbackIntegrationTest` `Tests run: 7`.

- [ ] **Step 6: Commit**

```bash
git add backend/api/openapi/openapi-distribution.yaml backend/src
git commit -m "feat(distribution): name the client, product and Dar es Salaam date on every commission line"
```

---

### Task 3: `GET /agents/me/team`, and the hierarchy rules on commission reads

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/api/AgentTeamMemberView.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/api/DistributionApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/application/DistributionApiImpl.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/AgentTeamMemberDto.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/distribution/infrastructure/AgentController.java` (after `getOwnAgentProfile`)
- Modify: `backend/api/openapi/openapi-distribution.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/DistributionContractTest.java`

**Interfaces:**
- Produces: `DistributionApi.listTeam(UUID partyId): List<AgentTeamMemberView>`; `AgentTeamMemberView(UUID agentId, String licenseNumber, LicenseStatus licenseStatus, UUID hierarchyParentId, String displayName)`; HTTP `GET /agents/me/team` → `AgentTeamMember[]` with `agentId`, `licenseNumber`, `licenseStatus`, `hierarchyParentId?`, `displayName?`.

- [ ] **Step 1: Write the failing contract tests**

Add to `DistributionContractTest`, in a new section after the `GET /agents/me` tests:

```java
    // ============================================================================================
    // GET /agents/me/team, and the hierarchy on commission reads
    // ============================================================================================

    @Test
    void getOwnTeamListsTheCallerFirstThenItsWholeDownlineAndNobodyElse() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent supervisor = onboardAgent(tenantId, "TEAM-SUP", null);
        Agent direct = onboardAgent(tenantId, "TEAM-DIRECT", supervisor.agentId());
        Agent indirect = onboardAgent(tenantId, "TEAM-INDIRECT", direct.agentId());
        Agent outsider = onboardAgent(tenantId, "TEAM-OUTSIDER", null);

        mockMvc.perform(get("/agents/me/team").with(agentOf(tenantId, supervisor.partyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].agentId").value(supervisor.agentId().toString()))
            .andExpect(jsonPath("$[0].displayName").value("Distribution Contract TEAM-SUP"))
            .andExpect(jsonPath("$[*].agentId").value(org.hamcrest.Matchers.containsInAnyOrder(
                supervisor.agentId().toString(), direct.agentId().toString(), indirect.agentId().toString())))
            .andExpect(jsonPath("$[*].agentId").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasItem(outsider.agentId().toString()))));
    }

    @Test
    void getOwnTeamForAnAgentWithNoDownlineListsOnlyItself() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent supervisor = onboardAgent(tenantId, "LEAF-SUP", null);
        Agent leaf = onboardAgent(tenantId, "LEAF", supervisor.agentId());

        // The supervisor is never in a downline agent's team.
        mockMvc.perform(get("/agents/me/team").with(agentOf(tenantId, leaf.partyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].agentId").value(leaf.agentId().toString()))
            .andExpect(jsonPath("$[0].hierarchyParentId").value(supervisor.agentId().toString()));
    }

    @Test
    void getOwnTeamReturns404WhenThePartyIsNotAnAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView notAnAgent = verifiedParty(tenantId, "TEAM-NOT-AGENT");

        mockMvc.perform(get("/agents/me/team").with(agentOf(tenantId, notAnAgent.partyId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }

    @Test
    void getOwnTeamReturns403ForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        onboardAgent(tenantId, "TEAM-STAFF", null);   // a real agent exists; staff still has no team

        mockMvc.perform(get("/agents/me/team").with(financeStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aSupervisorReadsADownlineAgentsStatementsAndLines() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-SUPREAD");
        createPlan(tenantId, productId);
        Agent supervisor = onboardAgent(tenantId, "SUPREAD-SUP", null);
        Agent downline = onboardAgent(tenantId, "SUPREAD-SUB", supervisor.agentId());
        UUID statementId = statementFor(tenantId, productId, downline.agentId(), "SUPREAD");

        mockMvc.perform(get("/agents/{agentId}/commission-statements", downline.agentId())
                .with(agentOf(tenantId, supervisor.partyId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].statementId").value(statementId.toString()));
        mockMvc.perform(get("/agents/{agentId}/commission-statements/{statementId}/accruals",
                    downline.agentId(), statementId)
                .with(agentOf(tenantId, supervisor.partyId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].clientName").value("Distribution Contract Holder SUPREAD"));
    }

    @Test
    void aDownlineAgentCannotReadItsSupervisorsCommission() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-UPREAD");
        createPlan(tenantId, productId);
        Agent supervisor = onboardAgent(tenantId, "UPREAD-SUP", null);
        Agent downline = onboardAgent(tenantId, "UPREAD-SUB", supervisor.agentId());
        UUID supervisorsStatement = statementFor(tenantId, productId, supervisor.agentId(), "UPREAD");

        mockMvc.perform(get("/agents/{agentId}/commission-statements", supervisor.agentId())
                .with(agentOf(tenantId, downline.partyId())))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/agents/{agentId}/commission-statements/{statementId}/accruals",
                    supervisor.agentId(), supervisorsStatement)
                .with(agentOf(tenantId, downline.partyId())))
            .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=DistributionContractTest` — Expected: the four `getOwnTeam*` tests FAIL (404 / no mapping); the two hierarchy tests PASS already (they pin existing behaviour against regression).

- [ ] **Step 3: Implement**

`AgentTeamMemberView.java`:

```java
package tz.co.nlolo.lifeplatform.distribution.api;

import java.util.UUID;

/** One agent in a caller's team -- the caller's own profile or one below it. {@code displayName} is the agent's
 * party name, null when it cannot be resolved. */
public record AgentTeamMemberView(UUID agentId, String licenseNumber, LicenseStatus licenseStatus,
                                  UUID hierarchyParentId, String displayName) {}
```

`DistributionApi.java`, directly after `resolveAgentTeam`:

```java
    /**
     * Agent commission view: the same set {@link #resolveAgentTeam} scopes reads to -- the party's own agent
     * profile(s), then everyone below them within the hierarchy depth cap -- each with its name, so a supervisor
     * can pick whose commission to read. Own profiles first, then by name, then by licence number. Empty when the
     * party is not an agent in this tenant. Listing an agent here grants nothing: every read of its figures still
     * passes the per-agent access check.
     */
    List<AgentTeamMemberView> listTeam(UUID partyId);
```

`DistributionApiImpl.java` (imports `tz.co.nlolo.lifeplatform.distribution.api.AgentTeamMemberView`, `java.util.ArrayList`, `java.util.Comparator`):

```java
    @Override
    @Transactional(readOnly = true)
    public List<AgentTeamMemberView> listTeam(UUID partyId) {
        UUID tenantId = TenantContext.get();
        List<AgentTeamMemberView> own = new ArrayList<>();
        List<AgentTeamMemberView> below = new ArrayList<>();
        for (UUID agentId : resolveAgentTeam(partyId)) {
            agentProfileRepository.findByAgentIdAndTenantId(agentId, tenantId).ifPresent(profile -> {
                AgentTeamMemberView member = new AgentTeamMemberView(profile.getAgentId(), profile.getLicenseNumber(),
                    profile.getLicenseStatus(), profile.getHierarchyParentId(),
                    partyName(profile.getPartyId()).orElse(null));
                (partyId.equals(profile.getPartyId()) ? own : below).add(member);
            });
        }
        below.sort(Comparator.comparing(AgentTeamMemberView::displayName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(AgentTeamMemberView::licenseNumber));
        own.addAll(below);
        return List.copyOf(own);
    }
```

`AgentTeamMemberDto.java`:

```java
package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.AgentTeamMemberView;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;

import java.util.UUID;

public record AgentTeamMemberDto(UUID agentId, String licenseNumber, LicenseStatus licenseStatus,
                                 UUID hierarchyParentId, String displayName) {

    public static AgentTeamMemberDto from(AgentTeamMemberView view) {
        return new AgentTeamMemberDto(view.agentId(), view.licenseNumber(), view.licenseStatus(),
            view.hierarchyParentId(), view.displayName());
    }
}
```

`AgentController.java`, after `getOwnAgentProfile` (imports `tz.co.nlolo.lifeplatform.distribution.api.AgentTeamMemberView` if needed):

```java
    /**
     * Agent commission view: who a supervisor may pick. The agent realm had no way to list its own downline --
     * {@code GET /agents} is staff-only, and rightly, because it has no per-row scoping. This list is scoped by
     * construction: it is {@code resolveAgentTeam} of the TOKEN's party, never of anything the caller supplies.
     * It grants nothing on its own; reading a member's figures still passes {@link #enforceAgentReadAccess}.
     */
    @GetMapping("/agents/me/team")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<List<AgentTeamMemberDto>> getOwnTeam(@AuthenticationPrincipal Jwt jwt) {
        String partyIdClaim = jwt.getClaimAsString("party_id");
        if (partyIdClaim == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        List<AgentTeamMemberView> team = distributionApi.listTeam(UUID.fromString(partyIdClaim));
        if (team.isEmpty()) {
            throw new AgentNotFoundException("Token's party is not an agent in this tenant");
        }
        return ResponseEntity.ok(team.stream().map(AgentTeamMemberDto::from).toList());
    }
```

`openapi-distribution.yaml` — new path directly after `/agents/me` (before `/agents/{agentId}`):

```yaml
  /agents/me/team:
    get:
      summary: List the caller's own agent record and its downline
      description: >
        Agents-realm only. The caller is the token's `party_id`. Rows are the caller's own agent
        profile(s) first, then every agent below them in the hierarchy (the same depth cap every
        hierarchy read uses), by name. An agent with no downline gets one row. 404 if the token's
        party is not an agent in this tenant. Listing grants nothing: reading any row's figures
        still passes the per-agent access check.
      security:
        - agentsAuth: [agent, supervisor]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/AgentTeamMember' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

and a schema directly after `AgentView`:

```yaml
    AgentTeamMember:
      type: object
      required: [agentId, licenseNumber, licenseStatus]
      properties:
        agentId: { type: string, format: uuid }
        licenseNumber: { type: string }
        licenseStatus: { type: string, enum: [ACTIVE, EXPIRED, SUSPENDED] }
        hierarchyParentId: { type: [string, "null"], format: uuid }
        displayName:
          type: [string, "null"]
          description: The agent's own name, from party. Null when it cannot be resolved.
```

- [ ] **Step 4: Compile clean and run every affected class (the backend gate)**

Stop the dev backend if it is running.
Run: `./mvnw -q clean test-compile` — Expected: BUILD SUCCESS.
Run (foreground, ~25-35 min):
`./mvnw test -Dtest='CommissionAccrualCivilDayTest,DistributionContractTest,DistributionSpecParsesTest,ClawbackIntegrationTest,CommissionAccrualEndToEndTest,CommissionCloseSweepPsqlTest,CommissionPayoutEndToEndTest,CreditLifeCommissionTest,DistributionApiIntegrationTest,AgentProfileTest,CommissionCalculatorTest,CommissionStatementStateMachineTest,AgentBookOfBusinessScopingTest,AppRolePrivilegesIntegrationTest,RowLevelSecurityIntegrationTest,ClaimAndCommissionPostingEndToEndTest,PolicyApiIntegrationTest,PolicyCustomerScopingTest,ModularityTests'`
Expected: `BUILD SUCCESS`, `Failures: 0, Errors: 0`. Count the `Tests run: ... - in` lines in the output: it must be 19 classes. Fewer than 19 means a class was skipped — investigate before claiming green.

- [ ] **Step 5: Commit**

```bash
git add backend/api/openapi/openapi-distribution.yaml backend/src
git commit -m "feat(distribution): GET /agents/me/team lists a caller and its downline"
```

---

### Task 4: Frontend API, store and regenerated types

**Files:**
- Modify: `frontend/src/api/distribution.ts`
- Modify: `frontend/src/store/distributionStore.ts`

**Interfaces:**
- Consumes: OpenAPI from Tasks 2-3.
- Produces: `type AgentTeamMember`, `getOwnTeam(): Promise<AgentTeamMember[]>`, store `ownTeam: Resource<AgentTeamMember[]>`, `loadOwnTeam(): Promise<void>`, `selectOwnTeam`.

- [ ] **Step 1: Give the worktree its node_modules and regenerate types**

The worktree has no `node_modules`. Link the main checkout's (same lockfile, nothing installed or changed):

```powershell
New-Item -ItemType Junction -Path "C:\Users\USER\Desktop\digital-life-insurance-platform\.worktrees\agent-commission\frontend\node_modules" -Target "C:\Users\USER\Desktop\digital-life-insurance-platform\frontend\node_modules"
```

Run (from `frontend/`): `npm run generate:api` — Expected: one module per spec written to `src/types/api/`.
Run: `grep -n "AgentTeamMember: {\|accruedOn\|clientName" src/types/api/distribution.ts` — Expected: `AgentTeamMember` present; `accruedOn: string;` (required); `clientName?: string | null;` (optional).

- [ ] **Step 2: Add the API call**

In `frontend/src/api/distribution.ts`, add `import type { components as DistributionComponents } from '@/types/api/distribution';` to the imports, and after `getOwnAgent`:

```ts
/** One row of `GET /agents/me/team`. Typed here rather than in `api/types.ts`, which this work leaves alone. */
export type AgentTeamMember = DistributionComponents['schemas']['AgentTeamMember'];

/**
 * `GET /agents/me/team` -- agents realm only. The caller's own agent record(s) first, then everyone
 * below them in the hierarchy. Scoped by the server from the token; the console never filters it, and
 * a row here grants nothing -- each agent's figures are still access-checked when read.
 */
export function getOwnTeam(): Promise<AgentTeamMember[]> {
  return get<AgentTeamMember[]>('/agents/me/team');
}
```

- [ ] **Step 3: Add the store slot**

In `frontend/src/store/distributionStore.ts`:
- import `getOwnTeam` with the other API functions and `import type { AgentSearchParams, AgentTeamMember } from '@/api/distribution';` (replacing the existing `AgentSearchParams` type import);
- in `DistributionState` after `ownAgent: Resource<AgentView>;` add `ownTeam: Resource<AgentTeamMember[]>;` and after `loadOwnAgent: () => Promise<void>;` add `loadOwnTeam: () => Promise<void>;`;
- in the initial state after `ownAgent: idle(),` add `ownTeam: idle(),`;
- after the `loadOwnAgent` action add:

```ts
  loadOwnTeam: () =>
    track(
      'distribution.ownTeam',
      getState().ownTeam,
      (next) => set({ ownTeam: next }),
      () => getOwnTeam(),
    ),
```

- after `export const selectOwnAgent = ...` add:

```ts
export const selectOwnTeam = (s: DistributionState) => s.ownTeam;
```

- [ ] **Step 4: Typecheck**

Run: `npm run typecheck` — Expected: exit 0, no output. (The old `CommissionStatementsPanel` still compiles: the accrual type only gained fields.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/distribution.ts frontend/src/store/distributionStore.ts
git commit -m "feat(console): read an agent's own team"
```

---

### Task 5: The commission arithmetic and labels, as pure functions

**Files:**
- Create: `frontend/src/features/distribution/commissionView.ts`
- Test: `frontend/src/features/distribution/commissionView.test.ts`

**Interfaces:**
- Produces: `darToday(now?: Date): string` (`YYYY-MM-DD`); `commissionTotals(statements, today): CommissionTotals[]`; `interface CommissionTotals { currencyCode; earnedThisMonth: Money; earnedThisYear: Money; paidOut: Money; awaitingPayout: Money }`; `statementMonths(statements, currentPeriod): StatementMonth[]`; `interface StatementMonth { key: string; period: string; statement: CommissionStatementView | null }`; `monthLabel(period): string`; `accrualTypeLabel(line): string`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/distribution/commissionView.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { CommissionStatementView } from '@/api/types';
import {
  accrualTypeLabel,
  commissionTotals,
  darToday,
  monthLabel,
  statementMonths,
} from './commissionView';

function statement(
  period: string,
  amount: string,
  status: CommissionStatementView['status'],
  currencyCode = 'TZS',
): CommissionStatementView {
  return {
    statementId: `${period}-${status}-${currencyCode}`,
    agentId: 'a1',
    period,
    totalAmount: { amount, currencyCode },
    status,
  };
}

describe('darToday', () => {
  it('is the Dar es Salaam date, which turns three hours before UTC', () => {
    expect(darToday(new Date('2026-10-31T21:30:00Z'))).toBe('2026-11-01');
    expect(darToday(new Date('2026-10-31T20:59:59Z'))).toBe('2026-10-31');
  });
});

describe('commissionTotals', () => {
  const today = '2026-10-12';

  it('sums this month, this year, paid and awaiting from the statements', () => {
    const [tzs] = commissionTotals(
      [
        statement('2026-10', '184000.00', 'OPEN'),
        statement('2026-09', '1022500.00', 'CLOSED'),
        statement('2026-08', '500.50', 'PAYOUT_REQUESTED'),
        statement('2026-07', '1000.00', 'PAID'),
        statement('2025-12', '7000.00', 'PAID'),
      ],
      today,
    );
    expect(tzs.earnedThisMonth).toEqual({ amount: '184000.00', currencyCode: 'TZS' });
    expect(tzs.earnedThisYear).toEqual({ amount: '1208000.50', currencyCode: 'TZS' });
    expect(tzs.paidOut).toEqual({ amount: '8000.00', currencyCode: 'TZS' });
    expect(tzs.awaitingPayout).toEqual({ amount: '1023000.50', currencyCode: 'TZS' });
  });

  it('counts a failed payout as still awaiting', () => {
    const [tzs] = commissionTotals([statement('2026-09', '300.00', 'PAYOUT_FAILED')], today);
    expect(tzs.awaitingPayout.amount).toBe('300.00');
    expect(tzs.paidOut.amount).toBe('0.00');
  });

  it('nets clawbacks: a negative statement reduces every figure it belongs to', () => {
    const [tzs] = commissionTotals(
      [statement('2026-10', '-10000.00', 'OPEN'), statement('2026-09', '25000.00', 'CLOSED')],
      today,
    );
    expect(tzs.earnedThisMonth.amount).toBe('-10000.00');
    expect(tzs.earnedThisYear.amount).toBe('15000.00');
  });

  it('keeps currencies apart', () => {
    const totals = commissionTotals(
      [statement('2026-10', '100.00', 'OPEN', 'TZS'), statement('2026-10', '5.00', 'OPEN', 'USD')],
      today,
    );
    expect(totals.map((t) => [t.currencyCode, t.earnedThisMonth.amount])).toEqual([
      ['TZS', '100.00'],
      ['USD', '5.00'],
    ]);
  });

  it('is empty when nothing has been earned', () => {
    expect(commissionTotals([], today)).toEqual([]);
  });

  it('adds exactly, past the precision of a JS number', () => {
    const [tzs] = commissionTotals(
      [statement('2026-10', '9007199254740993.99', 'OPEN'), statement('2026-09', '0.01', 'OPEN')],
      today,
    );
    expect(tzs.earnedThisYear.amount).toBe('9007199254740994.00');
  });
});

describe('statementMonths', () => {
  it('always lists the current month, newest first, even with no statement for it', () => {
    const months = statementMonths([statement('2026-08', '1.00', 'PAID')], '2026-10');
    expect(months.map((m) => [m.period, m.statement === null])).toEqual([
      ['2026-10', true],
      ['2026-08', false],
    ]);
  });

  it('does not add a second row when the current month has a statement', () => {
    const months = statementMonths([statement('2026-10', '1.00', 'OPEN')], '2026-10');
    expect(months).toHaveLength(1);
    expect(months[0].statement).not.toBeNull();
  });
});

describe('monthLabel', () => {
  it('names the month', () => {
    expect(monthLabel('2026-10')).toBe('October 2026');
    expect(monthLabel('2027-01')).toBe('January 2027');
  });
});

describe('accrualTypeLabel', () => {
  it('names every tier', () => {
    expect(accrualTypeLabel({ tierType: 'FIRST_YEAR' })).toBe('First year');
    expect(accrualTypeLabel({ tierType: 'RENEWAL' })).toBe('Renewal');
    expect(accrualTypeLabel({ tierType: 'OVERRIDE' })).toBe('Override');
    expect(accrualTypeLabel({ tierType: 'SUPERVISOR_OVERRIDE' })).toBe('Supervisor override');
  });

  it('calls a reversal a clawback, and says of what', () => {
    expect(accrualTypeLabel({ tierType: 'FIRST_YEAR', reversesAccrualId: 'x' })).toBe('Clawback (first year)');
    expect(accrualTypeLabel({ tierType: 'RENEWAL', reversesAccrualId: null })).toBe('Renewal');
  });
});
```

- [ ] **Step 2: Run to see it fail**

Run: `npx vitest run src/features/distribution/commissionView.test.ts` — Expected: FAIL, `Failed to resolve import "./commissionView"`.

- [ ] **Step 3: Implement**

`frontend/src/features/distribution/commissionView.ts`:

```ts
import type { CommissionAccrualView, CommissionStatementView } from '@/api/types';
import { isValidAmount, type Money } from '@/lib/money';

/**
 * The arithmetic and wording behind the agent commission view (spec 2026-10-12).
 *
 * lib/money holds that the backend computes every total, and these figures respect that: each is a sum of
 * statement totals the backend already computed, net of clawbacks, grouped by month and status. The user
 * chose this over a summary endpoint (D2). Exact, through BigInt cents -- never a JS number.
 */

const DAR_ES_SALAAM = 'Africa/Dar_es_Salaam';

/** Today in Dar es Salaam as `YYYY-MM-DD` -- not the browser's zone, not UTC. */
export function darToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: DAR_ES_SALAAM }).format(now);
}

export interface CommissionTotals {
  currencyCode: string;
  earnedThisMonth: Money;
  earnedThisYear: Money;
  /** All time (D4). */
  paidOut: Money;
  /** Closed, requested or failed: owed and not yet paid (D3). */
  awaitingPayout: Money;
}

const AWAITING: ReadonlySet<CommissionStatementView['status']> = new Set([
  'CLOSED',
  'PAYOUT_REQUESTED',
  'PAYOUT_FAILED',
]);

function toCents(amount: string): bigint | null {
  if (!isValidAmount(amount)) return null;
  const negative = amount.startsWith('-');
  const [whole, fraction = ''] = (negative ? amount.slice(1) : amount).split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  return negative ? -cents : cents;
}

function toMoney(cents: bigint, currencyCode: string): Money {
  const sign = cents < 0n ? '-' : '';
  const magnitude = cents < 0n ? -cents : cents;
  return { amount: `${sign}${magnitude / 100n}.${String(magnitude % 100n).padStart(2, '0')}`, currencyCode };
}

/**
 * The four headline figures, one entry per currency in order of first appearance -- currencies are never
 * added together. A statement whose amount is not a valid decimal is left out rather than guessed at.
 * `today` is `darToday()`'s `YYYY-MM-DD`.
 */
export function commissionTotals(
  statements: readonly CommissionStatementView[],
  today: string,
): CommissionTotals[] {
  const month = today.slice(0, 7);
  const yearPrefix = `${today.slice(0, 4)}-`;
  const sums = new Map<string, { month: bigint; year: bigint; paid: bigint; awaiting: bigint }>();
  for (const s of statements) {
    const cents = toCents(s.totalAmount.amount);
    if (cents === null) continue;
    const code = s.totalAmount.currencyCode;
    const sum = sums.get(code) ?? { month: 0n, year: 0n, paid: 0n, awaiting: 0n };
    if (s.period === month) sum.month += cents;
    if (s.period.startsWith(yearPrefix)) sum.year += cents;
    if (s.status === 'PAID') sum.paid += cents;
    if (AWAITING.has(s.status)) sum.awaiting += cents;
    sums.set(code, sum);
  }
  return [...sums].map(([code, sum]) => ({
    currencyCode: code,
    earnedThisMonth: toMoney(sum.month, code),
    earnedThisYear: toMoney(sum.year, code),
    paidOut: toMoney(sum.paid, code),
    awaitingPayout: toMoney(sum.awaiting, code),
  }));
}

export interface StatementMonth {
  /** The statement id, or `none:<period>` for the current month before anything has accrued in it. */
  key: string;
  period: string;
  statement: CommissionStatementView | null;
}

/**
 * The months to list, newest first. The current month is always there: a month with no commission yet is
 * an answer ("nothing yet"), not a missing row.
 */
export function statementMonths(
  statements: readonly CommissionStatementView[],
  currentPeriod: string,
): StatementMonth[] {
  const months: StatementMonth[] = statements.map((s) => ({ key: s.statementId, period: s.period, statement: s }));
  if (!statements.some((s) => s.period === currentPeriod)) {
    months.push({ key: `none:${currentPeriod}`, period: currentPeriod, statement: null });
  }
  return months.sort(
    (a, b) =>
      b.period.localeCompare(a.period) ||
      (a.statement?.totalAmount.currencyCode ?? '').localeCompare(b.statement?.totalAmount.currencyCode ?? ''),
  );
}

const MONTH_LABEL = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });

/** `2026-10` → `October 2026`. */
export function monthLabel(period: string): string {
  const [year, month] = period.split('-').map(Number);
  return MONTH_LABEL.format(new Date(Date.UTC(year, month - 1, 1)));
}

const TIER_LABELS: Record<CommissionAccrualView['tierType'], string> = {
  FIRST_YEAR: 'First year',
  RENEWAL: 'Renewal',
  OVERRIDE: 'Override',
  SUPERVISOR_OVERRIDE: 'Supervisor override',
  THRESHOLD_BONUS: 'Threshold bonus',
};

/** What a line is, in words. A reversal is a clawback of its tier. */
export function accrualTypeLabel(line: Pick<CommissionAccrualView, 'tierType' | 'reversesAccrualId'>): string {
  const tier = TIER_LABELS[line.tierType];
  return line.reversesAccrualId ? `Clawback (${tier.toLowerCase()})` : tier;
}
```

- [ ] **Step 4: Run tests, typecheck, lint**

Run: `npx vitest run src/features/distribution/commissionView.test.ts` — Expected: `1 passed` file, `Tests 12 passed`.
Run: `npm run typecheck; npx eslint src/features/distribution/commissionView.ts src/features/distribution/commissionView.test.ts` — Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/distribution/commissionView.ts frontend/src/features/distribution/commissionView.test.ts
git commit -m "feat(console): commission totals, months and line labels"
```

---

### Task 6: `AgentCommission`, on the staff agent page

**Files:**
- Create: `frontend/src/features/distribution/PayoutForm.tsx`
- Create: `frontend/src/features/distribution/AgentCommission.tsx`
- Delete: `frontend/src/features/distribution/CommissionStatementsPanel.tsx`
- Modify: `frontend/src/features/distribution/AgentDetailPage.tsx:33,113-115`
- Modify: `frontend/src/features/distribution/AgentProfilePage.tsx` (temporary: keep it compiling — Task 7 finishes it)

**Interfaces:**
- Consumes: Task 4 store, Task 5 functions.
- Produces: `AgentCommission({ agentId: string; realm: 'agents' | 'staff'; canManage: boolean })` — renders three `Panel`s as siblings (a fragment). Callers pass `key={agentId}` when the agent can change. `PayoutForm({ agentId, statement })` unchanged.

- [ ] **Step 1: Move `PayoutForm` out, verbatim**

Create `PayoutForm.tsx` containing the `PayoutForm` function exactly as it is in `CommissionStatementsPanel.tsx` (from `function PayoutForm({` to the end of the file), with `export` added before `function`, and these imports:

```ts
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { CommissionStatementView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { InlineError } from '@/components/InlineError';
import { Receipt } from '@/components/Receipt';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { selectRequestingPayout, useDistributionStore } from '@/store/distributionStore';
import {
  blankRequestPayoutForm,
  requestPayoutFormSchema,
  toApiRequest,
  type RequestPayoutFormValues,
} from './requestPayoutForm';
```

Above it, the old file's payout reasoning moves with it as the function's doc comment:

```ts
/**
 * Staff FINANCE_OFFICER/ADMIN payout of one statement. Deliberately NOT disabled for an OPEN/PAID/
 * PAYOUT_REQUESTED statement: `CommissionStatement.markPayoutRequested` genuinely 409s outside
 * CLOSED/PAYOUT_FAILED, and that real business rejection is worth showing plainly.
 */
```

- [ ] **Step 2: Write `AgentCommission.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { CommissionAccrualView, CommissionStatementView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney, NO_VALUE } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAccruals, selectStatements, useDistributionStore } from '@/store/distributionStore';
import {
  accrualTypeLabel,
  commissionTotals,
  darToday,
  monthLabel,
  statementMonths,
  type CommissionTotals,
  type StatementMonth,
} from './commissionView';
import { PayoutForm } from './PayoutForm';

/**
 * What one agent has earned: headline totals, the months, and the lines of the chosen month with
 * their client, product, type, date and amount (spec 2026-10-12).
 *
 * Shared by the agent's "My commission" screen and staff's AgentDetailPage. Who may read which
 * agent is the server's decision (`enforceAgentReadAccess`); this renders whatever it is allowed
 * to load. The selected month is local state -- callers remount with `key={agentId}` when the
 * agent changes, rather than resetting state in an effect.
 */
export function AgentCommission({
  agentId,
  realm,
  canManage,
}: {
  agentId: string;
  realm: 'agents' | 'staff';
  canManage: boolean;
}) {
  const [today] = useState(() => darToday());
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const loadStatements = useDistributionStore((s) => s.loadStatements);
  const statements = useDistributionStore(selectStatements(agentId));

  useEffect(() => {
    void loadStatements(agentId);
  }, [agentId, loadStatements]);

  if (isInitialLoad(statements)) return <LoadingBlock label="Loading commission" />;
  if (statements.status === 'error' && statements.error && statements.data === null) {
    return <ErrorPanel error={statements.error} onRetry={() => void loadStatements(agentId)} />;
  }

  const all = statements.data ?? [];
  const currentPeriod = today.slice(0, 7);
  const months = statementMonths(all, currentPeriod);
  const selected =
    months.find((m) => m.key === selectedKey) ?? months.find((m) => m.period === currentPeriod) ?? months[0];

  const monthColumns: Column<StatementMonth>[] = [
    { key: 'month', header: 'Month', render: (m) => monthLabel(m.period) },
    {
      key: 'status',
      header: 'Status',
      render: (m) => (m.statement ? <StatusBadge kind="commissionStatement" value={m.statement.status} /> : NO_VALUE),
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (m) => (m.statement ? formatMoney(m.statement.totalAmount) : NO_VALUE),
    },
  ];

  return (
    <>
      <Panel title="Totals" subtitle="Net of clawbacks. Paid when finance releases a closed month.">
        <Totals totals={commissionTotals(all, today)} />
      </Panel>

      <Panel title="Statements">
        <DataTable
          caption="Commission statements by month"
          columns={monthColumns}
          rows={months}
          rowKey={(m) => m.key}
          onRowActivate={(m) => setSelectedKey(m.key)}
          isRowSelected={(m) => m.key === selected.key}
        />
      </Panel>

      {selected.statement ? (
        <StatementLines
          key={selected.key}
          agentId={agentId}
          realm={realm}
          statement={selected.statement}
          canManage={canManage}
        />
      ) : (
        <Panel title={monthLabel(selected.period)}>
          <EmptyState title={`No commission earned in ${monthLabel(selected.period)} yet.`} />
        </Panel>
      )}
    </>
  );
}

function Totals({ totals }: { totals: CommissionTotals[] }) {
  if (totals.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing earned yet.</p>;
  }
  // The currency joins the label only when there is more than one, so the usual case reads plainly.
  const label = (text: string, code: string) => (totals.length > 1 ? `${text} (${code})` : text);
  return (
    <dl className="px-4 pb-2">
      {totals.map((t) => (
        <div key={t.currencyCode}>
          <Field label={label('Earned this month', t.currencyCode)} value={formatMoney(t.earnedThisMonth)} />
          <Field label={label('Earned this year', t.currencyCode)} value={formatMoney(t.earnedThisYear)} />
          <Field label={label('Paid out', t.currencyCode)} value={formatMoney(t.paidOut)} />
          <Field label={label('Awaiting payout', t.currencyCode)} value={formatMoney(t.awaitingPayout)} />
        </div>
      ))}
    </dl>
  );
}

function StatementLines({
  agentId,
  realm,
  statement,
  canManage,
}: {
  agentId: string;
  realm: 'agents' | 'staff';
  statement: CommissionStatementView;
  canManage: boolean;
}) {
  const loadAccruals = useDistributionStore((s) => s.loadAccruals);
  const accruals = useDistributionStore(selectAccruals(agentId, statement.statementId));

  useEffect(() => {
    void loadAccruals(agentId, statement.statementId);
  }, [agentId, statement.statementId, loadAccruals]);

  const month = monthLabel(statement.period);
  const rows = accruals.data ?? [];
  const title = accruals.data ? `${month} — ${rows.length} ${rows.length === 1 ? 'line' : 'lines'}` : month;

  const columns: Column<CommissionAccrualView>[] = [
    { key: 'client', header: 'Client', render: (a) => a.clientName ?? NO_VALUE },
    { key: 'product', header: 'Product', secondary: true, render: (a) => a.productName ?? NO_VALUE },
    {
      key: 'policy',
      header: 'Policy',
      nowrap: true,
      render: (a) => (
        <Link
          className="font-mono text-xs underline"
          to={`/${realm}/policies/${encodeURIComponent(a.policyNumber)}`}
        >
          {a.policyNumber}
        </Link>
      ),
    },
    { key: 'type', header: 'Type', render: (a) => accrualTypeLabel(a) },
    { key: 'accrued', header: 'Accrued', secondary: true, nowrap: true, render: (a) => formatDate(a.accruedOn) },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (a) => (
        <span className={a.reversesAccrualId ? 'text-status-danger-fg' : undefined}>{formatMoney(a.amount)}</span>
      ),
    },
  ];

  return (
    <Panel title={title}>
      {isInitialLoad(accruals) && <LoadingBlock label="Loading lines" />}
      {accruals.status === 'error' && accruals.error && accruals.data === null && (
        <div className="p-4">
          <ErrorPanel error={accruals.error} onRetry={() => void loadAccruals(agentId, statement.statementId)} />
        </div>
      )}
      {accruals.data && rows.length === 0 && <EmptyState title={`No commission earned in ${month} yet.`} />}
      {rows.length > 0 && (
        <DataTable caption={`Commission lines for ${month}`} columns={columns} rows={rows} rowKey={(a) => a.accrualId} />
      )}
      {canManage && (
        <div className="px-4 pb-4">
          <PayoutForm agentId={agentId} statement={statement} />
        </div>
      )}
    </Panel>
  );
}
```

- [ ] **Step 3: Use it on the staff page; delete the old panel**

In `AgentDetailPage.tsx` replace the import `import { CommissionStatementsPanel } from './CommissionStatementsPanel';` with `import { AgentCommission } from './AgentCommission';`, and replace

```tsx
        <Panel title="Commission statements">
          <CommissionStatementsPanel agentId={agentId} canManage={canManage} />
        </Panel>
```

with

```tsx
        <AgentCommission key={agentId} agentId={agentId} realm="staff" canManage={canManage} />
```

In the page's doc comment, replace "statements, and each statement's accrual line items." with "and its commission (`AgentCommission`)."

In `AgentProfilePage.tsx` delete the `CommissionStatementsPanel` import and the `<Panel title="Commission statements">…</Panel>` block (Task 7 puts the link there). Then `git rm frontend/src/features/distribution/CommissionStatementsPanel.tsx`.

- [ ] **Step 4: Typecheck, lint, unit tests**

Run (from `frontend/`): `npm run typecheck; npm run lint; npx vitest run` — Expected: typecheck and lint exit 0; vitest `Test Files N passed (N)` with 0 failed. Record N.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src/features/distribution
git commit -m "feat(console): one commission view, on the staff agent page"
```

---

### Task 7: "My commission" for agents, with the downline picker

**Files:**
- Create: `frontend/src/features/distribution/MyCommissionPage.tsx`
- Modify: `frontend/src/lazyPages.ts` (after `AgentProfilePage`)
- Modify: `frontend/src/screens.tsx` (lucide import; `AGENTS_SCREENS` after the `me` entry; lazy import list)
- Modify: `frontend/src/features/distribution/AgentProfilePage.tsx`

**Interfaces:**
- Consumes: `selectOwnAgent`, `selectOwnTeam`, `loadOwnTeam` (Task 4); `AgentCommission` (Task 6).
- Produces: route `/agents/commission`, menu item "My commission"; query `?agent=<agentId>`.

- [ ] **Step 1: Write the page**

`MyCommissionPage.tsx`:

```tsx
import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Select } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, selectOwnTeam, useDistributionStore } from '@/store/distributionStore';
import { AgentCommission } from './AgentCommission';

/**
 * "My commission": what the signed-in agent -- or, for a supervisor, one agent in their downline --
 * has earned. The picker offers only `GET /agents/me/team`, which the server scopes from the token;
 * choosing an agent changes nothing about access, because each read is checked again server-side.
 * The choice lives in `?agent=` so a reload keeps it. An id outside the team falls back to self.
 */
export function MyCommissionPage() {
  const own = useDistributionStore(selectOwnAgent);
  const team = useDistributionStore(selectOwnTeam);
  const loadOwnAgent = useDistributionStore((s) => s.loadOwnAgent);
  const loadOwnTeam = useDistributionStore((s) => s.loadOwnTeam);
  const [params, setParams] = useSearchParams();

  useEffect(() => {
    void loadOwnAgent();
    void loadOwnTeam();
  }, [loadOwnAgent, loadOwnTeam]);

  if (isInitialLoad(own) || isInitialLoad(team)) return <LoadingBlock label="Loading your commission" />;

  if (own.data === null && own.status === 'error' && own.error) {
    return (
      <div className="px-6 pt-6">
        <ErrorPanel error={own.error} onRetry={() => void loadOwnAgent()} />
      </div>
    );
  }

  const me = own.data;
  if (!me) return null;

  // A team that failed to load leaves the agent with their own commission and no picker.
  const members = team.data ?? [];
  const viewed =
    members.find((m) => m.agentId === params.get('agent')) ?? members.find((m) => m.agentId === me.agentId);
  const viewedId = viewed?.agentId ?? me.agentId;
  const description = viewed
    ? `${viewed.displayName ?? 'Unnamed agent'} · ${viewed.licenseNumber}`
    : me.licenseNumber;

  const picker = members.length > 1 && (
    <label className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
      Agent
      <Select
        inputSize="sm"
        value={viewedId}
        onChange={(e) => setParams(e.target.value === me.agentId ? {} : { agent: e.target.value })}
      >
        {members.map((m) => (
          <option key={m.agentId} value={m.agentId}>
            {m.agentId === me.agentId ? 'Me' : `${m.displayName ?? 'Unnamed agent'} (${m.licenseNumber})`}
          </option>
        ))}
      </Select>
    </label>
  );

  return (
    <>
      <PageHeader title="My commission" description={description} actions={picker || undefined} />
      <div className="space-y-5 px-6 pt-5 pb-8">
        <AgentCommission key={viewedId} agentId={viewedId} realm="agents" canManage={false} />
      </div>
    </>
  );
}
```

- [ ] **Step 2: Register the screen**

`lazyPages.ts`, after the `AgentProfilePage` export:

```ts
export const MyCommissionPage = lazy(() =>
  import('@/features/distribution/MyCommissionPage').then((m) => ({ default: m.MyCommissionPage })),
);
```

`screens.tsx`: add `MyCommissionPage` to the import list from `./lazyPages` (beside `AgentProfilePage`), add `Wallet` to the `lucide-react` import, and in `AGENTS_SCREENS` directly after the `me` entry:

```tsx
  {
    path: 'commission',
    element: <MyCommissionPage />,
    reach: { group: 'my-business', label: 'My commission', icon: Wallet },
  },
```

`screens.test.ts` asserts icons are unique per realm; if `Wallet` is already used in the agents realm, pick an unused lucide icon (`Coins`) instead.

- [ ] **Step 3: Point "My profile" at it**

In `AgentProfilePage.tsx` add `import { Link } from 'react-router-dom';` and, where the statements panel was (after the commission-plan `Panel`):

```tsx
        <Panel title="Commission earned">
          <p className="px-4 pb-4 text-sm">
            <Link to="/agents/commission" className="underline">
              View my commission
            </Link>
          </p>
        </Panel>
```

Update the page's doc comment: it reuses `CommissionPlanPanel` only, and commission earned lives on "My commission".

- [ ] **Step 4: Typecheck, lint, unit tests**

Run: `npm run typecheck; npm run lint; npx vitest run` — Expected: all exit 0; vitest file count = Task 6's N + 0 (screens tests still pass with the new entry).

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(console): My commission for agents, with a downline picker"
```

---

### Task 8: End-to-end against the real stack

**Files:**
- Create: `frontend/e2e/agents-my-commission.spec.ts`
- Create: `frontend/e2e/agents-commission-team-picker.spec.ts`
- Create: `frontend/e2e/staff-agent-commission.spec.ts`
- Modify: `frontend/e2e/agents-my-profile.spec.ts`, `frontend/e2e/staff-distribution.spec.ts:283-290`

- [ ] **Step 1: STOP — hand the real-stack steps to the user**

The dev backend applies neither V7 nor the new endpoint by itself. Tell the user, and wait for them to confirm:

1. Apply V7 to the dev database (superuser, as previous migrations were applied):
   `docker exec -i <postgres-container> psql -U <superuser> -d <database> < backend/db-migrations/distribution/V7__projection_policyholder.sql`
   (fill the container, user and database from `backend/infra` / the user's running setup before sending).
2. Check the backfill: `SELECT count(*) FILTER (WHERE policyholder_party_id IS NULL) AS missing, count(*) AS total FROM distribution.policy_projection;` — expect `missing = 0`.
3. Restart the dev backend from this branch's code so `GET /agents/me/team` and the new accrual fields exist.

- [ ] **Step 2: Update the specs that match the changed pages**

`agents-my-profile.spec.ts`, first test — append before its closing `});`:

```ts
    // Commission earned moved to its own screen; the profile links there instead of listing statements.
    await page.getByRole('link', { name: 'View my commission' }).click();
    await expect(page).toHaveURL(/\/agents\/commission$/);
    await expect(page.getByRole('heading', { name: 'My commission' })).toBeVisible();
```

`staff-distribution.spec.ts`, in "accrues a real commission statement…", replace

```ts
    await expect(async () => {
      await financePage.reload();
      await expect(financePage.getByText('No commission statements yet.')).not.toBeVisible();
    }).toPass({ timeout: 20_000 });

    await financePage.getByRole('button', { name: 'View accruals' }).first().click();
    await expect(financePage.getByText(/first year/)).toBeVisible();
```

with

```ts
    // The current month opens by default; its lines appear once the accrual has landed.
    await expect(async () => {
      await financePage.reload();
      await expect(financePage.getByRole('cell', { name: 'First year', exact: true })).toBeVisible();
    }).toPass({ timeout: 20_000 });
```

Run `grep -rn "Commission statements\|View accruals\|No commission statements\|Period (optional)" frontend/e2e` — Expected: no matches. Any match is another spec to update the same way.

- [ ] **Step 3: Write the agent spec**

`frontend/e2e/agents-my-commission.spec.ts`:

```ts
import { expect, test } from './agentSession';
import { selectAgentOfRecord } from './policies';
import { caseAwaitingManualIssue, selectUnderwritingCase } from './underwriting';

/**
 * "My commission" as `agent.senior`, against the real backend. The spec earns its own line: staff
 * issues a policy with this agent as agent of record, in force on arrival (MIGRATION basis), so a
 * FIRST_YEAR accrual exists this month whatever the dev database held before.
 */
async function ownAgentId(page: import('@playwright/test').Page): Promise<string> {
  await page.goto('/agents/me');
  const agentId = (
    await page.locator('dt').filter({ hasText: 'Agent id' }).locator('xpath=following-sibling::dd[1]').textContent()
  )?.trim();
  expect(agentId).toMatch(/^[0-9a-f-]{36}$/);
  return agentId as string;
}

test.describe('agents my commission', () => {
  test('shows a line earned this month with its client, product, type and date', async ({ page, browser }) => {
    test.slow();
    const agentId = await ownAgentId(page);

    const staffContext = await browser.newContext({ storageState: 'e2e/.auth/staff.json' });
    const staffPage = await staffContext.newPage();
    const caseId = await caseAwaitingManualIssue(staffPage);
    await staffPage.goto('/staff/policies/new');
    await selectUnderwritingCase(staffPage, caseId);
    await expect(staffPage.getByText('Resolving product version…')).not.toBeVisible();
    await staffPage.getByLabel('Sum assured').fill('1000000.00');
    await staffPage.getByLabel('Premium', { exact: true }).fill('500.00');
    await selectAgentOfRecord(staffPage, agentId);
    await staffPage.getByLabel('Why is this being issued by hand?').selectOption('MIGRATION');
    await staffPage.getByLabel('Reason for manual issue').fill('E2E my-commission fixture');
    await staffPage.getByRole('button', { name: 'Issue policy' }).click();
    await expect(staffPage).toHaveURL(/\/staff\/policies\/POL-[A-Z0-9]+$/, { timeout: 60_000 });
    const policyNumber = staffPage.url().split('/').pop() as string;
    await staffContext.close();

    await page.goto('/agents/commission');
    await expect(page.getByRole('heading', { name: 'My commission' })).toBeVisible();
    await expect(page.getByText('Earned this month')).toBeVisible();
    await expect(page.getByText('Awaiting payout')).toBeVisible();

    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: policyNumber }) });
    await expect(async () => {
      await page.reload();
      await expect(row).toBeVisible();
    }).toPass({ timeout: 20_000 });
    await expect(row.getByRole('cell', { name: 'First year', exact: true })).toBeVisible();
    // The client is a name, not a raw party id and not a dash.
    await expect(row.getByRole('cell').first()).not.toHaveText(/^([0-9a-f]{8}-|—$)/);
    await expect(row).toContainText(/[A-Z][a-z]{2} \d{1,2}, \d{4}/);

    // An agent never sees a payout control, on any statement.
    await expect(page.getByRole('button', { name: 'Request payout' })).toHaveCount(0);

    await row.getByRole('link', { name: policyNumber }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/policies/${policyNumber}$`));
  });
});
```

- [ ] **Step 4: Write the supervisor spec**

`frontend/e2e/agents-commission-team-picker.spec.ts`:

```ts
import { expect, test } from './agentSession';

/**
 * `agent.senior` supervises `agent.junior` (LIC-JUNIOR-001) in the seeded hierarchy. The picker
 * lists only the downline the server returns from GET /agents/me/team; the API refusing a
 * non-downline id is pinned by DistributionContractTest, not here.
 */
test.describe('agents commission team picker', () => {
  test('a supervisor picks a downline agent and sees that agent, and the choice survives a reload', async ({
    page,
  }) => {
    await page.goto('/agents/commission');
    const picker = page.getByLabel('Agent', { exact: true });
    await expect(picker).toBeVisible();
    await expect(picker.locator('option', { hasText: /^Me$/ })).toHaveCount(1);

    const junior = picker.locator('option', { hasText: 'LIC-JUNIOR-001' });
    await expect(junior).toHaveCount(1);
    const juniorId = (await junior.getAttribute('value')) as string;
    await picker.selectOption(juniorId);

    await expect(page).toHaveURL(new RegExp(`\\?agent=${juniorId}$`));
    await expect(page.getByText(/LIC-JUNIOR-001/).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();

    await page.reload();
    await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(juniorId);
  });
});
```

- [ ] **Step 5: Write the staff spec**

`frontend/e2e/staff-agent-commission.spec.ts`:

```ts
import { expect, test } from '@playwright/test';

/**
 * Staff read any agent's commission on the agent page, through the same view agents get, and a
 * finance officer still has the payout control. The 409-on-OPEN payout path is driven end to end
 * by staff-distribution.spec.ts.
 */
test.describe('staff agent commission', () => {
  test('shows the commission view for a seeded agent, with the payout control for finance', async ({ browser }) => {
    const context = await browser.newContext({ storageState: 'e2e/.auth/staff-finance.json' });
    const page = await context.newPage();

    await page.goto('/staff/agents');
    await page.getByLabel('Search by licence number').fill('LIC-SENIOR');
    await page.getByRole('button', { name: /LIC-SENIOR/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/agents\/[0-9a-f-]{36}$/);

    await expect(page.getByRole('heading', { name: 'Totals' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Statements' })).toBeVisible();
    await expect(page.getByText('Earned this year')).toBeVisible();
    await expect(page.getByPlaceholder('Payee mobile-money reference')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Request payout' })).toBeVisible();

    await context.close();
  });
});
```

If the seeded senior agent has no statement for the current month, the lines panel shows the empty state and the payout form is absent; in that case select a month row that has a statement (`page.getByRole('button', { name: /2026/ }).nth(1)`) before the payout assertions, or keep the payout assertions in staff-distribution only — record which in the commit message.

- [ ] **Step 6: Run the new and updated specs**

Run (from `frontend/`, with the dev stack up and the Step 1 changes applied):
`npx playwright test e2e/agents-my-commission.spec.ts e2e/agents-commission-team-picker.spec.ts e2e/staff-agent-commission.spec.ts e2e/agents-my-profile.spec.ts e2e/staff-distribution.spec.ts e2e/staff-payouts.spec.ts e2e/staff-credit-life-commission.spec.ts e2e/agents-my-book.spec.ts`
Expected: every listed spec file reports its tests passed, `0 failed`. A timeout in this run: re-run that spec alone before diagnosing. A failure: diagnose with systematic-debugging; do not mark it pre-existing.

- [ ] **Step 7: Commit**

```bash
git add frontend/e2e
git commit -m "test(e2e): my commission, the supervisor picker and the staff commission view"
```

---

### Task 9: Solo whole-branch review

- [ ] **Step 1:** Read `git diff main...agent-commission` end to end against the spec and this plan's Global Constraints. Check the seams: the DTO and the OpenAPI schema list the same fields; the generated `accruedOn` is required and the name fields optional; `realm` link prefixes match the routes; `key={agentId}` on every `AgentCommission` mount; no `setState` in an effect body; no `default:` added; nothing outside the agreed scope changed (`git diff --stat main...agent-commission`).
- [ ] **Step 2:** Fix what the review finds, re-run the covering tests (named classes/specs only), commit.
- [ ] **Step 3:** Report to the user with the evidence of each task, then use superpowers:finishing-a-development-branch.

# Credit Life Plan 5 — `regreporting` consumes member movement

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `SUM_ASSURED_IN_FORCE` tell the truth on a group scheme, by projecting `policy.GroupMemberAdded` and `policy.GroupMemberExited` into `regreporting`.

**Architecture:** One new handler pair on the existing `regreporting.application.PolicyEventListener`, driven by a **delta** between the scheme total the event carries and the total `policy_dimension` last recorded. Two new `policy_movement` columns keep the corrected stock metric separate from the flow metric it must not disturb.

**Tech Stack:** Spring Modulith, Java 21, JPA/Hibernate, Flyway (per-module dirs, applied by `scripts/migrate.sh`, **not** on boot), Postgres 16 with RLS, Testcontainers + JUnit 5 + AssertJ.

## Global Constraints

- Spec §2.14, verbatim: *"`policy.GroupMemberAdded` and `GroupMemberExited` have **no consumer**, so `regreporting.policy_dimension`'s sum assured goes stale whenever membership changes. On an employer scheme that is occasional drift. Here members are added every month and exited on every settlement, so it is continuous — and it lands in a TIRA return. This is a pre-existing gap being inherited, not created, but credit life is what turns the leak into a running tap. It is in scope."*
- **Where it lands *today*, stated so nobody over-claims the urgency:** the only return definition on the platform is `regreporting/V2`'s own seeded PLACEHOLDER, whose description says the line codes are invented and which exists under one hardcoded tenant. The real TIRA catalogue is C2-blocked. **That does not make this plan optional**, for one reason: the `policy_movement` rows are the permanent record, and movement that was never captured cannot be reconstructed later from anything. Whatever the final return asks for, it will be computed from rows written now.
- `regreporting` may not depend on `policy`. It learns everything from event payloads and its own dimension tables (`db-migrations/regreporting/V2` section 5).
- RLS predicates must use `NULLIF(current_setting('app.current_tenant_id', true), '')::uuid`. A bare cast raises on an unset GUC instead of matching nothing.
- Every new migration must be added to the migration list of **every** test class that applies the regreporting migrations, or those tests fail on a missing column. Verified 2026-09-23: that is **9 files**, and they do **not** all stop at the same migration — see Task 1 Step 8, which lists them. `regreporting/V4__rls_fail_closed.sql` is applied by **no test at all**, the same pre-existing gap `audit/V2` and `payment/V5` have. Do not add V4 while doing this; it is its own change with its own blast radius.
- Money is `NUMERIC(19,2)`; compare with `isEqualByComparingTo`, never `isEqualTo`.

---

## What exists today, verified 2026-09-23

Read these before writing anything; the plan's correctness depends on all six.

1. `regreporting.application.PolicyEventListener` handles exactly five events: `policy.PolicyActivated`, `PolicyLapsed`, `PolicyMatured`, `PolicySurrendered`, `PolicyReinstated`. There is no member case.
2. `policy_dimension` is `(tenant_id, policy_number)` with `sum_assured_amount NUMERIC(19,2) NOT NULL` under `CONSTRAINT policy_dimension_sum_assured_positive CHECK (sum_assured_amount > 0)`. **Zero is not writable.** It is written once, in `handlePolicyActivated`, and never updated.
3. `policy_movement` is `(tenant_id, period, product_id)` with `sum_assured_issued` and `sum_assured_terminated`, both guarded by `CONSTRAINT policy_movement_non_negative CHECK (… >= 0)`.
4. `MetricReaderRegistry.cumulativeSumAssured` computes `SUM_ASSURED_IN_FORCE` (a STOCK metric) as `Σ sum_assured_issued − Σ sum_assured_terminated`. `MetricReaderRegistry.sumSumAssuredIssued` computes `NEW_BUSINESS_SUM_ASSURED` (a FLOW metric) as `Σ sum_assured_issued` alone.
5. Both member events carry `schemeTotalCovered` as `{"amount": "...", "currencyCode": "..."}` — the scheme's **restated total after this change**, computed by `PolicyApiImpl.restateSchemeTotal`. `GroupMemberExited` also carries `policyMemberId` and `reason`; **`GroupMemberAdded` carries no member id at all** (a freeform borrower has no party id, so there is nothing stable to key on).
6. `issueGroupScheme` does **not** publish `GroupMemberAdded` for opening-schedule members. Their cover is already inside the `sumAssured` on `PolicyActivated`. There is therefore no double-count to avoid at issuance.

---

## The design, and the two decisions inside it

### Decision 1 — project a DELTA, not the member's own cover

The obvious implementation reads each event's `coveredAmount` and adds or subtracts it. Do not do that. Three reasons, in increasing order of importance:

- `GroupMemberExited` does not carry a `coveredAmount` at all, so it would need a producer change.
- A member's own cover is not the same as the change in scheme total when the scheme restates on a different basis.
- **`regreporting` has no de-duplication anywhere.** Its listeners are `AFTER_COMMIT` with at-least-once delivery, and every existing handler double-counts on redelivery. That is a known module-wide property, not something this plan should fix for one listener while leaving the other four.

A delta is immune to all three:

```
delta = schemeTotalCovered − policy_dimension.sum_assured_amount
```

A redelivered event finds the dimension already equal to the total it carries, computes a delta of zero, and does nothing. **Idempotency falls out of the arithmetic rather than being bolted on**, and the projection self-heals: a dropped event is corrected by the next one rather than leaving permanent drift.

### Decision 2 — new columns, because merging them destroys information that cannot be recovered

`sum_assured_issued` feeds two metrics:

| Metric | Kind | Computed from | Where it stands |
|---|---|---|---|
| `SUM_ASSURED_IN_FORCE` | STOCK | `Σ issued − Σ terminated` | **Wrong** on any scheme whose membership has changed — the bug this plan fixes |
| `NEW_BUSINESS_SUM_ASSURED` | FLOW | `Σ issued` | **Also arguably wrong for credit life** — see below |

**A correction worth making explicitly, because the first draft of this plan got it wrong.** That
second row was originally described here as "correct, and filed". Neither is true, and both were
asserted rather than checked:

- **Not filed.** The only return definition that references `NEW_BUSINESS_SUM_ASSURED` is the one
  `regreporting/V2` seeds, whose own description reads *"PLACEHOLDER pending the TIRA return
  catalog (C2). Line codes and labels are invented."* It exists solely under the hardcoded tenant
  `11111111-1111-1111-1111-111111111111`, and nothing copies it to a real tenant. No return is
  filed from it today.
- **Not obviously correct.** Line PL-04 is labelled *"New business sum assured in period"* and
  counts policy activations only. A credit-life scheme that opens with one borrower and enrols
  five hundred over the year would report that one borrower's cover as the period's new business.
  For this product that is a material understatement, not a rounding matter.

So the reason for separate columns is **not** "protect a correct number". It is that **merging
them throws away a distinction that cannot be reconstructed**. Once member cover is added into
`sum_assured_issued`, no query can ever again separate *cover from newly written schemes* from
*cover from new members on existing schemes* — the stored data no longer carries the difference.
With two columns either definition is computable, and whichever one the real TIRA catalogue turns
out to ask for is a reporting decision rather than a migration.

This plan therefore leaves `sumSumAssuredIssued` alone **as the status quo, not as an endorsement**.
It is entirely possible that the right answer for credit life is to include member-added cover in
new business; that is a question for whoever owns the return catalogue (C2), and including it
later is one `.add(...)` against a column that will already hold the right number.

### Decision 3 — the last member out is the close event's business, not ours

When the last active member leaves, `PolicyApiImpl.exitOneMember` sets the scheme total to **zero** and then calls `closeAsSurrendered`, which publishes `policy.PolicySurrendered`. That event already drives `applyTerminationMovement`, which reads `policy_dimension.sum_assured_amount` and terminates it.

If the member handler also applied its delta, two things break:

- It would try to write `0` into `policy_dimension.sum_assured_amount`, violating `policy_dimension_sum_assured_positive`.
- The close would then terminate an amount the member handler had *already* removed — the whole scheme counted out twice.

**So a `schemeTotalCovered` of zero is skipped entirely: no movement, no restatement.** The close event accounts for the remainder, exactly as it does today. This is the single most important line in the plan to get right, and Task 3 exists to prove it.

---

## File Structure

| File | Responsibility |
|---|---|
| `db-migrations/regreporting/V5__member_movement_columns.sql` (create) | The two new `policy_movement` columns, folded into the existing non-negative CHECK |
| `regreporting/domain/PolicyMovement.java` (modify) | `applyMemberCoverAdded` / `applyMemberCoverExited`, and their getters |
| `regreporting/domain/PolicyDimension.java` (modify) | `restateSumAssured(BigDecimal)` — the only way the stored total changes after activation |
| `regreporting/infrastructure/MetricReaderRegistry.java` (modify) | `cumulativeSumAssured` includes the new columns; `sumSumAssuredIssued` deliberately does not |
| `regreporting/application/PolicyEventListener.java` (modify) | Two new cases, one shared `handleSchemeTotalRestated` |
| `regreporting/PolicyMovementArithmeticTest.java` (create) | Pure unit tests for Task 1 — no container |
| `regreporting/MemberMovementProjectionTest.java` (create) | Listener behaviour against a real Postgres, events published directly |
| `regreporting/ProjectionEndToEndTest.java` (modify) | The real chain: enrol, settle, read the return |

---

## Task 1: The arithmetic, with the flow metric held still

**Files:**
- Create: `backend/db-migrations/regreporting/V5__member_movement_columns.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/domain/PolicyMovement.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/domain/PolicyDimension.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/infrastructure/MetricReaderRegistry.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/PolicyMovementArithmeticTest.java`

**Interfaces:**
- Produces, for Task 2: `PolicyMovement.applyMemberCoverAdded(BigDecimal)`, `PolicyMovement.applyMemberCoverExited(BigDecimal)`, `PolicyMovement.getSumAssuredMemberAdded()`, `PolicyMovement.getSumAssuredMemberExited()`, `PolicyDimension.restateSumAssured(BigDecimal)`.

- [ ] **Step 1: Write the failing test**

Create `PolicyMovementArithmeticTest.java`:

```java
package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure arithmetic, no container. The whole point of Plan 5 lives in these two assertions:
 * member cover moves the STOCK metric and leaves the FLOW metric alone.
 */
class PolicyMovementArithmeticTest {

    private static final String PERIOD = "2026-Q3";

    private PolicyMovement movement() {
        return new PolicyMovement(UUID.randomUUID(), PERIOD, UUID.randomUUID(), "TZS");
    }

    @Test
    void memberCoverAddedRaisesInForceSumAssuredWithoutTouchingNewBusiness() {
        // THE POINT OF THIS TASK. A borrower enrolled onto an existing scheme is more cover in
        // force. Whether they are also "new business written" is an actuarial question nobody
        // has answered, so this must not move that number by accident.
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverAdded(new BigDecimal("250000.00"));

        assertThat(MetricReaderRegistry.cumulativeSumAssured(List.of(m), PERIOD))
            .isEqualByComparingTo("1250000.00");
        assertThat(MetricReaderRegistry.sumSumAssuredIssued(List.of(m)))
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void memberCoverExitedLowersInForceSumAssuredWithoutTouchingNewBusiness() {
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverExited(new BigDecimal("400000.00"));

        assertThat(MetricReaderRegistry.cumulativeSumAssured(List.of(m), PERIOD))
            .isEqualByComparingTo("600000.00");
        assertThat(MetricReaderRegistry.sumSumAssuredIssued(List.of(m)))
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void memberCoverDoesNotMoveAnyPolicyCount() {
        // A scheme is ONE policy however many borrowers are on it. Counting a joiner as a policy
        // would report one lender's monthly file as several hundred new contracts.
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverAdded(new BigDecimal("250000.00"));
        m.applyMemberCoverExited(new BigDecimal("100000.00"));

        assertThat(MetricReaderRegistry.cumulativePolicyCount(List.of(m), PERIOD)).isEqualTo(1);
    }

    @Test
    void bothMeasuresAreGrossAndRefuseANegativeAmount() {
        // The columns carry CHECK (>= 0). A caller passing a signed delta straight through would
        // fail at the database with a constraint name and no explanation; fail here instead,
        // where the message can say which call was wrong.
        PolicyMovement m = movement();
        assertThatThrownBy(() -> m.applyMemberCoverAdded(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.applyMemberCoverExited(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./mvnw -q test -Dtest=PolicyMovementArithmeticTest
```
Expected: compilation failure — `applyMemberCoverAdded` does not exist.

- [ ] **Step 3: Write the migration**

Create `backend/db-migrations/regreporting/V5__member_movement_columns.sql`:

```sql
-- Membership change is a movement, and until now it was not recorded anywhere.
--
-- A group scheme's sum assured is the total of its member schedule. policy_dimension captures
-- that total once, at activation, and nothing has ever updated it: policy.GroupMemberAdded and
-- policy.GroupMemberExited had no consumer at all. On an employer scheme that is occasional
-- drift. On credit life, members are added every month in files of several hundred and exited on
-- every settled claim, so SUM_ASSURED_IN_FORCE drifts continuously and in both directions
-- (design spec 2.14).
--
-- No return is filed from it YET: the only return definition on the platform is the placeholder
-- this same migration directory seeds, whose own description says its line codes are invented and
-- which exists under one hardcoded tenant, pending the TIRA catalogue (C2). That is not a reason
-- to defer this. These movement rows are the permanent record; movement never captured cannot be
-- reconstructed afterwards from anything, whatever the final return turns out to ask for.
--
-- TWO NEW COLUMNS RATHER THAN REUSING sum_assured_issued, and this is a decision, not caution.
-- That column feeds two metrics: SUM_ASSURED_IN_FORCE (a STOCK figure, issued minus terminated,
-- which is the one that is wrong) and NEW_BUSINESS_SUM_ASSURED (a FLOW figure, issued alone,
-- which is correct today). Whether a borrower enrolled onto an existing scheme counts as new
-- business written is a real actuarial question that nobody has answered, and answering it
-- silently inside a fix for a different metric is how a regulatory number changes without anyone
-- deciding it should. These columns are read by the STOCK metric and ignored by the FLOW metric.
-- If Finance later rules that joiners ARE new business, including them becomes a deliberate
-- one-line change in MetricReaderRegistry.sumSumAssuredIssued.
ALTER TABLE regreporting.policy_movement
    ADD COLUMN sum_assured_member_added NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN sum_assured_member_exited NUMERIC(19,2) NOT NULL DEFAULT 0;

-- Folded into the EXISTING constraint rather than added as a second one, so there is one place
-- that says what a movement measure may hold. Every measure here is a GROSS non-negative figure
-- and the sign lives in which column is incremented -- see V2's own note on why this is >= 0 and
-- not > 0: an upsert creates the row with zeros and then increments exactly one column, so zero
-- is the normal value for every other cause in that period.
ALTER TABLE regreporting.policy_movement
    DROP CONSTRAINT policy_movement_non_negative;

ALTER TABLE regreporting.policy_movement
    ADD CONSTRAINT policy_movement_non_negative CHECK (
        policies_issued >= 0 AND policies_reinstated >= 0 AND policies_lapsed >= 0
        AND policies_matured >= 0 AND policies_claim_terminated >= 0
        AND sum_assured_issued >= 0 AND sum_assured_terminated >= 0
        AND sum_assured_member_added >= 0 AND sum_assured_member_exited >= 0);

COMMENT ON COLUMN regreporting.policy_movement.sum_assured_member_added IS
    'Cover added to a group scheme by members joining after activation. Raises '
    'SUM_ASSURED_IN_FORCE; deliberately NOT part of NEW_BUSINESS_SUM_ASSURED.';
COMMENT ON COLUMN regreporting.policy_movement.sum_assured_member_exited IS
    'Cover removed from a group scheme by members leaving. Lowers SUM_ASSURED_IN_FORCE. Does '
    'NOT include the final close-out when the last member leaves -- that arrives as '
    'policy.PolicySurrendered and lands in sum_assured_terminated.';
```

- [ ] **Step 4: Add the two measures to `PolicyMovement`**

In `PolicyMovement.java`, beside the existing `sumAssuredTerminated` field:

```java
    /** Cover added by members joining a scheme after activation. Read by SUM_ASSURED_IN_FORCE
     * and deliberately not by NEW_BUSINESS_SUM_ASSURED -- see regreporting/V5. */
    @Column(name = "sum_assured_member_added", nullable = false)
    private BigDecimal sumAssuredMemberAdded = BigDecimal.ZERO;

    @Column(name = "sum_assured_member_exited", nullable = false)
    private BigDecimal sumAssuredMemberExited = BigDecimal.ZERO;
```

and beside the existing `apply*` methods:

```java
    /**
     * Cover a joining member brought onto the scheme. Takes a POSITIVE magnitude: the direction
     * is carried by which method you call, exactly as it is for every other measure on this
     * entity, and the column's CHECK enforces it at the database besides.
     */
    public void applyMemberCoverAdded(BigDecimal amount) {
        this.sumAssuredMemberAdded = this.sumAssuredMemberAdded.add(requirePositiveMagnitude(amount));
        this.updatedAt = Instant.now();
    }

    /** Cover a leaving member took off the scheme. Positive magnitude, as above. */
    public void applyMemberCoverExited(BigDecimal amount) {
        this.sumAssuredMemberExited = this.sumAssuredMemberExited.add(requirePositiveMagnitude(amount));
        this.updatedAt = Instant.now();
    }

    /** Fails HERE rather than at the CHECK constraint, so the error names the call rather than
     * the column. A caller handing a signed delta straight through is the mistake this catches. */
    private static BigDecimal requirePositiveMagnitude(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(
                "A movement measure is a gross non-negative magnitude; got " + amount);
        }
        return amount;
    }

    public BigDecimal getSumAssuredMemberAdded() { return sumAssuredMemberAdded; }
    public BigDecimal getSumAssuredMemberExited() { return sumAssuredMemberExited; }
```

`this.updatedAt = Instant.now();` is written inline on purpose, not extracted into a helper:
verified 2026-09-23, all five existing `apply*` methods on this entity stamp it exactly that way
and there is no `touch()`. Introducing one here would leave five call sites on the old idiom and
two on a new one.

- [ ] **Step 5: Let `PolicyDimension` be restated**

In `PolicyDimension.java`, after the getters:

```java
    /**
     * The scheme's total cover after a member joined or left.
     *
     * <p>The only mutation on this entity. Everything else about a policy dimension is fixed at
     * activation; the sum assured is not, because on a group scheme it is the total of a member
     * schedule that changes every month.
     *
     * <p>Refuses a non-positive total, and that is not defensive coding -- {@code
     * policy_dimension_sum_assured_positive} forbids zero, and a scheme whose total has reached
     * zero is one whose last member has left. That case is the close event's business
     * ({@code policy.PolicySurrendered} terminates the last recorded total); a restatement to
     * zero here would both violate the constraint and remove cover the close is about to remove
     * again.
     */
    public void restateSumAssured(BigDecimal newTotal) {
        if (newTotal == null || newTotal.signum() <= 0) {
            throw new IllegalArgumentException("A policy dimension's sum assured must stay positive; "
                + "a scheme restated to " + newTotal + " is one the close event owns, not this one");
        }
        this.sumAssuredAmount = newTotal;
    }
```

- [ ] **Step 6: Include the new measures in the STOCK metric only**

In `MetricReaderRegistry.java`, replace the body of `cumulativeSumAssured`:

```java
    /** Cumulative in-force sum assured as of {@code asOfPeriod}: what was issued and what members
     * brought, less what terminated and what members took away, summed over every row with
     * {@code period <= asOfPeriod}.
     *
     * <p>The two member measures joined this sum in Plan 5. Before that a group scheme's figure
     * was frozen at its activation total however many borrowers had since joined or been paid
     * out — see db-migrations/regreporting/V5. */
    public static BigDecimal cumulativeSumAssured(List<PolicyMovement> movements, String asOfPeriod) {
        BigDecimal total = BigDecimal.ZERO;
        for (PolicyMovement m : movements) {
            if (m.getPeriod().compareTo(asOfPeriod) > 0) continue;
            total = total
                .add(m.getSumAssuredIssued())
                .add(m.getSumAssuredMemberAdded())
                .subtract(m.getSumAssuredTerminated())
                .subtract(m.getSumAssuredMemberExited());
        }
        return total;
    }
```

Leave `sumSumAssuredIssued` **exactly as it is**, and add one line of comment above it so the omission reads as a decision:

```java
    /**
     * FLOW: NEW_BUSINESS_SUM_ASSURED. Deliberately excludes sum_assured_member_added, and that is
     * a preserved STATUS QUO rather than a claim that excluding it is right.
     *
     * <p>Whether a borrower joining an existing scheme is "new business written" is an actuarial
     * question nobody has answered. As it stands this figure counts policy activations only, so a
     * credit-life scheme that opens with one borrower and enrols five hundred over the year
     * reports that one borrower's cover -- an understatement, if the question is ever answered the
     * other way. Changing a reporting definition was not Plan 5's job; recording that it looks
     * wrong is.
     *
     * <p>If it is answered the other way, this becomes one {@code .add(m.getSumAssuredMemberAdded())}
     * -- the column already holds the right number. That is exactly why Plan 5 added separate
     * columns instead of merging into sum_assured_issued: merged, the distinction could never be
     * recovered. See db-migrations/regreporting/V5.
     */
    public static BigDecimal sumSumAssuredIssued(List<PolicyMovement> movements) {
```

- [ ] **Step 7: Run the unit test**

```bash
cd backend && ./mvnw -q test -Dtest=PolicyMovementArithmeticTest
```
Expected: **4 tests, 0 failures.**

- [ ] **Step 8: Add V5 to every test that applies regreporting migrations**

Do **not** grep for the V3 line and append after it. Nine files apply regreporting migrations and
they do not all end in the same place — this is the exact trap that makes migration drift a
recurring failure in this repo. Enumerate them, and append after whatever each one's LAST
regreporting entry is:

```bash
cd backend && for f in $(grep -rln "db-migrations/regreporting/" src/test --include=*.java); do \
  echo "$(grep -o 'db-migrations/regreporting/V[0-9]*' "$f" | sort -u | tail -1)  <- $f"; done
```

Expected, verified 2026-09-23:

| Test class | Last regreporting migration it applies |
|---|---|
| `AppRolePrivilegesIntegrationTest` | V3 |
| `RowLevelSecurityIntegrationTest` | V3 |
| `regreporting/HistoricalPeriodReturnTest` | V3 |
| `regreporting/MissingDimensionTest` | V3 |
| `regreporting/ProjectionEndToEndTest` | V3 |
| `regreporting/RegreportingContractTest` | V3 |
| `regreporting/application/MovementConcurrencyTest` | V3 |
| `regreporting/application/RegreportingApiIntegrationTest` | V3 |
| **`regreporting/CumulativeMetricTest`** | **none — it is a PURE UNIT TEST** |

`CumulativeMetricTest` needs NO migration entry, and the reason matters: it has no
`@Testcontainers` and no container at all — it unit-tests `cumulativeSumAssured` directly, which
is the exact function Step 6 changes. (An earlier draft of this plan claimed it "stops at V2".
That was wrong: the only `regreporting/V2` string in the file is inside a javadoc comment, which
a naive grep matches. Verified 2026-09-23 by running it — 8 tests, no container, 4.3s.) Run it
alongside the new arithmetic test in Step 7; it is the existing guard on the function being
changed.

In each file add `"db-migrations/regreporting/V5__member_movement_columns.sql"` after that file's
own last regreporting entry. Watch the line ending: the last entry usually closes the argument
list with `);`, so the closing paren moves onto the new line.

- [ ] **Step 9: Clean-compile, because a signature changed**

```bash
cd backend && ./mvnw clean test-compile
```
Expected: plain `BUILD SUCCESS`. Do not pipe this through a narrow grep; Maven's incremental compiler will not recompile unchanged tests, and only `clean` surfaces a break in one.

- [ ] **Step 10: Commit**

```bash
git add backend/db-migrations/regreporting/V5__member_movement_columns.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/PolicyMovementArithmeticTest.java
git commit -m "feat(regreporting): member cover is a movement, and the flow metric holds still"
```

---

## Task 2: The listener, idempotent by arithmetic

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/application/PolicyEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/MemberMovementProjectionTest.java`

**Interfaces:**
- Consumes from Task 1: `PolicyMovement.applyMemberCoverAdded/applyMemberCoverExited`, `PolicyDimension.restateSumAssured`.
- Produces, for Task 3: two live cases on `PolicyEventListener.onDomainEvent` — `policy.GroupMemberAdded` and `policy.GroupMemberExited`.

- [ ] **Step 1: Write the failing test**

Create `MemberMovementProjectionTest.java`. Model the container, `@DynamicPropertySource`, migration list and `transactionTemplate().executeWithoutResult(...)` publishing idiom on the existing `ProjectionEndToEndTest` — copy them rather than inventing a variant.

```java
    private static final String PERIOD = "2026-Q3";
    private static final String CURRENCY = "TZS";
    private static final LocalDate ISSUED = LocalDate.of(2026, 8, 3);

    /** Publishes the real policy.PolicyActivated shape, which is the only event that creates the
     * dimension row every later movement depends on. */
    private void activateScheme(UUID tenantId, String policyNumber, UUID productId, String total) {
        publish(tenantId, "policy.PolicyActivated", Map.of(
            "policyNumber", policyNumber,
            "productId", productId,
            "productCategory", "CREDIT_LIFE",
            "issueDate", ISSUED.toString(),
            "sumAssured", Map.of("amount", total, "currencyCode", CURRENCY),
            "premium", Map.of("amount", "52000.00", "currencyCode", CURRENCY)));
    }

    private void memberAdded(UUID tenantId, String policyNumber, String schemeTotal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("memberPartyId", null);          // a freeform borrower has no party id
        payload.put("memberType", "FREEFORM");
        payload.put("memberName", "Amina Hassan Mwinyi");
        payload.put("joinedOn", ISSUED.toString());
        payload.put("coveredAmount", Map.of("amount", "250000.00", "currencyCode", CURRENCY));
        payload.put("underwritingStatus", "WITHIN_FCL");
        payload.put("schemeTotalCovered", Map.of("amount", schemeTotal, "currencyCode", CURRENCY));
        publish(tenantId, "policy.GroupMemberAdded", payload);
    }

    private void memberExited(UUID tenantId, String policyNumber, String schemeTotal, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyMemberId", UUID.randomUUID());
        payload.put("leftOn", ISSUED.plusMonths(6).toString());
        payload.put("reason", reason);
        payload.put("schemeTotalCovered", Map.of("amount", schemeTotal, "currencyCode", CURRENCY));
        publish(tenantId, "policy.GroupMemberExited", payload);
    }

    @Test
    void aJoiningMemberRaisesInForceSumAssured() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-JOIN-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(inForceSumAssured(tenantId, productId)).isEqualByComparingTo("1250000.00");
        assertThat(newBusinessSumAssured(tenantId, productId)).isEqualByComparingTo("1000000.00");
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("1250000.00");
    }

    @Test
    void aLeavingMemberLowersInForceSumAssured() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-LEAVE-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberExited(tenantId, scheme, "600000.00", "CLAIM_SETTLED");

        assertThat(inForceSumAssured(tenantId, productId)).isEqualByComparingTo("600000.00");
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("600000.00");
    }

    @Test
    void aRedeliveredMemberEventChangesNothing() {
        // THE REASON THIS PROJECTS A DELTA RATHER THAN THE MEMBER'S OWN COVER. regreporting has
        // no de-duplication anywhere -- every other handler in this module double-counts on
        // redelivery. Here the second delivery finds the dimension already equal to the total it
        // carries, computes a delta of zero, and does nothing. Idempotency out of the arithmetic.
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-REDLV-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberAdded(tenantId, scheme, "1250000.00");
        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(inForceSumAssured(tenantId, productId)).isEqualByComparingTo("1250000.00");
    }

    @Test
    void theLastMemberLeavingIsLeftToTheCloseEvent() {
        // THE MOST IMPORTANT TEST IN THIS PLAN. When the last member goes, PolicyApiImpl sets the
        // scheme total to ZERO and closes the scheme, and policy.PolicySurrendered then terminates
        // the last recorded total. If this handler also acted it would (a) try to write 0 into a
        // column whose CHECK forbids it and (b) remove cover the close is about to remove again.
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String scheme = "POL-LAST-" + shortId();
        activateScheme(tenantId, scheme, productId, "1000000.00");

        memberExited(tenantId, scheme, "0.00", "CLAIM_SETTLED");

        // Untouched: the dimension still carries the last positive total, and no member movement
        // was recorded.
        assertThat(dimensionTotal(tenantId, scheme)).isEqualByComparingTo("1000000.00");
        assertThat(inForceSumAssured(tenantId, productId)).isEqualByComparingTo("1000000.00");

        // And the close event then takes the whole scheme out exactly once.
        publish(tenantId, "policy.PolicySurrendered", Map.of(
            "policyNumber", scheme,
            "surrenderedAt", Instant.now().toString()));
        assertThat(inForceSumAssured(tenantId, productId)).isEqualByComparingTo("0.00");
    }

    @Test
    void aMemberEventForAnUnknownSchemeIsCountedAndDropped() {
        // A scheme reginsurance never saw PolicyActivated for -- a pre-M8 policy, or an event lost
        // before this listener existed. Dropping it silently is how a projection goes quietly
        // wrong; the counter is the signal.
        UUID tenantId = UUID.randomUUID();
        String scheme = "POL-UNKNOWN-" + shortId();
        double before = unattributedCount("policy.GroupMemberAdded");

        memberAdded(tenantId, scheme, "1250000.00");

        assertThat(unattributedCount("policy.GroupMemberAdded")).isEqualTo(before + 1);
    }
```

Helpers this test needs (write them alongside):

```java
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }

    private void publish(UUID tenantId, String eventType, Map<String, Object> payload) {
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status ->
            eventPublisher.publishEvent(DomainEventEnvelope.of(eventType, tenantId, payload)));
    }

    /** Every test here uses a fresh tenant with one product, so the existing period-scoped
     * finders are enough and no new repository method is needed. cumulativeSumAssured filters by
     * period itself; passing the <=-period rows is what it expects. */
    private BigDecimal inForceSumAssured(UUID tenantId, UUID productId) {
        TenantContext.set(tenantId);
        return MetricReaderRegistry.cumulativeSumAssured(
            policyMovementRepository.findByTenantIdAndPeriodLessThanEqual(tenantId, PERIOD), PERIOD);
    }

    /** sumSumAssuredIssued is a FLOW metric and expects the rows for ONE period only. */
    private BigDecimal newBusinessSumAssured(UUID tenantId, UUID productId) {
        TenantContext.set(tenantId);
        return MetricReaderRegistry.sumSumAssuredIssued(
            policyMovementRepository.findByTenantIdAndPeriod(tenantId, PERIOD));
    }

    private BigDecimal dimensionTotal(UUID tenantId, String policyNumber) {
        TenantContext.set(tenantId);
        return policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow(() -> new AssertionError("No policy_dimension row for " + policyNumber))
            .getSumAssuredAmount();
    }

    private double unattributedCount(String eventType) {
        return meterRegistry.counter("lifeplatform_regreporting_unattributed_movement_total",
            "eventType", eventType).count();
    }
```

`PolicyMovementRepository` already carries everything these helpers need — verified 2026-09-23,
it has `findByTenantIdAndPeriodAndProductId` (Optional), `findByTenantIdAndPeriodLessThanEqual`
and `findByTenantIdAndPeriod`. **Add no repository method.** There is deliberately no
product-scoped finder in the helpers above: each test uses a fresh tenant carrying one product,
so tenant-and-period scoping already isolates it, and a new finder would be a method with one
caller that exists only in tests.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./mvnw -q test -Dtest=MemberMovementProjectionTest
```
Expected: every test fails — no dimension is restated and no movement is recorded, because the listener has no member cases.

- [ ] **Step 3: Add the two cases and the shared handler**

In `PolicyEventListener.java`, in the switch:

```java
            case "policy.GroupMemberAdded" -> withTenant(envelope, this::handleSchemeTotalRestated);
            case "policy.GroupMemberExited" -> withTenant(envelope, this::handleSchemeTotalRestated);
```

and the handler:

```java
    /**
     * One handler for both member events, because they say the same thing: <b>this scheme's total
     * cover is now X</b>. What changed, and by how much, is the difference between that and the
     * total this module last recorded.
     *
     * <p><b>A delta, not the member's own cover.</b> Three reasons, and the third is the one that
     * matters. {@code GroupMemberExited} carries no {@code coveredAmount} to use. A member's own
     * cover is not necessarily the change in the scheme's total. And {@code regreporting} has no
     * de-duplication anywhere -- these listeners are {@code AFTER_COMMIT} with at-least-once
     * delivery, and every other handler in this module double-counts on redelivery. A delta
     * cannot: the second delivery finds the dimension already equal to the total it carries and
     * computes zero. The projection also self-heals, correcting drift from a dropped event on the
     * next one rather than carrying it forever.
     *
     * <p><b>A total of zero is skipped entirely, and that is load-bearing.</b> When the last
     * active member leaves, {@code PolicyApiImpl.exitOneMember} restates the scheme to zero and
     * then closes it, and the resulting {@code policy.PolicySurrendered} already terminates the
     * last recorded total through {@link #applyTerminationMovement}. Acting here as well would
     * write a zero into a column whose CHECK forbids it, and would remove cover the close event
     * is about to remove again -- the whole scheme counted out twice.
     *
     * <p>Opening-schedule members need no handling: {@code issueGroupScheme} publishes no
     * {@code GroupMemberAdded} for them, because their cover is already inside the
     * {@code sumAssured} on {@code PolicyActivated}. Verified, not assumed.
     */
    private void handleSchemeTotalRestated(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemeTotal = (Map<String, Object>) payload.get("schemeTotalCovered");
        BigDecimal newTotal = new BigDecimal((String) schemeTotal.get("amount"));

        if (newTotal.signum() <= 0) {
            log.info("Scheme {} restated to {} -- its last member has left, so the close event owns "
                + "taking it out of force. No member movement recorded.", policyNumber, newTotal);
            return;
        }

        Optional<PolicyDimension> maybeDimension =
            policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeDimension.isEmpty()) {
            // Same posture as applyTerminationMovement's missing-dimension branch: counted as well
            // as logged, because a dropped movement is a shilling missing from every figure later
            // computed off these rows, and nothing else would ever show it.
            log.warn("Member movement on scheme {} has no policy_dimension row -- no productId to "
                + "attribute it to, so it is dropped", policyNumber);
            meterRegistry.counter(ProjectionSupport.UNATTRIBUTED_MOVEMENT_COUNTER,
                "eventType", "policy.GroupMemberAdded").increment();
            return;
        }
        PolicyDimension dimension = maybeDimension.get();
        BigDecimal delta = newTotal.subtract(dimension.getSumAssuredAmount());
        if (delta.signum() == 0) {
            return; // a redelivery, or a member whose cover was nil
        }

        // The period is the one the CHANGE falls in, not the scheme's issue quarter: a borrower
        // enrolled in Q4 is Q4's movement even on a scheme issued in Q2.
        String period = ProjectionSupport.quarterOfDate(changeDateOf(payload));
        PolicyMovement movement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, dimension.getProductId())
            .orElseGet(() -> new PolicyMovement(tenantId, period, dimension.getProductId(),
                dimension.getSumAssuredCurrency()));
        if (delta.signum() > 0) {
            movement.applyMemberCoverAdded(delta);
        } else {
            movement.applyMemberCoverExited(delta.negate());
        }
        policyMovementRepository.save(movement);

        dimension.restateSumAssured(newTotal);
        policyDimensionRepository.save(dimension);
    }

    /** {@code joinedOn} on an add, {@code leftOn} on an exit -- the date the cover actually
     * changed, which is what the period must be derived from. */
    private static String changeDateOf(Map<String, Object> payload) {
        Object joined = payload.get("joinedOn");
        return (String) (joined != null ? joined : payload.get("leftOn"));
    }
```

- [ ] **Step 4: Run the test**

```bash
cd backend && ./mvnw -q test -Dtest=MemberMovementProjectionTest
```
Expected: **5 tests, 0 failures.**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting/application/PolicyEventListener.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/MemberMovementProjectionTest.java
git commit -m "feat(regreporting): a scheme's cover follows its member schedule"
```

---

## Task 3: The real chain, on a real credit-life scheme

**Files:**
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/ProjectionEndToEndTest.java`
- Modify: `backend/api/asyncapi-events.yaml` (register the two new consumers)

**Interfaces:**
- Consumes from Task 2: the two live listener cases.

- [ ] **Step 1: Write the failing test**

Add to `ProjectionEndToEndTest`, using that class's existing fixtures for issuing a scheme and settling a claim — do not hand-publish events here; the point of this task is that the real chain produces the right number.

```java
    @Test
    void aBorrowerJoiningAndThenDyingMovesTheReturnInBothDirections() {
        // THE SPEC'S OWN CASE (2.14): "members are added every month and exited on every
        // settlement, so it is continuous -- and it lands in a TIRA return." Hand-published
        // events proved the arithmetic in Task 2; this proves the real chain reaches it.
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        CreditLifeScheme scheme = issueCreditLifeSchemeWithOneBorrower(tenantId);   // 2,400,000

        BigDecimal atIssue = inForceSumAssured(tenantId, scheme.productId());
        assertThat(atIssue).isEqualByComparingTo("2400000.00");

        // A second borrower enrolled through the real addMember path.
        policyApi.addMember(scheme.policyNumber(), secondBorrower(), "staff-1");
        assertThat(inForceSumAssured(tenantId, scheme.productId()))
            .isEqualByComparingTo("3600000.00");

        // And the first one dies. The settled claim exits them, and the cover comes off.
        settleDeathClaimFor(tenantId, scheme, scheme.firstMemberId());
        assertThat(inForceSumAssured(tenantId, scheme.productId()))
            .isEqualByComparingTo("1200000.00");

        // The flow metric never moved. It counts new business written, and this scheme was
        // written once.
        assertThat(newBusinessSumAssured(tenantId, scheme.productId()))
            .isEqualByComparingTo("2400000.00");
    }
```

> `issueCreditLifeSchemeWithOneBorrower`, `secondBorrower` and `settleDeathClaimFor` are fixtures
> to write in this class. Model them on `CreditLifeClaimEndToEndTest`'s `issueCreditLifeScheme`
> and its settlement chain — that class already issues a credit-life scheme, registers, assesses
> and approves a claim, and drives the EFT confirmation that settles it. Copy those fixtures
> rather than re-deriving them, and add the `payment` migrations that chain needs.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./mvnw -q test -Dtest=ProjectionEndToEndTest
```
Expected: the new test fails on the second assertion — the joiner does not move the figure until Task 2's listener is wired into a run that also applies `regreporting/V5`.

- [ ] **Step 3: Make it pass**

No production change should be needed; Tasks 1 and 2 did the work. If it does not pass, the likely causes in order are: `regreporting/V5` missing from this class's migration list, the `payment` migrations missing so the settlement never completes, or the claim never reaching `SETTLED` because the EFT was not confirmed.

- [ ] **Step 4: Register the consumers in the event catalogue**

In `backend/api/asyncapi-events.yaml`, both channels currently name no regreporting consumer. Update their `description` lines:

```yaml
  policy.GroupMemberAdded:
    description: >-
      Producer: policy. Consumers: regreporting (raises SUM_ASSURED_IN_FORCE by the delta
      between schemeTotalCovered and the last recorded policy_dimension total), audit.
  policy.GroupMemberExited:
    description: >-
      Producer: policy. Consumers: billing (premium credit on a refundable exit), regreporting
      (lowers SUM_ASSURED_IN_FORCE by the same delta; a restatement to ZERO is skipped, because
      the last member leaving closes the scheme and policy.PolicySurrendered takes it out of
      force), audit.
```

> Check each channel's existing `description` before replacing it and keep any consumer already
> listed. Adding regreporting must not quietly drop billing.

- [ ] **Step 5: Remove the stale comment in `policy`**

`PolicyApiImpl.exitOneMember` carries a comment that is about to become false:

```java
        // regreporting still does not listen to this, nor to policy.GroupMemberAdded, so
        // policy_dimension's sum assured goes stale on a joiner and on a leaver alike. A known
        // gap (spec 2.14), not one this method introduced.
```

Replace it with:

```java
        // regreporting consumes this and policy.GroupMemberAdded as of Plan 5: the scheme total
        // below is what it restates its own dimension to, and the delta is what reaches
        // SUM_ASSURED_IN_FORCE. Keep schemeTotalCovered in this payload -- it is not decoration,
        // it is the whole input to that projection.
```

- [ ] **Step 6: Run the full suite**

```bash
cd backend && ./mvnw clean test
```
Expected: **the plan-4 baseline plus the ten tests this plan adds, 0 failures, 0 errors.**

Stop the dev backend first if one is running, and make sure no other Maven is running — a
second Maven sharing `target/` corrupts the run and produces failures that are not code.

- [ ] **Step 7: Commit**

```bash
git add backend/api/asyncapi-events.yaml \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/regreporting/ProjectionEndToEndTest.java
git commit -m "test(regreporting): the real chain moves the return in both directions"
```

---

## Self-review against the spec

**Spec coverage.** §2.14 is the whole of this plan: Task 1 gives the movement somewhere honest to
land, Task 2 consumes both events, Task 3 proves the real chain. Nothing else in §2.14 is
outstanding.

**Deliberately NOT in this plan:**

- **A lives-covered metric.** `POLICIES_IN_FORCE` counts *policies*, and a scheme is one policy
  however many borrowers sit on it. Whether TIRA wants lives as well is a question for whoever
  owns the return definition (C2), and inventing a second count here would put a number into a
  return that nobody has asked for.
- **De-duplication for the other four handlers.** `regreporting` double-counts a redelivered
  `PolicyLapsed`, `PolicyMatured`, `PolicySurrendered` or `PolicyReinstated` today. Plan 5's
  handler happens not to, because a delta cannot, but fixing the module-wide property is its own
  piece of work with its own test surface.
- **Backfilling existing schemes.** Every scheme already in a deployed database carries its
  activation total, and there is no event history to replay. The projection self-heals from the
  next membership change on that scheme; a backfill would need a policy-side reconciliation job,
  which is a different plan.

**One thing a reviewer should push back on if they disagree:** Decision 2 — and specifically the
*default*, not the columns.

Separate columns are not really arguable: merging member cover into `sum_assured_issued` destroys
the ability to tell the two kinds of cover apart, permanently and unrecoverably. Keep those.

What is arguable is that this plan leaves `NEW_BUSINESS_SUM_ASSURED` excluding member-added cover.
An earlier draft justified that by calling the metric "correct and filed"; **both were wrong** —
the only return definition referencing it is a seeded placeholder that says its own line codes are
invented, and its PL-04 line would report a five-hundred-borrower year as one borrower's worth of
new business. So the honest position is: this plan preserves the status quo because changing a
reporting definition is not its job, while recording plainly that the status quo looks wrong for
this product. If the reviewer's view is that a credit-life borrower is new business written — a
defensible and possibly correct view — the change is one `.add(m.getSumAssuredMemberAdded())` in
`sumSumAssuredIssued`, and `PolicyMovementArithmeticTest` names the two assertions that move.

**An open question this plan surfaces rather than answers**, for whoever owns C2: PL-04 today
understates new business on any scheme that grows after activation. That is true of employer
schemes as well as credit life; credit life only makes it loud.

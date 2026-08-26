# Staff List Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Policies and Claims a reliable newest-first default order and a free-text search bar, and replace all 11 native `<input type="date">` fields across 5 staff forms with a shared popover-anchored `DatePicker` built on `react-day-picker`.

**Architecture:** Backend: one `Sort` added to two existing `PageRequest.of(...)` call sites (no repository change), plus a `q` predicate added as a fifth column to two already-null-safe multi-predicate JPQL queries (`PolicyRepository.search`/`ClaimRepository.search`), exactly the pattern the party-picker's `q` used. Frontend: one new shared `DatePicker` component (Radix Popover shell, matching `PartyPicker`'s shape) wired into 5 forms via `react-hook-form`'s `Controller`, and a debounced search input added to the two list pages' existing filter-chip row.

**Tech Stack:** Spring Boot / JPA (backend), React 19 / TypeScript / react-hook-form / Zod / Zustand / `react-day-picker@10.0.1` / Radix Popover / Playwright / Vitest (frontend).

**Design doc:** `backend/docs/superpowers/specs/2026-08-26-staff-list-improvements-design.md`

## Global Constraints

- **No fabricated identities in e2e tests.** Every e2e test runs against the real stack (real Keycloak, real backend, real Postgres) — never mock auth or fake a JWT.
- **Backend build/test commands run on the host, in the foreground, never inside Docker.** From `backend/`: `export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64" && ./mvnw -o test -Dtest=<ClassName>`.
- **Run the narrowest backend test set that could detect a regression** for every task except the final one.
- **The dev backend process must be manually restarted** (`SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`, backgrounded, from `backend/`) before any e2e test can see a backend code change.
- **Frontend verification**, from `frontend/`: `npx tsc --noEmit`, `npx eslint .`, `npx vitest run`, `npm run build` — all four clean before any task is considered done.
- **e2e cadence**: do not run the full Playwright suite after every task. Run only the spec file(s) each task actually touches. Run the full suite once, in the final task.
- **CRLF/LF**: this repo's Windows checkout normalizes line endings on `git add`/`commit` automatically — the "LF will be replaced by CRLF" warning on `git commit` is expected, not an error.
- **`ISO_DATE_PATTERN` already exists** in `frontend/src/lib/patterns.ts` — reuse it, never redeclare a local copy. Same for `formatDate` in `frontend/src/lib/dates.ts`.
- **`react-day-picker@10.0.1` is the real current latest** (the design doc's "v9" was a design-time approximation) and is **already installed** — `frontend/package.json` already has `"react-day-picker": "^10.0.1"` and `node_modules` already has it, confirmed directly by installing and reading its real shipped type declarations (`node_modules/react-day-picker/dist/esm/types/props.d.ts`, `.../UI.d.ts`) before writing this plan, not guessed from memory of an older major version. Task 6 does not need to run `npm install` again for the base package — only for `@radix-ui/react-popover` if it is not already a dependency (it already is, added by the party-picker plan; confirm, don't reinstall blindly).
- **Verified `react-day-picker` API surface this plan relies on** (so no task needs to re-derive it): `<DayPicker mode="single" selected={Date | undefined} onSelect={(date: Date | undefined) => void} month={Date} onMonthChange={(m: Date) => void} disabled={Matcher | Matcher[]} classNames={Partial<ClassNames>} />`; a `Matcher` for a range bound is `{ before: Date }` / `{ after: Date }`; the `Day` UI element renders a `<td>` carrying both the `day` class and any active modifier class (`selected`/`today`/`outside`/`disabled`) concatenated on that same `<td>` — NOT nested under `day_button` — so `classNames` overrides for those four keys apply directly, no child-selector needed; the CSS import path is `react-day-picker/style.css` (confirmed via the package's real `exports` map).

---

## File Structure

**Backend (all modifications, no new files):**
- `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java` — `searchPolicies` gains `Sort` on its `PageRequest` and a `q` param.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java` — `search` gains a `q` predicate.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java` / `.../policy/application/PolicyApiImpl.java` — `searchPolicies` gains `q`.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java` — `listClaims` gains `Sort` on its `PageRequest` and a `q` param.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimRepository.java` — `search` gains a `q` predicate.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/api/ClaimsApi.java` / `.../claims/application/ClaimsApiImpl.java` — `searchClaims` gains `q`.
- `backend/api/openapi/openapi-policy.yaml`, `backend/api/openapi/openapi-claims.yaml` — `q` added to `GET /policies`/`GET /claims` parameters.
- `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`, `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsContractTest.java` — new tests.

**Frontend (new):**
- `frontend/src/components/DatePicker.tsx` — the shared component.
- `frontend/src/components/DatePicker.test.tsx` — its unit tests.

**Frontend (modified):**
- `frontend/src/api/policies.ts`, `frontend/src/api/claims.ts` — `PolicySearchParams`/`ClaimSearchParams` gain `q`.
- `frontend/src/features/policies/PoliciesPage.tsx`, `frontend/src/features/claims/ClaimsPage.tsx` — search input added to the filter row.
- `frontend/src/features/claims/RegisterClaimPage.tsx` — 5 date fields.
- `frontend/src/features/party/OnboardCustomerPage.tsx` — 1 date field (the individual-registration form).
- `frontend/src/features/distribution/OnboardAgentPage.tsx` — 1 date field.
- `frontend/src/features/reinsurance/CreateTreatyPage.tsx` — 2 date fields.
- `frontend/src/features/products/PublishVersionForm.tsx` — 2 date fields.

**e2e (modified, enumerated per task below).**

---

### Task 1: Backend — newest-first default sort on Policies and Claims

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java:103-104`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java:129`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsContractTest.java`

**Interfaces:** none — no signature changes, purely default ordering.

- [ ] **Step 1: Write the failing tests**

In `PolicyContractTest.java`, add:

```java
    @Test
    void searchPoliciesOrdersNewestCreatedFirstByDefault() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Two real policies issued in sequence -- the second one issued must be
        // items[0], proving a real ORDER BY, not incidentally-already-sorted seed data.
        String firstPolicy = manualIssue(tenantId, "SORT-ORDER-FIRST").policyNumber();
        String secondPolicy = manualIssue(tenantId, "SORT-ORDER-SECOND").policyNumber();

        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].policyNumber").value(secondPolicy))
            .andExpect(jsonPath("$.items[1].policyNumber").value(firstPolicy));
    }
```

Confirmed real helper already in this file (`PolicyContractTest.java:187-189`): `private IssuedPolicy manualIssue(UUID tenantId, String productCode) throws Exception` — issues a real policy end-to-end (registers an applicant, publishes a product, opens an underwriting case, then `POST /policies/manual-issue`) and returns `IssuedPolicy(String policyNumber, UUID applicantId)`. The test above already calls it correctly — no further lookup needed.

In `ClaimsContractTest.java`, this file's real fixture helpers (confirmed at lines 151-192) are `buildFixture(UUID tenantId, String productCode): Fixture` (a `record Fixture(UUID applicantId, UUID productId, UUID productVersionId)`), `issuePolicy(UUID tenantId, Fixture fixture): String` (returns the policy number), and `registerDeathClaim(UUID tenantId, UUID claimantId, String policyNumber): UUID` (returns the claim id). Add:

```java
    @Test
    void listClaimsOrdersNewestCreatedFirstByDefault() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SORT-ORDER-PRODUCT");
        // Two distinct policies (not two claims on one policy, to sidestep any
        // undocumented one-claim-per-policy assumption elsewhere in this domain) --
        // both real, both against the same real applicant/product fixture.
        String policyA = issuePolicy(tenantId, fixture);
        String policyB = issuePolicy(tenantId, fixture);
        UUID claimId1 = registerDeathClaim(tenantId, fixture.applicantId(), policyA);
        UUID claimId2 = registerDeathClaim(tenantId, fixture.applicantId(), policyB);

        mockMvc.perform(get("/claims")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].claimId").value(claimId2.toString()))
            .andExpect(jsonPath("$.items[1].claimId").value(claimId1.toString()));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest,ClaimsContractTest
```

Expected: both new tests FAIL — with no `Sort`, `items[0]` is whatever Postgres happens to return, not reliably `secondPolicy`/`claimId2`. (This may occasionally pass by coincidence on an unindexed small table; if it does, re-run once — if it fails on the second run, the fix below is still required and correct. Do not skip the fix because of a coincidental pass.)

- [ ] **Step 3: Add the Sort**

In `PolicyController.java`, change (line ~103-104):

```java
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, status, agentOfRecordIds,
            PageRequest.of(page, Math.min(pageSize, 100)));
```

to:

```java
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, status, agentOfRecordIds,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
```

Add the import: `import org.springframework.data.domain.Sort;`

In `ClaimController.java`, change (line ~129):

```java
        Page<ClaimView> result = claimsApi.searchClaims(status, effectiveClaimantPartyId, policyNumbers,
            PageRequest.of(page, Math.min(pageSize, 100)));
```

to:

```java
        Page<ClaimView> result = claimsApi.searchClaims(status, effectiveClaimantPartyId, policyNumbers,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
```

Add the same `import org.springframework.data.domain.Sort;` if not already present.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest,ClaimsContractTest
```

Expected: both pass, plus every pre-existing test in both classes still passes (this is a default-ordering change, not a filter change — no existing assertion should depend on a specific unordered position).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsContractTest.java
git commit -m "feat(policy,claims): default GET /policies and GET /claims to newest-created-first"
```

---

### Task 2: Backend — `q` search on `GET /policies`

**Files:**
- Modify: `backend/api/openapi/openapi-policy.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`

**Interfaces:**
- Produces: `PolicyApi.searchPolicies(UUID policyholderPartyId, PolicyStatus status, Set<UUID> agentOfRecordIds, String q, Pageable pageable): Page<PolicyView>` — Task 4's frontend reaches this via `GET /policies?q=...`.

- [ ] **Step 1: Write the failing contract tests**

Add to `PolicyContractTest.java`:

```java
    @Test
    void searchPoliciesByQMatchesACaseInsensitiveSubstringOfPolicyNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = manualIssue(tenantId, "Q-SEARCH-FIXTURE").policyNumber();

        // Deliberately lowercased query against a real POL-XXXXXXXX (uppercase-hex)
        // policy number -- falsifies "ILIKE is inherently case-insensitive" against a
        // real row rather than trusting the SQL.
        mockMvc.perform(get("/policies")
                .queryParam("q", policyNumber.toLowerCase())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].policyNumber").value(policyNumber));
    }

    @Test
    void searchPoliciesByQCombinesWithStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = manualIssue(tenantId, "Q-COMBO-FIXTURE").policyNumber();

        // The real policy is ACTIVE (manualIssue's own fixture shape) -- filtering by
        // the SAME q with a status it does NOT have must return zero, proving q and
        // status are genuinely ANDed, not either-or.
        mockMvc.perform(get("/policies")
                .queryParam("q", policyNumber)
                .queryParam("status", "LAPSED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void searchPoliciesByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/policies")
                .queryParam("q", "NoPolicyAnywhereHasThisExactNonsenseNumber12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
```

Uses the same `manualIssue(UUID tenantId, String productCode): IssuedPolicy` helper from Task 1. Confirmed directly against `Policy.java` (the `issuePolicy` domain method sets `this.status = "ACTIVE"`): a freshly manually-issued policy is always `ACTIVE`, so `"LAPSED"` in the test above is a genuinely non-matching status — no adjustment needed.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest
```

Expected: compile succeeds (the `queryParam` call accepts any param name), but the new assertions fail since `q` is not yet threaded through — either the response is unfiltered (returns unrelated policies too, failing the `length()` assertion) or the param is silently ignored.

- [ ] **Step 3: Add `q` to the OpenAPI spec**

In `backend/api/openapi/openapi-policy.yaml`, inside `/policies: get: parameters:`, add:

```yaml
        - name: q
          in: query
          description: Free-text, case-insensitive substring match against policyNumber.
          schema: { type: string }
```

- [ ] **Step 4: Add `q` to `PolicyRepository.search`**

In `PolicyRepository.java`, replace the existing `search` method (the docstring above it stays; only the query and signature change):

```java
    @Query("SELECT p FROM Policy p WHERE p.tenantId = :tenantId "
        + "AND (:policyholderPartyId IS NULL OR p.policyholderPartyId = :policyholderPartyId) "
        + "AND (:status IS NULL OR p.status = :status) "
        + "AND (:agentOfRecordIds IS NULL OR p.agentOfRecordId IN :agentOfRecordIds) "
        + "AND (:q IS NULL OR LOWER(p.policyNumber) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Policy> search(@Param("tenantId") UUID tenantId,
                         @Param("policyholderPartyId") UUID policyholderPartyId,
                         @Param("status") String status,
                         @Param("agentOfRecordIds") Collection<UUID> agentOfRecordIds,
                         @Param("q") String q,
                         Pageable pageable);
```

- [ ] **Step 5: Thread `q` through `PolicyApi` and `PolicyApiImpl`**

In `PolicyApi.java`, change the `searchPolicies` signature:

```java
    Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Set<UUID> agentOfRecordIds, String q, Pageable pageable);
```

In `PolicyApiImpl.java`, replace the `searchPolicies` method body:

```java
    @Override
    public Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Set<UUID> agentOfRecordIds, String q, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Policy> page;
        // A present q ALSO routes through the wider `search` query, same reasoning as the
        // agentOfRecordIds branch immediately below: only the truly-unfiltered common case
        // stays on the fast derived-query methods.
        boolean hasQ = q != null && !q.isBlank();
        if (agentOfRecordIds != null || hasQ) {
            page = policyRepository.search(tenantId, policyholderPartyId, status != null ? status.name() : null,
                agentOfRecordIds, hasQ ? q.trim() : null, pageable);
        } else if (policyholderPartyId != null && status != null) {
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
```

- [ ] **Step 6: Thread `q` through `PolicyController`**

In `PolicyController.java`, update `searchPolicies`:

```java
    @GetMapping("/policies")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicySearchResponse> searchPolicies(
            @RequestParam(required = false) UUID policyholderPartyId,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectivePolicyholderPartyId = isCustomer(authentication)
            ? ownPartyIdOrThrow(jwt) : policyholderPartyId;
        Set<UUID> agentOfRecordIds = isAgent(authentication) ? resolveOwnAgentTeamOrThrow(jwt) : null;
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, status, agentOfRecordIds, q,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(PolicySearchResponse.from(result));
    }
```

(Only the parameter list and the `searchPolicies(...)` call change — the `effectivePolicyholderPartyId`/`agentOfRecordIds` lines and the rest of the method body are unchanged from Task 1's edit.)

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest
```

Expected: all tests in the class pass, including Task 1's ordering test and the three new `q` tests.

- [ ] **Step 8: Commit**

```bash
git add backend/api/openapi/openapi-policy.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyRepository.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/PolicyApi.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java
git commit -m "feat(policy): add free-text q search (by policyNumber) to GET /policies"
```

---

### Task 3: Backend — `q` search on `GET /claims`

**Files:**
- Modify: `backend/api/openapi/openapi-claims.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/api/ClaimsApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/application/ClaimsApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsContractTest.java`

**Interfaces:**
- Produces: `ClaimsApi.searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, String q, Pageable pageable): Page<ClaimView>` — Task 5's frontend reaches this via `GET /claims?q=...`.

- [ ] **Step 1: Write the failing contract tests**

Add to `ClaimsContractTest.java`, using the same real `buildFixture`/`issuePolicy`/`registerDeathClaim` helpers confirmed in Task 1:

```java
    @Test
    void listClaimsByQMatchesACaseInsensitiveSubstringOfPolicyNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "Q-SEARCH-CLAIM-PRODUCT");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        // Deliberately lowercased query against a real POL-XXXXXXXX (uppercase-hex)
        // policy number -- falsifies "ILIKE is inherently case-insensitive" against a
        // real row rather than trusting the SQL.
        mockMvc.perform(get("/claims")
                .queryParam("q", policyNumber.toLowerCase())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].claimId").value(claimId.toString()));
    }

    @Test
    void listClaimsByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/claims")
                .queryParam("q", "NoClaimAnywhereIsAgainstThisExactNonsensePolicy12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=ClaimsContractTest
```

Expected: compiles, new assertions fail (unfiltered or ignored `q`).

- [ ] **Step 3: Add `q` to the OpenAPI spec**

In `backend/api/openapi/openapi-claims.yaml`, inside `/claims: get: parameters:`, add:

```yaml
        - name: q
          in: query
          description: Free-text, case-insensitive substring match against the claim's policyNumber.
          schema: { type: string }
```

- [ ] **Step 4: Add `q` to `ClaimRepository.search`**

In `ClaimRepository.java`, replace the existing `search` method:

```java
    @Query("SELECT c FROM Claim c WHERE c.tenantId = :tenantId "
        + "AND (:claimantPartyId IS NULL OR c.claimantPartyId = :claimantPartyId) "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:policyNumbers IS NULL OR c.policyNumber IN :policyNumbers) "
        + "AND (:q IS NULL OR LOWER(c.policyNumber) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Claim> search(@Param("tenantId") UUID tenantId,
                        @Param("claimantPartyId") UUID claimantPartyId,
                        @Param("status") ClaimStatus status,
                        @Param("policyNumbers") Collection<String> policyNumbers,
                        @Param("q") String q,
                        Pageable pageable);
```

- [ ] **Step 5: Thread `q` through `ClaimsApi` and `ClaimsApiImpl`**

In `ClaimsApi.java`:

```java
    Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, String q, Pageable pageable);
```

In `ClaimsApiImpl.java`, replace the `searchClaims` method body:

```java
    @Override
    public Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, String q, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Claim> page;
        boolean hasQ = q != null && !q.isBlank();
        if (policyNumbers != null || hasQ) {
            page = claimRepository.search(tenantId, claimantPartyId, status, policyNumbers, hasQ ? q.trim() : null, pageable);
        } else if (claimantPartyId != null && status != null) {
            page = claimRepository.findByTenantIdAndClaimantPartyIdAndStatus(tenantId, claimantPartyId, status, pageable);
        } else if (claimantPartyId != null) {
            page = claimRepository.findByTenantIdAndClaimantPartyId(tenantId, claimantPartyId, pageable);
        } else if (status != null) {
            page = claimRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = claimRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(claim -> toView(claim, deriveContestabilityReview(claim)));
    }
```

- [ ] **Step 6: Thread `q` through `ClaimController`**

In `ClaimController.java`, update `listClaims`:

```java
    @GetMapping("/claims")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ClaimSearchResponseDto> listClaims(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) UUID claimantPartyId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectiveClaimantPartyId = isCustomer(authentication) ? ownPartyIdOrThrow(jwt) : claimantPartyId;
        Set<String> policyNumbers = isAgent(authentication)
            ? policyApi.policyNumbersForAgentTeam(ownAgentPartyIdOrThrow(jwt)) : null;
        Page<ClaimView> result = claimsApi.searchClaims(status, effectiveClaimantPartyId, policyNumbers, q,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(ClaimSearchResponseDto.from(result));
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=ClaimsContractTest
```

- [ ] **Step 8: Commit**

```bash
git add backend/api/openapi/openapi-claims.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimRepository.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/claims/api/ClaimsApi.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/claims/application/ClaimsApiImpl.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsContractTest.java
git commit -m "feat(claims): add free-text q search (by policyNumber) to GET /claims"
```

---

### Task 4: Frontend — search bar on `PoliciesPage`

**Files:**
- Modify: `frontend/src/api/policies.ts`
- Modify: `frontend/src/features/policies/PoliciesPage.tsx`
- Modify: `frontend/e2e/staff-policy-lifecycle.spec.ts` (or wherever a "search finds a real policy" assertion best fits — see Step 4)

**Interfaces:**
- Consumes: `GET /policies?q=...` from Task 2.

- [ ] **Step 1: Thread `q` through `api/policies.ts`**

In `frontend/src/api/policies.ts`, update `PolicySearchParams`:

```ts
export interface PolicySearchParams {
  status?: PolicyStatus;
  policyholderPartyId?: string;
  q?: string;
  page?: number;
  pageSize?: number;
}
```

In `searchPolicies`'s `params` object, add `q` alongside the existing conditional spreads:

```ts
    params: {
      ...(params.status ? { status: params.status } : {}),
      ...(params.policyholderPartyId ? { policyholderPartyId: params.policyholderPartyId } : {}),
      ...(params.q ? { q: params.q } : {}),
      page,
      pageSize,
    },
```

- [ ] **Step 2: Add the search input to `PoliciesPage`**

In `PoliciesPage.tsx`, add `useState` alongside the existing React import (it is already imported: `import { useEffect, useState, type ReactNode } from 'react';` — no import change needed) and a `Search` icon import:

```tsx
import { Plus, Search } from 'lucide-react';
```

Read the `q` param, keep a local debounced input value, and thread `q` into `loadList`/`update`:

```tsx
  const statusParam = params.get('status');
  const status: PolicyStatus | undefined =
    statusParam && (POLICY_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as PolicyStatus)
      : undefined;
  const qParam = params.get('q') ?? '';
  const [qInput, setQInput] = useState(qParam);
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = usePolicyStore((s) => s.list);
  const loadList = usePolicyStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(status ? { status } : {}), ...(qParam ? { q: qParam } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, status, qParam, page]);

  // Keeps the input in sync if the URL changes from outside this input (back
  // button, a status-chip click that also clears q via `update` below).
  useEffect(() => {
    setQInput(qParam);
  }, [qParam]);

  // Debounced: writes to the URL (which is what actually triggers the fetch
  // above) 300ms after the user stops typing, not on every keystroke.
  useEffect(() => {
    if (qInput === qParam) return;
    const timer = setTimeout(() => update({ q: qInput || undefined }), 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qInput]);

  function update(next: { status?: PolicyStatus | undefined; q?: string | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('page');
    }
    if ('q' in next) {
      if (next.q) merged.set('q', next.q);
      else merged.delete('q');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }
```

(This replaces the existing `update` function and the two `useEffect`/`const` lines it sits among — the `status`/`page` logic is unchanged, only `q` is added throughout.)

In the JSX, add the search input into the existing filter row, right after the closing `</div>` of the `FilterChip` mapping and before `{renderBody()}`:

```tsx
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip
              label="All"
              active={status === undefined}
              onClick={() => update({ status: undefined })}
            />
            {POLICY_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="policy" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
            <div className="relative ml-auto w-full max-w-[220px]">
              <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <input
                value={qInput}
                onChange={(e) => setQInput(e.target.value)}
                placeholder="Search by policy number"
                className="h-8 w-full rounded-md border border-input bg-surface pl-7 pr-2.5 text-xs"
              />
            </div>
          </div>
```

- [ ] **Step 3: Also thread `q` into the retry/empty-state handlers**

The `renderBody` function's error-retry (`onRetry={() => void loadList({ ...(status ? { status } : {}), page })}`) and the `EmptyState`'s "Clear filter" button (`onClick={() => update({ status: undefined })}`) both predate `q`. Update the retry call to also include `q`:

```tsx
          onRetry={() => void loadList({ ...(status ? { status } : {}), ...(qParam ? { q: qParam } : {}), page })}
```

Leave the "Clear filter" button as `update({ status: undefined })` — it is specifically about the status filter, not search; a search-and-status combination clearing only status and leaving the search term in place is the more useful behavior, not a bug to fix here.

- [ ] **Step 4: Typecheck, lint**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
```

- [ ] **Step 5: Restart the dev backend to pick up Task 2's changes, then add and run an e2e proof**

```bash
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Wait for it to report ready, then add a new test to `frontend/e2e/staff-policy-lifecycle.spec.ts`, reusing its own existing `issuePolicyAgainst(page, productLabel, reason): Promise<string>` helper (confirmed at lines 44-61 of that file — issues a real policy via the picker flow and returns the real `POL-XXXXXXXX` number from the post-issuance URL):

```ts
test('the search bar finds a real policy by its policy number', async ({ page }) => {
  const policyNumber = await issuePolicyAgainst(page, 'Demo Term Life (DEMO-TERM-01)', 'E2E search bar fixture');

  await page.goto('/staff/policies');
  await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();
  await page.getByPlaceholder('Search by policy number').fill(policyNumber);
  await expect(page).toHaveURL(new RegExp(`q=${policyNumber}`), { timeout: 5000 });
  await expect(page.getByText(policyNumber)).toBeVisible();
});
```

```bash
cd frontend
npx playwright test --project=staff --grep "staff policy lifecycle"
```

- [ ] **Step 6: Unit test, build**

```bash
cd frontend
npx vitest run
npm run build
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/policies.ts frontend/src/features/policies/PoliciesPage.tsx frontend/e2e/staff-policy-lifecycle.spec.ts
git commit -m "feat(frontend): search bar on PoliciesPage (q by policy number)"
```

---

### Task 5: Frontend — search bar on `ClaimsPage`

**Files:**
- Modify: `frontend/src/api/claims.ts`
- Modify: `frontend/src/features/claims/ClaimsPage.tsx`
- Modify: `frontend/e2e/staff-claims.spec.ts`

**Interfaces:**
- Consumes: `GET /claims?q=...` from Task 3.

- [ ] **Step 1: Thread `q` through `api/claims.ts`**

Identical shape to Task 4 Step 1: add `q?: string` to `ClaimSearchParams`, and `...(params.q ? { q: params.q } : {})` to the `params` object in `searchClaims`.

- [ ] **Step 2: Add the search input to `ClaimsPage`**

Read `ClaimsPage.tsx` first — it is structurally near-identical to `PoliciesPage.tsx` (confirmed: same `flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5` filter row, same `FilterChip` component, same URL-params-as-state pattern). Apply the exact same transformation Task 4 Step 2 made to `PoliciesPage.tsx`: add `qParam`/`qInput` state, the two new `useEffect`s, extend `update()` to handle `q`, and add the same search input markup (placeholder text: `"Search by policy number"`, same as Policies — claims search by policy number too, per the design).

- [ ] **Step 3: Thread `q` into the retry handler**

Same as Task 4 Step 3, applied to `ClaimsPage.tsx`'s own `renderBody`/`onRetry`.

- [ ] **Step 4: Typecheck, lint**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
```

- [ ] **Step 5: Add and run an e2e proof**

No backend restart needed (Task 3's backend change was already picked up by Task 4's restart, and no backend code has changed since).

Add to `frontend/e2e/staff-claims.spec.ts`. `POL-6BD5702F` is confirmed as the one real seeded policy this whole file already centers on (its own module doc comment names it directly, and two existing tests already fill `page.getByPlaceholder('POL-XXXXXXXX')` with this exact value at lines 107/128) — the seeded DEATH claim (asserted by the existing `'lands on the claims list with the real seeded DEATH claim'` test, which checks a row `toContainText('DEATH')`) is filed against it:

```ts
test('the search bar finds a real claim by its policy number', async ({ page }) => {
  await page.goto('/staff/claims');
  await expect(page.getByRole('heading', { name: 'Claims' })).toBeVisible();
  await page.getByPlaceholder('Search by policy number').fill('POL-6BD5702F');
  await expect(page).toHaveURL(/q=POL-6BD5702F/, { timeout: 5000 });
  await expect(page.getByText('DEATH')).toBeVisible();
});
```

```bash
cd frontend
npx playwright test --project=staff --grep "staff claims "
```

- [ ] **Step 6: Unit test, build**

```bash
cd frontend
npx vitest run
npm run build
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/claims.ts frontend/src/features/claims/ClaimsPage.tsx frontend/e2e/staff-claims.spec.ts
git commit -m "feat(frontend): search bar on ClaimsPage (q by policy number)"
```

---

### Task 6: Frontend — the shared `DatePicker` component

**Files:**
- Modify: `frontend/package.json` (confirm `@radix-ui/react-popover` already present from the party-picker plan; `react-day-picker` is already installed per Global Constraints — no `npm install` needed for either unless verification in Step 1 shows otherwise)
- Create: `frontend/src/components/DatePicker.tsx`
- Test: `frontend/src/components/DatePicker.test.tsx`

**Interfaces:**
- Produces: `DatePicker` — `{ value: string | null; onChange: (isoDate: string | null) => void; placeholder?: string; disabled?: { before?: Date; after?: Date } }`. Tasks 7–11 import this and wire it via `react-hook-form`'s `Controller`, exactly like every `PartyPicker` integration point.

- [ ] **Step 1: Confirm dependencies**

```bash
cd frontend
grep -E "\"react-day-picker\"|\"@radix-ui/react-popover\"" package.json
```

Expected: both lines present. If `@radix-ui/react-popover` is somehow missing, run `npm install @radix-ui/react-popover` — but per this plan's Global Constraints, both are expected to already be there (the party-picker plan added the Radix dependency; this plan's own design/verification phase already installed `react-day-picker`).

- [ ] **Step 2: Write the failing component tests**

Create `frontend/src/components/DatePicker.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DatePicker } from './DatePicker';

describe('DatePicker', () => {
  it('shows a placeholder when value is null', () => {
    render(<DatePicker value={null} onChange={vi.fn()} placeholder="Pick a date" />);
    expect(screen.getByRole('button', { name: 'Pick a date' })).toBeInTheDocument();
  });

  it('shows the formatted date when a value is set', () => {
    render(<DatePicker value="2026-08-01" onChange={vi.fn()} />);
    expect(screen.getByText('August 1, 2026')).toBeInTheDocument();
  });

  it('opens the calendar on trigger click and selecting a day calls onChange with an ISO date, then closes', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await waitFor(() => expect(screen.getByPlaceholder('YYYY-MM-DD')).toBeInTheDocument());

    // react-day-picker renders each day as a real `<button>` (DayButton) whose
    // accessible name is the FULL formatted date via its default `labelDayButton`
    // (date-fns "PPPP" format, e.g. "Monday, August 10th, 2026", optionally
    // prefixed "Today, "/suffixed ", selected" -- confirmed by reading
    // `node_modules/react-day-picker/dist/esm/labels/labelDayButton.js` directly,
    // not assumed) -- NOT a bare day number, and no `role="grid"` is set on the
    // calendar table, so `<td>` cells carry the default "cell" role, not
    // "gridcell". Match the weekday-comma-month-day...year shape broadly enough
    // to survive whichever real month/year the test happens to run in.
    const dayButtons = screen.getAllByRole('button', { name: /\w+day, \w+ \d+.*\d{4}/ });
    expect(dayButtons.length).toBeGreaterThan(0);
    const selectableDay = dayButtons[10]!;
    await user.click(selectableDay);

    expect(onChange).toHaveBeenCalledTimes(1);
    const [calledWith] = onChange.mock.calls[0] as [string];
    expect(calledWith).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('typing a complete, valid ISO date in the popover input calls onChange immediately and closes', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await user.type(screen.getByPlaceholder('YYYY-MM-DD'), '2026-12-25');

    expect(onChange).toHaveBeenCalledWith('2026-12-25');
    expect(screen.queryByPlaceholderText('YYYY-MM-DD')).not.toBeInTheDocument();
  });

  it('typing an incomplete date does not call onChange', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value={null} onChange={onChange} placeholder="Pick a date" />);

    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    await user.type(screen.getByPlaceholder('YYYY-MM-DD'), '2026-12');

    expect(onChange).not.toHaveBeenCalled();
  });

  it('a clear button on a populated field calls onChange(null)', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<DatePicker value="2026-08-01" onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: 'Clear date' }));

    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('disables a day before the given "before" bound', async () => {
    const user = userEvent.setup();
    render(
      <DatePicker
        value={null}
        onChange={vi.fn()}
        placeholder="Pick a date"
        disabled={{ before: new Date(2099, 0, 15) }}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Pick a date' }));
    // react-day-picker marks out-of-range days aria-disabled -- the current month's
    // days are all before year 2099, so every day button should be disabled.
    // Same accessible-name shape as the test above (the full formatted date, not
    // a bare number) -- `labelDayButton` does not special-case `disabled`.
    const dayButton = screen.getAllByRole('button', { name: /\w+day, \w+ \d+.*\d{4}/ })[5]!;
    expect(dayButton).toBeDisabled();
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd frontend
npx vitest run src/components/DatePicker.test.tsx
```

Expected: FAIL — `DatePicker.tsx` does not exist yet.

- [ ] **Step 4: Write the component**

Create `frontend/src/components/DatePicker.tsx`:

```tsx
import * as Popover from '@radix-ui/react-popover';
import { X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { DayPicker, type Matcher } from 'react-day-picker';
import 'react-day-picker/style.css';
import { formatDate } from '@/lib/dates';
import { ISO_DATE_PATTERN } from '@/lib/patterns';

export interface DatePickerProps {
  /** ISO yyyy-MM-dd, or null. Same shape a native `<input type="date">` already
   *  produces, so form schemas built around `ISO_DATE_PATTERN` need no change. */
  value: string | null;
  onChange: (isoDate: string | null) => void;
  placeholder?: string;
  /** Disallows a range of dates, e.g. no date of event in the future. */
  disabled?: { before?: Date; after?: Date };
}

function toIso(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function fromIso(iso: string): Date | undefined {
  if (!ISO_DATE_PATTERN.test(iso)) return undefined;
  const [year, month, day] = iso.split('-').map(Number) as [number, number, number];
  const date = new Date(year, month - 1, day);
  // Rejects a syntactically-valid-but-nonexistent date (e.g. 2026-02-30), which
  // `new Date` would otherwise silently roll over into March.
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? date
    : undefined;
}

export function DatePicker({ value, onChange, placeholder = 'Select a date', disabled }: DatePickerProps) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState(value ?? '');
  const [month, setMonth] = useState<Date>(() => (value && fromIso(value)) || new Date());

  useEffect(() => {
    setTyped(value ?? '');
    const parsed = value ? fromIso(value) : undefined;
    if (parsed) setMonth(parsed);
  }, [value]);

  function select(date: Date | undefined) {
    if (!date) return;
    onChange(toIso(date));
    setOpen(false);
  }

  function onTypedChange(next: string) {
    setTyped(next);
    const parsed = fromIso(next);
    if (parsed) {
      onChange(next);
      setMonth(parsed);
      setOpen(false);
    }
  }

  function clear(e: React.MouseEvent) {
    e.stopPropagation();
    onChange(null);
    setTyped('');
  }

  const dayPickerDisabled: Matcher[] | undefined = disabled
    ? [
        ...(disabled.before ? [{ before: disabled.before }] : []),
        ...(disabled.after ? [{ after: disabled.after }] : []),
      ]
    : undefined;

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <div className="relative">
        <Popover.Trigger asChild>
          <button
            type="button"
            aria-label={value ? formatDate(value) : placeholder}
            className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
          >
            {value ? (
              <span className="min-w-0 truncate pr-6">{formatDate(value)}</span>
            ) : (
              <span className="min-w-0 truncate text-muted-foreground">{placeholder}</span>
            )}
          </button>
        </Popover.Trigger>
        {value && (
          <button
            type="button"
            aria-label="Clear date"
            onClick={clear}
            className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:bg-hover hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          className="z-50 rounded-md border border-border bg-surface p-2 shadow-lg"
        >
          <input
            value={typed}
            onChange={(e) => onTypedChange(e.target.value)}
            placeholder="YYYY-MM-DD"
            className="mb-2 h-8 w-full rounded-md border border-input bg-surface px-2 text-sm outline-none"
          />
          <DayPicker
            mode="single"
            selected={value ? fromIso(value) : undefined}
            onSelect={select}
            month={month}
            onMonthChange={setMonth}
            disabled={dayPickerDisabled}
            classNames={{
              month_caption: 'flex items-center justify-center h-8 text-sm font-medium',
              nav: 'flex items-center justify-between',
              button_previous: 'rounded p-1 hover:bg-hover',
              button_next: 'rounded p-1 hover:bg-hover',
              weekday: 'text-muted-foreground text-xs font-normal',
              day_button: 'size-8 rounded-md text-sm hover:bg-hover',
              selected: 'bg-selected rounded-md font-medium',
              today: 'border border-border-strong rounded-md',
              outside: 'text-muted-foreground opacity-50',
              disabled: 'text-muted-foreground opacity-30 pointer-events-none',
            }}
          />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd frontend
npx vitest run src/components/DatePicker.test.tsx
```

Expected: all 8 tests pass. If the "select a day" test's `dayButtons[10]` index lands on a disabled/outside-month day for the current real-world month at run time, adjust the index (or filter `dayButtons` to `:not([disabled])` first) rather than picking a hardcoded date that might not exist this month.

- [ ] **Step 6: Typecheck and lint**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
```

Both must be clean. If eslint flags a `react-hooks/set-state-in-effect` warning on the `useEffect` in Step 4 (the codebase has hit this exact rule twice already on `PartyPicker.tsx`/`PartyName.tsx` — check both for the established fix), add `// eslint-disable-next-line react-hooks/set-state-in-effect` immediately above the flagged line, matching that established pattern.

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/components/DatePicker.tsx frontend/src/components/DatePicker.test.tsx
git commit -m "feat(frontend): DatePicker -- popover-anchored date selection component"
```

---

### Task 7: Integrate `DatePicker` into `RegisterClaimPage` (5 fields)

**Files:**
- Modify: `frontend/src/features/claims/RegisterClaimPage.tsx`
- Modify: `frontend/e2e/staff-claims.spec.ts`
- Modify: `frontend/e2e/staff-claim-evidence.spec.ts`
- Modify: `frontend/e2e/staff-claims-adjudication.spec.ts`
- Modify: `frontend/e2e/agents-my-book.spec.ts`

**Interfaces:**
- Consumes: `DatePicker` from Task 6.

- [ ] **Step 1: Wire `Controller` and `DatePicker` for all 5 date fields**

`control` is already destructured in this file (from the party-picker work) and `Controller` is already imported. Add the `DatePicker` import:

```tsx
import { DatePicker } from '@/components/DatePicker';
```

Replace each of the 5 `<input type="date">` blocks. "Date of event" (top-level field):

```tsx
        <FormField label="Date of event" error={errors.dateOfEvent?.message}>
          <Controller
            control={control}
            name="dateOfEvent"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                placeholder="Select the date of event"
                disabled={{ after: new Date() }}
              />
            )}
          />
        </FormField>
```

(`disabled={{ after: new Date() }}` disallows a future date of event, a reasonable real-world constraint for an already-occurred claim event; the existing Zod schema is unaffected either way, so this is additive UX, not a new validation rule.)

"Date of death" (inside the DEATH details branch):

```tsx
              <FormField label="Date of death" error={detailError('dateOfDeath')}>
                <Controller
                  control={control}
                  name="details.dateOfDeath"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the date of death"
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
```

"Onset date" (DISABILITY branch):

```tsx
              <FormField label="Onset date" error={detailError('onsetDate')}>
                <Controller
                  control={control}
                  name="details.onsetDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the onset date"
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
```

"Diagnosis date" (CRITICAL_ILLNESS branch):

```tsx
              <FormField label="Diagnosis date" error={detailError('diagnosisDate')}>
                <Controller
                  control={control}
                  name="details.diagnosisDate"
                  render={({ field }) => (
                    <DatePicker
                      value={(field.value as string) || null}
                      onChange={(iso) => field.onChange(iso ?? '')}
                      placeholder="Select the diagnosis date"
                      disabled={{ after: new Date() }}
                    />
                  )}
                />
              </FormField>
```

"Maturity date" (MATURITY branch — no `after: new Date()` disabling here; a maturity date is naturally in the future or present, unlike the other 4 event-in-the-past fields):

```tsx
            <FormField label="Maturity date" error={detailError('maturityDate')}>
              <Controller
                control={control}
                name="details.maturityDate"
                render={({ field }) => (
                  <DatePicker
                    value={(field.value as string) || null}
                    onChange={(iso) => field.onChange(iso ?? '')}
                    placeholder="Select the maturity date"
                  />
                )}
              />
            </FormField>
```

The `(field.value as string)` casts on the 4 nested `details.*` fields match this file's own pre-existing pattern of narrowing the discriminated-union `details` type at read sites (see `detailError`'s own cast one function above) — not a new workaround introduced here.

- [ ] **Step 2: Update the 4 e2e spec files' call sites**

Every one of these currently does `page.locator('input[type="date"]').first().fill('2026-08-01')` (or `.nth(1)` for the second date field within the same form) to fill the two fields every DEATH-claim registration needs ("Date of event" and "Date of death"). Replace each pair with the picker interaction, using each field's own unique accessible name (from Step 1) rather than a positional locator — this also removes the fragility of `.first()`/`.nth(1)` ordering:

```ts
await page.getByRole('button', { name: 'Select the date of event' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill('2026-08-01');
await page.getByRole('button', { name: 'Select the date of death' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill('2026-08-01');
```

(Typing a complete ISO date auto-selects and closes the popover per Task 6's own design — no explicit day-click needed for a known target date, and no `page.getByPlaceholder('YYYY-MM-DD')` ambiguity since only one popover is open at a time.)

Apply this replacement at:
- `frontend/e2e/staff-claims.spec.ts:111-114` (date of event + date of death)
- `frontend/e2e/staff-claims.spec.ts:132-135` (same pair, second test)
- `frontend/e2e/staff-claim-evidence.spec.ts:48,51` (already uses `getByLabel('Date of event')`/`getByLabel('Date of death')`, not positional locators — replace those two `.fill()` calls directly with the picker interaction above)
- `frontend/e2e/staff-claims-adjudication.spec.ts:52,58` (same, already uses `getByLabel`)
- `frontend/e2e/agents-my-book.spec.ts:51,54` (positional `.first()`/`.nth(1)`, same fix as `staff-claims.spec.ts`)

For the two files already using `getByLabel('Date of event')`/`getByLabel('Date of death')` (`staff-claim-evidence.spec.ts`, `staff-claims-adjudication.spec.ts`): a `getByLabel` lookup for these fields will no longer resolve correctly once the native `<input>` is replaced by a `<button>` trigger with its OWN `aria-label` (not tied to the `<FormField>`'s `<label>` via `htmlFor`/`id`) — this is the exact same accessible-name shift the party picker's own rollout required. Use `getByRole('button', { name: 'Select the date of event' })` in place of `getByLabel('Date of event')` at both call sites.

- [ ] **Step 3: Restart the dev backend (if not already reflecting Task 3's changes), then run all 4 touched spec files**

```bash
cd frontend
npx playwright test --project=staff --project=agents --grep "staff claims |staff claim evidence|staff claims adjudication|agents my book"
```

- [ ] **Step 4: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/claims/RegisterClaimPage.tsx \
  frontend/e2e/staff-claims.spec.ts frontend/e2e/staff-claim-evidence.spec.ts \
  frontend/e2e/staff-claims-adjudication.spec.ts frontend/e2e/agents-my-book.spec.ts
git commit -m "feat(frontend): RegisterClaimPage uses DatePicker for all 5 date fields"
```

---

### Task 8: Integrate `DatePicker` into `OnboardCustomerPage`

**Files:**
- Modify: `frontend/src/features/party/OnboardCustomerPage.tsx`
- Modify: `frontend/e2e/agents-my-profile.spec.ts`
- Modify: `frontend/e2e/staff-agent-lifecycle.spec.ts`
- Modify: `frontend/e2e/staff-kyc-review.spec.ts`

**Interfaces:**
- Consumes: `DatePicker` from Task 6.

- [ ] **Step 1: Wire `Controller` and `DatePicker` for "Date of birth"**

This is the individual-registration form specifically (the file also has a separate corporate-registration form with no date field — leave that one untouched). `control` is NOT currently destructured (current: `register, handleSubmit, reset, formState`). Add it and the two imports:

```tsx
import { useForm, Controller } from 'react-hook-form';
...
import { DatePicker } from '@/components/DatePicker';
```

```tsx
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors },
  } = useForm<RegisterIndividualFormValues>({
```

Replace the "Date of birth" `FormField` block:

```tsx
      <FormField label="Date of birth" error={errors.dateOfBirth?.message}>
        <Controller
          control={control}
          name="dateOfBirth"
          render={({ field }) => (
            <DatePicker
              value={field.value || null}
              onChange={(iso) => field.onChange(iso ?? '')}
              placeholder="Select date of birth"
              disabled={{ after: new Date() }}
            />
          )}
        />
      </FormField>
```

- [ ] **Step 2: Update the 3 e2e call sites**

All 3 currently do `await page.getByLabel('Date of birth').fill('1990-05-12');`. Replace each with:

```ts
await page.getByRole('button', { name: 'Select date of birth' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill('1990-05-12');
```

At: `frontend/e2e/agents-my-profile.spec.ts:49`, `frontend/e2e/staff-agent-lifecycle.spec.ts:72`, `frontend/e2e/staff-kyc-review.spec.ts:29`.

- [ ] **Step 3: Run the 3 touched spec files against the real stack**

```bash
cd frontend
npx playwright test --project=staff --project=agents --grep "agents my profile|staff agent lifecycle|staff KYC review"
```

- [ ] **Step 4: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/party/OnboardCustomerPage.tsx \
  frontend/e2e/agents-my-profile.spec.ts frontend/e2e/staff-agent-lifecycle.spec.ts frontend/e2e/staff-kyc-review.spec.ts
git commit -m "feat(frontend): OnboardCustomerPage uses DatePicker for date of birth"
```

---

### Task 9: Integrate `DatePicker` into `OnboardAgentPage`

**Files:**
- Modify: `frontend/src/features/distribution/OnboardAgentPage.tsx`
- Modify: `frontend/e2e/staff-distribution.spec.ts`
- Modify: `frontend/e2e/staff-agent-lifecycle.spec.ts`

**Interfaces:**
- Consumes: `DatePicker` from Task 6.

- [ ] **Step 1: Wire `Controller` and `DatePicker` for "License expiry date"**

`control` is already destructured in this file (from the party-picker work). Add the import:

```tsx
import { DatePicker } from '@/components/DatePicker';
```

Replace the "License expiry date" `FormField` block:

```tsx
        <FormField label="License expiry date" error={errors.licenseExpiryDate?.message}>
          <Controller
            control={control}
            name="licenseExpiryDate"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                placeholder="Select the license expiry date"
                disabled={{ before: new Date() }}
              />
            )}
          />
        </FormField>
```

(`disabled={{ before: new Date() }}` — a license expiry date is, by definition, in the future; the backend already 422s an already-expired date, this just stops the picker offering one.)

- [ ] **Step 2: Update the 2 e2e call sites**

Both currently do `await page.getByLabel('License expiry date').fill(FUTURE_LICENSE_EXPIRY);` where `FUTURE_LICENSE_EXPIRY` is a `${new Date().getFullYear() + 5}-01-01`-shaped constant already defined in each file. Replace each:

```ts
await page.getByRole('button', { name: 'Select the license expiry date' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill(FUTURE_LICENSE_EXPIRY);
```

At: `frontend/e2e/staff-distribution.spec.ts:34`, `frontend/e2e/staff-agent-lifecycle.spec.ts:20`.

- [ ] **Step 3: Run the 2 touched spec files**

```bash
cd frontend
npx playwright test --project=staff --grep "staff distribution|staff agent lifecycle"
```

- [ ] **Step 4: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/distribution/OnboardAgentPage.tsx \
  frontend/e2e/staff-distribution.spec.ts frontend/e2e/staff-agent-lifecycle.spec.ts
git commit -m "feat(frontend): OnboardAgentPage uses DatePicker for license expiry date"
```

---

### Task 10: Integrate `DatePicker` into `CreateTreatyPage`

**Files:**
- Modify: `frontend/src/features/reinsurance/CreateTreatyPage.tsx`
- Modify: `frontend/e2e/staff-reinsurance.spec.ts`

**Interfaces:**
- Consumes: `DatePicker` from Task 6.

- [ ] **Step 1: Wire `Controller` and `DatePicker` for "Effective from"/"Effective to"**

`control` is NOT currently destructured (current: `register, handleSubmit, watch, reset, formState`). Add it and the two imports:

```tsx
import { useForm, Controller } from 'react-hook-form';
...
import { DatePicker } from '@/components/DatePicker';
```

```tsx
  const {
    register,
    handleSubmit,
    watch,
    reset,
    control,
    formState: { errors },
  } = useForm<CreateTreatyFormValues>({
```

Replace both fields:

```tsx
        <div className="grid grid-cols-2 gap-2">
          <FormField label="Effective from" error={errors.effectiveFrom?.message}>
            <Controller
              control={control}
              name="effectiveFrom"
              render={({ field }) => (
                <DatePicker
                  value={field.value || null}
                  onChange={(iso) => field.onChange(iso ?? '')}
                  placeholder="Select the effective-from date"
                />
              )}
            />
          </FormField>
          <FormField label="Effective to (optional)" error={errors.effectiveTo?.message}>
            <Controller
              control={control}
              name="effectiveTo"
              render={({ field }) => (
                <DatePicker
                  value={field.value || null}
                  onChange={(iso) => field.onChange(iso ?? '')}
                  placeholder="Select the effective-to date"
                />
              )}
            />
          </FormField>
        </div>
```

(No `disabled` range on either — a treaty's effective dates are a business decision, not constrained to past/future by this form; the existing Zod schema already enforces `effectiveTo` is after `effectiveFrom` where relevant, unaffected by this change.)

- [ ] **Step 2: Update the 2 e2e call sites**

Both currently do `await page.getByLabel('Effective from').fill('2020-01-01');`. Replace each:

```ts
await page.getByRole('button', { name: 'Select the effective-from date' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill('2020-01-01');
```

At: `frontend/e2e/staff-reinsurance.spec.ts:32`, `frontend/e2e/staff-reinsurance.spec.ts:74`.

- [ ] **Step 3: Run the touched spec file**

```bash
cd frontend
npx playwright test --project=staff --grep "staff reinsurance"
```

- [ ] **Step 4: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/reinsurance/CreateTreatyPage.tsx frontend/e2e/staff-reinsurance.spec.ts
git commit -m "feat(frontend): CreateTreatyPage uses DatePicker for treaty effective dates"
```

---

### Task 11: Integrate `DatePicker` into `PublishVersionForm`

**Files:**
- Modify: `frontend/src/features/products/PublishVersionForm.tsx`
- Modify: `frontend/e2e/staff-products.spec.ts`
- Modify: `frontend/e2e/staff-policy-lifecycle.spec.ts`
- Modify: `frontend/e2e/staff-distribution.spec.ts`

**Interfaces:**
- Consumes: `DatePicker` from Task 6.

- [ ] **Step 1: Wire `Controller` and `DatePicker` for "Effective date"/"Retirement date"**

`control` is already destructured in this file (used by 3 `useFieldArray` calls). Add the import:

```tsx
import { DatePicker } from '@/components/DatePicker';
```

Replace both fields:

```tsx
        <FormField label="Effective date" error={errors.effectiveDate?.message}>
          <Controller
            control={control}
            name="effectiveDate"
            render={({ field }) => (
              <DatePicker
                value={field.value || null}
                onChange={(iso) => field.onChange(iso ?? '')}
                placeholder="Select the effective date"
              />
            )}
          />
        </FormField>
      </div>

      <FormField label="Retirement date (optional)" error={errors.retirementDate?.message}>
        <Controller
          control={control}
          name="retirementDate"
          render={({ field }) => (
            <DatePicker
              value={field.value || null}
              onChange={(iso) => field.onChange(iso ?? '')}
              placeholder="Select the retirement date"
            />
          )}
        />
      </FormField>
```

(Read the surrounding JSX carefully before replacing — "Effective date" is the last field inside one `<div>...</div>` grid wrapper per the original code, and "Retirement date" is a sibling `FormField` immediately after that div closes; preserve that same wrapper structure, only swapping the `<input>` inside "Effective date"'s `FormField` for the `Controller`/`DatePicker`, same as shown above.)

- [ ] **Step 2: Update the 3 e2e call sites**

All 3 currently do `await page.getByLabel('Effective date').fill('2026-01-01');` (one of them, `staff-distribution.spec.ts:60`, uses this as a fixture setup step when publishing a product version to then author a commission plan against it — confirm this by reading that file's surrounding context before editing, since it is not primarily a product-publishing test). Replace each:

```ts
await page.getByRole('button', { name: 'Select the effective date' }).click();
await page.getByPlaceholder('YYYY-MM-DD').fill('2026-01-01');
```

At: `frontend/e2e/staff-products.spec.ts:60`, `frontend/e2e/staff-policy-lifecycle.spec.ts:39`, `frontend/e2e/staff-distribution.spec.ts:60`.

- [ ] **Step 3: Run the 3 touched spec files**

```bash
cd frontend
npx playwright test --project=staff --grep "staff products|staff policy lifecycle|staff distribution"
```

- [ ] **Step 4: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/products/PublishVersionForm.tsx \
  frontend/e2e/staff-products.spec.ts frontend/e2e/staff-policy-lifecycle.spec.ts frontend/e2e/staff-distribution.spec.ts
git commit -m "feat(frontend): PublishVersionForm uses DatePicker for effective/retirement dates"
```

---

### Task 12: Full verification and wrap-up

**Files:** none (verification only).

- [ ] **Step 1: Full backend test suite**

```bash
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test
```

Expected: 0 failures, 0 errors. Redirect to a real log file and grep it directly for `Tests run:`/`BUILD` rather than trusting a piped/backgrounded command's own reported exit code — this session has twice already seen a `tail`/background wrapper mis-report a real `BUILD FAILURE` as exit 0.

- [ ] **Step 2: Full frontend verification**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 3: Full affected-project e2e run**

Restart the dev backend one more time first to be certain it reflects every backend change across Tasks 1-3:

```bash
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

```bash
cd frontend
npx playwright test --project=staff --project=agents
```

Expected: all tests pass. A single failure that also fails on an immediate isolated re-run is a real regression — investigate and fix before proceeding. A single failure that passes cleanly on retry in isolation is the documented transient Keycloak-redirect flake already known to this suite — note it, do not chase it.

- [ ] **Step 4: Final commit (if any stray changes remain)**

```bash
cd /path/to/repo/root
git status --short
```

If clean, this step is a no-op. If anything stray remains, stage and commit it with a clear message.

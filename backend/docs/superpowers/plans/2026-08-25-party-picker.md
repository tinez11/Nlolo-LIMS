# Party Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw-UUID party-id text fields across five staff-console forms with a searchable, selectable `PartyPicker` combobox, backed by a `q` free-text search param added to the existing `GET /parties` endpoint.

**Architecture:** One backend change (a third optional filter dimension on an endpoint that already exists) feeds one new reusable frontend component (`cmdk` + Radix Popover), which is then wired into six JSX call sites (five files; `IssuePolicyPage` has two) via `react-hook-form`'s `Controller`. Agent search is explicitly out of scope — `agentOfRecordId`/`hierarchyParentId` stay raw-typed.

**Tech Stack:** Spring Boot / JPA (backend), React 19 / TypeScript / react-hook-form / Zod / Zustand / cmdk / Radix Popover / Playwright / Vitest (frontend).

**Design doc:** `backend/docs/superpowers/specs/2026-08-25-party-picker-design.md`

## Global Constraints

- **No fabricated identities in e2e tests.** Every e2e test in this suite runs against the real stack (real Keycloak, real backend, real Postgres) — this is a hard project rule, not a preference. Never mock auth or fake a JWT.
- **Backend build/test commands run on the host, in the foreground, never inside Docker** (breaks Testcontainers networking). From `backend/`: `export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64" && ./mvnw -o test -Dtest=<ClassName>`.
- **Run the narrowest backend test set that could detect a regression** — a single `-Dtest=ClassName` run, not the full suite, for every task in this plan except the final one.
- **The dev backend process must be manually restarted** (`SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run`, foreground/backgrounded-and-watched, from `backend/`) before any e2e test can see a backend code change — a stale dev process silently serves old behavior.
- **Frontend verification**, from `frontend/`: `npx tsc --noEmit`, `npx eslint .`, `npx vitest run`, `npm run build` — all four clean before any task is considered done.
- **e2e cadence**: per this session's own established preference, do not run the full Playwright suite after every task. Run only the spec file(s) each task actually touches. Run the full suite once, in the final task.
- **CRLF/LF**: this repo's Windows checkout normalizes line endings on `git add`/`commit` automatically (`autocrlf`) — the "LF will be replaced by CRLF" warning on `git commit` is expected and not an error.
- **Money/UUID/date patterns already exist** in `frontend/src/lib/patterns.ts` (`UUID_PATTERN`) and `frontend/src/lib/money.ts` (`AMOUNT_PATTERN`) — reuse them, never redeclare a local copy.

---

## File Structure

**Backend (all modifications, no new files):**
- `backend/api/openapi/openapi-party.yaml` — add the `q` query param to `GET /parties`.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyRepository.java` — add a `search` JPQL query combining `kycStatus`, `createdBy`, `q`.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/party/api/PartyApi.java` — `searchParties` gains a `q` parameter.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/party/application/PartyApiImpl.java` — `searchParties` routes through the new query when `q` is present.
- `backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyController.java` — `searchParties` accepts and forwards `q`.
- `backend/src/test/java/tz/co/nlolo/lifeplatform/party/PartyContractTest.java` — new tests for `q`.
- `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java` — new test: issuing against a nonexistent `policyholderPartyId` 404s (replaces e2e coverage that becomes unreachable in Task 4).
- `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java` — new test: opening a case against a nonexistent `applicantPartyId` 404s (replaces e2e coverage that becomes unreachable in Task 5).

**Frontend (new):**
- `frontend/src/components/PartyPicker.tsx` — the reusable component.
- `frontend/src/components/PartyPicker.test.tsx` — its unit tests.

**Frontend (modified):**
- `frontend/package.json` — add `cmdk`, `@radix-ui/react-popover`.
- `frontend/src/api/party.ts` — `searchParties`/`PartySearchParams` gain `q`.
- `frontend/src/features/policies/IssuePolicyPage.tsx` — Policyholder party id + the embedded beneficiary row's party id.
- `frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx` — Applicant party id.
- `frontend/src/features/claims/RegisterClaimPage.tsx` — Claimant party id.
- `frontend/src/features/distribution/OnboardAgentPage.tsx` — Party id (VERIFIED-filtered).
- `frontend/src/features/policies/BeneficiariesPanel.tsx` — the beneficiary row's party id (existing-party branch only).

**e2e (modified, enumerated per task below):** 13 spec files, ~23 call sites total, each already inventoried in the tasks that touch them.

---

### Task 1: Backend — `q` search param on `GET /parties`

**Files:**
- Modify: `backend/api/openapi/openapi-party.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/party/api/PartyApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/party/application/PartyApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyController.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/party/PartyContractTest.java`

**Interfaces:**
- Produces: `PartyApi.searchParties(KycStatus kycStatus, String createdBy, String q, Pageable pageable): Page<PartyView>` — Task 3's frontend `searchParties` API call reaches this via `GET /parties?q=...`.

- [ ] **Step 1: Write the failing contract tests**

Add to `PartyContractTest.java`, after the existing `getPartyRejectsCustomerReadingSomeoneElsesRecord` test (or any existing test — placement within the class doesn't matter, JUnit runs them independently):

```java
    // --- GET /parties?q=... -----------------------------------------------------------------

    @Test
    void searchPartiesByQMatchesACaseInsensitiveSubstringOfDisplayName() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Zawadi Search Fixture","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345695"}}
                    """))
            .andExpect(status().isCreated());

        // Deliberately the WRONG case from what was registered ("Zawadi" vs "zawadi") --
        // this is the falsifiable half of "ILIKE is inherently case-insensitive": a
        // case-SENSITIVE match would find zero rows here.
        mockMvc.perform(get("/parties")
                .queryParam("q", "zawadi")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Zawadi Search Fixture"));
    }

    @Test
    void searchPartiesByQCombinesWithKycStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Two parties sharing a name fragment, only one VERIFIED -- proves q and
        // kycStatus are genuinely ANDed together, not either alone.
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Baraka Combo Fixture Pending","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345696"}}
                    """))
            .andExpect(status().isCreated());
        String verifiedResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Baraka Combo Fixture Verified","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345697"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID verifiedPartyId = UUID.fromString(objectMapper.readValue(verifiedResponse, PartyView.class).partyId().toString());
        mockMvc.perform(post("/parties/" + verifiedPartyId + "/kyc")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"VERIFIED","evidenceDocumentRef":"doc-ref-combo-fixture"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/parties")
                .queryParam("q", "combo fixture")
                .queryParam("kycStatus", "VERIFIED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Baraka Combo Fixture Verified"));
    }

    @Test
    void searchPartiesByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/parties")
                .queryParam("q", "NoPartyAnywhereHasThisExactNonsenseName12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PartyContractTest
```

Expected: compile failure (`queryParam` calls pass `q` — fine, `MockMvcRequestBuilders` already supports arbitrary query params — but `PartyController.searchParties` has no `q` parameter to receive it, so the requests above 200 with an EMPTY/unfiltered result instead of the filtered one asserted, or the compile succeeds and the new assertions on `items.length()`/`displayName` fail). Confirm the failure is on the new assertions, not a compile error unrelated to this change.

- [ ] **Step 3: Add `q` to the OpenAPI spec**

In `backend/api/openapi/openapi-party.yaml`, inside the existing `/parties: get: parameters:` list (currently `kycStatus`, `page`, `pageSize`), add:

```yaml
        - name: q
          in: query
          description: Free-text, case-insensitive substring match against displayName.
          schema: { type: string }
```

- [ ] **Step 4: Add the repository query**

In `PartyRepository.java`, add alongside the existing four derived methods:

```java
    /**
     * The three-dimension combined filter -- kycStatus x createdBy x q -- following the
     * same null-safe JPQL pattern PolicyRepository.search/ClaimRepository.search already
     * established for their own 3-way filters, rather than enumerating 8 derived-method
     * combinations. `q` is a case-insensitive substring match against displayName; a null
     * `q` means "no text filter", not "match nothing".
     */
    @Query("SELECT p FROM Party p WHERE p.tenantId = :tenantId "
        + "AND (:kycStatus IS NULL OR p.kycStatus = :kycStatus) "
        + "AND (:createdBy IS NULL OR p.createdBy = :createdBy) "
        + "AND (:q IS NULL OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Party> search(@Param("tenantId") UUID tenantId, @Param("kycStatus") KycStatus kycStatus,
                        @Param("createdBy") String createdBy, @Param("q") String q, Pageable pageable);
```

Add the two new imports this needs, alongside the existing `Page`/`Pageable` ones:

```java
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 5: Thread `q` through `PartyApi` and `PartyApiImpl`**

In `PartyApi.java`, change the `searchParties` signature (and its javadoc, which currently only mentions `kycStatus`/`createdBy`):

```java
    /**
     * ... (keep the existing javadoc, and add:)
     * {@code q} is a free-text, case-insensitive substring match against displayName, combinable
     * with {@code kycStatus} -- both filters apply together, not either-or. Null means no filter
     * on that dimension.
     */
    Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q, Pageable pageable);
```

In `PartyApiImpl.java`, replace the existing `searchParties` method body:

```java
    @Override
    public Page<PartyView> searchParties(KycStatus kycStatus, String createdBy, String q, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        // The three no-q branches stay on the original derived-query methods (unchanged
        // shape) for the common (no-text-search) case -- only a present `q` routes
        // through the new three-way `search` query, same reasoning
        // PolicyApiImpl.searchPolicies/ClaimsApiImpl.searchClaims already use for their
        // own optional extra filter dimension.
        Page<Party> page;
        if (q != null && !q.isBlank()) {
            page = partyRepository.search(tenantId, kycStatus, createdBy, q.trim(), pageable);
        } else if (kycStatus != null && createdBy != null) {
            page = partyRepository.findByTenantIdAndKycStatusAndCreatedBy(tenantId, kycStatus, createdBy, pageable);
        } else if (kycStatus != null) {
            page = partyRepository.findByTenantIdAndKycStatus(tenantId, kycStatus, pageable);
        } else if (createdBy != null) {
            page = partyRepository.findByTenantIdAndCreatedBy(tenantId, createdBy, pageable);
        } else {
            page = partyRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(PartyApiImpl::toView);
    }
```

- [ ] **Step 6: Thread `q` through `PartyController`**

In `PartyController.java`, update the `searchParties` method:

```java
    @GetMapping("/parties")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PageResponse<PartyView>> searchParties(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        String effectiveCreatedBy = isAgent ? jwt.getSubject() : null;
        Page<PartyView> result = partyApi.searchParties(kycStatus, effectiveCreatedBy, q,
            PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(PageResponse.from(result));
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PartyContractTest
```

Expected: all tests in the class pass (exit code 0). This class has no explicit `Tests run:` summary printed under `-o` quiet mode on success in this repo's Maven config — a clean exit code 0 with no `ERROR`/`FAILURE` lines is the pass signal, matching this session's own established verification pattern.

- [ ] **Step 8: Commit**

```bash
git add backend/api/openapi/openapi-party.yaml \
  backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyRepository.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/party/api/PartyApi.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/party/application/PartyApiImpl.java \
  backend/src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/PartyController.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/party/PartyContractTest.java
git commit -m "feat(party): add free-text q search to GET /parties, for the party picker"
```

---

### Task 2: Backend — replacement 404 coverage for the e2e tests Task 4/5 will remove

**Why this task exists here, before the frontend work:** Task 4 and Task 5 delete two e2e tests each that currently exercise a real backend behavior (issuing/opening-a-case against a syntactically-valid but nonexistent party id) through the UI — a behavior that becomes unreachable through the UI once the raw-ID field is replaced by a picker (the picker can only ever produce an id that came from a real search result). Grepping both `PolicyContractTest.java` and the underwriting test package first confirms neither currently has a contract-test-level equivalent, so removing the e2e coverage without this task would be a real, silent loss of coverage, not just a redundant test being deleted.

**Files:**
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java`

**Interfaces:**
- Consumes: nothing new — both tests exercise existing, unmodified endpoints (`POST /policies/manual-issue`, `POST /underwriting/cases`).

- [ ] **Step 1: Write the failing tests**

In `PolicyContractTest.java`, add (matching that file's existing fixture style — a real `tenantId`, `jwt()` with `ROLE_REALM_STAFF`):

Request body shapes below are copied verbatim from this file's own existing, passing fixture helper (the `content("""..."""` block inside its `issueRealPolicy`-style helper around line 196-205 — confirmed by reading it directly, not guessed from the DTO) so this test cannot fail for the wrong reason (a 400 from a malformed body instead of the 404 under test):

```java
    @Test
    void manualIssueReturns404ForANonexistentPolicyholderPartyId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID nonexistentPartyId = UUID.randomUUID();

        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Contract test -- nonexistent policyholder"}
                    """.formatted(UUID.randomUUID(), nonexistentPartyId, UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }
```

Note `productVersionId` here is a random UUID, not a real published one — this is deliberate: `PolicyApiImpl.issuePolicy` must check `policyholderPartyId` and 404 on it BEFORE it ever gets to resolving `productVersionId`, or this test would 404/422 for the wrong reason. If this test fails with anything other than a plain 404 on the party, that ordering is the first thing to check.

In `UnderwritingContractTest.java`, add similarly — request body copied verbatim from that file's own existing `openCase`-style helper around line 58-64:

```java
    @Test
    void openCaseReturns404ForANonexistentApplicantPartyId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID nonexistentPartyId = UUID.randomUUID();

        mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(nonexistentPartyId, UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }
```

Same ordering caveat: `productId`/`productVersionId` are random, deliberately — `applicantPartyId` must be checked and 404 first.

- [ ] **Step 2: Run both test classes to verify the new tests fail or pass for the wrong reason**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest,UnderwritingContractTest
```

Expected: both new tests should actually PASS immediately if `PolicyApiImpl.issuePolicy`/`UnderwritingApiImpl` (or equivalent) already 404 on a nonexistent party — this is very likely already correct backend behavior (it is what the e2e tests being removed in Tasks 4/5 currently prove works). This step's real purpose is to catch it if that assumption is wrong: if either test fails, stop and investigate the real behavior (a 400/500 instead of 404) before proceeding — do not adjust the test's expected status code without first confirming with a debugger/log what the real response is and whether that's the CORRECT contract or an actual bug.

- [ ] **Step 3: If a genuine gap is found, fix it; otherwise proceed**

If Step 2 shows the endpoint does not actually 404 correctly, fix the real bug in `PolicyApiImpl`/`UnderwritingApiImpl` (a `findPartyOrThrow`-style guard should already exist there, called before any other write — locate it and confirm it runs early enough). If Step 2's tests already pass, this step is a no-op.

- [ ] **Step 4: Run both test classes to confirm a clean pass**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test -Dtest=PolicyContractTest,UnderwritingContractTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java \
  backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java
git commit -m "test(policy,underwriting): pin the 404-on-nonexistent-party contract, ahead of removing its e2e coverage"
```

---

### Task 3: Frontend — the `PartyPicker` component

**Files:**
- Modify: `frontend/package.json` (via `npm install`)
- Modify: `frontend/src/api/party.ts`
- Create: `frontend/src/components/PartyPicker.tsx`
- Test: `frontend/src/components/PartyPicker.test.tsx`

**Interfaces:**
- Consumes: `searchParties(params: PartySearchParams): Promise<Page<PartyView>>` from `api/party.ts` (modified this task to accept `q`).
- Produces: `PartyPicker` — `{ value: string | null; onChange: (partyId: string | null, party: PartyView | null) => void; kycStatus?: KycStatus; placeholder?: string }`. Tasks 4–8 import this and wire it via `react-hook-form`'s `Controller`.

- [ ] **Step 1: Install the new dependencies**

```bash
cd frontend
npm install cmdk @radix-ui/react-popover
```

- [ ] **Step 2: Thread `q` through `api/party.ts`**

In `frontend/src/api/party.ts`, update `PartySearchParams` and `searchParties`:

```ts
export interface PartySearchParams {
  kycStatus?: KycStatus;
  q?: string;
  page?: number;
  pageSize?: number;
}
```

In the `searchParties` function body, add `q` alongside the existing `kycStatus` conditional spread in the `params` object passed to `get`:

```ts
    params: {
      ...(params.kycStatus ? { kycStatus: params.kycStatus } : {}),
      ...(params.q ? { q: params.q } : {}),
      page,
      pageSize,
    },
```

- [ ] **Step 3: Write the failing component tests**

Create `frontend/src/components/PartyPicker.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as partyApi from '@/api/party';
import type { PartyView } from '@/api/types';
import { PartyPicker } from './PartyPicker';

vi.mock('@/api/party');

const aminaOwner: PartyView = {
  partyId: 'd9937444-3873-4336-9cb7-addb486f3e1b',
  partyType: 'INDIVIDUAL',
  kycStatus: 'VERIFIED',
  displayName: 'Amina Owner',
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('PartyPicker', () => {
  it('shows a placeholder and does not search below the minimum query length', async () => {
    const searchSpy = vi.spyOn(partyApi, 'searchParties');
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'A');

    expect(screen.getByText('Type a name to search')).toBeInTheDocument();
    expect(searchSpy).not.toHaveBeenCalled();
  });

  it('searches after a debounce once the query reaches 2 characters, and lists results', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument(), { timeout: 2000 });
    expect(partyApi.searchParties).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'Amina', pageSize: 10 }),
    );
  });

  it('searches immediately, bypassing the debounce and minimum length, when the query is a full UUID', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(
      screen.getByPlaceholderText('Type a name to search'),
      'd9937444-3873-4336-9cb7-addb486f3e1b',
    );

    await waitFor(() => expect(screen.getByText('Amina Owner')).toBeInTheDocument(), { timeout: 1000 });
  });

  it('shows a "no matches" message for a real query with zero results', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [],
      page: { page: 0, pageSize: 10, totalElements: 0 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Nobody Real');

    await waitFor(() => expect(screen.getByText(/No matches for/)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('shows an inline error when the search request fails', async () => {
    vi.spyOn(partyApi, 'searchParties').mockRejectedValue(new Error('network down'));
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() => expect(screen.getByText(/Couldn't search/)).toBeInTheDocument(), { timeout: 2000 });
  });

  it('calls onChange with both the partyId and the full PartyView on selection, and closes', async () => {
    vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [aminaOwner],
      page: { page: 0, pageSize: 10, totalElements: 1 },
    });
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');
    await waitFor(() => screen.getByText('Amina Owner'));
    await user.click(screen.getByText('Amina Owner'));

    expect(onChange).toHaveBeenCalledWith(aminaOwner.partyId, aminaOwner);
    expect(screen.queryByPlaceholderText('Type a name to search')).not.toBeInTheDocument();
  });

  it('passes kycStatus through to the search when a pre-filter is set', async () => {
    const searchSpy = vi.spyOn(partyApi, 'searchParties').mockResolvedValue({
      items: [],
      page: { page: 0, pageSize: 10, totalElements: 0 },
    });
    const user = userEvent.setup();
    render(<PartyPicker value={null} onChange={vi.fn()} kycStatus="VERIFIED" />);

    await user.click(screen.getByRole('button', { name: /search by name/i }));
    await user.type(screen.getByPlaceholderText('Type a name to search'), 'Amina');

    await waitFor(() =>
      expect(searchSpy).toHaveBeenCalledWith(expect.objectContaining({ kycStatus: 'VERIFIED' })),
    );
  });
});
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
cd frontend
npx vitest run src/components/PartyPicker.test.tsx
```

Expected: FAIL — `PartyPicker.tsx` does not exist yet, so the import fails.

- [ ] **Step 5: Write the component**

Create `frontend/src/components/PartyPicker.tsx`:

```tsx
import * as Popover from '@radix-ui/react-popover';
import { Command as CommandPrimitive } from 'cmdk';
import { Loader2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { searchParties } from '@/api/party';
import type { KycStatus, PartyView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import { UUID_PATTERN } from '@/lib/patterns';

export interface PartyPickerProps {
  /** The selected partyId, or null. This component does not resolve a label
   *  for a pre-existing value on mount -- every integration point on this
   *  platform today is a CREATE form, where value always starts null, so
   *  there is no existing id to resolve a name for. If a future caller needs
   *  to edit an already-selected party, it must pass an initial label some
   *  other way; that is out of scope until a real caller needs it. */
  value: string | null;
  onChange: (partyId: string | null, party: PartyView | null) => void;
  /** Pre-filters the search to only this KYC status -- e.g. `VERIFIED` for
   *  agent onboarding, which the backend already rejects any other status for. */
  kycStatus?: KycStatus;
  placeholder?: string;
}

const DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;

type SearchStatus = 'idle' | 'loading' | 'success' | 'error';

export function PartyPicker({ value, onChange, kycStatus, placeholder = 'Search by name…' }: PartyPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null);
  const [results, setResults] = useState<PartyView[]>([]);
  const [status, setStatus] = useState<SearchStatus>('idle');

  // A directly-typed/pasted UUID (a staff member copying an id from
  // elsewhere out of habit) searches immediately -- it is already a
  // complete, unambiguous value, so waiting for the debounce or the
  // 2-character minimum would only add latency with no benefit.
  const trimmed = query.trim();
  const isUuid = UUID_PATTERN.test(trimmed);

  useEffect(() => {
    if (!open) return;
    if (trimmed.length < MIN_QUERY_LENGTH && !isUuid) {
      setResults([]);
      setStatus('idle');
      return;
    }

    let cancelled = false;
    setStatus('loading');
    const timer = setTimeout(
      () => {
        searchParties({ q: trimmed, ...(kycStatus ? { kycStatus } : {}), pageSize: 10 })
          .then((page) => {
            if (cancelled) return;
            setResults(page.items);
            setStatus('success');
          })
          .catch(() => {
            if (cancelled) return;
            setStatus('error');
          });
      },
      isUuid ? 0 : DEBOUNCE_MS,
    );

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [open, trimmed, isUuid, kycStatus]);

  function select(party: PartyView) {
    onChange(party.partyId ?? null, party);
    setSelectedLabel(party.displayName ?? party.partyId ?? null);
    setOpen(false);
    setQuery('');
  }

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={value && selectedLabel ? selectedLabel : placeholder}
          className="flex h-9 w-full items-center rounded-md border border-input bg-surface px-2.5 text-left text-sm"
        >
          {value && selectedLabel ? (
            selectedLabel
          ) : (
            <span className="text-muted-foreground">{placeholder}</span>
          )}
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="start"
          sideOffset={4}
          className="z-50 w-[--radix-popover-trigger-width] rounded-md border border-border bg-surface shadow-lg"
        >
          <CommandPrimitive shouldFilter={false}>
            <CommandPrimitive.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Type a name to search"
              className="h-9 w-full border-b border-border bg-transparent px-2.5 text-sm outline-none"
            />
            <CommandPrimitive.List className="max-h-64 overflow-y-auto p-1">
              {status === 'loading' && (
                <div className="flex items-center gap-2 px-2.5 py-2 text-xs text-muted-foreground">
                  <Loader2 className="size-3.5 animate-spin" />
                  Searching…
                </div>
              )}
              {status === 'error' && (
                <p className="px-2.5 py-2 text-xs text-status-danger-fg">Couldn't search — try again</p>
              )}
              {status === 'success' && results.length === 0 && (
                <p className="px-2.5 py-2 text-xs text-muted-foreground">No matches for '{trimmed}'</p>
              )}
              {status === 'idle' && trimmed.length < MIN_QUERY_LENGTH && (
                <p className="px-2.5 py-2 text-xs text-muted-foreground">Type a name to search</p>
              )}
              {results.map((party) => (
                <CommandPrimitive.Item
                  key={party.partyId}
                  value={party.partyId}
                  onSelect={() => select(party)}
                  className="flex cursor-pointer items-center justify-between gap-2 rounded px-2.5 py-1.5 text-sm data-[selected=true]:bg-hover"
                >
                  <span className="min-w-0 truncate">{party.displayName ?? '—'}</span>
                  <span className="flex shrink-0 items-center gap-1.5">
                    <span className="text-[11px] text-muted-foreground">{party.partyType}</span>
                    {party.kycStatus && <StatusBadge kind="kyc" value={party.kycStatus} />}
                  </span>
                </CommandPrimitive.Item>
              ))}
            </CommandPrimitive.List>
          </CommandPrimitive>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd frontend
npx vitest run src/components/PartyPicker.test.tsx
```

Expected: all 7 tests pass. If the "no matches"/"error" tests are flaky on timing, increase their `waitFor` timeout rather than removing the assertion — the debounce is real and the test must wait through it.

- [ ] **Step 7: Typecheck and lint**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
```

Both must be clean before proceeding.

- [ ] **Step 8: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/api/party.ts \
  frontend/src/components/PartyPicker.tsx frontend/src/components/PartyPicker.test.tsx
git commit -m "feat(frontend): PartyPicker -- searchable party selection component"
```

---

### Task 4a: Integrate `PartyPicker` into `IssuePolicyPage`

**Files:**
- Modify: `frontend/src/features/policies/IssuePolicyPage.tsx`
- Modify: `frontend/e2e/staff-issue-policy.spec.ts`

**Interfaces:**
- Consumes: `PartyPicker` from Task 3.

- [ ] **Step 1: Wire `Controller` and `PartyPicker` for "Policyholder party id"**

In `IssuePolicyPage.tsx`, add `Controller` to the `react-hook-form` import and `PartyPicker` as a new import:

```tsx
import { useFieldArray, useForm, Controller } from 'react-hook-form';
...
import { PartyPicker } from '@/components/PartyPicker';
```

`control` is already destructured from `useForm` (used by `useFieldArray`) — no new destructuring needed.

Replace the "Policyholder party id" `FormField` block:

```tsx
        <FormField label="Policyholder party id" error={errors.policyholderPartyId?.message}>
          <Controller
            control={control}
            name="policyholderPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the policyholder by name"
              />
            )}
          />
        </FormField>
```

- [ ] **Step 2: Wire `PartyPicker` for the embedded beneficiary row's party id**

In the same file, inside the `beneficiaries` field array's `PARTY`-type branch (the `{type === 'PARTY' ? (...) : (...)}` conditional), replace the raw `<input>`:

```tsx
                    {type === 'PARTY' ? (
                      <Controller
                        control={control}
                        name={`beneficiaries.${index}.partyId`}
                        render={({ field }) => (
                          <div className="h-8 flex-1">
                            <PartyPicker
                              value={field.value || null}
                              onChange={(partyId) => field.onChange(partyId ?? '')}
                              placeholder="Search for the beneficiary by name"
                            />
                          </div>
                        )}
                      />
                    ) : (
```

(Leave the `FREEFORM` branch — the `else` half of this ternary — untouched.)

- [ ] **Step 3: Update `staff-issue-policy.spec.ts`'s existing call sites**

This spec file has 4 `getByLabel('Policyholder party id').fill(...)` call sites (lines 44, 58, 69, 103). Two are straightforward picks; two are the negative cases this task removes (replaced by Task 2's new backend contract test).

Add this helper near the top of the file, after the existing `REAL_PARTY_ID` constant (keep `REAL_PARTY_ID` itself — it is still used elsewhere in this file for other fields). Use the picker's exact accessible name, not a loose regex — Task 4a Step 1 gives the "Policyholder party id" field the placeholder/aria-label `"Search for the policyholder by name"` (each integration task gives its own field a distinct, field-specific placeholder, precisely so a loose `/search by name/i`-style pattern is never needed or safe to assume matches):

```ts
async function pickPolicyholder(page: Page, nameQuery = 'Amina') {
  await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill(nameQuery);
  await page.getByText('Amina Owner').click();
}
```

(Add `import type { Page } from '@playwright/test';` if this file's existing top-of-file import doesn't already bring in the `Page` type — check the existing `import { expect, test } from '@playwright/test';` line and extend it to `import { expect, type Page, test } from '@playwright/test';` if needed.)

- Line 44 (`'issues a real policy end to end...'` test): replace `await page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID);` with `await pickPolicyholder(page);`.
- Line 69 (a different test in the same file, also filling `REAL_PARTY_ID`): same replacement.
- Line 58 (`.fill('not-a-uuid')`, the malformed-input client-side-rejection test): this test's whole premise — typing free text into a raw field and asserting a client-side "Not a valid party id" message — is what Task 4a removes the possibility of. Repurpose the test itself to prove the picker's OWN "no results" state instead: rename it (find its current `test(...)` name and change it to something like `'shows no matches for a nonsense policyholder search, before reaching the network'`), and change its body to:

```ts
await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Zzzznonexistentnamezzz');
await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
```

  (No client-side-only "before reaching the network" assertion is meaningful here anymore — the picker's search genuinely IS a network call once 2+ characters are typed, unlike the old raw-input's purely client-side regex check. The test's real remaining value is proving a nonsense query surfaces "no matches" rather than an empty-looking dropdown or a crash.)
- Line 103 (`.fill('00000000-0000-4000-8000-000000000000')`, the well-formed-but-nonexistent-party negative test): DELETE this test entirely. Its coverage now lives in Task 2's `PolicyContractTest.manualIssueReturns404ForANonexistentPolicyholderPartyId`. Before deleting, read the full test to confirm it does not also assert anything ELSE not covered elsewhere (e.g. a specific error message shown in the UI) — if it does, keep that portion but change the setup: since a picker cannot produce a nonexistent-but-well-formed id, this scenario cannot be triggered from the UI at all anymore, so if the test asserts UI-level error rendering for a 404, that specific UI behavior has lost its only trigger and the test must be deleted regardless, not adapted.

- [ ] **Step 4: Restart the dev backend to pick up Task 1's changes, then run this spec against the real stack**

```bash
# Find and kill the stale dev backend process, then:
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Wait for it to report ready (or poll `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/products` until it returns `401`, confirming the app is serving requests), then:

```bash
cd frontend
npx playwright test --project=staff --grep "staff issue policy"
```

Expected: all tests in this spec pass, including the repurposed one and excluding the deleted one.

- [ ] **Step 5: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/policies/IssuePolicyPage.tsx frontend/e2e/staff-issue-policy.spec.ts
git commit -m "feat(frontend): IssuePolicyPage uses PartyPicker for policyholder and beneficiary rows"
```

---

### Task 4b: Migrate the other e2e fixtures that use `IssuePolicyPage` as setup

**Why a separate task:** these 9 files don't test `IssuePolicyPage` itself — they use it purely as a fixture (issue a real policy, then test something else against it). Task 4a already proved the component change works; this task is pure mechanical fixture-updating, and a reviewer might reasonably want to approve 4a's real component change independently of this file-by-file churn.

**Files (each gets the exact same one-line transformation — replace a `.fill(REAL_PARTY_ID)`/`.fill('d9937444-3873-4336-9cb7-addb486f3e1b')` call targeting the "Policyholder party id" field with the `pickPolicyholder`-style interaction from Task 4a):**

- `frontend/e2e/agents-my-book.spec.ts:28` (inside its own `issuePolicy` helper function)
- `frontend/e2e/staff-billing.spec.ts:25`
- `frontend/e2e/staff-claims-adjudication.spec.ts:35`
- `frontend/e2e/staff-finaccounting.spec.ts:75`
- `frontend/e2e/staff-policy-lifecycle.spec.ts:50`
- `frontend/e2e/staff-party-kyc.spec.ts:32`
- `frontend/e2e/staff-claim-evidence.spec.ts:31`
- `frontend/e2e/staff-reinsurance.spec.ts:92`
- `frontend/e2e/staff-distribution.spec.ts:83` (inside its `issueRealPolicyForAgent` helper) **and** `:109` (inline in the `'onboards an agent...'` test) — two distinct sites in this one file.

- [ ] **Step 1: For each file above, replace the fill call**

Each site currently reads (the exact literal varies — either the `REAL_PARTY_ID` constant or the literal string `'d9937444-3873-4336-9cb7-addb486f3e1b'`, both the same value):

```ts
await page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID);
```

Replace with:

```ts
await page.getByRole('button', { name: 'Search for the policyholder by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Amina');
await page.getByText('Amina Owner').click();
```

(In files using the page variable name `page`, keep it as `page`; `agents-my-book.spec.ts`'s helper uses `staffPage` for this exact call — use whichever variable name the surrounding code in that specific file already uses, matching what is already on the line immediately before/after the one being replaced.)

The button's accessible name is exact and unique to this one field (Task 4a Step 1 gives it the placeholder `"Search for the policyholder by name"`, used verbatim as its `aria-label` too by `PartyPicker`'s own implementation) — no `.first()`/`.nth()` disambiguation is ever needed for this specific replacement, regardless of what else is on the page.

- [ ] **Step 2: Run every touched spec file against the real stack**

Backend does not need restarting again for this task (no backend change since Task 1, which task 4a already restarted for).

```bash
cd frontend
npx playwright test --project=staff --project=agents --grep "staff billing|staff claims adjudication|staff finaccounting|staff policy lifecycle|staff party KYC|staff claim evidence|staff reinsurance|staff distribution|agents my book"
```

Expected: all tests across these 9 files pass.

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/agents-my-book.spec.ts frontend/e2e/staff-billing.spec.ts \
  frontend/e2e/staff-claims-adjudication.spec.ts frontend/e2e/staff-finaccounting.spec.ts \
  frontend/e2e/staff-policy-lifecycle.spec.ts frontend/e2e/staff-party-kyc.spec.ts \
  frontend/e2e/staff-claim-evidence.spec.ts frontend/e2e/staff-reinsurance.spec.ts \
  frontend/e2e/staff-distribution.spec.ts
git commit -m "test(e2e): migrate policyholder-party-id fixtures to drive the PartyPicker"
```

---

### Task 5: Integrate `PartyPicker` into `OpenUnderwritingCasePage`

**Files:**
- Modify: `frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx`
- Modify: `frontend/e2e/staff-underwriting.spec.ts`

**Interfaces:**
- Consumes: `PartyPicker` from Task 3.

- [ ] **Step 1: Wire `Controller` and `PartyPicker` for "Applicant party id"**

In `OpenUnderwritingCasePage.tsx`, `control` is NOT currently destructured from `useForm` (the current destructuring is `register, handleSubmit, watch, setValue, formState`). Add it:

```tsx
  const {
    register,
    handleSubmit,
    watch,
    setValue,
    control,
    formState: { errors },
  } = useForm<OpenCaseFormValues>({
```

Add the imports:

```tsx
import { useForm, Controller } from 'react-hook-form';
...
import { PartyPicker } from '@/components/PartyPicker';
```

Replace the "Applicant party id" `FormField` block:

```tsx
        <FormField label="Applicant party id" error={errors.applicantPartyId?.message}>
          <Controller
            control={control}
            name="applicantPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the applicant by name"
              />
            )}
          />
        </FormField>
```

- [ ] **Step 2: Update `staff-underwriting.spec.ts`'s 5 call sites**

- Line 53, 84, 103 (each fills `REAL_PARTY_ID`): replace each `await page.getByLabel('Applicant party id').fill(REAL_PARTY_ID);` with:

```ts
await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Amina');
await page.getByText('Amina Owner').click();
```

- Line 40 (`.fill('not-a-uuid')`, `'rejects a malformed applicant party id client-side...'` test): same treatment as `staff-issue-policy.spec.ts`'s equivalent in Task 4a — rename it (e.g. `'shows no matches for a nonsense applicant search, before reaching the network'`) and replace its body with:

```ts
await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Zzzznonexistentnamezzz');
await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
```
- Line 141 (`.fill('00000000-0000-4000-8000-000000000000')`): DELETE — coverage now lives in Task 2's `UnderwritingContractTest.openCaseReturns404ForANonexistentApplicantPartyId`. Same "read the whole test first" caveat as Task 4a's equivalent step.

- [ ] **Step 3: Run this spec against the real stack**

```bash
cd frontend
npx playwright test --project=staff --grep "staff underwriting"
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
git add frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx frontend/e2e/staff-underwriting.spec.ts
git commit -m "feat(frontend): OpenUnderwritingCasePage uses PartyPicker for the applicant"
```

---

### Task 6: Integrate `PartyPicker` into `RegisterClaimPage`

**Files:**
- Modify: `frontend/src/features/claims/RegisterClaimPage.tsx`
- Modify: `frontend/e2e/staff-claims-adjudication.spec.ts`
- Modify: `frontend/e2e/staff-claim-evidence.spec.ts`

**Interfaces:**
- Consumes: `PartyPicker` from Task 3.

- [ ] **Step 1: Wire `Controller` and `PartyPicker` for "Claimant party id"**

In `RegisterClaimPage.tsx`, `control` is not currently destructured (current: `register, handleSubmit, watch, setValue, formState`). Add it, and the two new imports, matching Task 5's exact pattern:

```tsx
  const {
    register,
    handleSubmit,
    watch,
    setValue,
    control,
    formState: { errors },
  } = useForm<RegisterClaimFormValues>({
```

```tsx
import { useForm, Controller } from 'react-hook-form';
...
import { PartyPicker } from '@/components/PartyPicker';
```

Replace the "Claimant party id" `FormField` block:

```tsx
        <FormField label="Claimant party id" error={errors.claimantPartyId?.message}>
          <Controller
            control={control}
            name="claimantPartyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                placeholder="Search for the claimant by name"
              />
            )}
          />
        </FormField>
```

- [ ] **Step 2: Update the two call sites**

Both `staff-claims-adjudication.spec.ts:49` and `staff-claim-evidence.spec.ts:43` fill `'Claimant party id'` with the same real party id. Replace each:

```ts
await page.getByLabel('Claimant party id').fill(REAL_PARTY_ID);
```

with:

```ts
await page.getByRole('button', { name: 'Search for the claimant by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Amina');
await page.getByText('Amina Owner').click();
```

Both call sites are on `/staff/claims/new` at the moment this runs — a fully separate route from `/staff/policies/new`, which any earlier policy-issuance step in the same test has already navigated away from (a client-side route change unmounts the previous page's component tree entirely). There is only ever one "search for the claimant by name" button on screen when this fires; the field's exact, unique accessible name makes `.first()`/`.nth()` disambiguation unnecessary regardless.

- [ ] **Step 3: Run both specs against the real stack**

```bash
cd frontend
npx playwright test --project=staff --grep "staff claims adjudication|staff claim evidence"
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
  frontend/e2e/staff-claims-adjudication.spec.ts frontend/e2e/staff-claim-evidence.spec.ts
git commit -m "feat(frontend): RegisterClaimPage uses PartyPicker for the claimant"
```

---

### Task 7: Integrate `PartyPicker` into `OnboardAgentPage` (VERIFIED-filtered) + the pre-filter e2e proof

**Files:**
- Modify: `frontend/src/features/distribution/OnboardAgentPage.tsx`
- Modify: `frontend/e2e/staff-agent-lifecycle.spec.ts`
- Modify: `frontend/e2e/staff-distribution.spec.ts`

**Interfaces:**
- Consumes: `PartyPicker` from Task 3, with `kycStatus="VERIFIED"` passed.

- [ ] **Step 1: Wire `Controller` and `PartyPicker` for "Party id"**

In `OnboardAgentPage.tsx`, add `control` to the existing `useForm` destructuring (currently `register, handleSubmit, formState`):

```tsx
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<OnboardAgentFormValues>({
```

Add the imports:

```tsx
import { useForm, Controller } from 'react-hook-form';
...
import { PartyPicker } from '@/components/PartyPicker';
```

Replace the "Party id" `FormField` block:

```tsx
        <FormField label="Party id" error={errors.partyId?.message}>
          <Controller
            control={control}
            name="partyId"
            render={({ field }) => (
              <PartyPicker
                value={field.value || null}
                onChange={(partyId) => field.onChange(partyId ?? '')}
                kycStatus="VERIFIED"
                placeholder="Search for a VERIFIED party by name"
              />
            )}
          />
        </FormField>
```

- [ ] **Step 2: Update the two existing call sites**

Both `staff-agent-lifecycle.spec.ts:17` and `staff-distribution.spec.ts:31` are inside each file's own `onboardRealAgent(page)` helper function. Replace:

```ts
await page.getByLabel('Party id').fill(REAL_PARTY_ID);
```

with:

```ts
await page.getByRole('button', { name: 'Search for a VERIFIED party by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Amina');
await page.getByText('Amina Owner').click();
```

(`REAL_PARTY_ID` — "Amina Owner" — is seeded `VERIFIED`, confirmed directly against the dev database: `SELECT display_name, kyc_status FROM party.party WHERE party_id = 'd9937444-3873-4336-9cb7-addb486f3e1b'` returns `Amina Owner | VERIFIED`. She will appear in a VERIFIED-filtered search.)

- [ ] **Step 3: Add a new e2e test proving the VERIFIED-only pre-filter genuinely excludes a PENDING party**

Add to `staff-agent-lifecycle.spec.ts` (or `staff-distribution.spec.ts` — either file already has the right fixtures available; place it in `staff-agent-lifecycle.spec.ts` since that file's whole focus is agent-onboarding-adjacent behavior):

```ts
test('the onboarding party picker excludes a real PENDING party, at the network level', async ({ page, browser }) => {
  // A real, fresh PENDING party -- created through the real agents-realm
  // onboarding flow (a separate real login), not seeded, so this proves the
  // whole loop: a party this platform itself just created as PENDING is
  // genuinely excluded by the server-side kycStatus=VERIFIED filter, not
  // merely assumed to be.
  const agentContext = await browser.newContext({ storageState: 'e2e/.auth/agent.json' });
  const agentPage = await agentContext.newPage();
  const pendingName = `E2E Picker Exclusion Fixture ${Date.now()}`;
  await agentPage.goto('/agents/customers/new');
  await agentPage.getByLabel('Full name').fill(pendingName);
  await agentPage.getByLabel('Date of birth').fill('1990-05-12');
  await agentPage.getByRole('button', { name: 'Register individual' }).click();
  await expect(agentPage.getByText('Registered', { exact: true })).toBeVisible({ timeout: 15_000 });
  await agentContext.close();

  await page.goto('/staff/agents/new');
  await page.getByRole('button', { name: 'Search for a VERIFIED party by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('E2E Picker Exclusion Fixture');

  await expect(page.getByText(/No matches for/)).toBeVisible({ timeout: 5000 });
  await expect(page.getByText(pendingName)).not.toBeVisible();
});
```

- [ ] **Step 4: Run both specs against the real stack**

```bash
cd frontend
npx playwright test --project=staff --project=agents --grep "staff agent lifecycle|staff distribution"
```

- [ ] **Step 5: Typecheck, lint, unit test, build**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/distribution/OnboardAgentPage.tsx \
  frontend/e2e/staff-agent-lifecycle.spec.ts frontend/e2e/staff-distribution.spec.ts
git commit -m "feat(frontend): OnboardAgentPage uses PartyPicker, VERIFIED-filtered"
```

---

### Task 8: Integrate `PartyPicker` into `BeneficiariesPanel`

**Files:**
- Modify: `frontend/src/features/policies/BeneficiariesPanel.tsx`
- Modify: `frontend/e2e/staff-beneficiaries.spec.ts`

**Interfaces:**
- Consumes: `PartyPicker` from Task 3.

- [ ] **Step 1: Wire `Controller` and `PartyPicker` for the beneficiary row's party id**

In `BeneficiariesPanel.tsx`'s `EditForm` component, `control` is already destructured (used by `useFieldArray`). Add the import:

```tsx
import { useFieldArray, useForm, Controller } from 'react-hook-form';
...
import { PartyPicker } from '@/components/PartyPicker';
```

Replace the `type === 'PARTY'` branch's raw `<input>` with the same pattern used in Task 4a:

```tsx
                {type === 'PARTY' ? (
                  <Controller
                    control={control}
                    name={`beneficiaries.${index}.partyId`}
                    render={({ field }) => (
                      <div className="h-8 flex-1">
                        <PartyPicker
                          value={field.value || null}
                          onChange={(partyId) => field.onChange(partyId ?? '')}
                          placeholder="Search for the beneficiary by name"
                        />
                      </div>
                    )}
                  />
                ) : (
```

(Leave the `FREEFORM` `else` branch untouched.)

- [ ] **Step 2: Update `staff-beneficiaries.spec.ts`'s one call site**

Line 89's test fills BOTH a party id AND a freeform designee to prove the client-side XOR rule fires (this test's real subject is "both provided is rejected", not "this specific id is real or valid"). Read the full test around line 89 first to see its exact current shape, then change:

```ts
await page.getByPlaceholder('Party id (uuid)').fill('11111111-1111-4111-8111-111111111111');
```

to:

```ts
await page.getByRole('button', { name: 'Search for the beneficiary by name' }).click();
await page.getByPlaceholder('Type a name to search').fill('Amina');
await page.getByText('Amina Owner').click();
```

The rest of that test (filling `'Also this'` into the freeform designee field immediately after, and asserting the XOR rejection message) stays as-is — the point being proven (both fields set together is rejected) is unaffected by which real party was picked.

This test calls `removeAllRows(page)` before adding exactly one fresh row, so exactly one `'Search for the beneficiary by name'` button exists at fill-time — the exact-name selector above resolves unambiguously here. This will NOT hold for any future test that adds more than one beneficiary row before filling one of them: every row's picker shares the identical placeholder text (there is nothing row-specific to distinguish them by name), so a future multi-row test needs to scope its locator to the specific row's container element first (e.g. `page.locator('.rounded-md.border.border-border.p-2\\.5').nth(rowIndex).getByRole('button', { name: 'Search for the beneficiary by name' })`) rather than a bare page-wide `getByRole` call. Not needed for this task's own test, but worth knowing before extending it.

- [ ] **Step 3: Run this spec against the real stack**

```bash
cd frontend
npx playwright test --project=staff --grep "staff beneficiaries"
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
git add frontend/src/features/policies/BeneficiariesPanel.tsx frontend/e2e/staff-beneficiaries.spec.ts
git commit -m "feat(frontend): BeneficiariesPanel uses PartyPicker for existing-party rows"
```

---

### Task 9: Full verification and wrap-up

**Files:** none (verification only).

- [ ] **Step 1: Full backend test suite**

```bash
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -o test
```

Expected: 0 failures, 0 errors. This plan's Global Constraints note this is normally skipped in favor of narrower runs — this is the one task in the plan where the full run is warranted (multiple modules' tests were touched: `party`, `policy`, `underwriting`).

- [ ] **Step 2: Full frontend verification**

```bash
cd frontend
npx tsc --noEmit
npx eslint .
npx vitest run
npm run build
```

- [ ] **Step 3: Full affected-project e2e run**

Restart the dev backend one more time first if it has been running since Task 4a with no restart since (confirm by checking whether any backend file changed after that restart — it should not have, since Tasks 2 to 8 in this plan are frontend/e2e-only after Task 1/2's backend work, but Task 2's backend changes were made AFTER Task 4a's restart if the tasks were executed in order — restart again to be certain the dev backend reflects Task 2's changes too):

```bash
cd frontend
npx playwright test --project=staff --project=agents
```

Expected: all tests pass. A single unrelated failure that also fails on an immediate isolated re-run (`--grep` to that one test) is a real regression — investigate and fix before proceeding. A single failure that passes cleanly on retry in isolation is the documented transient Keycloak-redirect flake already known to this suite — note it, do not chase it.

- [ ] **Step 4: Final commit (if any stray changes remain)**

```bash
cd /path/to/repo/root
git status --short
```

If clean (everything was already committed task-by-task), this step is a no-op. If anything stray remains (e.g. a `package-lock.json` diff not caught by an earlier task's `git add`), stage and commit it with a clear message.

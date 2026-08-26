# Underwriting Case Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real underwriting case queue — `GET /underwriting/cases` on the backend, and a staff-console list page on the frontend — so a case opened via `POST /underwriting/cases` has somewhere to browse back to, closing the gap where "Underwriting" in the staff nav only ever led to the create form.

**Architecture:** Backend gains one new paginated, status-filterable, tenant-scoped GET endpoint reusing the existing `UnderwritingCaseView` wire DTO (no new response shape for a single case). Frontend gains a data layer (`listCases` API function + a `list` store slot) and a UI layer (`UnderwritingQueuePage` + `UnderwritingCaseDrawer`, mirroring `TreatiesPage`/`TreatyDrawer` exactly), plus a route and one nav-target change.

**Tech Stack:** Spring Boot / Spring Data JPA (backend), React / Zustand / React Router (frontend), Playwright (e2e).

## Global Constraints

- No `q` free-text search on this endpoint — an underwriting case has no human-facing identifier (only a UUID `caseId`), unlike a policy number or claim.
- Same authorization as the existing `POST /underwriting/cases`/`GET /underwriting/cases/{caseId}`: `hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')`. Tenant-scoped only — no per-case ownership/agent-of-record scoping (none exists on `UnderwritingCase`).
- Newest-first default sort by `createdAt`, mirroring `PolicyController`/`ClaimController`'s established pattern from this session.
- Reuse `UnderwritingCaseView` as the list-item DTO as-is. Do not add a new DTO or expose the `@JsonIgnore`d fields (`productVersionId`, `sumAssuredAmount`, `sumAssuredCurrency`) — that is a separate, unrequested change.
- Every new backend list/filter test MUST seed a second, unrelated case in the same tenant so an ignored filter would genuinely return 2 items instead of 1 — a fresh-random-tenant test with only one fixture can pass even when the filter is completely ignored (this exact false-positive class was found and fixed earlier this session in `PolicyContractTest`/`ClaimsContractTest`).
- Every new backend HTTP fixture chain goes through real endpoints (`POST /parties/individuals`, `POST /products`, `POST /products/{id}/versions`, `GET /products/{id}/active-snapshot`, `POST /underwriting/cases`) — never direct repository/entity seeding.
- Staff-only for now. Do not touch the agents-realm nav (`AGENTS_NAV` in `AppShell.tsx`) or add an agents-realm route.

---

### Task 1: Backend — `GET /underwriting/cases` list endpoint

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/UnderwritingApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseSearchResponse.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingController.java`
- Modify: `backend/api/openapi/openapi-underwriting.yaml`
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java`

**Interfaces:**
- Consumes: `UnderwritingCaseView` (existing, unchanged — `underwriting/api/UnderwritingCaseView.java`), `TenantContext.get()` (existing static accessor, already used throughout `UnderwritingApiImpl`).
- Produces: `UnderwritingApi.listCases(UnderwritingCaseStatus status, Pageable pageable): Page<UnderwritingCaseView>` — Task 2/3 (frontend) call the HTTP endpoint this backs, not this method directly, but later backend work (if any) would use this signature.

- [ ] **Step 1: Write the failing contract tests**

Open `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java`. It already has `registerTestApplicant(tenantId)`, `publishTestProduct(tenantId)`, and `openCaseViaHttp(tenantId, applicantId, productId, productVersionId)` helpers — reuse them exactly, do not write new ones. Add these three tests at the end of the class, before the final closing `}`:

```java
    @Test
    void listCasesDefaultsToNewestCreatedFirst() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);

        String firstCase = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String firstCaseId = JsonPath.read(firstCase, "$.caseId");
        String secondCase = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String secondCaseId = JsonPath.read(secondCase, "$.caseId");

        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].caseId").value(secondCaseId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[1].caseId").value(firstCaseId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void listCasesFiltersByStatus() throws Exception {
        // Two cases in the SAME tenant, both left OPEN (nothing here decides
        // either) -- if the status filter were silently ignored, filtering to
        // DECIDED would still wrongly return these 2 OPEN cases instead of 0.
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .param("status", "OPEN")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/underwriting/cases")
                .param("status", "DECIDED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void listCasesIsTenantScoped() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());

        UUID otherTenantId = UUID.randomUUID();
        UUID otherApplicantId = registerTestApplicant(otherTenantId);
        ProductFixture otherProduct = publishTestProduct(otherTenantId);
        openCaseViaHttp(otherTenantId, otherApplicantId, otherProduct.productId(), otherProduct.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(1));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -o test -Dtest=UnderwritingContractTest#listCasesDefaultsToNewestCreatedFirst+listCasesFiltersByStatus+listCasesIsTenantScoped`
Expected: FAIL (404 — no `GET /underwriting/cases` route exists yet).

- [ ] **Step 3: Add the two repository methods**

In `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseRepository.java`, add `Pageable`/`Page` imports and the two derived methods:

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {
    Optional<UnderwritingCase> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);
    Page<UnderwritingCase> findByTenantId(UUID tenantId, Pageable pageable);
    Page<UnderwritingCase> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
}
```

- [ ] **Step 4: Add `listCases` to `UnderwritingApi`**

In `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/UnderwritingApi.java`, add the method to the interface (check the existing imports for `Page`/`Pageable` — add `org.springframework.data.domain.Page` and `org.springframework.data.domain.Pageable` if not already present):

```java
Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, Pageable pageable);
```

- [ ] **Step 5: Implement `listCases` in `UnderwritingApiImpl`**

In `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`, add (near `getCase`, after it):

```java
    @Override
    public Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<UnderwritingCase> page = status != null
            ? underwritingCaseRepository.findByTenantIdAndStatus(tenantId, status.name(), pageable)
            : underwritingCaseRepository.findByTenantId(tenantId, pageable);
        return page.map(this::toView);
    }
```

Add `import org.springframework.data.domain.Page;` and `import org.springframework.data.domain.Pageable;` to this file's imports if not already present (check first — `underwriting.api.*` and `underwriting.domain.*` are already wildcard-imported, but `Page`/`Pageable` are Spring Data types, not underwriting-package types, so they need their own import lines).

- [ ] **Step 6: Create `UnderwritingCaseSearchResponse`**

Create `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseSearchResponse.java`:

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import org.springframework.data.domain.Page;

import java.util.List;

public record UnderwritingCaseSearchResponse(List<UnderwritingCaseView> items, UnderwritingCaseSearchResponse.PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static UnderwritingCaseSearchResponse from(Page<UnderwritingCaseView> springPage) {
        return new UnderwritingCaseSearchResponse(
            springPage.getContent(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
```

- [ ] **Step 7: Add the `GET /cases` route to `UnderwritingController`**

In `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingController.java`, add imports for `org.springframework.data.domain.Page`, `org.springframework.data.domain.PageRequest`, `org.springframework.data.domain.Pageable`, and `org.springframework.data.domain.Sort` (the file currently has no Spring Data imports at all — only `jakarta.validation.Valid`, `org.springframework.http.*`, `org.springframework.security.*`, `org.springframework.web.bind.annotation.*`). Then add this method, placed before `openCase` (so `GET /cases` and `POST /cases` sit together at the top, matching the OpenAPI spec's own path-item ordering):

```java
    @GetMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseSearchResponse> listCases(
            @RequestParam(required = false) UnderwritingCaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Pageable pageable = PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UnderwritingCaseView> result = underwritingApi.listCases(status, pageable);
        return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(result));
    }
```

Add `import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;` to this file's imports (it currently only imports `UnderwritingApi` and `UnderwritingCaseView` from the `api` package).

- [ ] **Step 8: Add the `GET` operation to the OpenAPI spec**

In `backend/api/openapi/openapi-underwriting.yaml`, under the existing `/underwriting/cases:` path item (which currently has only a `post:` child), add a sibling `get:` — the path item becomes:

```yaml
  /underwriting/cases:
    get:
      summary: List underwriting cases for the current tenant
      security:
        - agentsAuth: [agent]
        - staffAuth: []
      parameters:
        - name: status
          in: query
          schema: { type: string, enum: [OPEN, IN_REVIEW, DECIDED] }
        - name: page
          in: query
          schema: { type: integer, default: 0 }
        - name: pageSize
          in: query
          schema: { type: integer, default: 20, maximum: 100 }
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: object
                properties:
                  items:
                    type: array
                    items: { $ref: '#/components/schemas/UnderwritingCaseView' }
                  page: { $ref: 'openapi-common.yaml#/components/schemas/PageMeta' }
    post:
      summary: Submit a new business application for underwriting
      description: Entry point for new business — agent portal, bancassurance portal, or staff back-office.
      security:
        - agentsAuth: [agent]
        - staffAuth: []
      parameters:
        - $ref: 'openapi-common.yaml#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/OpenCaseRequest' }
      responses:
        '201':
          description: Case opened
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UnderwritingCaseView' }
        '400': { $ref: 'openapi-common.yaml#/components/responses/BadRequest' }
        '401': { $ref: 'openapi-common.yaml#/components/responses/Unauthorized' }
        '422': { $ref: 'openapi-common.yaml#/components/responses/UnprocessableEntity' }
```

(The `post:` block above is copied unchanged from the file's current content — only the new `get:` sibling is new. Do not alter the `post:` block's content, only its position relative to the new `get:`.)

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=UnderwritingContractTest`
Expected: PASS, all tests in the class (the 3 new ones plus the existing ones — the existing ones must stay green too, since the OpenAPI file changed).

- [ ] **Step 10: Run the full backend suite**

Run: `cd backend && ./mvnw -o test > /tmp/uw-backend-test.log 2>&1; echo "EXIT:$?"; grep -E "Tests run:|BUILD " /tmp/uw-backend-test.log`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors — grep the log file directly rather than trusting a piped/backgrounded exit code (this session has twice seen a wrapper mis-report a real `BUILD FAILURE` as exit 0).

- [ ] **Step 11: Commit**

```bash
cd backend
git add src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseRepository.java \
  src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/UnderwritingApi.java \
  src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java \
  src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseSearchResponse.java \
  src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingController.java \
  api/openapi/openapi-underwriting.yaml \
  src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java
git commit -m "feat(underwriting): add GET /underwriting/cases list endpoint"
```

---

### Task 2: Frontend — data layer (`listCases` API function + store `list` slot)

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/underwriting.ts`
- Modify: `frontend/src/store/underwritingStore.ts`
- Modify: `frontend/src/store/underwritingStore.test.ts`

**Interfaces:**
- Consumes: `UnderwritingCaseView`, `Page<T>` (both existing, `frontend/src/api/types.ts`), `idle`/`success`/`track`/`isInitialLoad` from `@/store/createResourceSlice` (existing).
- Produces: `listCases(params?: UnderwritingListParams): Promise<Page<UnderwritingCaseView>>` (from `@/api/underwriting`) — Task 3 calls this indirectly via the store. `useUnderwritingStore`'s new `list: Resource<Page<UnderwritingCaseView>>` field and `loadList(params: UnderwritingListParams): Promise<void>` action — Task 3 (`UnderwritingQueuePage`) consumes these directly.

- [ ] **Step 1: Add the status-list constant to `api/types.ts`**

Open `frontend/src/api/types.ts`. Immediately after the existing line `export type UnderwritingCaseStatus = NonNullable<UnderwritingCaseView['status']>;` (around line 284), add:

```typescript
export const UNDERWRITING_CASE_STATUSES: readonly UnderwritingCaseStatus[] = ['OPEN', 'IN_REVIEW', 'DECIDED'];
```

- [ ] **Step 2: Write the failing store test**

Open `frontend/src/store/underwritingStore.test.ts` (currently 60 lines, two `describe` blocks: `resetOpenCase`, `resetSubmitAssessment`). Read the whole file first to match its existing mocking style exactly (it mocks `@/api/underwriting`'s functions via `vi.mock`). Add a new `describe` block:

```typescript
describe('loadList', () => {
  it('populates list with a fresh Page result', async () => {
    const page = { items: [], page: { page: 0, pageSize: 20, totalElements: 0 } };
    vi.mocked(listCases).mockResolvedValueOnce(page);

    await useUnderwritingStore.getState().loadList({});

    expect(useUnderwritingStore.getState().list.status).toBe('success');
    expect(useUnderwritingStore.getState().list.data).toEqual(page);
  });
});
```

Add `listCases` to the existing `vi.mock('@/api/underwriting', ...)` factory at the top of the file (read the current mock factory first — it lists `openCase`, `getCase`, `submitAssessment`, `referCase` as `vi.fn()`; add `listCases: vi.fn(),` alongside them) and add `listCases` to the file's existing import line from `@/api/underwriting`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/store/underwritingStore.test.ts`
Expected: FAIL — `loadList` is not a function / `list` is undefined.

- [ ] **Step 4: Add `listCases` to `api/underwriting.ts`**

Open `frontend/src/api/underwriting.ts`. First, correct the module doc comment (lines 4-15) — it currently states flatly "There is no `GET /underwriting/cases` list or search endpoint anywhere on this platform." Replace the whole doc comment block with:

```typescript
/**
 * Underwriting read/write surface.
 *
 * `GET /underwriting/cases` (added alongside this comment) is tenant-scoped and
 * status-filterable, but carries no free-text search -- a case has no
 * human-facing identifier the way a policy number or claim does, only a raw
 * UUID `caseId`. `POST /policies`'s own `underwritingCaseId` still never
 * round-trips back out through `GET /policies` either (`PolicyResponseDto` --
 * the actual wire DTO, not the internal `PolicyView` -- has no such field, and
 * neither does the OpenAPI spec's `PolicyView` response schema): the ONLY way
 * to reach a specific case afterward is either the id captured when it was
 * opened, or browsing the list below.
 */
```

Add `UnderwritingCaseStatus` to the existing `import type { OpenCaseRequest, SubmitAssessmentRequest, UnderwritingCaseView } from './types';` line, and add `Page` to it too. Then add, after the doc comment, before `openCase`:

```typescript
export interface UnderwritingListParams {
  status?: UnderwritingCaseStatus;
  page?: number;
  pageSize?: number;
}

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 100;

/** `GET /underwriting/cases` -- agent or staff, tenant-scoped, no free-text search. */
export async function listCases(params: UnderwritingListParams = {}): Promise<Page<UnderwritingCaseView>> {
  const page = params.page ?? 0;
  const pageSize = Math.min(params.pageSize ?? DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

  const body = await get<{
    items?: UnderwritingCaseView[];
    page?: { page?: number; pageSize?: number; totalElements?: number };
  }>('/underwriting/cases', {
    params: {
      ...(params.status ? { status: params.status } : {}),
      page,
      pageSize,
    },
  });

  return {
    items: body.items ?? [],
    page: {
      page: body.page?.page ?? page,
      pageSize: body.page?.pageSize ?? pageSize,
      totalElements: body.page?.totalElements ?? 0,
    },
  };
}
```

- [ ] **Step 5: Add the `list` slot and `loadList` to `underwritingStore.ts`**

In `frontend/src/store/underwritingStore.ts`:

Change the import line to add `listCases` and the `UnderwritingListParams` type:

```typescript
import { getCase, listCases, openCase, referCase, submitAssessment } from '@/api/underwriting';
import type { UnderwritingListParams } from '@/api/underwriting';
import type { OpenCaseRequest, Page, SubmitAssessmentRequest, UnderwritingCaseView } from '@/api/types';
```

Add to the `UnderwritingState` interface, as the first field (mirroring `PolicyState`'s `list` placement):

```typescript
  list: Resource<Page<UnderwritingCaseView>>;
```

and to its methods:

```typescript
  loadList: (params: UnderwritingListParams) => Promise<void>;
```

Add to the store's initial state object, as the first field:

```typescript
  list: idle(),
```

Add the action, mirroring `policyStore.ts`'s `loadList` exactly (constant key regardless of filter, so only the most recently requested filter wins):

```typescript
  loadList: (params) =>
    track(
      'underwriting.list',
      getState().list,
      (next) => set({ list: next }),
      () => listCases(params),
    ),
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/store/underwritingStore.test.ts`
Expected: PASS.

- [ ] **Step 7: Typecheck and lint**

Run: `cd frontend && npx tsc --noEmit && npx eslint src/api/types.ts src/api/underwriting.ts src/store/underwritingStore.ts src/store/underwritingStore.test.ts`
Expected: both clean.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/api/types.ts src/api/underwriting.ts src/store/underwritingStore.ts src/store/underwritingStore.test.ts
git commit -m "feat(frontend): underwriting listCases API function and store list slot"
```

---

### Task 3: Frontend — `UnderwritingQueuePage`, `UnderwritingCaseDrawer`, route, nav

**Files:**
- Create: `frontend/src/features/underwriting/UnderwritingQueuePage.tsx`
- Create: `frontend/src/features/underwriting/UnderwritingCaseDrawer.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AppShell.tsx`
- Modify: `frontend/src/features/reinsurance/TreatiesPage.tsx` (one comment)

**Interfaces:**
- Consumes: `useUnderwritingStore`'s `list`/`loadList` (Task 2) and existing `cases`/`loadCase` (`selectCase`, pre-existing), `UNDERWRITING_CASE_STATUSES`/`UnderwritingCaseStatus`/`UnderwritingCaseView` (Task 2 / pre-existing), `DataTable`/`Pager`/`Column` (`@/components/DataTable`), `StatCards`/`Stat` (`@/components/StatCards`), `StatusBadge` (`@/components/StatusBadge`, `kind="underwritingCase"`), `EmptyState`/`ErrorPanel`/`TableSkeleton`/`LoadingBlock` (`@/components/states`), `isInitialLoad` (`@/store/createResourceSlice`), `Sheet`/`SheetContent`/`SheetHeader`/`SheetBody`/`SheetFooter` (`@/components/ui/sheet`), `Field` (`@/components/Field`).
- Produces: `UnderwritingQueuePage` and `UnderwritingCaseDrawer` components — Task 4 (e2e) drives them through the browser, not through direct import.

- [ ] **Step 1: Create `UnderwritingCaseDrawer.tsx`**

Create `frontend/src/features/underwriting/UnderwritingCaseDrawer.tsx`, mirroring `frontend/src/features/reinsurance/TreatyDrawer.tsx`'s structure exactly:

```tsx
import { ArrowRight } from 'lucide-react';
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader } from '@/components/ui/sheet';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectCase, useUnderwritingStore } from '@/store/underwritingStore';

/**
 * The preview half of drawer-previews-page-acts, same convention every other
 * list page on this console follows (see TreatyDrawer, which -- like this one
 * -- has no mutating action inside it either; both are "the compact view",
 * not "the read-only twin of a page that also acts").
 */
export function UnderwritingCaseDrawer({
  caseId,
  onClose,
}: {
  caseId: string | null;
  onClose: () => void;
}) {
  const detail = useUnderwritingStore(selectCase(caseId ?? ''));
  const loadCase = useUnderwritingStore((s) => s.loadCase);

  useEffect(() => {
    if (caseId) void loadCase(caseId);
  }, [caseId, loadCase]);

  const uwCase = detail.data;

  return (
    <Sheet
      open={caseId !== null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent aria-label="Underwriting case preview">
        <SheetHeader
          title="Underwriting case"
          subtitle={uwCase?.applicantPartyId}
          action={uwCase?.status ? <StatusBadge kind="underwritingCase" value={uwCase.status} /> : undefined}
        />

        <SheetBody>
          {isInitialLoad(detail) && <LoadingBlock />}

          {detail.status === 'error' && detail.error && detail.data === null && (
            <ErrorPanel
              error={detail.error}
              {...(caseId ? { onRetry: () => void loadCase(caseId) } : {})}
            />
          )}

          {uwCase && (
            <dl className="space-y-0">
              <Field label="Applicant" value={<span className="font-mono text-xs">{uwCase.applicantPartyId}</span>} />
              <Field label="Product" value={<span className="font-mono text-xs">{uwCase.productId}</span>} />
              <Field
                label="Decision"
                value={uwCase.decisionOutcome ?? 'Not yet decided'}
              />
            </dl>
          )}
        </SheetBody>

        {caseId && (
          <SheetFooter>
            <Button asChild variant="outline" className="w-full justify-between">
              <Link to={`../underwriting/${caseId}`} relative="path">
                Full detail
                <ArrowRight />
              </Link>
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: Create `UnderwritingQueuePage.tsx`**

Create `frontend/src/features/underwriting/UnderwritingQueuePage.tsx`, mirroring `frontend/src/features/reinsurance/TreatiesPage.tsx`'s structure (paged variant, using `Pager`, like `PoliciesPage.tsx`):

```tsx
import { Plus } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { UNDERWRITING_CASE_STATUSES, type UnderwritingCaseStatus, type UnderwritingCaseView } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { UnderwritingCaseDrawer } from './UnderwritingCaseDrawer';

const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /underwriting/cases` -- staff console only for now (see AppShell.tsx's
 * STAFF_NAV comment). No free-text search: a case has no human-facing
 * identifier, only a raw UUID caseId, unlike policies/claims.
 */
export function UnderwritingQueuePage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const statusParam = params.get('status');
  const status: UnderwritingCaseStatus | undefined =
    statusParam && (UNDERWRITING_CASE_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as UnderwritingCaseStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useUnderwritingStore((s) => s.list);
  const loadList = useUnderwritingStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, status, page]);

  function update(next: { status?: UnderwritingCaseStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;
  const busy = list.status === 'loading';

  const stats: Stat[] = [
    {
      label: status ? `${status[0]}${status.slice(1).toLowerCase().replace('_', ' ')} cases` : 'All cases',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : status
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<UnderwritingCaseView>[] = [
    {
      key: 'caseId',
      header: 'Case ID',
      render: (c) => <span className="font-mono text-xs font-medium">{c.caseId ?? '—'}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (c) => (c.status ? <StatusBadge kind="underwritingCase" value={c.status} /> : '—'),
    },
    {
      key: 'applicantPartyId',
      header: 'Applicant',
      render: (c) => <span className="font-mono text-xs">{c.applicantPartyId ?? '—'}</span>,
    },
    {
      key: 'productId',
      header: 'Product',
      secondary: true,
      render: (c) => <span className="font-mono text-xs text-muted-foreground">{c.productId ?? '—'}</span>,
    },
    {
      key: 'decisionOutcome',
      header: 'Decision',
      secondary: true,
      render: (c) => <span className="text-muted-foreground">{c.decisionOutcome ?? '—'}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() => void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE })}
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase().replace('_', ' ')} cases` : 'No cases yet'}
          description={
            status
              ? 'Nothing in this tenant currently has that status.'
              : 'Cases appear here once opened.'
          }
          {...(status
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ status: undefined })}>
                    Clear filter
                  </Button>
                ),
              }
            : {})}
        />
      );
    }

    return (
      <>
        {list.status === 'error' && list.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(c) => c.caseId ?? JSON.stringify(c)}
          onRowActivate={(c) => {
            if (c.caseId) setPreviewing(c.caseId);
          }}
          isRowSelected={(c) => c.caseId === previewing}
          caption="Underwriting cases"
        />
        {list.data && (
          <Pager page={list.data.page} busy={busy} onPageChange={(next) => update({ page: next })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Underwriting"
        description="Every underwriting case in your tenant. Select one to preview it."
        actions={
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New case
            </Link>
          </Button>
        }
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: undefined })} />
            {UNDERWRITING_CASE_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="underwritingCase" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <UnderwritingCaseDrawer caseId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}

function FilterChip({
  label,
  active,
  onClick,
}: {
  label: ReactNode;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full px-2 py-1 text-xs transition-colors',
        active ? 'bg-selected ring-1 ring-border-strong ring-inset' : 'hover:bg-hover',
      )}
    >
      {label}
    </button>
  );
}
```

- [ ] **Step 3: Add the route**

In `frontend/src/App.tsx`, add the import:

```tsx
import { UnderwritingQueuePage } from '@/features/underwriting/UnderwritingQueuePage';
```

(alongside the existing `OpenUnderwritingCasePage`/`UnderwritingCaseDetailPage` imports at lines 29-30). Add the route immediately before the existing `underwriting/new` route:

```tsx
<Route path="underwriting" element={<UnderwritingQueuePage />} />
```

so the three underwriting routes read, in order: `underwriting`, `underwriting/new`, `underwriting/:caseId` — matching `treaties`/`treaties/new`/`treaties/:treatyId`'s ordering.

- [ ] **Step 4: Update the nav target**

In `frontend/src/components/AppShell.tsx` line 77, change:

```tsx
{ to: 'underwriting/new', label: 'Underwriting', icon: ClipboardCheck, implemented: true },
```

to:

```tsx
{ to: 'underwriting', label: 'Underwriting', icon: ClipboardCheck, implemented: true },
```

Then update the doc comment above `STAFF_NAV` (lines ~26-55) — it currently reads (in part): *"Underwriting and Agents are the two exceptions among what's left, and deliberately not 'paste an ID' screens: `POST /underwriting/cases` and `POST /agents` are the only entry points onto those domains that exist server-side (neither has a list/search endpoint)..."* and later: *"Underwriting's own case id is never re-surfaced anywhere else on this platform... so its detail page is explicit that the id it hands back is the only way to return."* Both of these are now false for Underwriting. Replace the whole doc comment block (from `/**` to `*/` immediately above `interface NavItem`) with:

```typescript
/**
 * Nav definition for the staff realm.
 *
 * `implemented` gates rendering. Every entry here has a real list endpoint behind
 * it -- entities that are STILL fetch-by-ID only (payments, payout batches,
 * documents) deliberately get NO nav item, because an item that leads to a
 * "paste an ID" screen reads as broken software. They are reached by drilling
 * in from a policy or claim. Parties used to be in that category too, until
 * `GET /parties` closed the gap: a party PENDING KYC with nothing yet
 * referencing it (a fresh registration) was otherwise invisible to staff, so
 * "KYC review" below is a real list, not a lookup box. Underwriting closed the
 * same gap later still (`GET /underwriting/cases`) -- its own case id STILL
 * never round-trips back out through any other endpoint's response
 * (`PolicyResponseDto` omits `underwritingCaseId` despite the internal
 * same-named `PolicyView` record carrying it), so the queue below is the only
 * way back to a case you didn't bookmark, not a supplementary one.
 *
 * Agents is the one remaining exception, and deliberately not a "paste an ID"
 * screen: `POST /agents` is the only entry point onto that domain that exists
 * server-side (no list/search endpoint), so its nav item goes straight to the
 * one real, working action -- onboarding an agent -- rather than a lookup box.
 * An agent IS reachable another way, though: `PolicyView.agentOfRecordId` DOES
 * round-trip through `GET /policies` for real, so an agent is also reachable
 * by drilling in from a policy that names one -- Agents' nav entry is just the
 * first way in, not the only one.
 *
 * The unimplemented entries are listed rather than deleted so the intended shape is
 * visible, but they are filtered out below: shipping a link to an empty page is the
 * same dead end by another route.
 */
```

- [ ] **Step 5: Fix the now-stale comment in `TreatiesPage.tsx`**

In `frontend/src/features/reinsurance/TreatiesPage.tsx`, the module doc comment currently reads: *"Unlike underwriting cases and agents, a treaty IS listable -- so this follows Policies/Claims/Products' full drawer-previews-page-acts shape rather than the create-only exception."* This is now false for underwriting cases. Replace with:

```tsx
/**
 * `GET /treaties` is a bare unpaged array (like products' catalog), so this
 * gets the no-pager table variant. Unlike agents (still create-only, see
 * AppShell.tsx's STAFF_NAV comment), a treaty IS listable -- so this follows
 * Policies/Claims/Products/Underwriting's full drawer-previews-page-acts
 * shape rather than the create-only exception.
 */
```

- [ ] **Step 6: Typecheck, lint, build**

Run: `cd frontend && npx tsc --noEmit && npx eslint . && npm run build`
Expected: all three clean.

- [ ] **Step 7: Manually verify in the browser**

Start the dev backend (if not already running) and the frontend dev server, log in as staff, open a case via `/staff/underwriting/new`, then click "Underwriting" in the nav and confirm the case appears in the queue and its drawer opens on click.

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/features/underwriting/UnderwritingQueuePage.tsx src/features/underwriting/UnderwritingCaseDrawer.tsx \
  src/App.tsx src/components/AppShell.tsx src/features/reinsurance/TreatiesPage.tsx
git commit -m "feat(frontend): underwriting case queue page, drawer, route, and nav"
```

---

### Task 4: e2e coverage and final verification

**Files:**
- Create: `frontend/e2e/staff-underwriting-queue.spec.ts`

**Interfaces:**
- Consumes: the real running stack (Keycloak, Postgres, backend, frontend dev server) — no new interfaces produced, this is the terminal task.

- [ ] **Step 1: Write the e2e spec**

Create `frontend/e2e/staff-underwriting-queue.spec.ts`, reusing the exact real case-opening flow already established in `staff-underwriting.spec.ts` (search "Amina" -> "Amina Owner" -> select "Demo Term Life (DEMO-TERM-01)" -> fill sum assured -> "Open case"):

```typescript
import { expect, test } from '@playwright/test';

/**
 * `GET /underwriting/cases` -- the queue that closes the gap where a created
 * case had nowhere to browse back to (staff-underwriting.spec.ts's own module
 * doc comment documents the OLD state this fixes: "no list/search endpoint").
 */

async function openRealCase(page: import('@playwright/test').Page): Promise<string> {
  await page.goto('/staff/underwriting/new');
  await page.getByRole('button', { name: 'Search for the applicant by name' }).click();
  await page.getByPlaceholder('Type a name to search').fill('Amina');
  await page.getByText('Amina Owner').click();
  await page.getByLabel('Product').selectOption({ label: 'Demo Term Life (DEMO-TERM-01)' });
  await expect(page.getByText('Resolving product version…')).not.toBeVisible();
  await page.getByLabel('Sum assured').fill('1500000.00');
  await page.getByRole('button', { name: 'Open case' }).click();
  await expect(page).toHaveURL(/\/staff\/underwriting\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  return page.url().split('/').pop() as string;
}

test.describe('staff underwriting queue', () => {
  test('a freshly opened case appears in the queue, previews in the drawer, and drills in to full detail', async ({
    page,
  }) => {
    const caseId = await openRealCase(page);

    await page.goto('/staff/underwriting');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await expect(page.getByText(caseId)).toBeVisible();

    await page.getByText(caseId).click();
    const drawer = page.getByRole('dialog', { name: 'Underwriting case preview' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('Open', { exact: true })).toBeVisible();

    await drawer.getByRole('link', { name: /full detail/i }).click();
    await expect(page).toHaveURL(new RegExp(`/staff/underwriting/${caseId}`));
    await expect(page.getByRole('heading', { name: 'Underwriting case' })).toBeVisible();
  });

  test('the status filter is shareable through the URL', async ({ page }) => {
    await openRealCase(page);

    await page.goto('/staff/underwriting');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await page.getByRole('button', { name: 'Open', exact: true }).click();
    await expect(page).toHaveURL(/status=OPEN/);
    // The case just opened above is genuinely OPEN, so filtering to it must not empty the table.
    await expect(page.getByText('No cases yet')).not.toBeVisible();
  });
});
```

- [ ] **Step 2: Restart the dev backend to reflect Task 1's backend changes**

```bash
cd backend
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Poll `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/products` until it returns `401` (confirms the backend is up) before running e2e.

- [ ] **Step 3: Run the new spec**

Run: `cd frontend && npx playwright test staff-underwriting-queue.spec.ts --project=staff`
Expected: both tests pass.

- [ ] **Step 4: Run the existing `staff-underwriting.spec.ts` to confirm no regression**

Run: `cd frontend && npx playwright test staff-underwriting.spec.ts --project=staff`
Expected: all existing tests still pass (the nav/route changes must not have broken the create flow).

- [ ] **Step 5: Full frontend verification**

Run: `cd frontend && npx tsc --noEmit && npx eslint . && npx vitest run && npm run build`
Expected: all clean.

- [ ] **Step 6: Full backend verification**

Run: `cd backend && ./mvnw -o test > /tmp/uw-final-backend.log 2>&1; echo "EXIT:$?"; grep -E "Tests run:|BUILD " /tmp/uw-final-backend.log`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Grep the log file directly, don't trust the piped exit code alone.

- [ ] **Step 7: Commit**

```bash
cd frontend
git add e2e/staff-underwriting-queue.spec.ts
git commit -m "test(frontend): e2e coverage for the underwriting case queue"
```

- [ ] **Step 8: Check for stray uncommitted changes**

Run: `git status --short` from the repo root. Expected: clean (or only pre-existing untracked tooling files unrelated to this plan — do not commit those).

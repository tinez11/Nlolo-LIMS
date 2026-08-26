# Underwriting Case Queue — Design

**Goal:** After opening an underwriting case, staff currently have nowhere to browse back to it — the "Underwriting" nav item goes straight to the create form, and no list/queue endpoint exists anywhere on the platform. This adds a real case queue: a paginated, status-filterable `GET /underwriting/cases` on the backend, and an `UnderwritingQueuePage` on the frontend, mirroring the pattern this session already built for Policies and Claims.

## Why this shape

- **No free-text search.** Unlike a policy (`POL-XXXXXXXX`) or a claim (filed against a policy number), an underwriting case has no human-facing identifier — only a raw UUID `caseId`. There is nothing meaningful to type into a search box, so this queue gets status filtering only, not a `q` param.
- **No new access-control surface.** `UnderwritingApiImpl.getCase` already lets any `REALM_AGENTS`/`REALM_STAFF` token fetch any case within its own tenant, with no per-case ownership check beyond `tenantId`. A list endpoint under the same authorization exposes nothing a caller couldn't already retrieve one-by-one by guessing or holding an id. No agent-of-record/book-of-business scoping exists on `UnderwritingCase` (unlike `Policy`), and building one is out of scope here.
- **Staff-only, for now.** The "Underwriting" nav item exists only in `STAFF_NAV` (`AppShell.tsx`) — the agents realm has no underwriting nav entry at all today, even though the backend technically permits agents to open/read cases. This spec adds the queue to the staff nav only; extending it to the agents realm is a separate, unasked-for scope decision.
- **Reuse `UnderwritingCaseView` as-is, not a new DTO.** The existing single-case response record already IS the wire DTO (no separate mapping layer), and its OpenAPI schema `@JsonIgnore`s `productVersionId`/`sumAssuredAmount`/`sumAssuredCurrency` to satisfy `additionalProperties`-style contract validation (`UnderwritingContractTest` enforces this today). Rather than inventing a second, wider DTO just to surface a "sum assured" column, the queue reuses the exact same view — matching precedent: neither `PoliciesPage` nor `ClaimsPage` shows a "created" column either (the sort order conveys recency; nothing prints the timestamp), and `UnderwritingCaseDetailPage` itself already shows only raw ids (`applicantPartyId`, `productId`), no name resolution, no sum-assured headline. This keeps the change additive and small: one new repository method pair, one new controller endpoint, one new response-envelope record, no entity/DTO changes.

## Backend

**`UnderwritingCaseRepository`** gains two derived-query methods, mirroring `PolicyController`'s simpler (non-`search`) branches — no JPQL `search()` method needed here at all, since there's no `q` or agent-set filter to combine:

```java
Page<UnderwritingCase> findByTenantId(UUID tenantId, Pageable pageable);
Page<UnderwritingCase> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
```

**`UnderwritingApi`/`UnderwritingApiImpl`** gain:

```java
Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, Pageable pageable);
```

Branches on `status != null`, calling the matching repository method, tenant-scoped via `TenantContext.get()` exactly like every other method in this class. Maps each `UnderwritingCase` through the existing private `toView` helper.

**`UnderwritingController`** gains:

```java
@GetMapping("/cases")
@PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
public ResponseEntity<UnderwritingCaseSearchResponse> listCases(
        @RequestParam(required = false) UnderwritingCaseStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
    Page<UnderwritingCaseView> result = underwritingApi.listCases(status,
        PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
    return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(result));
}
```

Same authorization as the existing `POST /cases`/`GET /cases/{caseId}` routes. Newest-first default sort, same as Policies/Claims.

**New `UnderwritingCaseSearchResponse`** (`underwriting/infrastructure`), mirroring `PolicySearchResponse` exactly:

```java
public record UnderwritingCaseSearchResponse(List<UnderwritingCaseView> items, PageMetaDto page) {
    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public static UnderwritingCaseSearchResponse from(Page<UnderwritingCaseView> springPage) {
        return new UnderwritingCaseSearchResponse(springPage.getContent(),
            new PageMetaDto(springPage.getNumber(), springPage.getSize(), (int) springPage.getTotalElements()));
    }
}
```

**OpenAPI (`openapi-underwriting.yaml`)** — new `GET /underwriting/cases` under the existing `/underwriting/cases` path item (alongside the existing `post`), mirroring `openapi-policy.yaml`'s `GET /policies` shape:

```yaml
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
```

## Frontend

**`frontend/src/api/underwriting.ts`** gains a `listCases(params)` function mirroring `searchPolicies`, returning `Page<UnderwritingCaseView>`. The module doc comment (which currently states flatly "there is no `GET /underwriting/cases` list ... endpoint anywhere on this platform") gets corrected in the same change.

**`frontend/src/store/underwritingStore.ts`** gains a `list: Resource<Page<UnderwritingCaseView>>` slot and `loadList`/status-filter plumbing, mirroring `policyStore`'s `list`/`loadList`.

**New `frontend/src/features/underwriting/UnderwritingQueuePage.tsx`**, mirroring `PoliciesPage.tsx`'s structure minus the search bar: `PageHeader` (with a "New case" action button linking to `new`), `StatCards` (total count for the current filter), status `FilterChip`s (All/Open/In Review/Decided via `StatusBadge kind="underwritingCase"` — this `StatusKind` already exists in `lib/status.ts`, no new work needed there), a `DataTable` with columns: Status, Applicant (raw `applicantPartyId`, linked to `/staff/parties/{id}` exactly as the detail page already does), Product (raw `productId`, unlinked, matching the detail page), Decision outcome (if decided). Row click navigates to `/staff/underwriting/{caseId}`. `Pager` for pagination. No drawer/preview — cases don't have a lightweight preview pattern like policies/claims do, and the detail page is already a single, fast navigation away.

**Routing (`App.tsx`)** — new index route:

```tsx
<Route path="underwriting" element={<UnderwritingQueuePage />} />
```

alongside the existing `underwriting/new` and `underwriting/:caseId`, exactly matching `treaties`/`treaties/new`/`treaties/:treatyId`'s three-route shape.

**Nav (`AppShell.tsx`)** — the "Underwriting" `STAFF_NAV` entry's `to` changes from `'underwriting/new'` to `'underwriting'`. The doc comment above `STAFF_NAV` explaining why Underwriting/Agents skip the list pattern gets corrected: Underwriting no longer belongs in that exception list (Agents still does — no `GET /agents` list endpoint exists, unrelated to this change).

## Testing

**Backend** — new tests in `UnderwritingContractTest` (or a focused new test class if that file is already large): `listCasesDefaultsToNewestCreatedFirst` (two cases, newest first — seeded with a second, unrelated case per this session's established false-positive-avoidance discipline), `listCasesFiltersByStatus`, `listCasesIsTenantScoped` (a case in a different tenant never appears). Reuses this session's `manualIssue`-style real-HTTP fixture chain already established in sibling test classes (open a case via the real `POST /cases` flow, not direct repository seeding).

**Frontend** — a new e2e spec `staff-underwriting-queue.spec.ts`: open a case via the real `/staff/underwriting/new` flow, land back on `/staff/underwriting`, confirm the case is visible (status `Open`), click it, confirm it navigates to the correct detail page. A second test confirms the status filter round-trips through the URL (matching `staff-claims.spec.ts`'s "a filter is shareable through the URL" pattern).

## Rollout

No feature flag, no data migration — purely additive (new endpoint, new route, changed nav target for one existing item). No backend restart risk beyond the usual dev-backend restart-after-backend-changes step this session has followed throughout.

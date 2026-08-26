# Staff list improvements — newest-first sort, search bar, popover date picker

**Status:** design approved, pending implementation plan
**Date:** 2026-08-26
**Depends on:** the party-picker work (`2026-08-25-party-picker-design.md`) — this reuses its `q`-search JPQL pattern for Policies/Claims and its Radix-Popover component shell for the date picker.

## 1. Why this exists

A staff-console review surfaced two real gaps on the Policies and Claims list pages, and the user separately asked for a better date-entry experience:

- **No reliable ordering.** `PolicyRepository.findByTenantId`/`search` and `ClaimRepository.findByTenantId`/`search` all take a `Pageable` with no `Sort` (confirmed by reading both files directly). Without one, Postgres makes no ordering guarantee — the most recently issued policy or registered claim does not reliably appear first, or anywhere predictable.
- **No way to find a specific record by typing.** `GET /policies` and `GET /claims` only filter by status and an exact party-id/UUID — there is no free-text search. A staff member who knows a policy number has no way to jump straight to it; they can only page through, in whatever order the database happens to return.
- **Every date field is a bare native `<input type="date">`.** 11 fields across 5 forms (`RegisterClaimPage` alone has 5: date of event, date of death, onset date, diagnosis date, maturity date; the rest are in `OnboardCustomerPage`, `OnboardAgentPage`, `CreateTreatyPage`, `PublishVersionForm`). No date-picker library is installed.

## 2. Newest-first default sort

One line per controller, no repository changes needed — `Sort` rides along on the same `Pageable` every existing query already takes, regardless of which derived-method/`search` branch runs:

- `PolicyController.searchPolicies` (`backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/PolicyController.java:103-104`): change
  `PageRequest.of(page, Math.min(pageSize, 100))` to
  `PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"))`.
- `ClaimController.listClaims` (`backend/src/main/java/tz/co/nlolo/lifeplatform/claims/infrastructure/ClaimController.java:129`): same change.

Both `Policy` and `Claim` already carry a mapped `createdAt: Instant` field (confirmed directly: `Policy.java:93`, `Claim.java:98`), so `Sort.by(..., "createdAt")` is a valid JPA property path, not a guess. No response-shape change, no new query param — this is purely "what order rows come back in by default."

## 3. Free-text search (`q`)

Same shape as the party picker's `q`, added as a fourth predicate to each module's existing `search` JPQL (both already null-safe multi-predicate queries — this literally extends the pattern already used for `agentOfRecordIds`/`policyNumbers`):

- **Policies**: `q` matches `policyNumber`, case-insensitive substring (`LOWER(p.policyNumber) LIKE LOWER(CONCAT('%', :q, '%'))`). Policy number is the one human-typable identifier staff actually have on hand (it's shown everywhere in the UI already), and this mirrors the exact null-safe-`LIKE` pattern `PartyRepository.search`'s `q` already established.
- **Claims**: `q` also matches `policyNumber` (claims have no short code of their own — `claimId` is a UUID, not something staff would type from memory) — "find the claims against this policy" is the realistic search here. `ClaimRepository.search` gains the same fifth predicate.
- Both `PolicyController.searchPolicies`/`ClaimController.listClaims` gain a `@RequestParam(required = false) String q`, threaded straight into the existing `search(...)` call alongside the params already there (`policyholderPartyId`/`claimantPartyId`, `status`, agent-scoping set). Combines with the existing status filter (both apply together — `q=POL-2026&status=ACTIVE` is one request), same as the party picker's `q`+`kycStatus` combination.
- OpenAPI: `q` added to `openapi-policy.yaml`'s and `openapi-claims.yaml`'s `GET /policies`/`GET /claims` parameter lists, same shape as the party-picker's `openapi-party.yaml` addition.

**Frontend**: `PoliciesPage`/`ClaimsPage` each gain a debounced (300ms) text input next to the existing status `FilterChip` row, writing `q` into the URL search params the same way `status`/`page` already round-trip (so a filtered+searched view stays shareable/back-button-correct, matching this file's own existing doc comment about why filter state lives in the URL). `api/policies.ts`/`api/claims.ts`'s search-params types and functions gain `q`, mirroring `api/party.ts`'s `PartySearchParams.q` addition exactly.

## 4. Popover-anchored date picker

**New dependency:** `react-day-picker` (v9). **New component:** `frontend/src/components/DatePicker.tsx`, matching `PartyPicker.tsx`'s established shell: a Radix `Popover.Root`/`Popover.Trigger`/`Popover.Content` wrapping `react-day-picker`'s `<DayPicker>` in place of the search-and-list body.

```tsx
interface DatePickerProps {
  value: string | null;        // ISO yyyy-MM-dd, or null -- same shape the native <input type="date"> already produces (ISO_DATE_PATTERN)
  onChange: (isoDate: string | null) => void;
  placeholder?: string;
  /** Disables dates outside this range, e.g. "no future date of event". */
  disabled?: { before?: Date; after?: Date };
}
```

- The trigger button shows the formatted date (via the existing `lib/dates.ts#formatDate`) or the placeholder, same visual pattern as `PartyPicker`'s trigger (`value && label ? label : placeholder`).
- Selecting a day calls `onChange` with an ISO string and closes the popover — react-day-picker's `Day` selection mode already returns a `Date`; convert via the existing date-formatting helpers rather than a new date library, keeping `ISO_DATE_PATTERN`/`lib/dates.ts` as the one source of date-formatting truth this codebase already has.
- A small clear ("×") affordance when `value` is set, matching the fix already shipped on `PartyPicker` for the same reason (an edit form seeding a pre-existing date needs a way to unset it).
- No network calls — this is a pure client-side calendar widget, unlike `PartyPicker`; only the popover shell/interaction pattern is shared, not the search/debounce logic.

**Rollout**: wired into all 11 fields across the 5 forms in one pass via `react-hook-form`'s `Controller` (identical integration shape to every `PartyPicker` integration point), replacing each `<input type="date"> {...register(...)}` block. Each form's Zod schema keeps its existing `ISO_DATE_PATTERN`-based validation unchanged — the picker can only ever produce a well-formed ISO date, same "the schema becomes defense in depth" reasoning the party-picker design used for its own field-validation loosening.

## 5. Testing

**Backend** (contract tests, one file per module): a `q`-only policy-number-substring match, `q` combined with `status`, `q` with zero matches, and a case-insensitivity check against a seeded mixed-case-adjacent policy number (mirroring `PartyContractTest`'s own `q` test trio). A newest-first ordering test: seed two policies/claims in sequence, assert the second-created one is `items[0]`.

**Frontend unit** (`DatePicker.test.tsx`, same vitest+testing-library setup as `PartyPicker.test.tsx`): opens on trigger click, selecting a day calls `onChange` with the correct ISO string and closes the popover, the clear button calls `onChange(null)`, a `disabled` range genuinely blocks selecting an out-of-range day.

**E2E** (real stack, no fabricated identities): one test proving a freshly-issued policy appears first in the list without any filter; one test proving `q=<policy number>` finds exactly that policy; one test per form (or a representative subset, decided in the plan) driving the new date picker through a real submission, confirming the ISO date reaches the backend correctly — the same "assert what actually reached the server" discipline the party picker's own final-review fix (I3) established.

## 6. Rollout note

This changes existing, e2e-tested list pages (`staff-policies.spec.ts`/equivalent, `staff-claims.spec.ts`) only additively (new search input, unchanged default-view assertions still hold since `q` is optional) — no existing call site needs migration, unlike the party picker's raw-UUID-field replacement. The date-picker rollout DOES require migrating existing e2e call sites that currently do `page.locator('input[type="date"]').fill(...)` — every such call site across the 5 forms' specs needs the same kind of interaction-shape update the party picker required (click a button, pick a day, instead of a bare `.fill()`), enumerated precisely in the implementation plan.

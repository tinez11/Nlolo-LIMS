# Party Picker — searchable party selection, replacing raw-UUID fields

**Status:** design approved, pending implementation plan
**Date:** 2026-08-25
**Depends on:** `GET /parties` (staff KYC review queue, shipped `e586eac`) — this design extends that endpoint rather than adding a new one.
**Explicitly out of scope:** agent search (`GET /agents` doesn't exist — a separate, later design), product/policy pickers (product already has a real `<select>` dropdown via `GET /products`; policy numbers are short human-legible codes, not opaque UUIDs).

## 1. Why this exists

Every form on this platform that references a party — the policyholder issuing a policy, the applicant opening an underwriting case, the claimant registering a claim, the party being onboarded as an agent, a beneficiary — asks staff to paste a raw UUID into a bare text field (`placeholder="uuid"`). Confirmed by grep: at least five real, distinct input fields across four modules do this today (`IssuePolicyPage`, `OpenUnderwritingCasePage`, `RegisterClaimPage`, `OnboardAgentPage`, `BeneficiariesPanel`). Every e2e test covering these forms fills them with a UUID constant copied from a prior fixture — there is no path, anywhere, for a staff member to find a party's id from its name inside the product itself.

The user's framing: staff should never need to type or memorize an ID unless truly unavoidable. This design covers the party-selection half of that; agent selection is a separate, later slice (see §7).

## 2. Backend: extend the existing `GET /parties` endpoint

No new endpoint. `GET /parties` (added for the KYC review queue) already does tenant-scoped, paginated party listing with an optional `kycStatus` filter and agents-realm `createdBy` force-scoping. This adds one new optional query parameter:

- **`q`** (string, optional) — free-text, case-insensitive substring match against `party.display_name` (`ILIKE '%q%'` — Postgres, tenant-scoped like every other predicate on this endpoint). Combinable with the existing `kycStatus` filter: `GET /parties?q=amina&kycStatus=VERIFIED` is a single request, not two round trips.
- When `q` is present, the caller (the picker component) requests a small `pageSize` (10) — a dropdown shows "enough to recognize the right match, keep typing to narrow further," not a full paginated browse. No new response shape: `q` still returns the same `PageResponse<PartyView>` envelope, just typically fewer rows.

**No `PartyView` schema change.** Per the approved design, results show name + party type + KYC status — all three fields `PartyView` already carries (`partyId`, `partyType`, `kycStatus`, `displayName`). Phone/email disambiguation was considered and explicitly deferred (a real schema addition, not justified for this slice).

`PartyRepository` needs one new query method combining the existing two dimensions with a new third one (`q`), following the same null-safe JPQL pattern `PolicyRepository.search`/`ClaimRepository.search` already established for their own 3-way filters (see `2986622`, `317cad6`):

```java
@Query("SELECT p FROM Party p WHERE p.tenantId = :tenantId "
    + "AND (:kycStatus IS NULL OR p.kycStatus = :kycStatus) "
    + "AND (:createdBy IS NULL OR p.createdBy = :createdBy) "
    + "AND (:q IS NULL OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', :q, '%')))")
Page<Party> search(@Param("tenantId") UUID tenantId, @Param("kycStatus") KycStatus kycStatus,
                    @Param("createdBy") String createdBy, @Param("q") String q, Pageable pageable);
```

`PartyApiImpl.searchParties` and `PartyController.searchParties` both gain the `q` param, threaded through the same branching/force-scoping logic already in place (agents-realm callers stay force-scoped to their own `createdBy` regardless of `q`).

## 3. Frontend: a reusable `PartyPicker` component

New dependency: `cmdk` + `@radix-ui/react-popover` — the standard accessible combobox stack, consistent with the Radix primitives already in this codebase (dialog, tabs, tooltip). Keyboard navigation and ARIA combobox semantics come from the library rather than being hand-rolled, which matters for a component reused in five places.

**New file:** `frontend/src/components/PartyPicker.tsx`

```tsx
interface PartyPickerProps {
  value: string | null;           // the selected partyId, or null
  onChange: (partyId: string | null, party: PartyView | null) => void;
  kycStatus?: KycStatus;          // pre-filter, e.g. 'VERIFIED' for agent onboarding
  placeholder?: string;
}
```

Wired into forms via `react-hook-form`'s `Controller`, matching how every other custom-shaped field already integrates with this codebase's forms (e.g. `BeneficiariesPanel`'s `useFieldArray` rows).

**Behavior:**
- Debounced search (300ms), fires once the query is 2+ characters.
- If the typed text matches the UUID pattern (`UUID_PATTERN` from `lib/patterns.ts`, already used across this codebase), search fires immediately on it without waiting for the debounce or the 2-character minimum — a staff member who pastes a known id out of habit still gets a match, with no separate raw-ID field needed.
- Each result row: `displayName`, a small `partyType` tag, and a `StatusBadge` (`kind="kyc"`) for `kycStatus` — reused directly, no new badge variant.
- Selecting a row calls `onChange(partyId, party)` and closes the popover, showing the selected name in the field.
- Empty/short query: no fetch, a quiet "Type a name to search" placeholder.
- Zero results for a real query: "No matches for '{q}'".
- In-flight: a small spinner inside the popover — never blocks the input itself.
- A failed search request shows an inline "Couldn't search — try again" row in the popover; typing further or reopening retries naturally (no special retry button needed, since every keystroke re-fires the search).

`frontend/src/api/party.ts`'s `searchParties`/`PartySearchParams` gains the `q` field, threaded straight through as a query param (mirrors the `kycStatus` param already there).

## 4. Integration points (this slice)

| Field | File | Pre-filter |
|---|---|---|
| Policyholder party id | `IssuePolicyPage.tsx` | none |
| Applicant party id | `OpenUnderwritingCasePage.tsx` | none |
| Claimant party id | `RegisterClaimPage.tsx` | none |
| Party id (being onboarded as an agent) | `OnboardAgentPage.tsx` | `kycStatus="VERIFIED"` — `DistributionApiImpl.onboardAgent` already 422s for anything else; the picker just stops staff from picking a party that's guaranteed to fail |
| Beneficiary party id (per row) | `BeneficiariesPanel.tsx` | none |

**`BeneficiariesPanel` needs care, not a blind swap.** Each row already toggles between "an existing party" and "a freeform designee" (`BeneficiaryInput.freeformDesignee`, for a beneficiary who isn't a registered party at all — a minor, an organization). `PartyPicker` replaces the raw-UUID input only in the "existing party" branch of that toggle; the freeform-name path is untouched.

Each form's Zod schema loosens slightly: the field's validation becomes "a well-formed UUID is present" (already true), since the picker can only ever produce one. The `rejects a malformed id, before reaching the network` style tests some of these forms carry today become tests of "the field is required" rather than "a hand-typed malformed string is rejected" — the malformed-input path effectively can't happen through the UI anymore, but the schema-level guard stays as defense in depth (a directly-dispatched form submission, or a future regression in `PartyPicker` itself, still can't reach the network with a bad value).

## 5. Testing

**Backend** (`PartyContractTest`): `q` alone, `q` combined with `kycStatus`, `q` with zero matches, and a real case-insensitivity check against a seeded mixed-case name (`ILIKE` is inherently case-insensitive in Postgres, but this is the platform's own discipline of proving it against a real row rather than trusting the SQL).

**Frontend unit** (`PartyPicker.test.tsx`, vitest + testing-library, same setup `StatusBadge.test.tsx` already uses): debounce timing, immediate UUID-pattern search, empty/loading/no-results states, selection calling `onChange` with both `partyId` and the full `PartyView`. Plus a unit test for `searchParties`'s new `q` param actually reaching the request.

**E2E** (real stack, no fabricated identities — this platform's standing rule):
1. Type a few characters of a real seeded party's name into `IssuePolicyPage`'s picker, select it, submit, confirm the issued policy's `policyholderPartyId` matches the selected party — not just that the UI *looked* right.
2. `OnboardAgentPage`'s picker: confirm a real PENDING party does **not** appear when searching by its name (the `kycStatus="VERIFIED"` pre-filter genuinely excludes it at the network level, not just a client-side assumption) — a real, seeded VERIFIED party does.

## 6. Rollout note

This changes five existing, e2e-tested forms. Each form's existing e2e coverage (`staff-issue-policy.spec.ts`, `staff-underwriting.spec.ts`, `staff-claims.spec.ts` / adjudication, `staff-agent-lifecycle.spec.ts` / distribution, `staff-beneficiaries.spec.ts`) fills the raw-UUID field directly today (`page.getByLabel('Policyholder party id').fill(REAL_PARTY_ID)`) — those call sites all need updating to drive the picker (type + select) instead of a bare `.fill()`, or they will fail against the new UI, not because behavior regressed but because the interaction shape changed. This is real, expected churn across roughly a dozen existing e2e call sites, not a sign of an unrelated break.

## 7. Deferred: agent search

`OnboardAgentPage`'s "Hierarchy parent id" and `IssuePolicyPage`'s "Agent of record id" stay raw-typed after this slice. Agent search needs a `GET /agents` endpoint that does not exist today (confirmed repeatedly this session — the only ways to reach an agent record are by an id already in hand, or drilling in from a policy's `agentOfRecordId`), and that endpoint needs its own realm-scoping decision: an agents-realm caller should almost certainly not be able to search *every* agent in the tenant, only itself and its downline (the same `DistributionApi.resolveAgentTeam` scoping `PolicyController`/`ClaimController` already use for "browse my book of business"). That is a separate design, not a follow-on task bolted onto this one.

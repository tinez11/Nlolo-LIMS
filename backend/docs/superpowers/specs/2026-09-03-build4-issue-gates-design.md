# Build 4 — issue gates

Status: **built 2026-09-03** (gates half; proposal identity deferred, see §6).
Fourth of six builds from the underwriting requirements review. Consumes Builds
1–3. Delivers the decision recorded in `frontend/PLAN.md` §14.3.

---

## 1. What this is

`src/gates/issueGates.ts` plus its `<GatePanel>` on `IssuePolicyPage`: the
preconditions a policy issue must satisfy, declared before the submit button
rather than discovered after a round trip.

Promised by name in `PLAN.md` §13 C2 (`claimGates` and `issueGates` first) and
never written. Committed to again in §14.3, where the decision was that **a
mutating surface declares its preconditions before it renders a submit button**,
and that spreading `GatePanel` beyond its single screen is what closes the
"authored in its judgment, generic in its form" verdict without touching the
CRM shell.

## 2. The gates

| Gate | Severity | Source |
| --- | --- | --- |
| Applicant within the product's entry age | **hard** | Build 3 bounds + party DOB |
| Term is one this product offers | **hard** | Build 3 bounds + Build 2 term |
| Sum assured within the product's range | soft | Build 3 bounds |
| Policyholder's identity is verified | soft | `PartyDetailView.kycStatus` |
| Agent of record holds an active licence | **hard** | `AgentView.licenseStatus` |
| That licence is valid when cover starts | **hard** | `AgentView.licenseExpiryDate` |

Severity follows the client decision recorded in Build 3 §2. The short form: **a
hard block you cannot override is a revenue risk; a soft flag with no record is a
compliance risk.**

### Two severity calls that are not obvious

**KYC is soft.** No `@PreAuthorize` and no domain rule refuses issuance to a
PENDING party. A hard gate would therefore be the UI inventing a refusal the
platform does not make — the opposite of the "render only what the platform can
prove" doctrine. Flagging is honest; blocking would not be.

**No agent produces no gate at all.** A direct sale names no agent of record.
Emitting a failed gate would put a red panel on every direct policy, which is
how a gate set teaches people to ignore it.

## 3. Two things computed at commencement, not today

Both are easy to get wrong and both have tests.

**Entry age** is the age at which cover *starts*. A proposal commencing next
quarter can cross a birthday before the risk begins, and the bound applies then,
not at data entry.

**Licence expiry** is checked against the commencement date for the same reason:
a licence valid today but expired by the day cover starts has not licensed that
sale.

`ageOn` is calendar arithmetic, mirroring `Period.between(dob, asOf).getYears()`.
Deliberately not `(now - dob) / 365.25`, which disagrees with the backend on leap
years and on the applicant's own birthday. There is a 29-February test.

## 4. What makes the soft gates real

A warning nobody records is one staff learn to click past, which launders the
decision rather than capturing it. So a soft breach changes the
`reasonForManualIssue` field: its placeholder becomes "Say why the flagged check
above is acceptable", and a line beneath states how many checks are flagged and
that this is where the decision is recorded.

`reasonForManualIssue` already exists, is already required, and already feeds
audit. No new override mechanism was needed, so the deferred underwriting-override
work (staff-portal audit gap #7) stays deferred.

The submit button disables **only** on a hard failure. A soft breach never
disables: above retention is cedeable business, and refusing it in the console
would make the UI the reason the insurer declined a large case.

## 5. A Build 3 gap this build had to fix first

`ProductSnapshotView` did not expose the eligibility bounds. **Build 3 made them
writable and never made them readable**, so nothing could read a bound it had
just written.

Build 3's round-trip test did not catch it because it read the bounds back
through `ProductVersionRepository` rather than through the API — **it
round-tripped through the wrong seam**. The API path was never exercised.

Both construction sites now carry them. That matters: the second,
`getSnapshotByVersionId`, is what `PolicyController.manualIssue` resolves, so
patching only `getActiveSnapshot` would have left the issue path as the one place
unable to see the bounds its own gates check.

## 6. Deferred to a follow-up: proposal identity

The `underwriting_case` half of Build 4 — a sequenced human `proposal_number`,
`branch`, `source_of_business`, `proposed_commencement_date` and
`life_assured_party_id` — is **not** in this build.

It is a clean split: the gates consume product, party and agent records and need
nothing from the underwriting case, while proposal identity is a schema change to
a different aggregate with its own screens. Shipping them together would have put
two unrelated migrations behind one verification cycle.

`life_assured_party_id` remains the most consequential item outstanding: with a
single `applicant_party_id`, a proposal where the policyholder insures somebody
else cannot be expressed at all.

## 7. Also still deferred

`claimGates` judges "had risk commenced?" against `issueDate` and should now use
`commencementDate` (Build 2). Left alone deliberately: it changes an existing
gate's behaviour on a screen this build does not touch, and it deserves its own
verification rather than riding along.

## 8. Notes from building it

### 8.1 A bad grep hid a compile error from me

Checking for other `new ProductSnapshotView(...)` sites, I ran
`grep -v "ProductApiImpl"` — excluding the single file most likely to hold a
second one. It held exactly that, at line 454.

Maven's incremental compilation then skipped the file, `./mvnw -o compile`
reported success, and the IDE's `Unresolved compilation problem` classes were
reporting a **real** error that I twice dismissed as editor staleness.

**Never exclude a file from a find-all-call-sites grep.** The file being edited
is the likeliest place for another one.

### 8.2 Incremental compilation makes a green scoped run meaningless

Separately and genuinely: Build 2 widened `ManualIssueRequestDto`, which broke
`PolicyNegativeAmountDefenceTest` — a file that did not itself change, so Maven
never recompiled it, and which lists no migrations, so it appeared in no
affected-class list and was never selected to run. The break was committed in
Build 2, inherited by Build 3, and surfaced only under `clean`.

**After any signature change: `./mvnw -o clean test-compile`, and one
`./mvnw -o clean test` as a single invocation before committing.** Separate
`clean`, `compile` and `test` invocations leave windows for the IDE's Java
language server — which compiles into the same `target/` — to overwrite Maven's
output with its own stale view.

The full suite after both fixes: **844 tests, 0 failures, 0 errors.**

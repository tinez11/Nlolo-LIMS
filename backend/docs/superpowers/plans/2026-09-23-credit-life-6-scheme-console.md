# Credit Life Plan 6 — the scheme console page

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give staff one screen that runs a credit-life scheme — upload a lender's file, see what was rejected and why, accept or withdraw it, and do the same for the monthly exits.

**Architecture:** Backend first, because the surface this page needs does not exist: enrolment submissions cannot be listed, and `ExitApi` has no controller at all. Then a NEW console page at `credit-life-schemes/:policyNumber`, not an extension of `GroupSchemePage`, which renders grades and salary multiples this product has none of.

**Tech Stack:** Spring Boot + Spring Security (`@PreAuthorize`), React + TypeScript + Vite, TanStack Query, Zod form modules, Vitest for units, Playwright for e2e.

## Global Constraints

- Spec item 13, verbatim: *"The credit-life scheme page — a new page sharing the shell, not an extension of `GroupSchemePage`, which renders grades and benefit bases this product does not use."*
- **Never run Prettier.** It is not a dependency and there is no config; `npx prettier --write` once reformatted 25 files and forced a full revert. Match surrounding style by hand.
- **Never repaint `index.css` or the `AppShell` sidebar.** The console migration was started and explicitly called off; the Keycloak "Split leaf" login theme is Nlolo-green *by design*.
- The lint bans a synchronous `setState` in a `useEffect` body — derive rendered state instead of storing it.
- `openapi-typescript` makes any property with a `default:` **required** in generated request types. Do not add `default:` to a request schema unless callers really must send it.
- Playwright specs couple to **accessible names**. A component rename has broken 11 of 24 spec files with all unit tests green — never argue e2e out of scope for UI work.
- Money is a string over the wire and is rendered through `formatMoney`; never `toFixed` a float.
- Full backend suite at merge gates only; targeted runs during development.

---

## What does not exist yet, verified 2026-09-23

This is the part that changes the plan's shape, and none of it was obvious from the spec.

1. **Enrolment submissions cannot be listed.** `EnrolmentApi` has `submit`, `accept`, `withdraw`, `getSubmission(id)`, `listRows(id)`, `renderReport(id)` — and no way to ask "what submissions does this scheme have?" A page that opens on a scheme has nothing to render without it.
2. **`ExitApi` has NO controller.** `submit`/`accept`/`withdraw`/`getSubmission`/`listRows`/`renderReport` exist on the interface and in `ExitApiImpl`, and the only files in the whole backend that reference `ExitApi` are those two. **The entire monthly exits file that plan 3 built is unreachable over HTTP.** Nothing in the console — or anywhere else — can use it.
3. The frontend has no credit-life concept at all. `CREDIT_LIFE` appears in exactly two non-generated places: the product-creation dropdown and generated OpenAPI types.
4. `GroupSchemePage.tsx` is 659 lines and branches on `benefitBasis === 'GRADED'`, renders a grade table, and formats `salaryMultiple`. A credit-life scheme is `AMORTISING_LOAN` with no grades and no salary, so those branches are dead weight on it — which is why the spec asks for a separate page rather than another branch.

**Both view shapes are already identical in structure**, which the page should exploit rather than fight:

```java
EnrolmentSubmissionView(UUID submissionId, String policyNumber, SubmissionStatus status,
                        String fileName, int rowCount, int enrolledCount, int rejectedCount,
                        String submittedBy, Instant submittedAt, String acceptedBy, Instant acceptedAt)

ExitSubmissionView(UUID submissionId, String policyNumber, SubmissionStatus status,
                   String fileName, int rowCount, int exitedCount, int rejectedCount,
                   String submittedBy, Instant submittedAt, String acceptedBy, Instant acceptedAt)
```

They differ in exactly one field name: `enrolledCount` vs `exitedCount`.

---

## File Structure

| File | Responsibility |
|---|---|
| `policy/api/EnrolmentApi.java` (modify) | `listSubmissions(String policyNumber)` |
| `policy/api/ExitApi.java` (modify) | `listSubmissions(String policyNumber)` |
| `policy/infrastructure/EnrolmentController.java` (modify) | the list endpoint |
| `policy/infrastructure/ExitController.java` (create) | the whole exits REST surface |
| `api/openapi/openapi-policy.yaml` (modify) | both, documented |
| `frontend/src/api/creditLife.ts` (create) | the client for both submission kinds |
| `frontend/src/features/policies/CreditLifeSchemePage.tsx` (create) | the page |
| `frontend/src/features/policies/SubmissionsPanel.tsx` (create) | one panel, both kinds — they differ in one field |
| `frontend/src/features/policies/enrolmentUploadForm.ts` (create) | the Zod module + its unit test |
| `frontend/src/screens.tsx` (modify) | the route |
| `frontend/e2e/staff-credit-life-scheme.spec.ts` (create) | the e2e |

---

## Task 1: A scheme's submissions can be listed

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/EnrolmentApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/EnrolmentApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentController.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/EnrolmentSubmissionRepository.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentIntegrationTest.java` (existing — add to it)

**Interfaces:**
- Produces, for Task 3 and the frontend: `GET /credit-life-schemes/{policyNumber}/enrolments` returning `List<EnrolmentSubmissionView>`, newest first.

- [ ] **Step 1: Write the failing test**

Add to the existing enrolment integration test:

```java
    @Test
    void submissionsAreListedNewestFirstAndScopedToTheirOwnScheme() {
        // A page that opens on a scheme has nothing to render without this: every other
        // enrolment operation needs a submissionId the caller is assumed to already hold.
        //
        // NOTE the signature: submit(policyNumber, stream, fileName, submittedBy). There is NO
        // contentType parameter -- the controller normalises and refuses that before it ever
        // reaches the API.
        String other = issueScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN);

        UUID first = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "august.csv", "staff.one").submissionId();
        enrolmentApi.accept(first, "staff.two");   // a scheme allows only ONE submission in flight
        UUID second = enrolmentApi.submit(creditLifeScheme, csv(ONE_GOOD_ROW),
            "september.csv", "staff.one").submissionId();
        enrolmentApi.submit(other, csv(ONE_GOOD_ROW), "other-bank.csv", "staff.one");

        List<EnrolmentSubmissionView> listed = enrolmentApi.listSubmissions(creditLifeScheme);

        assertThat(listed).extracting(EnrolmentSubmissionView::submissionId)
            .as("newest first -- a lender's latest file is what staff came to look at")
            .containsExactly(second, first);
        assertThat(listed).extracting(EnrolmentSubmissionView::fileName)
            .doesNotContain("other-bank.csv");
    }

    @Test
    void aSchemeWithNoSubmissionsListsEmptyRatherThanThrowing() {
        // The normal state of a scheme on its first day. A 404 here would make the page render
        // an error for a situation that is not one.
        assertThat(enrolmentApi.listSubmissions(
            issueScheme(ProductCategory.CREDIT_LIFE, BenefitBasis.AMORTISING_LOAN))).isEmpty();
    }
```

**The helper names above are this class's real ones, verified 2026-09-23**: `csv(String)`,
`issueScheme(ProductCategory, BenefitBasis)`, the `ONE_GOOD_ROW` constant, and a
`creditLifeScheme` field the class already sets up. Do not introduce a second set.

**The accept() in the middle is load-bearing, not padding.** A scheme allows only ONE submission
in flight, so a second `submit` on the same scheme is refused until the first is resolved. A
first draft of this test that simply submitted twice would fail on the second call and read as a
bug in `listSubmissions`.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./mvnw -q test -Dtest=EnrolmentApiIntegrationTest
```
Expected: compilation failure — `listSubmissions` does not exist.

- [ ] **Step 3: Add the repository finder**

In `EnrolmentSubmissionRepository`:

```java
    /** Newest first: a lender's latest file is what staff opened the page to look at. */
    List<EnrolmentSubmission> findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(
        UUID tenantId, String policyNumber);
```

- [ ] **Step 4: Add the API method**

In `EnrolmentApi`:

```java
    /**
     * Every submission this scheme has had, newest first.
     *
     * <p>Exists because every other operation on this interface takes a {@code submissionId} the
     * caller is assumed to already hold, which is true of the upload flow and false of anybody
     * arriving at a scheme cold. Without it the console page has nothing to render.
     *
     * <p>Empty, never an exception, for a scheme that has had none — that is the normal state of
     * a scheme on its first day, not an error.
     */
    List<EnrolmentSubmissionView> listSubmissions(String policyNumber);
```

In `EnrolmentApiImpl`, mirroring however that class already maps a submission to its view:

```java
    @Override
    @Transactional(readOnly = true)
    public List<EnrolmentSubmissionView> listSubmissions(String policyNumber) {
        return enrolmentSubmissionRepository
            .findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(TenantContext.get(), policyNumber)
            .stream().map(this::toView).toList();
    }
```

> If the existing view mapping is a static helper or inline, follow it exactly rather than adding
> a second way of building the same record.

- [ ] **Step 5: Add the endpoint**

In `EnrolmentController`:

```java
    /** The scheme's submission history. REALM_STAFF, matching every other operation on this
     * controller: an enrolment file is staff work, and the lender-facing view is the portal's
     * problem, not this endpoint's. */
    @GetMapping("/credit-life-schemes/{policyNumber}/enrolments")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<EnrolmentSubmissionView> listSubmissions(@PathVariable String policyNumber) {
        return enrolmentApi.listSubmissions(policyNumber);
    }
```

- [ ] **Step 6: Run the test**

```bash
cd backend && ./mvnw -q test -Dtest=EnrolmentApiIntegrationTest
```
Expected: the two new tests pass alongside the existing ones.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/EnrolmentApiIntegrationTest.java
git commit -m "feat(policy): a scheme's enrolment submissions can be listed"
```

---

## Task 2: The exits file gets a REST surface at all

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/ExitApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/ExitApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/ExitSubmissionRepository.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/infrastructure/ExitController.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/ExitControllerContractTest.java`

**Interfaces:**
- Consumes from Task 1: nothing — these are independent.
- Produces: `POST /credit-life-schemes/{policyNumber}/exits` (multipart), `GET .../exits`, `GET .../exits/{submissionId}`, `GET .../exits/{submissionId}/rows`, `GET .../exits/{submissionId}/report`, `POST .../exits/{submissionId}/acceptance`, `POST .../exits/{submissionId}/withdrawal`.

**Why this task exists at all:** plan 3 built the monthly exits file end to end — parser, submission state machine, pro-rata refund, commission clawback — and never exposed it. `ExitApi` and `ExitApiImpl` are the only two files in the backend that name it. Until this task, **no HTTP client can run an exits file**, so the console page could show enrolments and silently offer nothing for the other half of the lender's month.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.policy;

/**
 * The exits file, over HTTP. Plan 3 built the whole feature -- parser, state machine, refund,
 * clawback -- and shipped it with no controller, so every one of these calls 404s today.
 *
 * <p>Mirrors the shape of the enrolment controller's own contract coverage: the role gate, the
 * propose-then-accept pair, and the refusal that matters (a second file in flight).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ExitControllerContractTest {

    @Test
    void anExitsFileIsUploadedListedAndAccepted() throws Exception {
        String scheme = issueCreditLifeSchemeWithMembers();

        MvcResult upload = mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(new MockMultipartFile("file", "exits-september.csv", "text/csv", EXITS_CSV))
                .with(staff()))
            .andExpect(status().isCreated())
            .andReturn();
        UUID submissionId = UUID.fromString(
            objectMapper.readTree(upload.getResponse().getContentAsString())
                .path("submissionId").asText());

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits").with(staff()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].submissionId").value(submissionId.toString()))
            .andExpect(jsonPath("$[0].status").value("PROPOSED"));

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/rows")
                .with(staff()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        // A DIFFERENT staff user accepts. The whole point of propose-then-accept is that one
        // person cannot do both, and a contract test that used the same token would prove the
        // endpoint exists while proving nothing about the control.
        mockMvc.perform(post("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/acceptance")
                .with(staffNamed("staff-2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void theSameStaffUserCannotAcceptTheirOwnExitsFile() {
        // Separation of duties, enforced by ExitApiImpl and asserted HERE because a controller
        // that lost the actor -- passing a literal, or the wrong JWT claim -- would silently
        // turn two people into one.
        String scheme = issueCreditLifeSchemeWithMembers();
        UUID submissionId = uploadExitsAs("staff-1", scheme);

        mockMvc.perform(post("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/acceptance")
                .with(staffNamed("staff-1")))
            .andExpect(status().isConflict());
    }

    @Test
    void anExitsUploadRequiresStaffAndRejectsACustomerToken() throws Exception {
        String scheme = issueCreditLifeSchemeWithMembers();
        mockMvc.perform(multipart("/credit-life-schemes/" + scheme + "/exits")
                .file(new MockMultipartFile("file", "exits.csv", "text/csv", EXITS_CSV))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void theReportIsServedAsCsvAndNamesTheFileItReportsOn() throws Exception {
        // The report is the deliverable, not a courtesy: it is the only place a lender learns
        // which exits were refused and why.
        String scheme = issueCreditLifeSchemeWithMembers();
        UUID submissionId = uploadExitsAs("staff-1", scheme);

        mockMvc.perform(get("/credit-life-schemes/" + scheme + "/exits/" + submissionId + "/report")
                .with(staff()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")));
    }
}
```

> Copy `EnrolmentController`'s own contract test for the harness: container, migration list, the
> `staff()` / `staffNamed(...)` JWT helpers and the scheme fixture. Do not invent a second harness
> shape — and note the migration list must include everything a credit-life scheme needs
> (`product/V14`, `product/V15`, `policy/V13`–`V22`, `claims/V5`–`V6`).

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./mvnw -q test -Dtest=ExitControllerContractTest
```
Expected: every case 404s — there is no controller.

- [ ] **Step 3: Add `listSubmissions` to `ExitApi`**

```java
    /** Every exits file this scheme has had, newest first. Same reason as the enrolment
     * equivalent: every other operation here takes a submissionId the caller is assumed to hold. */
    List<ExitSubmissionView> listSubmissions(String policyNumber);
```

and in `ExitSubmissionRepository`:

```java
    List<ExitSubmission> findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(
        UUID tenantId, String policyNumber);
```

and in `ExitApiImpl`, following that class's existing view mapping:

```java
    @Override
    @Transactional(readOnly = true)
    public List<ExitSubmissionView> listSubmissions(String policyNumber) {
        return exitSubmissionRepository
            .findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(TenantContext.get(), policyNumber)
            .stream().map(this::toView).toList();
    }
```

- [ ] **Step 4: Write `ExitController`**

Copy `EnrolmentController` and change the nouns. Its class javadoc should say why it exists:

```java
/**
 * The exits file over HTTP.
 *
 * <p>Plan 3 built this feature completely — parser, submission state machine, pro-rata refund,
 * commission clawback — and shipped it with no controller. {@code ExitApi} and
 * {@code ExitApiImpl} were the only two files in the backend that named it, so the monthly file
 * a lender sends to take repaid loans off cover could not be run by any HTTP client at all.
 *
 * <p>Deliberately a mirror of {@link EnrolmentController} rather than a merged "submissions"
 * controller. The two flows share a shape but not a meaning: one puts people on cover and the
 * other takes them off, they have different row validation and different money consequences, and
 * a single controller parameterised by kind would make the next difference between them into a
 * branch rather than a separate file.
 */
```

The endpoints, with `REALM_STAFF` on every one, matching the enrolment controller exactly:

| Method | Path |
|---|---|
| POST (multipart) | `/credit-life-schemes/{policyNumber}/exits` → 201 |
| GET | `/credit-life-schemes/{policyNumber}/exits` |
| GET | `/credit-life-schemes/{policyNumber}/exits/{submissionId}` |
| GET | `/credit-life-schemes/{policyNumber}/exits/{submissionId}/rows` |
| GET (text/csv) | `/credit-life-schemes/{policyNumber}/exits/{submissionId}/report` |
| POST | `/credit-life-schemes/{policyNumber}/exits/{submissionId}/acceptance` |
| POST | `/credit-life-schemes/{policyNumber}/exits/{submissionId}/withdrawal` |

Reuse `AllowedDocumentContentTypes.normalizeOrThrow` and the same CSV-only refusal the enrolment
upload makes — an exits file is a CSV for the same reason an enrolment file is.

- [ ] **Step 5: Run the test**

```bash
cd backend && ./mvnw -q test -Dtest=ExitControllerContractTest
```
Expected: 4 tests pass.

- [ ] **Step 6: Document both in the OpenAPI spec**

Add all seven exit operations and Task 1's enrolment list to `api/openapi/openapi-policy.yaml`,
following the shapes already there for the enrolment endpoints.

**Then verify every cross-spec `$ref` resolves, before committing:**

```bash
cd backend && grep -o "openapi-common.yaml#/components/[a-z]*/[A-Za-z]*" api/openapi/openapi-policy.yaml | sort -u
```

Confirm each name exists in `openapi-common.yaml`. It defines `ProblemDetails` (**plural**) and
`Money` as schemas, and `BadRequest`/`Unauthorized`/`Forbidden`/`NotFound`/`Conflict`/
`UnprocessableEntity` as responses. A 409 reuses the shared `Conflict` response. **An unresolvable
`$ref` fails the whole spec load, not the operation carrying it** — that exact mistake erred all
twelve cases of an unrelated contract test earlier in this build, with no message naming the
endpoint at fault.

- [ ] **Step 7: Run both controller contract tests**

```bash
cd backend && ./mvnw -q test -Dtest='ExitControllerContractTest,PolicyContractTest'
```
Expected: green. `PolicyContractTest` is included because it validates responses against the spec
file Step 6 just edited.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/ \
        backend/api/openapi/openapi-policy.yaml \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/ExitControllerContractTest.java
git commit -m "feat(policy): the exits file is reachable over HTTP at last"
```

---

## Task 3: The page

**Files:**
- Create: `frontend/src/api/creditLife.ts`
- Create: `frontend/src/features/policies/CreditLifeSchemePage.tsx`
- Create: `frontend/src/features/policies/SubmissionsPanel.tsx`
- Create: `frontend/src/features/policies/enrolmentUploadForm.ts` + `.test.ts`
- Modify: `frontend/src/screens.tsx`
- Modify: `frontend/src/api/types.ts`
- Test: `frontend/e2e/staff-credit-life-scheme.spec.ts`

**Interfaces:**
- Consumes from Tasks 1 and 2: the nine endpoints above.

**This task's implementation is handed to the `impeccable` skill.** What follows is the brief it
must satisfy, not a component-by-component transcript — the visual and interaction design is that
skill's job.

### What the page is for

A staff user runs a lender's month on this screen. They arrive from the policy record (drill-in,
never a sidebar item — the same reasoning `screens.tsx` already records for `GroupSchemePage`:
a nav entry would lead to a "paste a policy number" screen, which reads as broken software).

Three things they do, in order of how often:

1. **Upload this month's enrolment file** and read what came back — how many enrolled, how many
   rejected, and per row *why*. The rejection reasons are a closed set:
   `MISSING_REQUIRED_FIELD`, `DUPLICATE_LOAN`, `DISBURSEMENT_DATE_IN_FUTURE`,
   `ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS`, `MALFORMED_VALUE`.
2. **Accept or withdraw** a proposed file. Acceptance is what actually enrols anybody, and it
   must be a different person from the one who uploaded it — the backend enforces this and the UI
   must not imply otherwise.
3. **Upload the monthly exits file** and do the same.

### The four things this page must get right

- **Nothing is enrolled until someone accepts.** A `PROPOSED` submission has changed nothing. The
  page must not let "uploaded" read as "done" — that misreading puts borrowers on cover in
  somebody's head who are not on cover in the system.
- **`member_reference` is the deliverable.** It is the only place the lender ever learns the
  reference the insurer minted, and they quote it back on later files. It is the report's first
  data column and must be easy to get out of this page.
- **"This borrower is not covered" is the message that matters.** A rejected row is a person
  without insurance their lender may believe is insured. Rejections are not an error state to
  tuck away — they are the main output of an upload.
- **No grades, no salary multiple, no benefit-basis picker.** Those are `GroupSchemePage`'s and
  this product has none of them. What belongs here instead: the premium rate, the free cover
  limit, the interest method, and the member schedule.

### What it may show, all of it already available

| Data | Source |
|---|---|
| Scheme header (lender, FCL, premium rate, interest method, status) | `GET /group-schemes/{policyNumber}` |
| Members, filterable by status | `GET /group-schemes/{policyNumber}/members` |
| Enrolment submissions | `GET /credit-life-schemes/{policyNumber}/enrolments` (Task 1) |
| One submission's rows + reasons | `GET .../enrolments/{id}/rows` |
| The report | `GET .../enrolments/{id}/report` (text/csv) |
| Exits submissions and their rows/report | Task 2's endpoints |

### Constraints the skill must honour

- A **new** page. Do not extend `GroupSchemePage` and do not import its grade or salary rendering.
- `SubmissionsPanel` serves both kinds. The two views differ in exactly one field name —
  `enrolledCount` vs `exitedCount` — so take a label and an accessor rather than forking the panel.
- Use the existing `Panel`, `DataTable`, `PageHeader`, `StatusBadge`, `ConfirmAct`, `DetailLayout`,
  `StatCards` components. Do not introduce a parallel set.
- The upload follows `src/api/claims.ts`'s `FormData` precedent — that is the codebase's
  established multipart shape.
- The form module is a Zod schema in its own `.ts` file with a sibling `.test.ts`, like
  `addMemberForm.ts`. Validation lives there, not inline in the component.
- Never repaint `index.css` or the `AppShell` sidebar.
- Do not run Prettier.

- [ ] **Step 1: Hand the brief above to the `impeccable` skill and build the page.**

- [ ] **Step 2: Write the form module's unit test and run it**

```bash
cd frontend && npx vitest run src/features/policies/enrolmentUploadForm.test.ts
```

- [ ] **Step 3: Register the route**

In `screens.tsx`, beside the existing group-scheme entries, and drill-in for the reason recorded
there:

```tsx
  // Drill-in from the policy record, exactly like group-schemes/:policyNumber above and for the
  // same reason. A credit-life scheme gets its OWN page rather than a branch inside
  // GroupSchemePage: that page renders grades and salary multiples, and this product has neither.
  { path: 'credit-life-schemes/:policyNumber', element: <CreditLifeSchemePage />, reach: 'drill-in' },
```

and make `PolicyDetailPage` link here when the policy's `productCategory` is `CREDIT_LIFE`,
rather than to `group-schemes/:policyNumber`.

- [ ] **Step 4: Run the unit suite and typecheck**

```bash
cd frontend && npx tsc --noEmit ; npx vitest run ; npx eslint src --max-warnings 0
```

**Do not run vitest while Playwright is running** — concurrently they fail the heaviest e2e tests.

- [ ] **Step 5: Write the e2e spec**

`frontend/e2e/staff-credit-life-scheme.spec.ts`, modelled on the existing staff specs and using
their auth fixtures. Cover: upload a file, see the rejected rows and their reasons, accept as a
second staff user, see the members appear. Couple assertions to **accessible names**.

- [ ] **Step 6: Run the e2e spec alone, then the suite**

```bash
cd frontend && npx playwright test e2e/staff-credit-life-scheme.spec.ts
```

The dev backend must be running, and **the dev database must have this branch's migrations** —
they are applied by `scripts/migrate.sh` as a separate step, never on boot, so a green
Testcontainers suite does not mean the dev DB is in sync.

- [ ] **Step 7: Commit**

```bash
git add frontend/src frontend/e2e
git commit -m "feat(console): a credit-life scheme has a page that fits it"
```

---

## Self-review against the spec

**Spec coverage.** Item 13 is Task 3. Tasks 1 and 2 are not in the spec's list at all — they are
what the research found missing underneath it, and item 13 cannot be built without them.

**Deliberately NOT in this plan:**

- **The bank portal** (items 18–21). This page is staff-facing. The lender sees none of it.
- **Rejection-report notices** (item 12), deferred 2026-09-23 — see spec §0c. Nothing here emails
  anybody; the report is downloaded from this page by a staff user.
- **Editing a member from the page.** `exitMember` and `promoteMember` exist on `PolicyApi`, but
  a credit-life member's life is driven by the lender's files, and hand-editing one is the
  exception path that deserves its own thought rather than a button added because the API is
  there.

**One thing a reviewer should push back on if they disagree:** Task 2 builds `ExitController` as a
mirror of `EnrolmentController` rather than merging the two into one submissions controller
parameterised by kind. The duplication is real — seven near-identical endpoints. The argument for
two files is that the flows share a shape and not a meaning: one puts people on cover, the other
takes them off, they validate different rows and have different money consequences, and a merged
controller would turn the next difference between them into a branch. A reviewer who values the
DRY-ness more than that separation should say so before Task 2, not after.

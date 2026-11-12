# Client Premiums Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On an individual's or a company's page, a **Premiums** tab shows finance and customer service, in one place,
every premium the client owes across all their policies, soonest first, with their last receipt one click away and
one file of it all to download.

**Architecture:** One new server read, in the omnichannel module, which already builds the customer's receipts and the
payment-schedule files and depends on policy, billing, payment and organisation. It lists the policies the client
holds (`PolicyApi.searchPolicies(partyId, …)`), each policy's open invoices (`BillingApi.listInvoices`), and each
policy's receipts (`PaymentApi.receiptsForPolicy`). It totals nothing the screen could not check: the three figures
are sums of the rows it returns, worked out on the server. The download is a `CustomerDocument`, drawn by the same
`DocumentPdf` and xlsx writer as the payment schedule.

**Tech Stack:** Spring Boot 3.3 / Java 21 (omnichannel), React 19 + TypeScript + Vitest, OpenAPI 3.1
(`npm run generate:api`), Playwright.

## Decisions (the user, 2026-11-12)

- **Name: Premiums** (chosen over Billing and Payments).
- **Default: the next 30 days**, overdue always on top; chips for the next 90 days and every invoice raised.
- **Download: yes** — one file of the client's premiums across all policies. The per-policy payment schedule already
  downloads on the policy's Billing tab (`PaymentScheduleDownloads`); this does not repeat it.
- Last receipt on the tab (the user, same day: "a receipt for their last payment on staff for a customer"; today four
  clicks per policy).

## Facts found in the code (2026-11-12)

- Invoices are raised **a year ahead** (rolling billing, `BillingApiImpl` "invoices are raised a year ahead").
  `InvoiceStatus` = DUE, PARTIALLY_PAID, PAID, IN_GRACE, OVERDUE, WAIVED. Open = DUE, PARTIALLY_PAID, IN_GRACE,
  OVERDUE; owed = `balanceDue`.
- A **SINGLE**-premium policy gets one invoice at inception and nothing ahead; a scheme billed **per enrolment file**
  (`premiumPerEnrolment`, credit life) gets no invoices at all (`billing.PolicyEventListener`). On dev, group life
  schemes are SINGLE (one invoice each), credit-life schemes have none.
- `PaymentApi.receiptsForPolicy(policyNumber)` gives every receipted amount, newest first, with `receiptNumber`.
- The staff receipt PDF: `GET /policies/{policyNumber}/receipts/{receiptId}/pdf` (omnichannel).

## Global Constraints

- Simple English; each figure said once; money in full. Every figure is the server's.
- Guard: `@access.can('collections.view')` on the read and the file; the tab shows only with `collections.view`.
  "Record payment" shows only with `collections.manage` or `policies.service` (the receipt form's own gate,
  `screens.tsx` `'receipts/new'`).
- Host Maven only; dev backend stopped for backend tests and restarted after; one heavy run at a time; Playwright
  `--workers=1`, MCP browser closed; never Prettier; Edit/Write tools only. Frontend through impeccable: one batched
  desktop + phone round, one confirm round.
- Branch `client-premiums-tab`, `merge --no-ff`, push `origin main`; trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Structure

| File | Responsibility |
|---|---|
| `omnichannel/api/ClientPremiumsView.java` (new) | the tab's wire shape |
| `omnichannel/application/ClientPremiums.java` (new) | the read and the file |
| `omnichannel/infrastructure/ClientPremiumsController.java` (new) | `GET /parties/{partyId}/premiums`, `…/premiums/{format}` |
| `api/openapi/openapi-omnichannel.yaml` (modify; the file that holds `/policies/{policyNumber}/payment-schedule`) | paths + schemas |
| `frontend/src/api/clientPremiums.ts` (new) | types, read, download |
| `frontend/src/features/party/ClientPremiumsTab.tsx` (new) | the tab |
| `frontend/src/features/party/PartyDetailPage.tsx` (modify) | register "Premiums" after "Policies" |
| `frontend/e2e/staff-client-premiums.spec.ts` (new) | e2e |

---

### Task 1: The read

**Interfaces — Produces:**

```java
public record ClientPremiumsView(
    String window,                    // "30", "90" or "ALL" -- what the list holds
    long overdueCount, BigDecimal overdueOwed,
    LocalDate nextDueOn, BigDecimal nextDueAmount,            // the soonest open invoice not yet overdue; null if none
    long dueInWindowCount, BigDecimal dueInWindowOwed,        // open, not overdue, due on or before today + window
    String currency,                                          // TZS; rows in another currency are listed, never added
    LastReceipt lastReceipt,                                  // null when the client has never paid
    List<Row> rows,                                           // overdue first (oldest first), then by due date
    List<NotInvoiced> notInvoicedAhead) {
    public record LastReceipt(UUID receiptId, String receiptNumber, String policyNumber, LocalDate receivedOn,
                              BigDecimal amount, String currency) {}
    public record Row(UUID invoiceId, String policyNumber, String productName, LocalDate dueDate,
                      BigDecimal owed, String currency, String status) {}   // status: OVERDUE, IN_GRACE, DUE, PARTIALLY_PAID
    /** A policy with nothing raised ahead, and why: SINGLE ("paid once") or PER_ENROLMENT_FILE. */
    public record NotInvoiced(String policyNumber, String productName, String how) {}
}
```

- [ ] **Step 1: Failing test** `ClientPremiumsTest` (Mockito mocks of `PolicyApi`, `BillingApi`, `PaymentApi`, built
  directly — never `@MockBean` of a module api). Fixture: two policies of one party; policy A has invoices overdue
  (12 Oct, 80,000), due 1 Dec (80,000), due 1 Feb next year (80,000); policy B (funeral) due 20 Nov (15,000); a
  credit-life scheme with no invoices and `premiumFrequency` SINGLE; receipts on A (5 Nov, RC-2026-000123) and B
  (1 Oct). Today 12 Nov 2026, window 30. Assert: overdue 1 / 80,000; next due 20 Nov 15,000; due in 30 days 1 /
  15,000 (1 Dec is 19 days away → 2 / 95,000 — write the test from the dates, not from this note); rows overdue first
  then by date, 1 Feb absent; last receipt RC-2026-000123; the scheme in `notInvoicedAhead`.
- [ ] **Step 2:** run it, see it fail.
- [ ] **Step 3:** implement `ClientPremiums.read(UUID partyId, String window, LocalDate today)`: page through
  `searchPolicies(partyId, null, null, …)` (all statuses — a lapsed policy can still owe); open invoices per policy;
  `receiptsForPolicy` per policy, keep status CONFIRMED, newest `receivedOn`; a policy with no open invoice and no
  future invoice that is SINGLE or billed per file goes to `notInvoicedAhead` (read `premiumFrequency` and the
  per-enrolment flag from `PolicyView`; if the flag is not on it, ask billing whether a schedule exists — check in code,
  do not guess).
- [ ] **Step 4:** controller `GET /parties/{partyId}/premiums?window=30|90|ALL` (default 30; anything else 400),
  `@PreAuthorize("@access.can('collections.view')")`; OpenAPI paths and schemas (nullable as `type: [..., 'null']`,
  objects as `anyOf: [ {type: 'null'}, {$ref} ]`).
- [ ] **Step 5:** contract test: FINANCE_OFFICER 200 and valid; UNDERWRITER 403 (a real role without
  `collections.view`, never a bare staff token). Run `ClientPremiumsTest`, the omnichannel contract test,
  `PermissionCatalogueTest`, `ModularityTests`.
- [ ] **Step 6:** commit — `feat(omnichannel): a client's premiums across all their policies, and their last receipt`.

### Task 2: The file

- [ ] **Step 1: Failing test** — `GET /parties/{partyId}/premiums/xlsx` returns
  `Content-Disposition: attachment; filename="premiums-<client>-<date>.xlsx"`; the PDF renders with the letterhead.
- [ ] **Step 2:** build a `CustomerDocument` the way `paymentScheduleDocument` does: title "Premiums due", the client's
  name, the window in words ("Next 30 days, and everything overdue"), one row per invoice (due date, policy, product,
  amount, status), the three figures as totals, the not-invoiced lines as notes. Render through the controller's
  existing `file(...)` helper (pdf or xlsx). Kind: `STATEMENT` (as the payment schedule).
- [ ] **Step 3:** run, commit — `feat(omnichannel): download a client's premiums due`.

### Task 3: The tab (invoke impeccable)

The same design as the dashboards built 2026-11-12 (the user: "frontend to impeccable to use the same design"): the
lit band exactly as `ManagementDashboardPanel` and the agent sales panel draw it (`hero-scope bg-hero`, `text-display`
figures, the label above, one small note below), `StatusBadge` for statuses, `DataTable` and `FilterChip` as the My
sales page uses them. No new visual pattern.

Layout, each figure once:
- Lit band (`hero-scope bg-hero`), three figures: **Overdue now** (owed; "N invoices"), **Next due** (amount; "on
  1 Dec 2026"), **Due in the next 30 days** (owed; follows the chip).
- **Last receipt** line: number · date · amount · Download PDF (the existing receipt PDF). "No payment yet" when null.
- Chips **Next 30 days / Next 90 days / All raised** (the URL keeps the choice); Download Excel / Download PDF.
- `DataTable`: Due date · Policy (link) · Product · Amount · Status (`StatusBadge`), overdue rows first;
  **Record payment** → `/staff/receipts/new?policy=…&from=client` when the person may record payments.
- Under the table, one muted line per not-invoiced policy: "GRP-091E… is paid once a year — nothing raised ahead." /
  "CRL-… is paid per enrolment file."
- Empty: "Nothing owed in the next 30 days." with the next due date if there is one.

- [ ] **Step 1:** `npm run generate:api`; `api/clientPremiums.ts`.
- [ ] **Step 2: Failing tests** `ClientPremiumsTab.test.tsx`: the figures in full, overdue row first, the receipt link,
  Record payment hidden without the permission, the not-invoiced line, the 30/90/All chips asking the server.
- [ ] **Step 3:** implement; register the tab after "Policies" in `PartyDetailPage` for staff with `collections.view`,
  individuals and organisations alike; `tsc`, eslint, `vitest src/features/party` → PASS.
- [ ] **Step 4:** impeccable round on dev (a client with several policies; an organisation with a scheme), desktop
  and phone together; fix once; confirm once.
- [ ] **Step 5:** commit — `feat(clients): the Premiums tab`.

### Task 4: e2e, merge

- [ ] `staff-client-premiums.spec.ts` as the finance login: open a client with a policy, the Premiums tab shows the
  band and at least the policy's next invoice under "All raised", Download Excel gives `premiums-…xlsx`, the last
  receipt opens a PDF when one exists; an underwriter sees no Premiums tab.
- [ ] Affected backend classes, `vitest src/features/party src/features/dashboard`, Playwright the new spec plus
  `staff-clients` with `--workers=1`.
- [ ] Merge `--no-ff`, push, memory.

## Self-review

- Coverage: name, default window, download, last receipt, group schemes (Facts), permissions — each has a step.
- Open by design, checked in code at Task 1 Step 3: where the per-enrolment flag can be read.

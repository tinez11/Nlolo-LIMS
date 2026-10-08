# Customer self-service portal — design

Date: 2026-10-08. Status: proposed, awaiting the user's review.
Source: the user's PRD "DLICP Customer Self-Service Portal v1.0", checked against the code on main (737f0c6e).

## 1. Decisions (the user's, 2026-10-08)

| # | Question | Answer |
|---|---|---|
| D1 | How a login is linked to a client record | **Staff send an invite.** No self-registration. |
| D2 | Who may see a policy | **The policyholder only.** Life assured and beneficiaries get no portal access in V1. |
| D3 | Customer abilities open today that the PRD puts behind approval or a later phase | **Switched off for customers now**: replacing beneficiaries, applying for a policy loan, creating a client record. |
| D4 | Where the portal lives | **Inside the existing console**, the "Policyholder" realm, mobile-first. Not a separate app. |

## 2. Principle

The portal is a channel. It calls the existing modules and never decides status, premium, claim value, account value,
fund value or postings itself (PRD §3, §61). Every customer endpoint derives the customer from the token's `party_id`;
an id in a URL or body is never trusted for ownership (already the rule in PolicyController/ClaimController).

## 3. What exists (verified)

- **Isolation**: customers read only policies they hold (policyholder = token `party_id`), and their own claims and
  loans; covered by tests with a second customer who owns nothing.
- **Open to customers today (31 endpoints)**: policies list/detail, coverage status, covered lives, invoices, next due,
  credits, surrender value, payment schedule and savings statement (table/PDF/Excel), claims list/detail/register,
  claim evidence upload/download, mobile-money payment request on an invoice, products list/detail, reference codes,
  own client record.
- **Not yet usable**: the `customers` realm has no browser client (`lifeplatform-spa`), so nobody can sign in from the
  console; the console's customer route list is empty; `party_id` is set on a Keycloak user by hand.

## 4. Scope by step

Each step ships on its own branch and merges when green; the user tests each one.

### Step 0 — Close the three abilities (D3)
Remove `REALM_CUSTOMERS` from `PUT /policies/{n}/beneficiaries`, `POST /policies/{n}/loans`, `POST /parties/individuals`.
Contract tests assert a customer token gets 403 on each. (Loan **repayments** stay open.)

### Step 1 — Invite and sign-in (D1)
- **Keycloak**: add the `lifeplatform-spa` public client (PKCE S256) to the `customers` realm; enable
  `resetPasswordAllowed`; password policy (length 10, not username); brute-force detection. Same additions mirrored into
  `scripts/apply-spa-client.sh` for existing dev databases.
- **Server**: a confidential `lifeplatform-admin` client with a service account holding `manage-users` in the
  `customers` realm only; the backend calls the Keycloak Admin API through it. Secret from the environment.
- **New table `party.portal_access`**: `party_id` (unique per tenant), `keycloak_user_id`, `username`, `status`
  (INVITED / ACTIVE / REVOKED), `invited_by`, `invited_at`, `activated_at`, `revoked_by`, `revoked_at`. RLS like every
  party table.
- **Invite** (`POST /parties/{partyId}/portal-access`, staff with CUSTOMER_SERVICE_REP or ADMIN): only for an
  INDIVIDUAL client who holds at least one policy and has an email or phone. Creates the Keycloak user with
  username = email (else phone), attributes `tenant_id` and `party_id` set by the server -- never typed -- and required
  action UPDATE_PASSWORD.
  - Client has an **email**: Keycloak sends its "set your password" link (execute-actions email; needs SMTP).
  - **Phone only**: the server generates a one-time temporary password and shows it to the staff member once; staff
    hand it over in person or by phone, and the customer must change it at first sign-in. **Not sent by SMS**
    (changed while building): the communication module records every message body in its dispatch log, so an SMS
    would have stored the password in the database.
- **Re-send** and **revoke** (`DELETE` disables the Keycloak user; status REVOKED). A revoked client can be invited again.
- **Status** becomes ACTIVE at the first successful sign-in (first authenticated customer request).
- **Console**: on a client's page, a "Portal access" panel: Not invited / Invited (re-send) / Active / Revoked.
- **Login page**: the existing "Policyholder" option on the realm picker; "Forgot password?" enabled.

### Step 2 — Dashboard and My policies
- `GET /customer/dashboard`: counts and the next premium due, assembled from existing module APIs.
- Screens: Dashboard (cards shown only when they apply, PRD §42), My policies, Policy detail with Overview,
  Benefits, Premiums (invoices + payment schedule), Beneficiaries (read-only), Claims on this policy.
- Open to customers the existing reads they lack: savings account value and transactions, unit-linked units and value,
  annuity income -- each behind the same policyholder check.

### Step 3 — Documents
Document centre listing what exists: payment schedule, savings statement, premium receipts, claim evidence.
New: a **policy schedule** PDF generated from the policy (same CustomerDocument renderer as the payment schedule).

### Step 4 — Claims
- Report a claim: choose own policy, claim type (from what the product covers), event details, upload documents,
  review, submit. Funeral policies name the covered life.
- Timeline from the claim's status and dated events (PRD §17), with customer wording per status:
  Registered → "Claim received"; Under assessment / Reopened → "Being reviewed"; Approved → "Approved";
  Settlement requested → "Payment being processed"; Settled → "Paid"; Rejected → "Declined".
- Decline wording per coded reason for customers; staff notes and assessments never exposed.
- **New**: "action required" document requests -- staff request a document on a claim, the customer sees it and
  uploads against it. Its own table and claims-side screen.

### Step 5 — Products, quotes, applications
- Product fields for customers: short description, key benefits, "available online" flag (per version).
- Customer access to the existing quote endpoints (premium quote, funeral quote) for online products, marked
  indicative.
- "Request this product" opens an underwriting case as the applicant (policyholder = self); My applications lists the
  customer's cases with customer wording. No drafts in V1.

### Step 6 — Payments
Pay premium screen over the existing mobile-money payment request; payment history and receipts.

### Step 7 — Notifications
Inbox from the communication module's dispatch records for the customer's party.

## 5. Out of V1 (PRD §5, D2, D3)
Life assured / beneficiary access; beneficiary changes; loans; surrenders; reinstatement; profile change approval;
drafts of applications; MFA (planned before production); a separate mobile app.

## 6. Cross-cutting
- **Audit**: customer actions (sign-in, claim submitted, document uploaded, payment requested, product requested,
  document downloaded) recorded with the customer's party and the request id. This needs the platform-wide
  "who did it" work; the portal steps record the party on their own events from the start.
- **Errors**: customer screens show plain messages with a reference, never server text meant for staff.
- **Testing**: every customer endpoint has a test that a second customer is refused; e2e journeys A, B and D from the
  PRD as each step lands.
- **Production** (separate from the portal): Keycloak production mode with HTTPS, secrets outside the repository, no
  test users imported, SMTP and SMS providers configured.

## 7. Open questions for the user
1. Username for an invited customer: email where present, else phone number -- acceptable?
2. Invite permission: Customer service rep and Admin -- or customer service only?

# Universal Login — Step 2: Resets and Notices — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Forgot-password by SMS code for customers, agents and regulators only; staff reset by an administrator, by
SMS; a "your password was changed" SMS after every password change.

**Architecture:** The Keycloak extension (`backend/keycloak/extensions`) gains a staff skip and an account-level resend
gap in the reset authenticator, and an event-listener provider that tells the platform, signed, when a password
changes. The backend gains `POST /internal/password-changed` (iam) that texts the notice -- to the party's phone for
agents and customers, to `iam.staff_user.phone` for staff -- and staff resets by SMS (`Delivery.SMS_PASSWORD`).

Spec: `2026-11-06-universal-login-design.md` §2. Built on branch `universal-login-step2` (off `universal-login`).

## Global Constraints

- Same page for every input on forgot-password; nothing reveals whether an account exists, is staff, or has a phone.
- Staff (realm role `staff`) are sent nothing by the forgot-password flow.
- Per account: at most 1 code a minute and 3 an hour (the hourly cap already exists, 3, stricter than the spec's 5 --
  kept). The minute gap moves from the browser session to the account.
- Notice text EN: `Your Nlolo password was changed on {d MMM} at {HH:mm}. If this wasn't you, call {SUPPORT_PHONE}.`
  SW: `Nenosiri lako la Nlolo limebadilishwa tarehe {d MMM} saa {HH:mm}. Kama si wewe, piga {SUPPORT_PHONE}.`
  Times in Africa/Dar_es_Salaam.
- `app.support-phone` from `SUPPORT_PHONE`; `ProductionSettingsGuard` refuses it empty in prod.
- Notices fire on Keycloak `UPDATE_PASSWORD` only (self change, reset completion, first change after a temporary
  password). Not on `UPDATE_CREDENTIAL` (Keycloak 26 raises both for one change). An admin reset already texts the
  person their temporary password, so it raises no separate notice.
- Signed exactly like `/internal/password-reset/sms`: HMAC-SHA256 over `timestamp.body`, secret
  `PASSWORD_RESET_SMS_SECRET`, headers `X-Reset-Timestamp`/`X-Reset-Signature`, 5-minute window. Caddy already blocks
  `/api/internal/*` publicly.
- Staff texts carry no party, so they bypass the dispatch log (party_id NOT NULL): `NotificationApi.sendSystemSms`.
- Nothing is compiled until step 1's backend suite has finished (user, 2026-11-06).

## Tasks

### Task 1: Extension — staff skip and account-level minute gap
- `ResetCodes`: `MIN_GAP_SECONDS = 60`; `maySend(List<Long> recent, long now)` false when the hour cap is reached OR
  the newest send is under 60 s old. Tests in `ResetCodesTest`.
- `ResetDeliveryAuthenticator.deliver`: after the enabled check, a user holding realm role `staff` gets an event
  `SEND_RESET_PASSWORD` error `staff_reset_by_administrator` and nothing is sent; `withinHourlyCap` passes `now`.

### Task 2: Extension — password-changed listener
- `PasswordChangeNotifier` (HTTP, signed, like `ResetCodeTexter`): POSTs `{tenantId, userId, partyId|null, language}`
  to `LP_PASSWORD_CHANGED_URL`.
- `PasswordChangedListener` (`EventListenerProvider`): on `EventType.UPDATE_PASSWORD` with a user id, reads the user,
  posts. Never throws into Keycloak.
- `PasswordChangedListenerFactory` id `lifeplatform-password-changed`; registered in
  `META-INF/services/org.keycloak.events.EventListenerProviderFactory`.
- Realm: `eventsListeners` gains `lifeplatform-password-changed`; `email` listener stays (it emails only with mail on).
- Compose (dev + server): `LP_PASSWORD_CHANGED_URL`.

### Task 3: Backend — the notice
- `NotificationApi.sendSystemSms(String phone, String templateKey, String body)` -> null or why not; no dispatch row.
- iam `PasswordChangedNotices.notify(tenantId, userId, partyId, language)`: phone from `PartyApi` for a party, else
  `iam.staff_user.phone`; text per Global Constraints; never throws.
- iam `PasswordChangedController` `POST /internal/password-changed`: signature check, 202 always when signed.
- `SecurityConfig`: permitAll that exact path. `application.yml`: `app.support-phone: ${SUPPORT_PHONE:}`; guard.
- Tests: notice text EN/SW and phone routing (unit, fakes); controller signature (401 unsigned/stale, 202 signed).

### Task 4: Backend + console — staff reset by SMS
- `AccessAdministration.Delivery` gains `SMS_PASSWORD`; `PasswordIssued(delivery, temporaryPassword, smsSentTo,
  notice)`. Order: email link (mail on, email known) -> SMS (phone) -> shown once. A failed SMS falls back to showing
  the password with the reason (agent-login pattern). Text: `Your Nlolo staff login: username {u}, one-time password
  {p}. Sign in at {console} - you will choose your own password first.`
- OpenAPI `openapi-iam.yaml`: enum + `smsSentTo`, `notice`. Console `UserDetailPage`: "A one-time password was texted
  to {phone}." and the notice.
- Tests: `AccessAdministrationIntegrationTest` case for SMS delivery.

### Task 5: Login theme — the forgot page says what happens
- `lpResetIntroSms` (EN, SW): adds "Staff: ask your administrator to reset your password." Same on the code page.

### Task 6: Docs + verification
- `backend/keycloak/README.md` forgot-password section; `deploy/README.md` `SUPPORT_PHONE`, `LP_PASSWORD_CHANGED_URL`.
- After step 1 merges: build the extension, restart Keycloak, run the touched backend tests and vitest, then a live
  check: staff forgot-password sends nothing; agent receives code; change -> notice in the SMS mock.

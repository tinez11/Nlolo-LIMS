# Replace Keycloak with Authentik

Label: wayfinder:map

## Destination

An approved migration spec for replacing Keycloak with Authentik in one cut-over branch, ready to hand to `/to-tickets` and build. A feasibility go/no-go comes first: if Authentik cannot carry every current sign-in behaviour, the map ends in a "no" instead of a spec.

## Notes

- **Why:** preference. Login flows, the texted codes and the SMS hooks should be configured in Authentik's admin UI and blueprints, not written as custom Java extensions (SPIs) on Keycloak. So the aim is to delete `backend/keycloak/extensions`, not to port it.
- **Settled at charting (2026-11-19, the user):**
  - Dev data only. There are no real logins to migrate; Authentik is seeded fresh.
  - Direct swap. No provider-neutral layer: rename the adapters, change the claims.
  - Every current behaviour carries over:
    - a texted code at every staff sign-in, which production refuses to start without
    - password reset by SMS only: a six-digit code that lasts 15 minutes, typed into the reset page, in English and Swahili
    - the "your password changed" text
    - universal login (one sign-in, workspace chosen by `X-Workspace`)
    - the phone number as the username
    - the green login theme, kept apart from the console
    - staff cannot reset their own password

    Anything Authentik can't do natively becomes a ticket, never a silent drop.
  - Texts go Authentik → a backend webhook → the existing SMS gateway. That keeps one SMS provider, one allowlist and the existing wording.
  - Roles travel in a plain `roles` claim, no longer `realm_access.roles`. `tenant_id` and `party_id` keep their names.
  - The login look: same feel (green palette, brand photo, a layout close to today's) built with Authentik branding and CSS. Small differences are accepted; pixel parity is not required.
  - Configuration lives in Authentik blueprints (YAML in the repo), replacing `deploy/keycloak/build-realms.mjs`.
  - Cut over all at once: one branch removes Keycloak entirely, with no period where both run. It merges when backend, frontend and e2e all sign in through Authentik.
- **Skills:** grilling tickets call `grilling` + `domain-modeling`. Use the vocabulary in the root `CONTEXT.md`.
- **Repo constraints that bind the spec:**
  - The dev box has 7.8 GB of RAM.
  - Email is never a channel.
  - Run Maven on the host, never in Docker.
  - Staff OTP is required in production by `ProductionSettingsGuard`.
- **Where Keycloak lives now** (about 230 files):
  - `iam/infrastructure/SecurityConfig.java` (claims → authorities, `X-Workspace`)
  - `KeycloakStaffLogins`, `KeycloakAgentLogins`, `KeycloakCustomerAccounts` (admin API)
  - `KeycloakSignature` plus the three callback controllers (reset SMS, staff sign-in code, password changed)
  - `backend/keycloak/{extensions,themes}`
  - `deploy/keycloak/build-realms.mjs`, `deploy/auth/Dockerfile`, both compose files
  - `frontend/src/auth/{claims,realms}.ts`
  - the e2e `auth*.setup.ts` files

## Decisions so far

<!-- one line per closed ticket: [title](issues/NN-slug.md): gist -->

- [Can Authentik text staff a sign-in code on every sign-in?](issues/01-staff-sign-in-code.md): yes, with configuration only. A bearer secret replaces the signed request, and the phone is set up silently at the first sign-in (needs a test). The three-wrong-codes stop and the sending limits move to the backend or change.
- [Can Authentik tell the backend when a password changes?](issues/03-password-changed-text.md): yes, with configuration only: event, then notification rule, then webhook. There is no signing, so trust comes from a header mapping. One event covers admin and API resets too, so a policy must filter them. Delivery is queued with 5 retries over about 8–16 minutes, so the backend dedupes on the event id.
- [Can Authentik send a password reset by SMS only?](issues/02-sms-password-reset.md): not natively, but possible with a flow webhook plus the backend texting authentik's 15-minute recovery link. Today's typed code becomes a link: a change for the go/no-go. Phone numbers must match the username exactly.
- [What does it take to run Authentik here?](issues/06-running-authentik.md): a server and a worker, no Redis, its own database on the existing Postgres, and roughly the same RAM as Keycloak (not measured). Blueprints cover everything; the same feel is achievable, not the same page. There is no Swahili, now its own ticket.
- [What does Authentik's API offer for the backend's login operations?](issues/04-admin-api-for-logins.md): all 14 operations map, none a hard gap. Workarounds: the temporary password becomes an attribute plus a check stage; signing someone out takes extra refresh-token calls; a duplicate username comes back as 400. A non-expiring token with per-object rights; UUID `sub` against integer API ids.
- [Can Authentik issue the tokens universal login needs?](issues/05-tokens-and-universal-login.md): feasible. One public provider and one scope mapping carry roles, tenant_id, party_id and home_branch. The blueprint must keep claims in the id_token, use a UUID `sub` and an RSA key. Gaps: PKCE not enforced, no key-rotation overlap, no hard 10-hour session cap. The backend should add issuer and audience validators.
- [Swahili on the Authentik login pages](issues/11-swahili-login-pages.md): the hybrid. The reset request moves to a bilingual platform page, and the backend texts Authentik's recovery link. Only Authentik's sign-in and set-new-password pages stay English. Stock image, no Swahili fork.
- [Go or no-go on Authentik](issues/07-go-no-go.md): **go**, conditional on testing the silent staff phone set-up. The reset stays a typed code, with the link never texted. Three-wrong-codes becomes slowed retries. The console enforces the 10-hour cap. "Password changed" is texted for own changes only. PKCE by policy; key rotation is a planned operation.
- [Test the silent staff phone set-up](issues/13-test-silent-phone-setup.md): **works** on stock 2026.8.5. There's no phone screen; the first code goes to the login's `phone` attribute. The device then keeps that number, so a phone change needs routing by staff record or a device reset. A missing phone must deny, not skip. The server needs about 1 GiB of memory.
- [The webhook contract between Authentik and the backend](issues/08-webhook-contract.md): two calls (sign-in code, password changed), each with its own bearer secret behind Caddy's internal block. The device is keyed by username, and the backend texts the staff record's phone. Status codes map to messages the person sees; the caps live in the backend. Password-changed is answered 202 and deduped on the event id. One `STAFF_SIGN_IN_CODE` switch feeds both the blueprint and the guard.
- [Login look in Authentik](issues/09-login-look.md): **variant A, Split leaf**: `content_right` layout, photo background, white panel by CSS, username and password on one page. The photo is lost on phones (accepted). A wordmark-free photo is needed. Labels, font and language switcher stay authentik's.
- [How workspaces and job roles map to Authentik groups](issues/10-groups-and-roles.md): four groups named after the workspace roles, with job roles staying in the platform's `iam` tables. The `roles` claim is filtered to those four. One login can sit in several groups. The service account may change `agent` and `customer`, add `staff` only, and never touch `regulator`.
- [Where a password reset starts](issues/12-reset-request-page.md): one public bilingual `/reset` page, built from the public quote's parts (Turnstile, visit token, `PhoneCodes`, `PhoneNumbers.normalise`). It answers neutrally and takes today's six-digit code, then sends the browser to Authentik's recovery link. Authentik's recovery flow redirects to `/reset`, and people land on `/` afterwards.
- [How e2e tests sign in under Authentik](issues/14-e2e-sign-in.md): the UI sign-in uses `getByLabel` in one shared helper. A dev-only blueprint holds the 14 test users and app-password tokens for `creditLife.ts`, plus an `e2e-sign-in-code` group so one spec tests the texted code through the mock SMS gateway. Production memory limits; `--workers=2`.
- [Deploying and operating Authentik](issues/15-deployment-and-operations.md): our own `-auth` image built from the stock image with the production blueprints baked in. Dev blueprints are mounted only in dev, and `smoke.sh` checks for leaks. An `authentik` database is backed up paired with `lifeplatform`. The admin is closed to the public, and superusers are refused on the public sign-in flow. A blueprint creates the backend's token; `add-user.sh` keeps its interface. Renamed settings with a stricter guard.
- [Build order inside the cut-over branch](issues/16-build-order.md): step 0 on `main`, the issuer and audience validators. Then nine steps, from Authentik in dev through backend tokens, frontend (Keycloak leaves dev here), adapters, webhooks, reset, e2e and deployment, to deletions. Merge gate: full backend, vitest and e2e one at a time, plus a fresh-bootstrap rehearsal, then a whole-branch review.
- [Write and approve the migration spec](issues/17-write-the-spec.md): **destination reached.** The spec `backend/docs/superpowers/specs/2026-11-19-authentik-migration-design.md` was approved by the user on 2026-11-19. Next: step 0 on `main`, then `/to-tickets` for the cut-over branch.

## Not yet specified

<!-- All fog graduated on 2026-11-19 into: How e2e tests sign in under Authentik, Deploying and operating Authentik,
     Build order inside the cut-over branch (which absorbed the backend test changes), Write and approve the migration spec. -->

## Out of scope

- Migrating existing users or passwords: dev data only (2026-11-19).
- A provider-neutral login layer: the user chose a direct swap (2026-11-19).
- Email delivery of anything: email is never a channel on this platform.
- Contributing a Swahili translation to Authentik upstream: a goodwill follow-up after this effort, not on its route (2026-11-19, from [Swahili on the Authentik login pages](issues/11-swahili-login-pages.md)).

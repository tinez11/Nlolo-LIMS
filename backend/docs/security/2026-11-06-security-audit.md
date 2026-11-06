# Security audit — login, deployment and platform (2026-11-06)

Scope: the system as merged at `abf675bb` (universal login steps 1–3), the production deployment files under
`deploy/`, the Keycloak realm, the backend's security configuration and the database's tenant isolation. Every finding
below was checked against the code or a running dev stack, not assumed. Fixes are **not** applied: this report is for
deciding which to make.

Severity: **High** — exploitable from the internet or puts money/data at direct risk; fix before real customers.
**Medium** — a real weakness with a mitigation already partly in place; fix in the next few weeks. **Low** — hygiene.

## Summary

| # | Finding | Severity | Effort |
|---|---|---|---|
| H1 | Keycloak's `master` realm (the admin's own login) is reachable from the internet, with no lockout | **High** | Small |
| H2 | The mobile-money rail is a mock with no authentication, and it is in the production compose file | **High** (before real money) | Large (real integration) |
| M1 | No Content-Security-Policy on the console | Medium | Small–medium |
| M2 | No rate limiting at the edge | Medium | Medium |
| M3 | Off-site backups are not encrypted by the platform | Medium | Small |
| M4 | Three high-severity advisories in the console's runtime dependencies | Medium | Small |
| M5 | No second factor for staff | Medium | Medium |
| M6 | Refresh-token rotation is off | Medium | Small |
| M7 | Keycloak is pinned at 26.0.7 | Medium | Small–medium |
| L1 | Four unit-linked child tables carry no `tenant_id` and no row-level security | Low | Small |
| L2 | Containers run without hardening flags; no SSH brute-force ban | Low | Small |
| L3 | 400 responses echo `IllegalArgumentException` messages | Low | Small |
| L4 | Backend (Maven) dependencies were not scanned for CVEs in this audit | Gap | Small |

## High

### H1 — The Keycloak admin login is reachable from the internet, with no lockout

**Where.** `deploy/web/Caddyfile` blocks `/admin` on `AUTH_DOMAIN` but proxies everything else, including
`/realms/master/...`. `KC_HOSTNAME_ADMIN=http://localhost:8081` moves only the admin *console*; the `master` realm's
token endpoint is still served on the public hostname. The `master` realm has `bruteForceProtected: false` (Keycloak's
default; confirmed on the dev stack), and the platform's admin account is the bootstrap one (`KC_BOOTSTRAP_ADMIN_*`),
which Keycloak 26 intends as temporary.

**Risk.** Anyone can guess the Keycloak admin password at `https://AUTH_DOMAIN/realms/master/protocol/openid-connect/token`
(`admin-cli`, password grant) as fast as the network allows. Success owns every account on the platform.

**Fix.** (1) In the Caddyfile: `@master path /realms/master /realms/master/*` → `respond @master 404`. (2) Turn on
brute-force protection for `master` (the release can set it with `kcadm`). (3) Create a permanent admin with a long
random password, then delete the bootstrap admin. (1) alone closes the internet path.

### H2 — The money rail is a mock

**Where.** `deploy/server/compose.yml` runs `wiremock` as the mobile-money gateway; `MobileMoneyGatewayAdapter` sends
`/disburse` and `/collect` with no authentication (known since 2026-10-21, still true).

**Risk.** Acceptable for the pilot, where no real money moves. Before real money: no integration with M-Pesa / Tigo /
Airtel exists, so a real payout path would have no authentication or signature checks.

**Fix.** A real aggregator integration (its own project). Until then, keep the pilot rule: premiums recorded as cash or
field receipts.

## Medium

### M1 — No Content-Security-Policy on the console

**Where.** `deploy/web/Caddyfile` sets HSTS, `nosniff`, `Referrer-Policy` and `X-Frame-Options`, but no
`Content-Security-Policy` and no `Permissions-Policy`; HSTS has no `includeSubDomains`.

**Risk.** Tokens live in memory (good: nothing in storage to steal), which makes injected script the remaining way to
use them. A CSP is the standard second line against that.

**Fix.** Start with `default-src 'self'; connect-src 'self' https://AUTH_DOMAIN; frame-ancestors 'none';
img-src 'self' data:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com`
(adjust to what the console loads — the public quote page uses Cloudflare Turnstile, which needs
`https://challenges.cloudflare.com` in `script-src` and `frame-src`), first as `Content-Security-Policy-Report-Only`,
then enforced. Add `Permissions-Policy: camera=(), microphone=(), geolocation=()`.

### M2 — No rate limiting at the edge

**Where.** Nothing in Caddy limits request rates. Keycloak locks an *account* after 5 failures, the SMS reset is capped
per account (1 a minute, 3 an hour), and the public quote link has its own limits — but nothing limits one address
trying many accounts (credential stuffing), or flooding the API.

**Fix.** Rate limiting in front of Caddy (Cloudflare's free tier is the least work and also absorbs floods), or a Caddy
build with a rate-limit module, on `/realms/lifeplatform/protocol/openid-connect/token`, the login form, the reset form
and `/api/*`.

### M3 — Off-site backups are not encrypted by the platform

**Where.** `deploy/server/backup.sh` copies dumps to S3 with rclone (`s3` remote), with no `crypt` layer. The dumps hold
customer records and Keycloak's password hashes.

**Fix.** Wrap the remote in rclone's `crypt` (a key kept off the server, in the runbook's safe), or confirm the bucket
enforces server-side encryption with a key you control. Then prove a restore from the encrypted copy.

### M4 — Three high advisories in the console's runtime dependencies

**Where.** `npm audit --omit=dev`: `axios` (prototype-pollution gadgets), `follow-redirects` (header stripping on
cross-origin redirects; reached through axios, mostly a Node concern), `zod` (memory exhaustion on oversized input).

**Fix.** Upgrade to the patched versions; re-run vitest and the e2e smoke. Add `npm audit --omit=dev` to the release.

### M5 — No second factor for staff

**Where.** User decision D5 (2026-10-13): password policy and lockout, no 2FA yet. A staff account can approve payouts,
journals and claims.

**Fix.** Keycloak's TOTP as a required action for the staff workspace — first for ADMIN and FINANCE_APPROVER, then all
staff. (A conditional flow on the `staff` role; agents and customers keep SMS-only.)

### M6 — Refresh-token rotation is off

**Where.** The `lifeplatform` realm sets no `revokeRefreshToken`; Keycloak defaults apply (access 5 min, idle 30 min,
max 10 h).

**Fix.** `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`, so a stolen refresh token stops working the moment the
real session uses it. Consider a shorter `ssoSessionMaxLifespan` for staff (8 h).

### M7 — Keycloak is pinned at 26.0.7

**Where.** `deploy/server/versions.env`, `backend/infra/docker-compose.yml`. Keycloak supports only its latest
major/minor; 26.0.7 is from late 2024 and has had security releases since.

**Fix.** Move to the newest 26.x patch, with a Keycloak database dump first (upgrades are one-way — see
`docs/09-local-development.md`). Rebuild the extension against the same version.

## Low

- **L1 — Tenant isolation gaps.** Row-level security is on for 273 of 288 tables and applies to `app_role` (not an owner,
  no `BYPASSRLS`). Without it: four `unitlinked` child tables (`premium_split_fund`, `switch_leg`, `top_up_fund`,
  `withdrawal_fund`) — reached only through RLS-protected parents today, but they carry no `tenant_id`. Matters when a
  second insurer shares the database.
- **L2 — Hardening flags.** No `read_only`, `cap_drop: [ALL]` or `security_opt: [no-new-privileges:true]` on the
  containers; no fail2ban for SSH (password login is already off, so the risk is small).
- **L3 — 400 messages.** `GlobalExceptionHandler` returns `IllegalArgumentException` messages to the client. Most are the
  platform's own wording; a library's message could reveal an internal name. (The 500 handler says only "An unexpected
  error occurred" — sound.)
- **L4 — Not done here.** The backend's Maven dependencies were not scanned for CVEs (OWASP dependency-check needs a
  network download of the vulnerability database). Run it once, or add it to CI.

## Already sound

- Only 80/443 are public; Keycloak listens on 127.0.0.1; Postgres, SeaweedFS and actuator are not published.
- `/api/internal/*` and Keycloak's `/admin` return 404 from the internet.
- One origin for console and API, so no CORS surface; bearer tokens, so CSRF does not apply.
- Tokens in memory only; PKCE; the public client cannot use the password grant; the confidential test client is
  dropped from the production realm.
- One realm with workspace roles: a request carries exactly one `ROLE_REALM_*`, staff powers only in the staff
  workspace, staff accounts never shared with another workspace.
- Password policy (8 characters, not username/email, last 3 refused) and lockout (5 failures) on the platform realm.
- Forgot password reveals nothing about an account; codes are 6 digits, 15 minutes, 5 tries, 1 a minute and 3 an hour
  per account; staff are sent nothing; every change is texted to the person.
- Keycloak→platform calls are HMAC-signed with a 5-minute window.
- `ProductionSettingsGuard` refuses to start on dev secrets, plain-http issuers or a missing support number.
- SSH: keys only, root without password, firewall default-deny, automatic security updates.
- Upload limits (10 MB per file, 12 MB per request); 500s leak nothing.

## Suggested order

1. **H1** — one Caddy rule and two Keycloak settings; before anything else goes live.
2. **M1, M6, M4** — small, independent, high value.
3. **M3** — before the first real customer's data is backed up.
4. **M2, M7** — next release cycle.
5. **M5** — when staff are onboarded beyond the pilot team.
6. **H2** — its own project, before real money.
7. Low items as convenient.

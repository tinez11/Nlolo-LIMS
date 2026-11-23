# Edge rate limiting in Caddy — design

**Date:** 2026-11-23
**Status:** approved in conversation, awaiting review of this written spec
**Part of:** the production-readiness items "edge rate limiting", "monitoring and alerting" and "branch scoping",
built one at a time in that order. This spec is the first.

## Why

Every per-person limit already exists: Authentik locks a login for 15 minutes after five wrong passwords
(`deploy/authentik/blueprints/production/lifeplatform.yaml`, "Account lockout"); `/reset` gives a number three codes an
hour (`PasswordResets`); a staff member gets five sign-in codes an hour (`StaffSignInCodes`); Turnstile guards the
public pages. Nothing limits **one visitor address**. One machine can try one common password against thousands of
logins without tripping any per-login lock, or flood the site.

## Decisions (the user, 2026-11-23)

1. **In Caddy, on our own server** (option A). Not Cloudflare: customers' personal and policy data would pass through
   it decrypted. Not fail2ban: Authentik answers a failed sign-in with 200, so logs cannot tell failure from success.
2. **An office allowlist** (option i), `RATE_LIMIT_EXEMPT`, rather than one loose sign-in limit for everyone.

## How Caddy gets the module

- `deploy/server/versions.env` gains `CADDY_VERSION` and `CADDY_RATELIMIT_VERSION` (github.com/mholt/caddy-ratelimit),
  both exact, with `XCADDY_VERSION` for the build tool.
- New `deploy/server/install-caddy.sh`, called by `remote-release.sh` the way `install-authentik.sh` is: builds Caddy
  with the module using xcaddy at those versions into `/opt/caddy/<CADDY_VERSION>-<CADDY_RATELIMIT_VERSION>/caddy`.
  It skips the build when that directory already holds a binary, and keeps the two most recent builds so a rollback
  finds its own.
- The apt `caddy` package stays (its systemd unit, `caddy` user and directories). `dpkg-divert` moves the packaged
  `/usr/bin/caddy` aside and a symlink points `/usr/bin/caddy` at the build, which is Caddy's documented way to run a
  custom binary on Debian. Without the divert, an unattended `apt upgrade` would restore a binary without the module.
- Go comes from Debian's `golang` package, already installed by `bootstrap-server.sh` for Authentik. If the pinned
  Caddy needs a newer Go than Debian's, `GOTOOLCHAIN` fetches the exact version the build asks for.

## The limits

Per client address (`{remote_host}`; Caddy faces the internet directly, so it is the real address), sliding window of
one minute.

| Zone | Matches | Events per minute |
|---|---|---|
| `signin` | auth domain: `POST /api/v3/flows/executor/lifeplatform-*` | 60 |
| `public` | app domain: `/api/public/*` | 30 |
| `api` | app domain: every other `/api/*` | 600 |
| none | the console's files, the sign-in pages' static files, `/application/o/*`, `/oauth/revoke/` | unlimited |

60 sign-in steps is about 15 sign-ins a minute from one address (3-4 steps each). The flow executor is the only place
a password or a code is typed, so it is the only sign-in path limited. `/application/o/token/` is deliberately not: in
production it only exchanges an issued code or refresh token (the password grant exists in the dev blueprint only), and
the console calls it on every full page load's silent sign-in, so counting it would spend signed-in people's allowance.

**`RATE_LIMIT_EXEMPT`** (server `.env`, space-separated addresses or CIDR ranges, empty by default): exempt from
`signin` and `api`, never from `public`. When empty, the Caddyfile uses `192.0.2.255/32` (TEST-NET-1, never routed),
so an empty list is valid configuration rather than an empty matcher. `remote-release.sh` passes it into `caddy.env`.

## What a limited visitor sees

- **`/api/*` (the console):** 429, `Retry-After`, and `application/problem+json`:
  `{"title":"Too many requests","status":429,"detail":"Too many requests from your network. Wait a minute and try again.","errorCode":"RATE_LIMITED"}`.
  The console already shows a problem's title and detail (`frontend/src/lib/apiError.ts`).
- **The auth domain:** 429 with `Retry-After` and the same words as plain text. Authentik's page shows its own error
  box; rewording it would mean changing Authentik, which this does not do.
- Each 429 is in Caddy's journal with the address, for the monitoring piece to count.

## Failure behaviour

- Counts are in Caddy's memory. A restart clears them: at worst an attacker gets a fresh minute.
- `remote-release.sh` runs `caddy validate` on the new Caddyfile before restarting Caddy. A Caddyfile the binary cannot
  load stops the release, and the previous one keeps serving.
- `smoke.sh` fails a release when `caddy list-modules` does not list `http.handlers.rate_limit`.

## Testing

- **Local rehearsal** (`deploy/test/rate-limit-rehearsal.sh`): builds the pinned Caddy in a throwaway `golang`
  container, runs the real `deploy/web/Caddyfile` with test domains in front of a stub upstream, and asserts:
  - the 61st `signin` request in a minute gets 429 with `Retry-After`;
  - the 31st `/api/public` request gets 429; `/api/*` answers 429 in the problem+json shape;
  - console files and `/application/o/token/` never get 429;
  - an exempt address passes `signin` and `api` but is still limited on `public`;
  - Caddy starts with `RATE_LIMIT_EXEMPT` empty.
- **On the server:** `smoke.sh`'s module check and `caddy validate`. Tripping a limit there would spend real users'
  allowance, so behaviour is proven by the rehearsal.
- The Caddyfile keeps reading both domains from settings (`{$APP_DOMAIN}`, `{$AUTH_DOMAIN}`), never a literal name
  (deployment pilot decision D2); the rehearsal runs it with test domains, which proves that.

## Docs

- `deploy/DEPLOYMENT-GUIDE.md` Step 8: "put Cloudflare in front" becomes what the built-in limits do and how to add
  the insurer's office addresses to `RATE_LIMIT_EXEMPT`; Part 2's server table notes the Caddy build.
- `deploy/server/.env.example` gains `RATE_LIMIT_EXEMPT=` with a comment.

## Out of scope

- Limits inside the backend (it already caps per number and per person).
- Changing Authentik's error wording.
- Blocking or banning addresses for longer than the window.

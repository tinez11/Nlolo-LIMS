# Edge Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Caddy limits requests per visitor address (sign-in steps 60/min, public pages 30/min, other `/api` 600/min), with an office allowlist, built and installed at pinned versions on the native server.

**Architecture:** Caddy is rebuilt with the `caddy-ratelimit` module by a new `deploy/server/install-caddy.sh` (xcaddy, pinned versions, `dpkg-divert` + `update-alternatives` over the apt binary). The limits are three zones in `deploy/web/Caddyfile`. A local rehearsal script builds the same Caddy in a `golang` container through `install-caddy.sh --build-to` and proves every limit; the release validates the Caddyfile before installing it and `smoke.sh` refuses a Caddy without the module.

**Tech Stack:** Caddy 2 + github.com/mholt/caddy-ratelimit, xcaddy, Go (pinned via `GOTOOLCHAIN`), bash, Docker (rehearsal only), Debian 13 `dpkg-divert`/`update-alternatives`.

**Spec:** `backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md`

## Global Constraints

- Limits per `{remote_host}`, window `1m`: `signin` 60 (auth domain, `POST /api/v3/flows/executor/lifeplatform-*`), `public` 30 (app domain, `/api/public/*`), `api` 600 (app domain, other `/api/*`).
- Never limited: console files, sign-in static files, `/application/o/*` (token endpoint included), `/oauth/revoke/`.
- `RATE_LIMIT_EXEMPT` (space-separated IPs/CIDRs) exempts from `signin` and `api`, **never** `public`. Empty means `192.0.2.255/32`.
- 429 on `/api/*`: `application/problem+json` body exactly `{"title":"Too many requests","status":429,"detail":"Too many requests from your network. Wait a minute and try again.","errorCode":"RATE_LIMITED"}`, plus `Retry-After`.
- 429 on the auth domain: `text/plain` body `Too many requests from your network. Wait a minute and try again.`, plus `Retry-After`.
- Domains are always `{$APP_DOMAIN}` / `{$AUTH_DOMAIN}`, never literal names.
- Every version exact in `deploy/server/versions.env`; nothing "latest".
- Edit files with Edit/Write, never shell heredocs (memory: shell edits corrupted files before). Commit with explicit paths.
- Run the rehearsal from Git Bash. Docker DNS on this PC fails transiently; re-run before diagnosing.

## Files

| File | Responsibility |
|---|---|
| `deploy/server/versions.env` (modify) | `CADDY_VERSION`, `CADDY_RATELIMIT_VERSION`, `XCADDY_VERSION`, `CADDY_GO_VERSION` |
| `deploy/server/install-caddy.sh` (create) | Build Caddy+module at the pins (`--build-to <dir>`), or build-if-missing and install it as the system `caddy` |
| `deploy/test/rate-limit-rehearsal.sh` (create) | Build via `install-caddy.sh --build-to` in a `golang` container, run the real Caddyfile, assert every limit |
| `deploy/web/Caddyfile` (modify) | `order rate_limit`, the three zones, the two 429 answers |
| `deploy/server/remote-release.sh` (modify) | `RATE_LIMIT_EXEMPT` into `caddy.env`; install Caddy and validate the Caddyfile before installing units, on release and rollback |
| `deploy/server/smoke.sh` (modify) | Fail when the running Caddy lacks `http.handlers.rate_limit` |
| `deploy/server/.env.example`, `deploy/DEPLOYMENT-GUIDE.md`, `deploy/README.md` (modify) | Document the setting and the limits |

---

### Task 1: Pin the versions and the build script

**Files:**
- Modify: `deploy/server/versions.env`
- Create: `deploy/server/install-caddy.sh`

**Interfaces:**
- Produces: `versions.env` keys `CADDY_VERSION` (no `v`, e.g. `2.10.2`), `CADDY_RATELIMIT_VERSION` (a Go module version as the proxy lists it, with `v`), `XCADDY_VERSION` (no `v`), `CADDY_GO_VERSION` (e.g. `1.25.4`).
- Produces: `install-caddy.sh --build-to <dir>` writes `<dir>/caddy`, built with the module, and verifies `list-modules` shows `http.handlers.rate_limit`; needs only Go on PATH and network. `install-caddy.sh` with no argument (root, on the server) builds into `/opt/caddy/<CADDY_VERSION>-<CADDY_RATELIMIT_VERSION>/caddy` if missing and makes it `/usr/bin/caddy`.

- [ ] **Step 1: Resolve the exact versions**

From Git Bash (needs network):
```bash
for m in github.com/caddyserver/caddy/v2 github.com/caddyserver/xcaddy github.com/mholt/caddy-ratelimit; do
  echo "== $m"; curl -s "https://proxy.golang.org/$m/@v/list" | sort -V | tail -3; curl -s "https://proxy.golang.org/$m/@latest"; echo
done
curl -s "https://proxy.golang.org/github.com/caddyserver/caddy/v2/@v/v<CADDY>.mod" | grep '^go '
```
Choose: the newest stable `v2.x.y` Caddy tag (no `-beta`/`-rc`); the newest xcaddy tag; for caddy-ratelimit the `@latest` `Version` (a pseudo-version if it has no tags); `CADDY_GO_VERSION` = the newest Go patch release of the minor version Caddy's `go.mod` names (from https://go.dev/dl/?mode=json, or `go1.X.Y` tags), and check `golang:<CADDY_GO_VERSION>` exists on Docker Hub.

- [ ] **Step 2: Add them to versions.env**

Append after the `RCLONE_VERSION` line:
```bash

# --- the front door: Caddy, built with the rate-limit module (install-caddy.sh; 2026-11-23, edge rate limiting) ---
# Built with xcaddy because the packaged Caddy has no rate limiting. Go is pinned too, through GOTOOLCHAIN, so the
# server's build and the local rehearsal (deploy/test/rate-limit-rehearsal.sh) compile the same thing.
CADDY_VERSION=<from step 1>
CADDY_RATELIMIT_VERSION=<from step 1>
XCADDY_VERSION=<from step 1>
CADDY_GO_VERSION=<from step 1>
```
(The `<from step 1>` values are filled from step 1's output; they are data, not design.)

- [ ] **Step 3: Write install-caddy.sh**

```bash
#!/usr/bin/env bash
# Caddy with the rate-limit module (2026-11-23, backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md).
# The packaged Caddy has no rate limiting, so this builds one with xcaddy at the versions versions.env names.
#
#   ./install-caddy.sh --build-to <dir>   build only, into <dir>/caddy (the local rehearsal uses this; needs Go)
#   ./install-caddy.sh                    on the server: build into /opt/caddy/<versions> once, then make it the
#                                         system's caddy
#
# The apt package stays installed for its systemd unit, caddy user and directories. Its binary is moved aside with
# dpkg-divert and /usr/bin/caddy becomes an alternative pointing at our build -- Caddy's documented way to run a custom
# binary on Debian. Without the divert, an unattended apt upgrade would put back a Caddy with no rate limiting.
set -euo pipefail
cd "$(dirname "$0")"
# The pins come from the caller when it has them -- a rollback passes the release's own, which may be older than this
# folder's versions.env -- and from versions.env otherwise.
PINS=CADDY_VERSION,CADDY_RATELIMIT_VERSION,XCADDY_VERSION,CADDY_GO_VERSION
if [ -z "${CADDY_VERSION:-}" ]; then set -a; . ./versions.env; set +a; fi

MODULE=github.com/mholt/caddy-ratelimit

build() {   # build <dir>
  local out="$1" work
  work="$(mktemp -d)"
  export GOTOOLCHAIN="go$CADDY_GO_VERSION" GOPATH="${GOPATH:-$work/gopath}"
  go install "github.com/caddyserver/xcaddy/cmd/xcaddy@v$XCADDY_VERSION"
  (cd "$work" && "$GOPATH/bin/xcaddy" build "v$CADDY_VERSION" --with "$MODULE@$CADDY_RATELIMIT_VERSION" \
     --output "$work/caddy")
  "$work/caddy" list-modules | grep -qx 'http.handlers.rate_limit' \
    || { echo "The built Caddy has no http.handlers.rate_limit" >&2; exit 1; }
  mkdir -p "$out"
  install -m 0755 "$work/caddy" "$out/caddy"
  # Go's module cache is read-only; make it removable first.
  chmod -R u+w "$work"; rm -rf "$work"
  echo "Caddy $CADDY_VERSION with $MODULE@$CADDY_RATELIMIT_VERSION built in $out"
}

if [ "${1:-}" = "--build-to" ]; then
  build "${2:?Usage: ./install-caddy.sh --build-to <dir>}"
  exit 0
fi

[ "$(id -u)" = 0 ] || exec sudo --preserve-env="$PINS" bash "$0" "$@"
CADDY_OPT=/opt/caddy
DIR="$CADDY_OPT/$CADDY_VERSION-$CADDY_RATELIMIT_VERSION"
if [ ! -x "$DIR/caddy" ]; then
  # Go's module and build caches live with the builds, not in root's home.
  export GOPATH="$CADDY_OPT/toolchain/gopath" GOCACHE="$CADDY_OPT/toolchain/cache"
  build "$DIR"
fi

if ! dpkg-divert --list /usr/bin/caddy | grep -q caddy.default; then
  dpkg-divert --divert /usr/bin/caddy.default --rename /usr/bin/caddy
  update-alternatives --install /usr/bin/caddy caddy /usr/bin/caddy.default 10
fi
update-alternatives --install /usr/bin/caddy caddy "$DIR/caddy" 50
update-alternatives --set caddy "$DIR/caddy"
caddy list-modules | grep -qx 'http.handlers.rate_limit' \
  || { echo "/usr/bin/caddy is not the build with rate limiting" >&2; exit 1; }

# Two builds are kept: the one in service and the one before, for a rollback.
for old in $(ls -1d "$CADDY_OPT"/[0-9]*/ 2>/dev/null | sort -V | head -n -2); do
  update-alternatives --remove caddy "${old%/}/caddy" || true
  rm -rf "$old"
done
echo "caddy: $(caddy version | head -1) with rate limiting"
```

- [ ] **Step 4: Syntax check**

Run (Git Bash): `bash -n deploy/server/install-caddy.sh && echo ok`
Expected: `ok`. (The build itself is exercised by Task 2's rehearsal.)

- [ ] **Step 5: Commit**

```bash
git add deploy/server/install-caddy.sh
git commit -m "feat(deploy): build Caddy with the rate-limit module at pinned versions" -- deploy/server/versions.env deploy/server/install-caddy.sh
```

---

### Task 2: The rehearsal, failing against today's Caddyfile

**Files:**
- Create: `deploy/test/rate-limit-rehearsal.sh`

**Interfaces:**
- Consumes: `install-caddy.sh --build-to <dir>`; `versions.env` `CADDY_GO_VERSION`.
- Produces: `deploy/test/rate-limit-rehearsal.sh` exits 0 only when every limit in Global Constraints holds; prints `REHEARSAL PASSED`.

- [ ] **Step 1: Write the rehearsal**

```bash
#!/usr/bin/env bash
# Proves the edge rate limits (backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md) on this PC,
# before any server: builds Caddy exactly as the server does (install-caddy.sh --build-to), in a golang container at
# the pinned Go, then runs the REAL deploy/web/Caddyfile with test domains in front of stub upstreams.
#
# Usage, from Git Bash at the repository root: deploy/test/rate-limit-rehearsal.sh
set -euo pipefail

if [ "${1:-}" != "--inside" ]; then
  cd "$(dirname "$0")/../.."
  . deploy/server/versions.env
  # Git Bash would rewrite /repo into a Windows path; pwd -W gives Docker the C:/ form.
  MSYS_NO_PATHCONV=1 exec docker run --rm -v "$(pwd -W 2>/dev/null || pwd):/repo:ro" \
    "golang:$CADDY_GO_VERSION" bash /repo/deploy/test/rate-limit-rehearsal.sh --inside
fi

# ---- inside the container ----------------------------------------------------------------------------------------
cp -r /repo/deploy/server /tmp/server
sed -i 's/\r$//' /tmp/server/*.sh /tmp/server/versions.env
bash /tmp/server/install-caddy.sh --build-to /tmp/bin
CADDY=/tmp/bin/caddy

# Stub upstreams: the backend (8080) and Authentik (9000) each answer 200 "ok".
"$CADDY" respond --listen 127.0.0.1:8080 --body ok >/dev/null 2>&1 &
"$CADDY" respond --listen 127.0.0.1:9000 --body ok >/dev/null 2>&1 &
mkdir -p /opt/platform/current/console
echo '<div id="root"></div>' > /opt/platform/current/console/index.html

sed 's/\r$//' /repo/deploy/web/Caddyfile > /tmp/Caddyfile
APP=app.rehearsal.test; AUTH=auth.rehearsal.test
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=1; }

start_caddy() {   # start_caddy <RATE_LIMIT_EXEMPT value>
  [ -n "${CADDY_PID:-}" ] && { kill "$CADDY_PID"; wait "$CADDY_PID" 2>/dev/null || true; }
  APP_DOMAIN=$APP AUTH_DOMAIN=$AUTH ACME_EMAIL=ops@rehearsal.test RATE_LIMIT_EXEMPT="$1" \
    CADDY_GLOBAL_OPTIONS=$'local_certs\n\tskip_install_trust' \
    "$CADDY" run --config /tmp/Caddyfile --adapter caddyfile >/tmp/caddy.log 2>&1 &
  CADDY_PID=$!
  for _ in $(seq 1 50); do curl -sk -o /dev/null --resolve "$APP:443:127.0.0.1" "https://$APP/" && return 0; sleep 0.2; done
  cat /tmp/caddy.log >&2; echo "Caddy did not start with RATE_LIMIT_EXEMPT='$1'" >&2; exit 1
}

code() {   # code <host> <curl args...>  -> the HTTP status
  local host="$1"; shift
  curl -sk -o /dev/null -w '%{http_code}' --resolve "$host:443:127.0.0.1" "$@"
}

# expect_limit <label> <allowed> <host> <curl args...>: the first <allowed> answers are not 429, the next one is.
expect_limit() {
  local label="$1" allowed="$2" host="$3"; shift 3
  local i c
  for i in $(seq 1 "$allowed"); do
    c=$(code "$host" "$@"); [ "$c" != 429 ] || { fail "$label: request $i of $allowed was already 429"; return; }
  done
  c=$(code "$host" "$@"); [ "$c" = 429 ] || fail "$label: request $((allowed + 1)) answered $c, expected 429"
}

expect_never() {   # expect_never <label> <count> <host> <curl args...>
  local label="$1" n="$2" host="$3"; shift 3
  local i c
  for i in $(seq 1 "$n"); do
    c=$(code "$host" "$@"); [ "$c" != 429 ] || { fail "$label: request $i was 429"; return; }
  done
}

SIGNIN=(-X POST "https://$AUTH/api/v3/flows/executor/lifeplatform-sign-in/")

echo "-- nothing exempt (the release's placeholder)"
start_caddy 192.0.2.255/32
expect_never "console files" 700 "$APP" "https://$APP/"
expect_never "token endpoint" 100 "$AUTH" -X POST "https://$AUTH/application/o/token/"
expect_limit "signin" 60 "$AUTH" "${SIGNIN[@]}"
headers=$(curl -sk -D - -o /tmp/body --resolve "$AUTH:443:127.0.0.1" "${SIGNIN[@]}")
grep -qi '^retry-after:' <<<"$headers" || fail "signin 429 has no Retry-After"
grep -qi '^content-type: text/plain' <<<"$headers" || fail "signin 429 is not text/plain"
grep -q 'Too many requests from your network. Wait a minute and try again.' /tmp/body || fail "signin 429 words"
expect_limit "public" 30 "$APP" "https://$APP/api/public/products"
expect_limit "api" 600 "$APP" "https://$APP/api/policies"
headers=$(curl -sk -D - -o /tmp/body --resolve "$APP:443:127.0.0.1" "https://$APP/api/policies")
grep -qi '^retry-after:' <<<"$headers" || fail "api 429 has no Retry-After"
grep -qi '^content-type: application/problem+json' <<<"$headers" || fail "api 429 is not problem+json"
grep -q '"errorCode":"RATE_LIMITED"' /tmp/body || fail "api 429 body: $(cat /tmp/body)"

echo "-- this address exempt"
start_caddy 127.0.0.1/32
expect_never "signin, exempt" 100 "$AUTH" "${SIGNIN[@]}"
expect_never "api, exempt" 700 "$APP" "https://$APP/api/policies"
expect_limit "public, exempt address still limited" 30 "$APP" "https://$APP/api/public/products"

echo "-- RATE_LIMIT_EXEMPT unset: the Caddyfile's own default"
kill "$CADDY_PID"; wait "$CADDY_PID" 2>/dev/null || true
APP_DOMAIN=$APP AUTH_DOMAIN=$AUTH ACME_EMAIL=ops@rehearsal.test CADDY_GLOBAL_OPTIONS=$'local_certs\n\tskip_install_trust' \
  "$CADDY" validate --config /tmp/Caddyfile --adapter caddyfile >/tmp/validate.log 2>&1 \
  || { cat /tmp/validate.log >&2; fail "the Caddyfile does not validate with RATE_LIMIT_EXEMPT unset"; }

[ "$FAILED" = 0 ] || { echo "REHEARSAL FAILED" >&2; exit 1; }
echo "REHEARSAL PASSED"
```

- [ ] **Step 2: Run it against today's Caddyfile; it must fail**

Run (Git Bash, repo root): `bash deploy/test/rate-limit-rehearsal.sh`
Expected: the build succeeds (`... built in /tmp/bin`), then `FAIL: signin: request 61 answered 200, expected 429` and `REHEARSAL FAILED`. A build or Docker error is not this failure: fix that first (Docker DNS: re-run).

- [ ] **Step 3: Commit**

```bash
git add deploy/test/rate-limit-rehearsal.sh
git commit -m "test(deploy): a local rehearsal of the edge rate limits" -- deploy/test/rate-limit-rehearsal.sh
```

---

### Task 3: The limits in the Caddyfile

**Files:**
- Modify: `deploy/web/Caddyfile`

**Interfaces:**
- Consumes: env `RATE_LIMIT_EXEMPT` (Task 4 always sets it on the server); defaults to `192.0.2.255/32` when unset.

- [ ] **Step 1: Global ordering**

In the global block, after `email {$ACME_EMAIL}`:
```
	# The rate-limit module's directive runs before anything that answers (2026-11-23, edge rate limiting).
	order rate_limit before basic_auth
```

- [ ] **Step 2: The app domain's zones and answer**

At the top of the `{$APP_DOMAIN} {` site, after `encode zstd gzip`:
```
	# Per visitor address, one minute (2026-11-23, backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md).
	# The public pages (the quote link, /reset) are limited for everyone, office addresses included; the rest of /api is
	# a flood ceiling that RATE_LIMIT_EXEMPT's office addresses skip. The console's own files are never limited.
	rate_limit {
		zone public {
			match {
				path /api/public/*
			}
			key {remote_host}
			events 30
			window 1m
		}
		zone api {
			match {
				path /api/*
				not path /api/public/*
				not remote_ip {$RATE_LIMIT_EXEMPT:192.0.2.255/32}
			}
			key {remote_host}
			events 600
			window 1m
		}
	}

	# What a limited console call gets: the problem shape the console already shows (frontend/src/lib/apiError.ts).
	handle_errors 429 {
		header ?Retry-After 60
		header Content-Type application/problem+json
		respond `{"title":"Too many requests","status":429,"detail":"Too many requests from your network. Wait a minute and try again.","errorCode":"RATE_LIMITED"}` 429
	}
```

- [ ] **Step 3: The auth domain's zone and answer**

In the `{$AUTH_DOMAIN} {` site, before `@signin {`:
```
	# Sign-in steps per visitor address (2026-11-23): one sign-in is 3-4 steps, so 60 a minute is about 15 people from
	# one address; the insurer's offices can be listed in RATE_LIMIT_EXEMPT. Only the flow executor is limited: it is
	# the one place a password or a code is typed. /application/o/token/ is not -- in production it only exchanges an
	# issued code or refresh token, on every page load's silent sign-in.
	rate_limit {
		zone signin {
			match {
				method POST
				path /api/v3/flows/executor/lifeplatform-*
				not remote_ip {$RATE_LIMIT_EXEMPT:192.0.2.255/32}
			}
			key {remote_host}
			events 60
			window 1m
		}
	}
	handle_errors 429 {
		header ?Retry-After 60
		header Content-Type "text/plain; charset=utf-8"
		respond "Too many requests from your network. Wait a minute and try again." 429
	}
```

- [ ] **Step 4: Run the rehearsal; it must pass**

Run: `bash deploy/test/rate-limit-rehearsal.sh`
Expected: `REHEARSAL PASSED`. If `not remote_ip` with a space-separated list or `handle_errors 429` misbehaves in this Caddy version, the rehearsal names which assertion; fix the Caddyfile, not the assertion.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(deploy): per-address rate limits at the front door" -- deploy/web/Caddyfile
```

---

### Task 4: The release installs it and refuses a broken Caddyfile

**Files:**
- Modify: `deploy/server/remote-release.sh` (caddy.env at ~103-108; release flow ~244-246; rollback ~212-216)
- Modify: `deploy/server/.env.example` (after `CADDY_GLOBAL_OPTIONS=`)

**Interfaces:**
- Consumes: `install-caddy.sh` (no argument), Task 3's `RATE_LIMIT_EXEMPT`.
- Produces: `caddy_ready <caddyfile>` in remote-release.sh: installs the pinned Caddy, then `caddy validate`s `<caddyfile>` with `/etc/platform/caddy.env`; exits the release on failure. The pins reach `install-caddy.sh` through the environment: `platform-lib.sh` exports versions.env with `set -a`, and the rollback sources the release's own versions.env with `set -a` before calling it.

- [ ] **Step 1: caddy.env carries the exemption**

In `render_settings`, the `write_env caddy.env caddy <<ENV` block gains a line before `ENV`:
```
RATE_LIMIT_EXEMPT=${RATE_LIMIT_EXEMPT:-192.0.2.255/32}
```

- [ ] **Step 2: caddy_ready**

After `install_units() { ... }`:
```bash
# --- Caddy: the pinned build with rate limiting, and a Caddyfile it can load -----------------------------------------
# Validated BEFORE install_units copies it into place, with the settings Caddy itself will read (systemd-run reads
# caddy.env the way the unit does), so a Caddyfile this Caddy cannot load stops the release while the previous one
# keeps serving.
caddy_ready() {   # caddy_ready <caddyfile>
  ./install-caddy.sh
  if ! out=$(sudo systemd-run --quiet --wait --pipe --collect -p EnvironmentFile="$ETC/caddy.env" \
               /usr/bin/caddy validate --config "$1" --adapter caddyfile 2>&1); then
    printf '%s\n' "$out" | tail -20 >&2
    echo "Caddy cannot load $1 -- the release stops here" >&2
    exit 1
  fi
}
```

- [ ] **Step 3: Call it on release and rollback**

Release flow: between `render_settings` and `install_units "$RELEASE_DIR/server"` insert
```bash
caddy_ready "$RELEASE_DIR/server/Caddyfile"
```
Rollback: between `render_settings` and `install_units "$RELEASE_DIR/server"` insert the same line. (A rollback's `versions.env` is that release's, so it puts back that release's Caddy build.)

- [ ] **Step 4: .env.example**

After `CADDY_GLOBAL_OPTIONS=` add:
```bash
# Office addresses that skip the sign-in and console rate limits (2026-11-23), space-separated, e.g.
# "196.41.0.10 41.59.12.0/28". Never the public pages. Empty: nobody is exempt.
RATE_LIMIT_EXEMPT=
```

- [ ] **Step 5: Syntax check**

Run: `bash -n deploy/server/remote-release.sh && bash -n deploy/server/install-caddy.sh && echo ok`
Expected: `ok`.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(deploy): the release installs the rate-limited Caddy and validates the Caddyfile first" -- deploy/server/remote-release.sh deploy/server/.env.example
```

---

### Task 5: smoke.sh and the docs

**Files:**
- Modify: `deploy/server/smoke.sh` (before `echo "Smoke check passed."`; header comment list)
- Modify: `deploy/DEPLOYMENT-GUIDE.md` (Step 3 settings table; Step 8 Cloudflare item; Part 9 if it mentions rate limiting)
- Modify: `deploy/README.md` (one line on the Caddy build)

- [ ] **Step 1: smoke check**

Before `echo "Smoke check passed."`:
```bash
# The front door's rate limits (2026-11-23): the Caddy in service is our build with the module. Tripping a limit here
# would spend real visitors' allowance, so the limits themselves are proven by deploy/test/rate-limit-rehearsal.sh.
caddy list-modules 2>/dev/null | grep -qx 'http.handlers.rate_limit' \
  || fail "the installed Caddy has no rate limiting (run ./install-caddy.sh)"
```
(Only the module, not the exact build: after a rollback the build is the older release's, and this folder's
versions.env names the newer one.)
And add to the header comment's list: `the front door's Caddy has its rate limits,`.

- [ ] **Step 2: DEPLOYMENT-GUIDE.md**

Replace the Step 8 item
```
- [ ] **Rate limiting:** put Cloudflare (free plan) in front of both addresses. It slows down anyone trying many
      passwords across many accounts.
```
with
```
- [ ] **Office addresses for the rate limits:** the front door limits each visitor address: 60 sign-in steps a minute
      (about 15 people signing in), 30 public-page calls and 600 console calls. An office where many staff share one
      internet address should be listed in `RATE_LIMIT_EXEMPT` in `.env` (space-separated, e.g.
      `196.41.0.10 41.59.12.0/28`), then released again. Ask the office's internet provider for its fixed address.
      The public pages stay limited for everyone.
```
In Step 3's settings table add a row: `` | `RATE_LIMIT_EXEMPT` | The insurer's office addresses, if known | Optional; see Step 8 | ``. In Part 3 Step 6 (Release) note that the first release also builds Caddy with rate limiting (a few minutes). Search the guide for "Cloudflare (free plan)" and "rate limit" and update any other mention to match.

- [ ] **Step 3: README**

In `deploy/README.md`'s description of the server, add: `Caddy is built with its rate-limit module by server/install-caddy.sh at the versions in server/versions.env; deploy/test/rate-limit-rehearsal.sh proves the limits locally.`

- [ ] **Step 4: Checks**

Run: `bash -n deploy/server/smoke.sh && echo ok` → `ok`. Run the rehearsal once more → `REHEARSAL PASSED`.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs(deploy): the rate limits, RATE_LIMIT_EXEMPT, and a smoke check for the module" -- deploy/server/smoke.sh deploy/DEPLOYMENT-GUIDE.md deploy/README.md
```

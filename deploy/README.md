# Running the platform on a server

The short reference. `DEPLOYMENT-GUIDE.md` is the full walk-through, with the why and the checks; start there the
first time.

## 1. What runs where

One Debian 13 server, everything as systemd services, no Docker (2026-11-20, ticket 10 of .scratch/authentik-cutover):

| Service | What | Listens on |
|---|---|---|
| `caddy` | HTTPS for `APP_DOMAIN` (console + `/api`) and `AUTH_DOMAIN` (the sign-in pages only) | 80, 443 |
| `platform-backend` | The system (`/opt/platform/current/lifeplatform.jar`) | 127.0.0.1:8080, management :9090 |
| `authentik-server`, `authentik-worker` | Sign-in, built from source (`/opt/authentik/current`) | 127.0.0.1:9000 and :9001 |
| `postgresql@16-main` | `lifeplatform` and `authentik` databases | localhost:5432 |
| `seaweedfs` | Documents (S3) | 127.0.0.1:8333 |
| `platform-mock-mobile-money` | The money mock: no money moves | 127.0.0.1:8089 |

Files on the server:

| Where | What |
|---|---|
| `~/platform` (deploy user) | `.env`, the scripts below, `backups/`, `RELEASED` |
| `/opt/platform/releases/<v>`, `current` | The last five releases; `current` is in service |
| `/opt/authentik/<version>`, `current` | The last two Authentik builds |
| `/etc/platform/*.env` | Each service's own settings, written from `.env` by every release |
| `/var/lib/authentik`, `/var/lib/seaweedfs` | Authentik's files and blueprints; the documents |

## 2. First deploy

```bash
scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER:
ssh root@SERVER bash bootstrap-server.sh       # Debian 13 only
ssh deploy@SERVER nano platform/.env           # domains, SMS, SUPPORT_PHONE, TURNSTILE_SECRET, FIRST_ADMIN_PHONE, backups
cp deploy/release.env.example deploy/release.env   # DEPLOY_HOST, AUTH_DOMAIN, TURNSTILE_SITE_KEY
deploy/release.sh v1.0.0                       # first time 45-90 min: the server builds Authentik
```

It prints `platform.admin`'s one-time password once. Sign in, enter the texted code, choose a password, then create a
second administrator.

## 3. Releasing and rolling back

```bash
deploy/release.sh v1.1.0                # builds backend + console here, ships, runs remote-release.sh there
deploy/release.sh --rollback v1.0.0     # relinks /opt/platform/current and restarts; migrations are not undone
```

On the server, `./remote-release.sh --rollback "$(cat RELEASED)"` re-applies `.env` to the services without a build.

## 4. Backups and restoring

```bash
./backup.sh manual                         # lifeplatform + authentik + documents, then encrypted off-server
./restore.sh backups/STAMP --scratch       # into a throwaway database, touching nothing live
./restore.sh backups/STAMP                 # REPLACES live data (asks first)
./fetch-backup.sh [daily/STAMP]            # lists, or brings back, an off-server copy
```

## 5. Logins the console cannot make

```bash
./add-user.sh staff asha.juma --roles ADMIN --home-branch DSM --phone +255712345678 --first-name Asha --last-name Juma
./add-user.sh regulator tira.inspector --first-name Salum --last-name Tira
./add-user.sh agent john.mwita --party-id 7ddd05c3-... --first-name John --last-name Mwita
```

Prints a one-time password; the person chooses their own at first sign-in. Staff, agents and customers are normally
added from the console.

## 6. The Authentik admin

Never on the internet. `ssh -L 9000:127.0.0.1:9000 deploy@SERVER`, then
`http://localhost:9000/if/flow/default-authentication-flow/?next=/if/admin/` as `akadmin` with
`AUTHENTIK_BOOTSTRAP_PASSWORD`. Configuration lives in the blueprint
(`deploy/authentik/blueprints/production/lifeplatform.yaml`) and changes by release only; see the guide, Part 7, also
for rotating the signing key.

## 7. Watching it

```bash
./status.sh                                # version, services, disk, last backup, backend health
sudo journalctl -u platform-backend -f     # or authentik-server, authentik-worker, caddy, seaweedfs, postgresql@16-main
./smoke.sh                                 # the release's last gate, any time
./watch.sh --check                         # the watcher's six checks, once
./watch.sh --test-text                     # text ALERT_PHONES a test message
```

`platform-watch.timer` runs `./watch.sh` every 5 minutes and texts `ALERT_PHONES` through NextSMS when something breaks,
lasts (every 6 hours) or clears, plus a Monday 08:00 "still watching" text. The rules are `server/watch-rules.sh`;
`deploy/test/watch-test.sh` proves them.

## 8. Versions

`server/versions.env` pins Authentik and its build tools, Postgres's major version, Flyway, SeaweedFS, WireMock,
rclone, and Caddy with its rate-limit module. Change one there and release; `install-tools.sh`, `install-authentik.sh`
and `install-caddy.sh` install exactly that.

## 9. Rate limits

Caddy is built with `caddy-ratelimit` by `server/install-caddy.sh` (dpkg-divert over the apt binary, so an upgrade
cannot drop it). Per visitor address a minute: 60 sign-in steps, 30 `/api/public` calls, 600 other `/api` calls.
`RATE_LIMIT_EXEMPT` in `.env` lists office addresses that skip the sign-in and `/api` limits. Prove changes locally
with `deploy/test/rate-limit-rehearsal.sh` (Git Bash, Docker) before releasing.

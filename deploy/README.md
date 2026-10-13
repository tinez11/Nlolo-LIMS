# Running the platform on a server

The pilot deployment: one Linux server, Docker Compose, releases pushed from a developer's machine.
Design: `backend/docs/superpowers/specs/2026-10-13-deployment-pilot-design.md`.

Hostnames are **settings**, never spelled out in code or config. `APP_DOMAIN` is the console and the API;
`AUTH_DOMAIN` is Keycloak. Both live in the server's `.env`; renaming either is step 9 below.

## 1. What runs where

| Service | What it is | Reachable from the internet |
|---|---|---|
| `caddy` | Caddy with the built console inside it. TLS for both hostnames; `/api/*` proxied to the backend with the prefix stripped | **yes**, 80 and 443 only |
| `backend` | The platform. **Exactly one instance** — 32 scheduled jobs have no cluster lock, so a second would double every reminder | no |
| `keycloak` | Keycloak 26, production mode, login theme baked in | through caddy; `/admin` is 404 from outside |
| `postgres` | PostgreSQL 16 with pg_partman and pg_cron. Two databases, `lifeplatform` and `keycloak`, each with its own login | no |
| `objectstore` | SeaweedFS, S3 API. The four document buckets | no |
| `mock-mobile-money` | WireMock. The money rail is still a mock — see §12 | no |

Because the console and the API share one origin, **no CORS configuration is needed**, which is why the backend
has never had one.

## 2. First deploy

1. **A server**: Ubuntu 24.04 LTS, at least 4 vCPU, 8 GB RAM, 80 GB SSD.
2. **Bootstrap it**, once, as root:
   ```bash
   scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER:
   ssh root@SERVER bash bootstrap-server.sh
   ```
   It installs Docker, creates the key-only `deploy` user, closes the firewall to everything but 22/80/443,
   generates every secret into `/home/deploy/platform/.env`, and installs the nightly backup.
3. **Fill in the rest of `.env`**: `APP_DOMAIN`, `AUTH_DOMAIN`, `ACME_EMAIL`, `IMAGE_PREFIX`, the `SMS_GATEWAY_*`
   settings and the `BACKUP_S3_*` settings.
4. **DNS**: an `A` record for each hostname pointing at the server. Caddy cannot obtain certificates until they
   resolve.
5. **Registry access**: `ssh deploy@SERVER` then `docker login ghcr.io` with a token that has `read:packages`.
6. **Release**, from your machine:
   ```bash
   cp deploy/release.env.example deploy/release.env   # fill in DEPLOY_HOST, IMAGE_PREFIX, AUTH_DOMAIN
   deploy/release.sh v0.1.0
   ```
7. It prints the **first staff login's one-time password, once**. Sign in at `https://$APP_DOMAIN/staff` as
   `staff.admin` and change it immediately.

## 3. Releasing

```bash
deploy/release.sh v0.2.0
```

Builds and pushes the four images, copies the server files over, and then on the server: backup → pull →
migrate → configure-db → Keycloak → realm configuration → restart → smoke check. It stops at the first failure,
and nothing restarts the backend until the database has migrated, so a failed migration leaves the previous
version running against the schema it was built for.

The git tag is created locally and **not pushed**. Push it when the release proves good: `git push origin v0.2.0`.

## 4. Rolling back

```bash
deploy/release.sh --rollback v0.1.0
```

Points the containers back at the earlier images. **Migrations are not undone** — they only go forward. If a
release's migration has to be reversed, restore the pre-release backup (§5); `release.sh` prints its name before
it touches anything.

## 5. Backups and restoring

Nightly at 02:30, and before every release. Each backup holds both databases and every document, and is copied
off the server when the `BACKUP_S3_*` settings are filled in. Kept: 7 on the server, 14 daily and 8 weekly off it.

Keycloak's database is backed up *with* the platform's, and the two must be restored together — portal logins
live in Keycloak and the people they belong to live in the platform.

```bash
ssh deploy@SERVER 'cd platform && ./backup.sh manual'           # on demand
ssh deploy@SERVER 'cd platform && ./restore.sh backups/STAMP --scratch'   # prove a backup, touching nothing live
ssh deploy@SERVER 'cd platform && ./restore.sh backups/STAMP'            # REPLACES the live data; asks first
```

**Run the `--scratch` restore before go-live and once a month.** A backup nobody has restored is a file, not a
backup.

## 6. Adding a staff, agent or regulator login

```bash
ssh deploy@SERVER 'cd platform && ./add-user.sh staff asha.juma \
    --roles UNDERWRITER --home-branch DSM --first-name Asha --last-name Juma'
```

Roles are the realm's own: `UNDERWRITER`, `SENIOR_UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`,
`FINANCE_OFFICER`, `FINANCE_APPROVER`, `CUSTOMER_SERVICE_REP`, `ADMIN`. Comma-separate several.

- **Staff need `--home-branch`** — it goes in the token, and without it they cannot post a journal.
- **Agents need `--party-id`**, the agent's party id from their record in the console.
- **Customers are not created here.** Staff invite them from the console, which creates the login and writes the
  party id from the record on screen, so nobody transcribes an id.

The script prints a one-time password; Keycloak requires a new one at first sign-in.

## 7. The Keycloak admin console

It is not on the internet. Tunnel to it:

```bash
ssh -L 8081:localhost:8081 deploy@SERVER
```

then open `http://localhost:8081/admin` — user `admin`, password `KC_ADMIN_PASSWORD` from the server's `.env`.

Realms themselves are configuration, not console work: they are generated from `backend/keycloak/*-realm.json`
by `deploy/keycloak/build-realms.mjs` and applied on every release. A change made by hand in the console will be
overwritten by the next release unless it is a user (users are never deleted).

## 8. Switching email on

Email is off in the pilot: portal invites go by SMS, and Keycloak's self-service password reset is off.
Turning it on needs no code change and no new release of the images:

1. Open an account with a transactional email provider (Brevo, Mailgun, SES, Zoho).
2. Add their SPF and DKIM records to the domain.
3. Fill `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` in `.env` and set
   `MAIL_ENABLED=true`.
4. Release again. Both the platform and Keycloak's SMTP follow that one setting.

Clients with an email address then get an emailed set-your-password link instead of an SMS.

## 9. Changing a hostname

1. `APP_DOMAIN` and/or `AUTH_DOMAIN` in the server's `.env`.
2. `AUTH_DOMAIN` in your local `deploy/release.env` — it is compiled into the console.
3. DNS `A` records for the new names.
4. `deploy/release.sh v0.x.0`.

Everyone signs in again once; nothing else is affected. Keycloak's realm redirect URIs follow `APP_DOMAIN`
automatically.

## 10. The SMS allowlist

During the pilot `SMS_GATEWAY_ALLOWED_RECIPIENTS` holds the team's numbers in `255...` form, comma-separated, so
a stray notification cannot text a real customer. Any other recipient is refused and recorded as a FAILED
dispatch.

**Empty means every recipient is delivered to.** Empty it only when real customers are onboarding:

```bash
ssh deploy@SERVER 'cd platform && sed -i "s/^SMS_GATEWAY_ALLOWED_RECIPIENTS=.*/SMS_GATEWAY_ALLOWED_RECIPIENTS=/" .env \
  && docker compose --env-file .env --env-file versions.env -f compose.yml up -d backend'
```

## 11. Watching it

- An external uptime monitor (UptimeRobot or similar) on both hostnames, every 5 minutes.
- `ssh deploy@SERVER 'cd platform && ./status.sh'` — what is deployed, container health, disk, the age of the
  last backup, and the backend's own health including the scheduled-jobs and partition checks.
- `docker compose --env-file .env --env-file versions.env -f compose.yml logs -f backend` for the log.

## 12. Known limits of the pilot

- **One backend instance.** The scheduled jobs have no cluster lock; a second instance would duplicate them.
- **Mobile money is a mock.** Its callbacks are signed with the mock's own secret, so payments through that rail
  may not confirm. Record premiums as cash or field receipts until a real rail is integrated.
- **No two-factor login.** The realms carry a password policy and brute-force lockout; a second factor is a realm
  setting away when you want it.
- **No Grafana, no alerting, no JSON logs, no tracing.** The uptime monitor and `status.sh` are the pilot's
  whole observability story.
- **No staging server.** The dress rehearsal (running this stack locally) stands in for one.

## 13. Go-live checklist

- [ ] DNS resolves for both hostnames
- [ ] `.env` complete — the backend refuses to start on any leftover dev secret, so a successful release proves this
- [ ] First admin's password changed
- [ ] A `--scratch` restore has been run and reported data
- [ ] Uptime monitor on both hostnames
- [ ] `SMS_GATEWAY_ALLOWED_RECIPIENTS` set for the pilot
- [ ] `BACKUP_S3_*` filled in, and one nightly backup seen in the off-server bucket

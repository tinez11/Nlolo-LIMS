# Deployment guide

How to put the life platform on a server for a customer, from nothing to people signing in, and how to keep it
running afterwards. Written for the person doing it, with no prior knowledge of this platform assumed. Every step
says **what** to do, **why** it matters, and **how to know it worked**.

`deploy/README.md` is the short reference for the same scripts; come back to it once you know your way around.

---

## Part 1 — What you are building

One Debian 13 server runs the whole platform as seven ordinary system services (systemd), with no Docker:

```
                 Internet
                    │  (only ports 80 and 443 are open)
                    ▼
   ┌──────────────────────────────────────┐
   │ caddy   web server + HTTPS            │  app.yourdomain  → the console and its API
   │                                       │  auth.yourdomain → the sign-in pages, nothing else
   └──────┬───────────────────┬────────────┘
          ▼                   ▼
   ┌──────────────────┐  ┌──────────────────┐
   │ platform-backend │◄►│ authentik-server │  sign-in pages, passwords, sign-in codes
   │ the system       │  │ authentik-worker │  its configuration, "password changed" notices
   └─┬────────────┬───┘  └────────┬─────────┘
     ▼            ▼               ▼
┌──────────┐ ┌───────────┐ ┌─────────────────────────────┐
│ postgres │ │ seaweedfs │ │ platform-mock-mobile-money  │
│ all data │ │ documents │ │ stands in for M-Pesa        │
└──────────┘ └───────────┘ └─────────────────────────────┘
```

- **Two web addresses.** `APP_DOMAIN` (for example `app.insurer.co.tz`) is where everyone works: staff, agents and
  customers all sign in there. `AUTH_DOMAIN` (for example `auth.insurer.co.tz`) serves the sign-in pages. They are
  settings, so you can rename them later without touching code.
- **Only the web server faces the internet.** Every other service listens on the server itself only.
- **Exactly one backend.** The platform runs about 30 scheduled jobs (reminders, lapses, month-end). A second copy
  would run each one twice, so never start two.
- **Releases are pushed from your computer.** The backend and the console are built on your machine and uploaded;
  one command updates the server. Nobody edits files on the server by hand except the settings file.
- **The sign-in system, Authentik, is built on the server.** Its makers ship it only as a container; this platform
  runs without containers, so the server compiles it from its published source, once per Authentik version. The
  first release therefore takes an extra 30–60 minutes; later releases reuse that build.

### Money is still simulated — read this before going live

The mobile-money connection is a **mock**: it answers "accepted" to everything, and no money moves. Until a real
mobile-money integration is built:

- **Record premiums as cash, bank or field receipts**, not mobile money. A mobile-money collection would wait for
  finance's approval as if money had arrived.
- **Pay out by bank transfer or cash**, not mobile money. A mobile-money payout would be recorded as paid although
  nobody was paid.

Everything else (clients, policies, claims, accounts, reports, SMS, documents) is real and stored for good.

---

## Part 2 — What you need before you start

Gather all of this first. Most delays come from waiting on one of these halfway through.

### Accounts and things to buy

| What | Why | Example |
|---|---|---|
| **A server** with **Debian 13** ("trixie"), at least 4 vCPU, 8 GB RAM and 80 GB SSD | Runs everything. Debian 13 because the sign-in system is built there, and its makers build it on Debian 13 and nowhere else | Hetzner, DigitalOcean, Contabo, a local data centre |
| **A domain** you control, with access to its DNS settings | The two web addresses live under it | `insurer.co.tz` |
| **An SMS account with NextSMS** (messaging-service.co.tz), with an approved sender name | Every password, sign-in code and customer notice goes by SMS. Email is not used | Sender name such as `AMC` |
| **A free Cloudflare account**, with a **Turnstile** widget for `APP_DOMAIN` | "Forgot your password?" (the `/reset` page) checks each visitor is a person, not a script trying numbers. The backend will not start without it | Cloudflare dashboard › Turnstile › Add widget: hostname `app.insurer.co.tz`, mode **Managed**. Keep its **site key** and **secret key** |
| **Off-server backup storage** (any S3-compatible service) | If the server is lost, backups on it are lost too | Backblaze B2 is cheap and simple |
| **A password manager** or a company safe | Several passwords must exist somewhere other than the server | Bitwarden, 1Password |
| **The first administrator's mobile number** | Staff sign-in asks for a code texted to their phone, from the very first sign-in | `+255712345678` |
| **A support phone number** | Customers are told "if this wasn't you, call …" when a password changes | The insurer's call centre |

### Your own computer

The backend and the console are built on your machine. You need:

- **Git**, and on Windows **Git Bash** (comes with Git for Windows). Run every command in this guide in Git Bash,
  not PowerShell.
- **Java 21** (`java -version`) and **Node.js 22** (`node --version`): the backend and the console are built with
  them.
- **An SSH key** (`ls ~/.ssh/id_ed25519.pub`). If you have none: `ssh-keygen -t ed25519`, press Enter at each
  question.
- **The repository**, checked out on `main`, with no uncommitted changes. A release refuses to run otherwise, so
  every release names an exact version of the code.

---

## Part 3 — First deployment, step by step

### Step 1 — Get the server and let your key in

Create the server at your provider, choosing **Debian 13**. When asked for an SSH key, paste the content of
`~/.ssh/id_ed25519.pub`.

**Why:** the server will refuse password logins entirely, so your key is the way in.

**Check:** `ssh root@SERVER_IP` logs you in without asking for a password, and `cat /etc/debian_version` starts
with `13`.

### Step 2 — Prepare the server (once)

From the repository folder on your computer:

```bash
scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER_IP:
ssh root@SERVER_IP bash bootstrap-server.sh
```

This takes 5–10 minutes. It:

- installs the system's own pieces: PostgreSQL 16, Java, the Caddy web server, and the libraries the sign-in system
  is built against, plus automatic security updates;
- creates a user for each service, and a `deploy` user who logs in with your key only and may run admin commands
  (`sudo`); password logins are switched off;
- closes the firewall to everything except SSH (22) and the web (80, 443), and adds swap for the sign-in build;
- creates `/home/deploy/platform/.env`, the settings file, with **every password and secret already generated**;
- schedules the nightly backup.

**Check:** `ssh deploy@SERVER_IP` works. From now on you never need `root` again.

### Step 3 — Fill in the settings file

```bash
ssh deploy@SERVER_IP
nano platform/.env
```

The generated secrets are already there; leave them alone. Fill in the rest:

| Setting | What to put | Why it matters |
|---|---|---|
| `APP_DOMAIN` | `app.insurer.co.tz` | The address everyone uses |
| `AUTH_DOMAIN` | `auth.insurer.co.tz` | The sign-in pages' address |
| `ACME_EMAIL` | An address you read | Let's Encrypt writes there if a certificate is about to expire |
| `SMS_GATEWAY_AUTHORIZATION` | From NextSMS, **in quotes**: `"Basic abc123..."` | Lets the platform send texts. Without quotes, the space breaks the release |
| `SMS_GATEWAY_SENDER_NAME` | Your approved sender name | What the customer's phone shows as the sender |
| `SMS_GATEWAY_ALLOWED_RECIPIENTS` | Your team's numbers, `255...` without `+`, comma-separated | **Safety net while testing:** only these numbers receive texts. See Part 5 |
| `SUPPORT_PHONE` | The call-centre number | Required; the backend will not start without it |
| `TURNSTILE_SECRET` | The Turnstile widget's **secret key** | Required; `/reset` checks visitors with it |
| `FIRST_ADMIN_PHONE` | The first administrator's mobile, `+255...` | Their sign-in code is texted here |
| `FIRST_ADMIN_HOME_BRANCH` | Their branch code, e.g. `DSM` | Staff need a branch to post accounting entries |
| `BACKUP_S3_ENDPOINT`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY`, `BACKUP_S3_BUCKET` | From your backup storage | Sends an encrypted copy of every backup off the server |
| `RATE_LIMIT_EXEMPT` | Optional: the insurer's office addresses, in quotes, space-separated | Staff sharing one office address are not slowed by the sign-in limit. See Step 8 |

**Leave as they are:** `MAIL_ENABLED=false` (email is not used; everything goes by SMS), `STAFF_SIGN_IN_CODE=true`,
`SMS_GATEWAY_LIVE=true`, `PUBLIC_QUOTE_ENABLED=false` (the public quote link stays off until the insurer wants it),
`CADDY_GLOBAL_OPTIONS` empty.

**Copy these to your password manager now**, because they are needed if the server is ever lost:
`BACKUP_CRYPT_PASSWORD` (without it, no off-server backup can ever be read), `AUTHENTIK_BOOTSTRAP_PASSWORD` (the
sign-in system's administrator, Part 7) and `POSTGRES_PASSWORD`.

**Never commit or email this file.** It holds every key to the platform. The services never read it directly: each
release gives each service only its own settings.

**Check:** `grep -E '^(APP_DOMAIN|AUTH_DOMAIN|SUPPORT_PHONE|TURNSTILE_SECRET|FIRST_ADMIN_PHONE)=.' platform/.env`
shows five filled lines.

### Step 4 — Point the two addresses at the server

At your domain's DNS provider, add two `A` records, both pointing at the server's IP address:

```
app.insurer.co.tz    A    SERVER_IP
auth.insurer.co.tz   A    SERVER_IP
```

**Why:** the web server obtains free HTTPS certificates from Let's Encrypt automatically, and it can only do that
once these names lead to the server.

**Check:** `nslookup app.insurer.co.tz` and `nslookup auth.insurer.co.tz` both answer with the server's IP. This can
take from a few minutes to a few hours; do the next step meanwhile.

### Step 5 — Tell your computer where to release to

```bash
cp deploy/release.env.example deploy/release.env
```

Edit `deploy/release.env`:

```
DEPLOY_HOST=deploy@SERVER_IP
AUTH_DOMAIN=auth.insurer.co.tz          # the same as on the server
TURNSTILE_SITE_KEY=0x4AAAA...           # the Turnstile widget's SITE key (the public half)
```

**Why `AUTH_DOMAIN` twice:** the console is built with the sign-in address baked in, so a mismatch means nobody can
sign in. The Turnstile site key is baked in the same way. `release.env` is never committed and holds no secrets.

### Step 6 — Release

```bash
deploy/release.sh v1.0.0
```

The **first** release takes 45–90 minutes, most of it the server building the sign-in system. Later releases take a
few minutes. It prints a heading for each stage:

| Stage | What happens |
|---|---|
| `== building` | Builds the backend and the console on your computer |
| `== staging`, `== copying` | Sends them, the database changes, the sign-in configuration and the server scripts up |
| `== backup before` | Backs up the current data (skipped on the very first release, when there is none) |
| `== installing` | Puts the release in place, installs the pinned tools, writes each service its settings, builds the web server with its rate limits if this version is new (a few minutes) and checks it can load its configuration |
| `== database` | Creates the database the first time, then creates or updates every table |
| `== data services` | Starts the document store |
| `== identity` | Builds Authentik if this version is new (**the long part, the first time**), starts it, applies its configuration, starts the web server |
| `== first staff login` | Creates `platform.admin` and **prints a one-time password, once** |
| `== application` | Starts the backend |
| smoke check | Automatic checks (Part 7 explains each one) |

It stops at the first error and says which stage failed. The backend is only restarted after the database has been
updated and the sign-in system is up, so a failed release leaves the previous version running.

**Copy the one-time password the moment it appears.** It is shown only once.

**Check:** the last lines read `Released v1.0.0.` Then save the version marker:
`git push origin v1.0.0`. The script tags the version locally and leaves the push to you, once you are happy with
it.

### Step 7 — First sign-in

1. Open `https://app.insurer.co.tz/`.
2. Sign in as `platform.admin` with the one-time password.
3. Enter the code texted to `FIRST_ADMIN_PHONE`.
4. Choose a new password when asked.

**Then, straight away:**

- **Create a second administrator** under Administration › Users, with their own phone. If the only administrator is
  locked out, nobody can reset them: staff cannot reset their own password.
- **Administration › Organisation:** the company name, letterhead, signatories and branches. These appear on
  receipts and letters.
- **Communications › Message templates:** read every message once, in Swahili and English. The offer messages
  tell customers to pay to **Lipa Namba 000000**, which is a placeholder: replace it with the insurer's real Lipa
  Namba before any offer goes out.
- **Add staff**, each with a phone number, from Administration › Users.

### Step 8 — Prove it before real clients arrive

Work through this list on the live server. Each item has caught a real problem before.

- [ ] **Sign in on each workspace:** a staff member (with the texted code), an agent and a customer. Open a document
      on each. If something fails to load, press F12; the browser console names anything the security policy
      blocked.
- [ ] **Forgot your password?** Try it once as an agent or customer. A six-digit code should arrive by SMS, and the
      new password should sign in.
- [ ] **A real backup restored:** `ssh deploy@SERVER_IP 'cd platform && ./backup.sh manual'`, then
      `./restore.sh backups/STAMP --scratch` (STAMP is the folder name `backup.sh` printed). It restores into a
      scratch copy and reports what it found, touching nothing live.
- [ ] **The off-server copy can be read back:** the next morning, `./fetch-backup.sh` lists last night's backup;
      `./fetch-backup.sh daily/STAMP`, then `./restore.sh backups/STAMP --scratch`.
- [ ] **A rollback works:** after your second release, `deploy/release.sh --rollback v1.0.0`, then release the newer
      version again.
- [ ] **An uptime monitor** (UptimeRobot, free) on both addresses, alerting someone by SMS or phone.
- [ ] **Office addresses for the rate limits:** the web server limits each visitor address: 60 sign-in steps a
      minute (about 15 people signing in), 30 public-page calls and 600 console calls. An office where many staff
      share one internet address should be listed in `RATE_LIMIT_EXEMPT` in `.env`, in quotes and space-separated
      (e.g. `"196.41.0.10 41.59.12.0/28"`), then applied with a release or
      `ssh deploy@SERVER_IP 'cd platform && ./remote-release.sh --rollback "$(cat RELEASED)"'`. Ask the office's
      internet provider for its fixed address. The public pages stay limited for everyone.

---

## Part 4 — Everyday running

### Releasing an update

```bash
git checkout main && git pull
deploy/release.sh v1.1.0           # always a new, higher version number
git push origin v1.1.0             # once you have checked the release is good
```

Users may see one or two minutes of the site being unavailable while the backend restarts. Release outside office
hours. A release that moves `AUTHENTIK_VERSION` (in `deploy/server/versions.env`) builds the new Authentik first, so
it takes the long time again.

### Rolling back

```bash
deploy/release.sh --rollback v1.0.0
```

This puts the previous version back in minutes: the server keeps the last five releases and the last two Authentik
builds. **Database changes are not undone**: they only ever move forward. If a release changed the database in a way
the old version cannot read, restore the backup taken just before that release (the release printed its name,
`pre-v1.1.0`).

### Backups

Taken automatically **every night at 02:30** and **before every release**. Each backup holds the platform database,
the sign-in database and every document, because they belong together and must be restored together.

| Where | How many are kept |
|---|---|
| On the server | The last 7 |
| Off the server, encrypted | 14 daily and 8 weekly |

```bash
ssh deploy@SERVER_IP 'cd platform && ./backup.sh manual'                    # take one now
ssh deploy@SERVER_IP 'cd platform && ./restore.sh backups/STAMP --scratch'  # test one, touching nothing
ssh deploy@SERVER_IP 'cd platform && ./restore.sh backups/STAMP'            # REPLACE live data (asks first)
```

**Test-restore one backup every month.** A backup that has never been restored is not yet a backup.

### Adding people

- **Staff:** from the console, Administration › Users. Always add their phone, which their sign-in code goes to.
- **Agents:** onboard the agent in the console, then **Give a login** on the agent's page. Their username is their
  phone number, and their password arrives by SMS.
- **Customers:** from the client's page, **Invite to portal**. The same SMS process applies.
- **Regulators** have no screen for it; on the server:
  `./add-user.sh regulator tira.inspector --first-name Salum --last-name Tira`. It prints a one-time password.
- **A forgotten staff password:** an administrator resets it from Administration › Users, and the new one is texted to
  the staff member.

### Checking on it

```bash
ssh deploy@SERVER_IP 'cd platform && ./status.sh'
```

It shows the deployed version, whether each service is running, free disk space, the age of the last backup, and
whether the scheduled jobs are running. For a service's log:

```bash
ssh deploy@SERVER_IP
sudo journalctl -u platform-backend -f        # or authentik-server, authentik-worker, caddy, seaweedfs
```

---

## Part 5 — Opening SMS to real customers

While `SMS_GATEWAY_ALLOWED_RECIPIENTS` lists your team's numbers, **only those numbers receive texts**. A text to
anyone else is not sent and shows in Messages sent as "not on the SMS testing allowlist". This protects real people
from test messages while you set up.

When the insurer starts onboarding real clients, empty it, which means "send to everyone", and release the current
version again so the backend gets the new setting:

```bash
ssh deploy@SERVER_IP 'cd platform && sed -i "s/^SMS_GATEWAY_ALLOWED_RECIPIENTS=.*/SMS_GATEWAY_ALLOWED_RECIPIENTS=/" .env \
  && ./remote-release.sh --rollback "$(cat RELEASED)"'
```

(`--rollback` to the version already running re-applies the settings without a new build.)

**Check:** in the console, Messages sent shows "SMS: live — reaching customers".

---

## Part 6 — Changing an address later

1. Change `APP_DOMAIN` and/or `AUTH_DOMAIN` in the server's `.env`.
2. Change `AUTH_DOMAIN` in your `deploy/release.env`, and the Turnstile widget's hostname if `APP_DOMAIN` changed.
3. Add DNS `A` records for the new names.
4. Release a new version.

Everyone signs in once more; nothing else changes.

---

## Part 7 — The sign-in system (Authentik)

### Changing the sign-in

Every part of the sign-in — its pages, the texted code, the rules, the look — is written in one file in the code,
`deploy/authentik/blueprints/production/lifeplatform.yaml` (a "blueprint"). Each release applies it, and the release
stops if it does not apply.

**Change the sign-in only there, then release.** Authentik's admin screens can change the same things, but a change
made there stays only until the blueprint file next changes, when the release overwrites it without warning. Until
then the server quietly differs from the code, which is the hardest kind of problem to find.

**Check after a release:** the smoke check confirms the blueprint's status is `successful`.

### The Authentik admin

It is deliberately not on the internet: the public address answers 404 to everything except the sign-in pages. To
reach it, tunnel through SSH from your computer:

```bash
ssh -L 9000:127.0.0.1:9000 deploy@SERVER_IP
```

Then open **`http://localhost:9000/if/flow/default-authentication-flow/?next=/if/admin/`** and sign in as `akadmin`
with `AUTHENTIK_BOOTSTRAP_PASSWORD` from `.env` (or your password manager).

**Why that address:** the platform's own sign-in page refuses administrators, so that a stolen administrator password
cannot be used from the internet. The stock sign-in page above is reachable only through the tunnel.

Use the admin to look (events, users, sessions), not to configure: see "Changing the sign-in" above.

### Rotating the signing key

Every sign-in token is signed with one key, and the platform accepts only tokens signed with the key Authentik
currently publishes. **Changing the key signs everyone out at once.** Plan it: announce it, and do it at a quiet hour.

1. Through the admin (above): System › Certificates › **Generate**, named for example `lifeplatform-signing-2027`,
   valid for several years.
2. In `deploy/authentik/blueprints/production/lifeplatform.yaml`, the provider's `signing_key` line names the
   certificate to use: change its name to the new one.
3. Release. From that moment every existing session ends; people sign in again as usual.

Do this if the key may have leaked (a lost backup, a former administrator), and otherwise every few years.

### Updating Authentik

Change `AUTHENTIK_VERSION` (and, if its release notes say so, the build tools listed beside it) in
`deploy/server/versions.env`, read Authentik's release notes for anything the blueprint uses, and release. The server
builds the new version beside the old one and only switches once it is built and healthy; a rollback switches back.

---

## Part 8 — When something goes wrong

### The release stopped

The last `==` heading says which stage failed; the lines under it say why.

| Message | Meaning | What to do |
|---|---|---|
| `Uncommitted changes` | Your copy of the code has unsaved edits | Commit or stash them, then release again |
| `Tag v1.x.0 already exists` | That version number was used | Use the next number |
| `deploy/release.env is missing`, or `TURNSTILE_SITE_KEY is empty` | Step 5 not done | Do Step 5 |
| `Set FIRST_ADMIN_PHONE in .env` | Required setting empty | Fill it, release again with a new number |
| A setting "not found" / "command not found" in a strange place | A value with a space has no quotes in `.env` | Put quotes around it (Step 3) |
| A failure during `== identity` while building | Authentik's build failed: usually a download, or the server ran out of memory or disk | Read the lines above; check `df -h` and `free -h`. Releasing again resumes from the start of the build |
| `The lifeplatform blueprint did not apply` | The sign-in configuration has an error | The lines above are Authentik's own reason |
| `... did not become ready in three minutes` | A service would not start | The log printed under it names the reason: often a missing setting, as the backend refuses to start with development passwords or missing required values |

### The smoke check failed

The last step asks a list of questions. Its message tells you which one failed:

| Message | What it means |
|---|---|
| `does not serve the console` | The web server is not answering: DNS not pointing here yet, or caddy not running |
| `/api without a token answered …, expected 401` | The web server cannot reach the backend |
| `the backend does not report itself UP` | The backend started but something it needs is unhealthy; check its log |
| `Authentik does not report issuer …` | The sign-in configuration is missing, or `AUTH_DOMAIN` differs between the server and `release.env`: nobody could sign in |
| `the sign-in page answered …` | Authentik is not serving the platform's sign-in page |
| `…/oauth/revoke/ does not reach Authentik` | Signing out would leave the session's refresh token alive; the web server's configuration is wrong |
| `… answered … from the internet, expected 404` | Something other than the sign-in pages is reachable on the sign-in address. It must not be |
| `lifeplatform blueprint is '…', not successful` | The sign-in configuration did not fully apply |
| `dev test users exist in Authentik` | Logins from the developers' test setup are on this server, with a password everyone knows. Delete them in the admin (Part 7) |

### Users cannot sign in

- **"Invalid password"** for everyone: check the address in the browser is exactly `APP_DOMAIN`.
- **No sign-in code arrives** (staff): check the staff member's phone in Administration › Users; check
  `SMS_GATEWAY_ALLOWED_RECIPIENTS` includes them while testing; check Messages sent for the reason.
- **"Too many wrong passwords. Wait 15 minutes, then try again."** Five wrong passwords for a login within 15
  minutes lock it, whoever typed them and from wherever, until the oldest of them is 15 minutes old. Even the right
  password is refused meanwhile. It lifts on its own; there is nothing to reset. If someone is locked out again and
  again without having tried, someone else is guessing their password: look at the sign-in events in the Authentik
  admin (Part 7).

---

## Part 9 — Known limits today

- **Mobile money is simulated** (Part 1). Use cash, bank and field receipts, and bank or cash payouts.
- **One backend only.** It cannot be scaled to two servers yet.
- **No built-in alerting.** The uptime monitor and `status.sh` are what you have; check `status.sh` weekly.
- **No staging server.** Test a release on a copy (or at a quiet hour), knowing rollback is one command.
- **Agents and customers sign in with a password only.** Staff also get a texted code.
- **The sign-in system is built from source** on the server, a way of installing it its makers do not support:
  an Authentik update is a build, tried first at a quiet hour, with the previous build kept for a rollback.

---

## Words used in this guide

| Word | Meaning |
|---|---|
| **Service** | One program the server keeps running, such as the database or the backend (managed by systemd) |
| **Release** | One version of the whole platform, named like `v1.2.0` |
| **Migration** | An automatic change to the database's tables that comes with a release |
| **Authentik** | The sign-in system: passwords, texted codes, password resets |
| **Blueprint** | The file in the code that holds Authentik's whole configuration |
| **Caddy** | The web server; it also obtains the HTTPS certificates |
| **Smoke check** | Quick automatic checks at the end of a release that the basics work |
| **`.env`** | The settings file on the server, holding addresses, passwords and keys |
| **Allowlist** | The numbers allowed to receive SMS while testing |

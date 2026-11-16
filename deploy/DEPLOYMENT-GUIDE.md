# Deployment guide

How to put the life platform on a server for a customer, from nothing to people signing in, and how to keep it
running afterwards. Written for the person doing it, with no prior knowledge of this platform assumed. Every step
says **what** to do, **why** it matters, and **how to know it worked**.

`deploy/README.md` is the short reference for the same scripts; come back to it once you know your way around.

---

## Part 1 — What you are building

One Linux server runs the whole platform in six Docker containers:

```
                 Internet
                    │  (only ports 80 and 443 are open)
                    ▼
   ┌──────────────────────────────────────┐
   │ caddy   web server + HTTPS            │  app.yourdomain  → the console and its API
   │         (the console is built in)     │  auth.yourdomain → the sign-in pages
   └──────┬───────────────────┬────────────┘
          ▼                   ▼
   ┌────────────┐      ┌────────────┐
   │ backend    │◄────►│ keycloak   │  sign-in, passwords, reset codes
   │ the system │      └─────┬──────┘
   └─┬────────┬─┘            │
     ▼        ▼              ▼
┌──────────┐ ┌─────────────┐ ┌───────────────────────┐
│ postgres │ │ objectstore │ │ mock-mobile-money     │
│ all data │ │ documents   │ │ stands in for M-Pesa  │
└──────────┘ └─────────────┘ └───────────────────────┘
```

- **Two web addresses.** `APP_DOMAIN` (for example `app.insurer.co.tz`) is where everyone works: staff, agents and
  customers all sign in there. `AUTH_DOMAIN` (for example `auth.insurer.co.tz`) serves the sign-in pages. They are
  settings, so you can rename them later without touching code.
- **Only the web server faces the internet.** The database, documents and backend are reachable only from inside
  the server.
- **Exactly one backend.** The platform runs about 30 scheduled jobs (reminders, lapses, month-end). A second copy
  would run each one twice, so never start two.
- **Releases are pushed from your computer.** You build the platform on your machine, upload it, and one command
  updates the server. Nobody edits files on the server by hand except the settings file.

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
| **A server** with Ubuntu 24.04 LTS, at least 4 vCPU, 8 GB RAM and 80 GB SSD | Runs everything | Hetzner, DigitalOcean, Contabo, a local data centre |
| **A domain** you control, with access to its DNS settings | The two web addresses live under it | `insurer.co.tz` |
| **An SMS account with NextSMS** (messaging-service.co.tz), with an approved sender name | Every password, sign-in code and customer notice goes by SMS. Email is not used | Sender name such as `AMC` |
| **A GitHub account** with access to the platform's repository and a token with `write:packages` and `read:packages` | The built platform is stored in GitHub's registry and pulled by the server | |
| **Off-server backup storage** (any S3-compatible service) | If the server is lost, backups on it are lost too | Backblaze B2 is cheap and simple |
| **A password manager** or a company safe | Several passwords must exist somewhere other than the server | Bitwarden, 1Password |
| **The first administrator's mobile number** | Staff sign-in asks for a code texted to their phone, from the very first sign-in | `+255712345678` |
| **A support phone number** | Customers are told "if this wasn't you, call …" when a password changes | The insurer's call centre |

### Your own computer

The release is built on your machine. You need:

- **Git**, and on Windows **Git Bash** (comes with Git for Windows). Run every command in this guide in Git Bash,
  not PowerShell.
- **Docker Desktop**, running. The four platform images are built with it.
- **Node.js 22** (`node --version`). One step generates the sign-in configuration with it.
- **An SSH key** (`ls ~/.ssh/id_ed25519.pub`). If you have none: `ssh-keygen -t ed25519`, press Enter at each
  question.
- **The repository**, checked out on `main`, with no uncommitted changes. A release refuses to run otherwise, so
  every release names an exact version of the code.

---

## Part 3 — First deployment, step by step

### Step 1 — Get the server and let your key in

Create the server at your provider. When asked for an SSH key, paste the content of `~/.ssh/id_ed25519.pub`.

**Why:** the server will refuse password logins entirely, so your key is the way in.

**Check:** `ssh root@SERVER_IP` logs you in without asking for a password.

### Step 2 — Prepare the server (once)

From the repository folder on your computer:

```bash
scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER_IP:
ssh root@SERVER_IP bash bootstrap-server.sh
```

This takes a few minutes. It:

- installs Docker and automatic security updates;
- creates a `deploy` user who logs in with your key only, and switches off password logins;
- closes the firewall to everything except SSH (22) and the web (80, 443);
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
| `IMAGE_PREFIX` | `ghcr.io/YOUR-GITHUB-NAME/platform` | Where the server fetches the platform from |
| `SMS_GATEWAY_AUTHORIZATION` | From NextSMS, **in quotes**: `"Basic abc123..."` | Lets the platform send texts. Without quotes, the space breaks the release |
| `SMS_GATEWAY_SENDER_NAME` | Your approved sender name | What the customer's phone shows as the sender |
| `SMS_GATEWAY_ALLOWED_RECIPIENTS` | Your team's numbers, `255...` without `+`, comma-separated | **Safety net while testing:** only these numbers receive texts. See Part 5 |
| `SUPPORT_PHONE` | The call-centre number | Required; the backend will not start without it |
| `FIRST_ADMIN_PHONE` | The first administrator's mobile, `+255...` | Their sign-in code is texted here |
| `FIRST_ADMIN_HOME_BRANCH` | Their branch code, e.g. `DSM` | Staff need a branch to post accounting entries |
| `BACKUP_S3_ENDPOINT`, `BACKUP_S3_ACCESS_KEY`, `BACKUP_S3_SECRET_KEY`, `BACKUP_S3_BUCKET` | From your backup storage | Sends an encrypted copy of every backup off the server |

**Leave as they are:** `MAIL_ENABLED=false` (email is not used; everything goes by SMS), `STAFF_SIGN_IN_CODE=true`,
`SMS_GATEWAY_LIVE=true`, `CADDY_GLOBAL_OPTIONS` and `EXTRA_CA_FILE` empty, `RELEASE` empty (the release fills it).

**Copy these to your password manager now**, because they are needed if the server is ever lost:
`BACKUP_CRYPT_PASSWORD` (without it, no off-server backup can ever be read), `KC_ADMIN_PASSWORD` and
`POSTGRES_PASSWORD`.

**Never commit or email this file.** It holds every key to the platform.

**Check:** `grep -E '^(APP_DOMAIN|AUTH_DOMAIN|SUPPORT_PHONE|FIRST_ADMIN_PHONE)=' platform/.env` shows four filled
lines.

### Step 4 — Point the two addresses at the server

At your domain's DNS provider, add two `A` records, both pointing at the server's IP address:

```
app.insurer.co.tz    A    SERVER_IP
auth.insurer.co.tz   A    SERVER_IP
```

**Why:** the web server obtains free HTTPS certificates from Let's Encrypt automatically, and it can only do that
once these names lead to the server.

**Check:** `nslookup app.insurer.co.tz` and `nslookup auth.insurer.co.tz` both answer with the server's IP. This can
take from a few minutes to a few hours; do the next steps meanwhile.

### Step 5 — Let the server fetch the platform

Create a GitHub token (Settings → Developer settings → Personal access tokens) with `read:packages`, then:

```bash
ssh deploy@SERVER_IP
docker login ghcr.io -u YOUR-GITHUB-NAME      # paste the token as the password
```

On your own computer, log in the same way with a token that has `write:packages`, so you can upload:

```bash
docker login ghcr.io -u YOUR-GITHUB-NAME
```

**Check:** both print `Login Succeeded`.

### Step 6 — Tell your computer where to release to

```bash
cp deploy/release.env.example deploy/release.env
```

Edit `deploy/release.env`:

```
DEPLOY_HOST=deploy@SERVER_IP
DEPLOY_MODE=remote
IMAGE_PREFIX=ghcr.io/YOUR-GITHUB-NAME/platform     # the same as on the server
AUTH_DOMAIN=auth.insurer.co.tz                       # the same as on the server
```

**Why `AUTH_DOMAIN` twice:** the console is built with the sign-in address baked in, so a mismatch means nobody can
sign in. `release.env` is never committed and holds no secrets.

### Step 7 — Release

```bash
deploy/release.sh v1.0.0
```

The first time takes 15–30 minutes, mostly building. It prints a heading for each stage:

| Stage | What happens |
|---|---|
| `== building` | Builds the four images: backend, database, sign-in, web |
| `== pushing` | Uploads them to GitHub's registry |
| `== copying` | Sends the server scripts, database migrations and sign-in settings |
| `== backup before` | Backs up the current data (skipped on the very first release, when there is none) |
| `== data services` | Starts the database, the document store and the money mock |
| `== database` | Creates or updates every table |
| `== identity` | Starts sign-in and applies its configuration |
| `== first staff login` | Creates `staff.admin` and **prints a one-time password, once** |
| `== application` | Starts the backend |
| smoke check | Five automatic checks (Part 7 explains each one) |

It stops at the first error and says which stage failed. The backend is only restarted after the database has been
updated successfully, so a failed release leaves the previous version running.

**Copy the one-time password the moment it appears.** It is shown only once.

**Check:** the last lines read `Released v1.0.0.` Then save the version marker:
`git push origin v1.0.0`. The script tags the version locally and leaves the push to you, once you are happy with
it.

### Step 8 — First sign-in

1. Open `https://app.insurer.co.tz/`.
2. Sign in as `staff.admin` with the one-time password.
3. Enter the code texted to `FIRST_ADMIN_PHONE`.
4. Choose a new password.

**Then, straight away:**

- **Create a second administrator** under Administration › Users, with their own phone. If the only administrator is
  locked out, nobody can reset them: staff cannot reset their own password.
- **Administration › Organisation:** the company name, letterhead, signatories and branches. These appear on
  receipts and letters.
- **Communications › Message templates:** read every message once, in Swahili and English. The offer messages
  tell customers to pay to **Lipa Namba 000000**, which is a placeholder: replace it with the insurer's real Lipa
  Namba before any offer goes out.
- **Add staff**, each with a phone number, from Administration › Users.

### Step 9 — Prove it before real clients arrive

Work through this list on the live server. Each item has caught a real problem before.

- [ ] **Sign in on each workspace:** a staff member (with the texted code), an agent and a customer. Open a document
      on each. If something fails to load, press F12; the browser console names anything the security policy
      blocked.
- [ ] **Forgot your password?** Try it once as an agent or customer. A six-digit code should arrive by SMS.
- [ ] **A real backup restored:** `ssh deploy@SERVER_IP 'cd platform && ./backup.sh manual'`, then
      `./restore.sh backups/STAMP --scratch` (STAMP is the folder name `backup.sh` printed). It restores into a scratch copy
      and reports what it found, touching nothing live.
- [ ] **The off-server copy can be read back:** the next morning, `./fetch-backup.sh` lists last night's backup;
      `./fetch-backup.sh daily/STAMP`, then `./restore.sh backups/STAMP --scratch`.
- [ ] **A rollback works:** after your second release, `deploy/release.sh --rollback v1.0.0`, then release the newer
      version again.
- [ ] **An uptime monitor** (UptimeRobot, free) on both addresses, alerting someone by SMS or phone.
- [ ] **Rate limiting:** put Cloudflare (free plan) in front of both addresses. Nothing else stops one person trying
      many passwords across many accounts.

---

## Part 4 — Everyday running

### Releasing an update

```bash
git checkout main && git pull
deploy/release.sh v1.1.0           # always a new, higher version number
git push origin v1.1.0             # once you have checked the release is good
```

Users may see one or two minutes of the site being unavailable while the backend restarts. Release outside office
hours.

### Rolling back

```bash
deploy/release.sh --rollback v1.0.0
```

This puts the previous version back in minutes. **Database changes are not undone**: they only ever move forward. If
a release changed the database in a way the old version cannot read, restore the backup taken just before that
release (the release printed its name, `pre-v1.1.0`).

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
- **A forgotten staff password:** an administrator resets it from Administration › Users, and the new one is texted to the
  staff member.

### Checking on it

```bash
ssh deploy@SERVER_IP 'cd platform && ./status.sh'
```

It shows the deployed version, the health of every container, free disk space, the age of the last backup, and
whether the scheduled jobs are running. For the backend's log:

```bash
ssh deploy@SERVER_IP
cd platform && docker compose --env-file .env --env-file versions.env -f compose.yml logs -f backend
```

---

## Part 5 — Opening SMS to real customers

While `SMS_GATEWAY_ALLOWED_RECIPIENTS` lists your team's numbers, **only those numbers receive texts**. A text to
anyone else is not sent and shows in Messages sent as "not on the SMS testing allowlist". This protects real people
from test messages while you set up.

When the insurer starts onboarding real clients, empty it, which means "send to everyone":

```bash
ssh deploy@SERVER_IP 'cd platform && sed -i "s/^SMS_GATEWAY_ALLOWED_RECIPIENTS=.*/SMS_GATEWAY_ALLOWED_RECIPIENTS=/" .env \
  && docker compose --env-file .env --env-file versions.env -f compose.yml up -d backend'
```

**Check:** in the console, Messages sent shows "SMS: live — reaching customers".

---

## Part 6 — Changing an address later

1. Change `APP_DOMAIN` and/or `AUTH_DOMAIN` in the server's `.env`.
2. Change `AUTH_DOMAIN` in your `deploy/release.env`.
3. Add DNS `A` records for the new names.
4. Release a new version.

Everyone signs in once more; nothing else changes.

---

## Part 7 — When something goes wrong

### The release stopped

The last `==` heading says which stage failed; the lines under it say why.

| Message | Meaning | What to do |
|---|---|---|
| `Uncommitted changes` | Your copy of the code has unsaved edits | Commit or stash them, then release again |
| `Tag v1.x.0 already exists` | That version number was used | Use the next number |
| `deploy/release.env is missing` | Step 6 not done | Do Step 6 |
| `denied` / `unauthorized` while pushing or pulling | Registry login missing or expired | Repeat Step 5 |
| `Set FIRST_ADMIN_PHONE in .env` | Required setting empty | Fill it, release again with a new number |
| A setting "not found" / "command not found" in a strange place | A value with a space has no quotes in `.env` | Put quotes around it (Step 3) |
| The backend never becomes healthy | Usually a missing or leftover setting: the backend refuses to start with development passwords or missing required values | Read its log (Part 4); the first error names the setting |

### The smoke check failed

The last step asks five questions. Its message tells you which one failed:

| Message | What it means |
|---|---|
| `does not serve the console` | The web server is not answering: DNS not pointing here yet, or caddy not running |
| `/api without a token answered …, expected 401` | The web server cannot reach the backend |
| `the backend does not report itself UP` | The backend started but something it needs is unhealthy; check its log |
| `the realm does not report issuer …` | `AUTH_DOMAIN` differs between the server and `release.env`: nobody could sign in |
| `Keycloak's master realm answered … expected 404` | Keycloak's administrator sign-in is reachable from the internet; it must not be |

### Users cannot sign in

- **"Invalid username or password"** for everyone: check the address in the browser is exactly `APP_DOMAIN`.
- **No sign-in code arrives** (staff): check the staff member's phone in Administration › Users; check
  `SMS_GATEWAY_ALLOWED_RECIPIENTS` includes them while testing; check Messages sent for the reason.
- **Locked out after five wrong passwords:** the lock lifts on its own: one minute at first, growing to at most 15 minutes with further attempts. An administrator can
  also set a new password.

### The Keycloak admin console

It is deliberately not on the internet. To reach it, tunnel through SSH:

```bash
ssh -L 8081:localhost:8081 deploy@SERVER_IP
```

Then open `http://localhost:8081/admin` and sign in as user `admin` with `KC_ADMIN_PASSWORD` from `.env`. Changes
made there, other than to users, are overwritten by the next release; the sign-in configuration lives in the code.

---

## Part 8 — Known limits today

- **Mobile money is simulated** (Part 1). Use cash, bank and field receipts, and bank or cash payouts.
- **One backend only.** It cannot be scaled to two servers yet.
- **No built-in alerting.** The uptime monitor and `status.sh` are what you have; check `status.sh` weekly.
- **No staging server.** Test a release on a copy (or at a quiet hour), knowing rollback is one command.
- **Agents and customers sign in with a password only.** Staff also get a texted code.

---

## Words used in this guide

| Word | Meaning |
|---|---|
| **Container** | One self-contained program running on the server, such as the database or the backend |
| **Image** | The packaged form of a container, built on your computer and uploaded |
| **Release** | One version of the whole platform, named like `v1.2.0` |
| **Migration** | An automatic change to the database's tables that comes with a release |
| **Keycloak** | The sign-in system: passwords, texted codes, password resets |
| **Caddy** | The web server; it also obtains the HTTPS certificates |
| **Smoke check** | Quick automatic checks at the end of a release that the basics work |
| **`.env`** | The settings file on the server, holding addresses, passwords and keys |
| **Allowlist** | The numbers allowed to receive SMS while testing |

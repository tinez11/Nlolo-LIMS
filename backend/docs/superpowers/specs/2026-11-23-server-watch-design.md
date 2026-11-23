# The server watches itself and texts — design

**Date:** 2026-11-23
**Status:** approved in conversation, awaiting review of this written spec
**Part of:** the production-readiness items, second of three (after edge rate limiting, before branch scoping).
Built on branch `server-watch`, stacked on `edge-rate-limit` (both change `remote-release.sh` and `smoke.sh`).

## Why

Nothing on the server watches it. `status.sh` knows the services, the disk, the backup's age and the backend's own
health, but only when someone runs it. The failures that hurt are the silent ones: the scheduled jobs missing for
weeks, a backup that stopped, a disk filling, a certificate whose renewal broke.

## Decisions (the user, 2026-11-23)

1. **SMS through the existing NextSMS account to on-call numbers** (option a). Not an outside uptime service, not both.
   Accepted cost: if the whole server or its network is down, nothing can send the text.
2. **Checks 1-6 below.** Not a rate-limit burst alarm: that needs Caddy request logging, a separate decision.
3. **A watcher script on a systemd timer** (approach A), not Prometheus/Alertmanager or Monit.
4. **Two runs before a text, a pause during releases, one text per run, reminders every 6 hours, a weekly
   "still watching" text.**

## The checks (every 5 minutes)

Each is a function that prints nothing when fine, or one short line naming the problem.

| # | Key | Problem when |
|---|---|---|
| 1 | `services` | any of `postgresql@<major>-main`, `seaweedfs`, `platform-mock-mobile-money`, `authentik-server`, `authentik-worker`, `platform-backend`, `caddy` is not active (names them) |
| 2 | `backend` | `http://127.0.0.1:9090/actuator/health` is not `"status":"UP"`, or does not answer. The endpoint shows no details (`show-details: never`), so the line names any scheduled job or partition missing from `SELECT item FROM ops.platform_readiness() WHERE NOT present` (run with `psql_`, as the backend's `ScheduledJobsHealthIndicator` reads it) |
| 3 | `site` | `https://APP_DOMAIN/` does not serve the console, or `https://AUTH_DOMAIN/if/flow/lifeplatform-sign-in/` does not answer 200, through Caddy on this machine (`--resolve` to 127.0.0.1, as `smoke.sh` does) |
| 4 | `disk` | `/` is 85% full or more (gives the percentage) |
| 5 | `backup` | no backup folder with a `.complete` marker in the last 30 hours; or off-server backup is configured (`BACKUP_S3_ENDPOINT` set) and `backups/.offsite-ok` is older than 30 hours or missing |
| 6 | `certificates` | either domain's certificate, read with `openssl s_client` against 127.0.0.1:443 with that server name, ends within 14 days (gives the date) |

`backup.sh` gains: `touch "$DIR/.complete"` after the dumps succeed, and `touch backups/.offsite-ok` after the
off-server copy succeeds.

## When a text goes out

- State per check under `~/platform/watch/<key>`: whether the problem was seen on the last run, since when, when it
  was last texted, and its line.
- **Problem:** seen on two runs in a row → texted. Seen once → nothing.
- **Still a problem:** texted again 6 hours after its last text.
- **Cleared:** one text once a texted problem is no longer seen. A problem never texted (seen once) clears silently.
- **One text per run** holding every new problem, reminder and clearance, in that order. Format:
  - `Nlolo APP_DOMAIN PROBLEM: backend health DOWN (missing: commission close); disk 91% full.`
  - `Nlolo APP_DOMAIN STILL: disk 91% full (since Mon 09:10).`
  - `Nlolo APP_DOMAIN OK again: backend health (was down 25 min).`
- **Weekly:** Monday, first run at or after 08:00 Africa/Dar_es_Salaam, once per week (`watch/weekly` holds the
  date): `Nlolo APP_DOMAIN watcher: all 6 checks OK.` or `... watcher: N open: <keys>.`
- **Pause:** while `~/platform/watch/paused` exists and is younger than 2 hours, a run does nothing (no checks, no
  state change). `remote-release.sh` creates it at its start and removes it on success; a crashed release's pause
  lapses after 2 hours. Rollback likewise.
- **Sending:** POST `SMS_GATEWAY_BASE_URL/api/sms/v1/text/single` with `Authorization: SMS_GATEWAY_AUTHORIZATION`,
  JSON `{"from": SMS_GATEWAY_SENDER_NAME, "to": <255... without +>, "text": ..., "reference": <uuid>}`, to each number
  in `ALERT_PHONES` (space- or comma-separated; `+255...`, `0...` or `255...`). Accepted when the reply's
  `messages[0].status.groupName` is `PENDING` or `DELIVERED` (the backend's `SmsGatewayAdapter` rules). Not subject
  to `SMS_GATEWAY_ALLOWED_RECIPIENTS`: these are the operator's own numbers. When `SMS_GATEWAY_LIVE` is not `true`,
  the text is only written to the journal.
- **A failed send:** logged to the journal; the state is not marked texted, so the next run sends it again.

## Commands

- `./watch.sh` — one run (what the timer calls).
- `./watch.sh --check` — runs the six checks once and prints each result; no state, no text. Exit 1 if any fails.
- `./watch.sh --test-text` — sends `Nlolo APP_DOMAIN watcher: test message` to `ALERT_PHONES`; exit 1 if any send is
  refused.

## Installation

- `deploy/server/systemd/platform-watch.service` (Type=oneshot, User=deploy, `~/platform/watch.sh`) and
  `platform-watch.timer` (every 5 minutes, `Persistent=false`). Installed by `install_units` with the other units,
  enabled by the release.
- `.env` gains `ALERT_PHONES=`. `remote-release.sh` refuses to release while it is empty.
- `smoke.sh` fails when `platform-watch.timer` is not active.
- The checks and the texting rules are separate: `watch.sh` holds the checks and the command handling; the rules live
  in functions a test can drive with stand-in checks, a fixed clock (`WATCH_NOW`, epoch seconds) and a sender that
  writes to a file (`WATCH_OUTBOX`).

## Testing

- `deploy/test/watch-test.sh`, Git Bash, no Docker: drives `watch.sh` with stand-in checks and asserts:
  seen once → no text; seen twice → one text; unchanged → silent until 6 h, then one reminder; cleared → one
  "OK again" with the duration; two problems together → one text; pause → nothing, pause older than 2 h → ignored;
  failed send → retried next run; Monday 08:00 → one weekly text, not again that week; `SMS_GATEWAY_LIVE` false →
  nothing sent.
- On the server: `./watch.sh --check` (the first release prints it; the guide tells the operator to run it) and
  `./watch.sh --test-text`; Step 8 gains "you received the test text".

## Docs

- `deploy/DEPLOYMENT-GUIDE.md`: Step 3 table gains `ALERT_PHONES`; Step 8's "uptime monitor (UptimeRobot)" item
  becomes the watcher and its test text, naming the accepted gap (a dead server cannot text); Part 4 "Checking on it"
  mentions `./watch.sh --check`.
- `deploy/README.md` section 7 lists `watch.sh`.

## Out of scope

- An outside check for "the whole server is down".
- Counting rate-limit refusals (needs Caddy request logging).
- Any change to the backend.

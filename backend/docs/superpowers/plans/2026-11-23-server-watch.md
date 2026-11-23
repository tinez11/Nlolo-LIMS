# Server Watch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every 5 minutes the server checks six things and texts `ALERT_PHONES` through NextSMS when one breaks, clears, or stays broken; a weekly text proves the watcher lives.

**Architecture:** `deploy/server/watch-rules.sh` holds the texting rules (state per check, two-run confirmation, 6-hour reminders, clearances, pause, weekly, one text per run, retry on failed send) as functions over pluggable `check_<key>` functions, a clock (`WATCH_NOW`) and a sender. `deploy/server/watch.sh` holds the six real checks, the NextSMS sender and the commands, and is run by `platform-watch.timer`. `deploy/test/watch-test.sh` drives the rules with stand-in checks and a file outbox.

**Tech Stack:** bash, curl, openssl, systemd timers, NextSMS HTTP API.

**Spec:** `backend/docs/superpowers/specs/2026-11-23-server-watch-design.md`

## Global Constraints

- Checks every 5 minutes; keys in this order: `services backend site disk backup certificates`.
- A problem is texted on its **second** consecutive run; reminder **21600 s** (6 h) after its last text; cleared → one text, only if it had been texted.
- One text per run: `Nlolo <APP_DOMAIN> PROBLEM: a; b. STILL: c (since Mon 09:10). OK again: d (was down 25 min).` (only the parts that apply, in that order).
- Weekly: Monday, at or after 08:00 `Africa/Dar_es_Salaam`, once per ISO week: `Nlolo <APP_DOMAIN> watcher: all 6 checks OK.` or `Nlolo <APP_DOMAIN> watcher: N open: <keys>.`
- Pause file `<WATCH_DIR>/paused` holds an epoch; honoured while younger than 7200 s.
- Sender: `POST $SMS_GATEWAY_BASE_URL/api/sms/v1/text/single`, header `Authorization: $SMS_GATEWAY_AUTHORIZATION`, JSON `{"from","to","text","reference"}`, `to` = 255… without `+`; accepted when `groupName` is `PENDING` or `DELIVERED`. Ignores `SMS_GATEWAY_ALLOWED_RECIPIENTS`. `SMS_GATEWAY_LIVE` not `true` → journal only.
- Edit files with Edit/Write; commit with explicit paths; run tests from Git Bash.

## Files

| File | Responsibility |
|---|---|
| `deploy/server/watch-rules.sh` (create) | The texting rules; sourced |
| `deploy/test/watch-test.sh` (create) | Proves the rules with stand-ins |
| `deploy/server/watch.sh` (create) | The six checks, the NextSMS sender, `--check`, `--test-text`, the timer's run |
| `deploy/server/backup.sh` (modify) | `.complete` and `.offsite-ok` markers |
| `deploy/server/systemd/platform-watch.service`, `.timer` (create) | Run it every 5 minutes as `deploy` |
| `deploy/server/remote-release.sh` (modify) | Pause during releases; refuse an empty `ALERT_PHONES`; install and enable the timer; print `--check` |
| `deploy/server/smoke.sh` (modify) | Fail when the timer is not active |
| `deploy/server/.env.example`, `deploy/bootstrap-server.sh`, `deploy/DEPLOYMENT-GUIDE.md`, `deploy/README.md` (modify) | `ALERT_PHONES` and the watcher |

---

### Task 1: The texting rules, test first

**Files:**
- Create: `deploy/test/watch-test.sh`
- Create: `deploy/server/watch-rules.sh`

**Interfaces:**
- Produces (watch-rules.sh, sourced): `watch_pass` — one timer run. Reads `WATCH_DIR`, `WATCH_KEYS` (default `services backend site disk backup certificates`), `WATCH_NOW` (default `date +%s`), `APP_DOMAIN`; calls `check_<key>` (prints nothing = fine, one line = problem) and `watch_send <text>` (returns 0 when sent). `watch_label <key>` gives the words used in clearances. `watch_weekly` — the Monday text.
- The caller (watch.sh or the test) defines `check_<key>` and `watch_send`.

- [ ] **Step 1: Write the test**

`deploy/test/watch-test.sh`:
```bash
#!/usr/bin/env bash
# The watcher's texting rules (backend/docs/superpowers/specs/2026-11-23-server-watch-design.md), proven with
# stand-in checks, a fixed clock and an outbox file. Git Bash, no Docker, no server.
#
# Usage, from the repository root: bash deploy/test/watch-test.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
. deploy/server/watch-rules.sh

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
export WATCH_DIR="$T/watch" APP_DOMAIN=app.test WATCH_KEYS="disk backend"
OUTBOX="$T/outbox"; FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=1; }

# Stand-ins: a check reports whatever is in $T/problem-<key>; sending appends to the outbox unless $T/send-fails exists.
check_disk() { cat "$T/problem-disk" 2>/dev/null || true; }
check_backend() { cat "$T/problem-backend" 2>/dev/null || true; }
watch_send() { [ ! -f "$T/send-fails" ] || return 1; printf '%s\n' "$1" >> "$OUTBOX"; }

broken() { echo "$2" > "$T/problem-$1"; }
fixed() { rm -f "$T/problem-$1"; }
texts() { [ -f "$OUTBOX" ] && wc -l < "$OUTBOX" | tr -d ' ' || echo 0; }
last() { tail -1 "$OUTBOX" 2>/dev/null; }
# A Tuesday 10:00 EAT start (2026-11-24 07:00 UTC), so the weekly text stays out of the way.
NOW=1795503600
run() { WATCH_NOW=$NOW watch_pass; }
at() { NOW=$((NOW + $1)); }   # move the clock on by seconds

reset() { rm -rf "$WATCH_DIR" "$OUTBOX" "$T"/problem-* "$T/send-fails"; mkdir -p "$WATCH_DIR"; NOW=1795503600; }

echo "-- seen once: nothing; seen twice: one text"
reset; broken disk "disk 91% full"
run; [ "$(texts)" = 0 ] || fail "a problem seen once was texted"
at 300; run; [ "$(texts)" = 1 ] || fail "a problem seen twice sent $(texts) texts, expected 1"
[ "$(last)" = "Nlolo app.test PROBLEM: disk 91% full." ] || fail "problem text: $(last)"

echo "-- unchanged: silent until 6 hours, then one reminder"
at 300; run; [ "$(texts)" = 1 ] || fail "an unchanged problem was texted again at once"
at 21000; run; [ "$(texts)" = 1 ] || fail "reminded before 6 hours"
at 600; run; [ "$(texts)" = 2 ] || fail "no reminder after 6 hours"
case "$(last)" in "Nlolo app.test STILL: disk 91% full (since "*")") ;; *) fail "reminder text: $(last)";; esac
at 300; run; [ "$(texts)" = 2 ] || fail "reminded twice"

echo "-- cleared: one OK-again text with the duration"
fixed disk; at 300; run; [ "$(texts)" = 3 ] || fail "a clearance sent no text"
[ "$(last)" = "Nlolo app.test OK again: disk space (was down 380 min)." ] || fail "clearance text: $(last)"
at 300; run; [ "$(texts)" = 3 ] || fail "a clearance was texted twice"

echo "-- seen once then gone: silent"
reset; broken disk "disk 91% full"; run; fixed disk; at 300; run; at 300; run
[ "$(texts)" = 0 ] || fail "a one-run blip sent $(texts) texts"

echo "-- two problems together: one text"
reset; broken disk "disk 91% full"; broken backend "backend health DOWN"
run; at 300; run
[ "$(texts)" = 1 ] || fail "two problems sent $(texts) texts"
[ "$(last)" = "Nlolo app.test PROBLEM: disk 91% full; backend health DOWN." ] || fail "two-problem text: $(last)"

echo "-- paused: nothing; a pause older than 2 hours is ignored"
reset; broken disk "disk 91% full"; echo "$NOW" > "$WATCH_DIR/paused"
run; at 300; run; [ "$(texts)" = 0 ] || fail "texted while paused"
at 7200; run; at 300; run; [ "$(texts)" = 1 ] || fail "a stale pause still silenced the watcher ($(texts) texts)"

echo "-- a failed send is retried on the next run"
reset; broken disk "disk 91% full"; touch "$T/send-fails"
run; at 300; run; [ "$(texts)" = 0 ] || fail "a failed send reached the outbox"
rm -f "$T/send-fails"; at 300; run
[ "$(texts)" = 1 ] || fail "the failed text was not retried"
[ "$(last)" = "Nlolo app.test PROBLEM: disk 91% full." ] || fail "retried text: $(last)"

echo "-- Monday 08:00 EAT: one weekly text, not again that week"
reset
NOW=1795845540; run; [ "$(texts)" = 0 ] || fail "weekly text before 08:00"     # Mon 2026-11-30 07:59 EAT
NOW=1795845600; run; [ "$(texts)" = 1 ] || fail "no weekly text at 08:00"      # 08:00
[ "$(last)" = "Nlolo app.test watcher: all 2 checks OK." ] || fail "weekly text: $(last)"
at 300; run; at 7200; run; [ "$(texts)" = 1 ] || fail "weekly text sent twice"
broken disk "disk 91% full"; NOW=$((1795845600 + 604800 - 600)); run; at 300; run; at 300; run   # next Mon, 07:50-08:00
grep -q "watcher: 1 open: disk." "$OUTBOX" || fail "weekly text with an open problem: $(cat "$OUTBOX")"

[ "$FAILED" = 0 ] || { echo "WATCH TEST FAILED" >&2; exit 1; }
echo "WATCH TEST PASSED"
```

- [ ] **Step 2: Run it; it must fail**

Run: `bash deploy/test/watch-test.sh`
Expected: an error that `deploy/server/watch-rules.sh` does not exist.

- [ ] **Step 3: Write watch-rules.sh**

```bash
# shellcheck shell=bash
# The watcher's texting rules (2026-11-23, backend/docs/superpowers/specs/2026-11-23-server-watch-design.md). Sourced by
# watch.sh, which supplies the six real checks and the NextSMS sender, and by deploy/test/watch-test.sh, which supplies
# stand-ins. Each check_<key> prints nothing when fine and one line naming the problem otherwise; watch_send <text>
# returns 0 when the text went.
#
# Per check, a state file WATCH_DIR/<key>: "SEEN SINCE TEXTED" (runs in a row it has failed, capped at 2; when it
# started; when it was last texted, 0 for never) and its line. A problem is texted on its second run in a row, again
# every 6 hours, and once when it clears if it was ever texted. One text per run. A state change that a text reports is
# written only once the text went, so a failed send is simply repeated by the next run.

WATCH_KEYS="${WATCH_KEYS:-services backend site disk backup certificates}"
WATCH_REMIND=21600
WATCH_PAUSE_MAX=7200
WATCH_TZ=Africa/Dar_es_Salaam

watch_label() {
  case "$1" in
    services) echo "services" ;; backend) echo "backend health" ;; site) echo "the site" ;;
    disk) echo "disk space" ;; backup) echo "backups" ;; certificates) echo "certificates" ;; *) echo "$1" ;;
  esac
}

watch_paused() {   # watch_paused <now>
  local since
  [ -f "$WATCH_DIR/paused" ] || return 1
  since="$(cat "$WATCH_DIR/paused" 2>/dev/null)"
  [[ "$since" =~ ^[0-9]+$ ]] && [ $(( $1 - since )) -lt "$WATCH_PAUSE_MAX" ]
}

watch_pass() {
  local now="${WATCH_NOW:-$(date +%s)}" key line seen since texted prev_line
  local new=() still=() cleared=() commit=()
  mkdir -p "$WATCH_DIR"
  watch_paused "$now" && return 0
  for key in $WATCH_KEYS; do
    line="$("check_$key" 2>&1 | head -1)"
    seen=0; since=0; texted=0; prev_line=""
    if [ -f "$WATCH_DIR/$key" ]; then
      read -r seen since texted < "$WATCH_DIR/$key"
      prev_line="$(sed -n 2p "$WATCH_DIR/$key")"
    fi
    if [ -n "$line" ]; then
      if [ "$seen" = 0 ]; then
        printf '1 %s 0\n%s\n' "$now" "$line" > "$WATCH_DIR/$key"
      elif [ "$texted" = 0 ]; then
        new+=("$line"); commit+=("$key 2 $since $now")
        printf '2 %s 0\n%s\n' "$since" "$line" > "$WATCH_DIR/$key"
      elif [ $(( now - texted )) -ge "$WATCH_REMIND" ]; then
        still+=("$line (since $(TZ=$WATCH_TZ date -d "@$since" '+%a %H:%M'))"); commit+=("$key 2 $since $now")
        printf '2 %s %s\n%s\n' "$since" "$texted" "$line" > "$WATCH_DIR/$key"
      else
        printf '2 %s %s\n%s\n' "$since" "$texted" "$line" > "$WATCH_DIR/$key"
      fi
    elif [ "$texted" != 0 ]; then
      cleared+=("$(watch_label "$key") (was down $(( (now - since) / 60 )) min)"); commit+=("$key clear")
    else
      rm -f "$WATCH_DIR/$key"
    fi
  done

  if [ ${#new[@]} -gt 0 ] || [ ${#still[@]} -gt 0 ] || [ ${#cleared[@]} -gt 0 ]; then
    local text="Nlolo $APP_DOMAIN" IFS_OLD="$IFS"
    IFS=';'
    [ ${#new[@]} -gt 0 ] && text="$text PROBLEM: $(printf '%s; ' "${new[@]}" | sed 's/; $//')."
    [ ${#still[@]} -gt 0 ] && text="$text STILL: $(printf '%s; ' "${still[@]}" | sed 's/; $//')."
    [ ${#cleared[@]} -gt 0 ] && text="$text OK again: $(printf '%s; ' "${cleared[@]}" | sed 's/; $//')."
    IFS="$IFS_OLD"
    if watch_send "$text"; then
      local c k a b d
      for c in "${commit[@]}"; do
        read -r k a b d <<<"$c"
        if [ "$a" = clear ]; then rm -f "$WATCH_DIR/$k"
        else sed -i "1s/.*/$a $b $d/" "$WATCH_DIR/$k"; fi
      done
    else
      echo "watch: the text did not go; the next run tries again: $text" >&2
    fi
  fi
  watch_weekly "$now"
}

watch_weekly() {   # watch_weekly <now>
  local now="$1" day hour week open=() key seen
  day="$(TZ=$WATCH_TZ date -d "@$now" +%u)"; hour="$(TZ=$WATCH_TZ date -d "@$now" +%H)"
  week="$(TZ=$WATCH_TZ date -d "@$now" +%G-W%V)"
  [ "$day" = 1 ] && [ "$hour" -ge 8 ] || return 0
  [ "$(cat "$WATCH_DIR/weekly" 2>/dev/null)" = "$week" ] && return 0
  for key in $WATCH_KEYS; do
    [ -f "$WATCH_DIR/$key" ] || continue
    read -r seen _ < "$WATCH_DIR/$key"
    [ "$seen" = 2 ] && open+=("$key")
  done
  local count; count=$(wc -w <<<"$WATCH_KEYS" | tr -d ' ')
  local text
  if [ ${#open[@]} = 0 ]; then text="Nlolo $APP_DOMAIN watcher: all $count checks OK."
  else text="Nlolo $APP_DOMAIN watcher: ${#open[@]} open: $(IFS=,; echo "${open[*]}" | sed 's/,/, /g')."; fi
  watch_send "$text" && echo "$week" > "$WATCH_DIR/weekly"
}
```

- [ ] **Step 4: Run the test; it must pass**

Run: `bash deploy/test/watch-test.sh`
Expected: `WATCH TEST PASSED`. Fix the rules, never the expectations, unless an expectation contradicts the spec.

- [ ] **Step 5: Commit**

```bash
git add deploy/test/watch-test.sh deploy/server/watch-rules.sh
git commit -m "feat(deploy): the watcher's texting rules, proven with stand-ins" -- deploy/test/watch-test.sh deploy/server/watch-rules.sh
```

---

### Task 2: The six checks, the sender and the commands

**Files:**
- Create: `deploy/server/watch.sh`
- Modify: `deploy/server/backup.sh` (after the `cat RELEASED` line; after the two `rc copy` lines)

**Interfaces:**
- Consumes: `watch_pass`, `watch_label` from Task 1; `platform-lib.sh` (`PLATFORM_HOME`, `PG_SERVICE`, `BACKEND_MANAGEMENT_PORT`, `psql_`, `.env`).
- Produces: `watch.sh` (no argument: one timer run), `watch.sh --check` (exit 1 if any check fails), `watch.sh --test-text`.

- [ ] **Step 1: backup.sh markers**

After `cat RELEASED > "$DIR/RELEASE" 2>/dev/null || echo unknown > "$DIR/RELEASE"` add:
```bash
# The dumps are whole (set -e would have stopped otherwise): the watcher looks for this (watch.sh, check_backup).
touch "$DIR/.complete"
```
After `if [ "$(date +%u)" = "7" ]; then rc copy "$DIR" "safe:weekly/$STAMP"; fi` add:
```bash
  # The off-server copy went: the watcher's check_backup reads this file's age.
  touch backups/.offsite-ok
```

- [ ] **Step 2: Write watch.sh**

```bash
#!/usr/bin/env bash
# The server watches itself and texts (2026-11-23, backend/docs/superpowers/specs/2026-11-23-server-watch-design.md).
# Run every 5 minutes by platform-watch.timer as the deploy user. Six checks; the texting rules are watch-rules.sh.
#
#   ./watch.sh               one run: check, text ALERT_PHONES about what started, lasts or cleared
#   ./watch.sh --check       run the six checks once and print each; no text, no state. Exit 1 if any fails
#   ./watch.sh --test-text   text ALERT_PHONES a test message
#
# If the whole server or its network is down, nothing here can text -- the accepted cost of texting from the server
# itself (the user, 2026-11-23).
set -uo pipefail
cd "$(dirname "$0")" || exit 1
. ./platform-lib.sh
. ./watch-rules.sh
WATCH_DIR="$PLATFORM_HOME/watch"
NOW_REAL="$(date +%s)"

check_services() {
  local unit down=()
  for unit in "$PG_SERVICE" seaweedfs platform-mock-mobile-money authentik-server authentik-worker platform-backend caddy; do
    systemctl is-active --quiet "$unit" || down+=("$unit")
  done
  [ ${#down[@]} = 0 ] || echo "stopped: ${down[*]}"
}

check_backend() {
  local body status missing
  body="$(curl -s -m 10 "http://127.0.0.1:$BACKEND_MANAGEMENT_PORT/actuator/health" 2>/dev/null)"
  grep -q '"status":"UP"' <<<"$body" && return 0
  status="$(grep -o '"status":"[A-Z_]*"' <<<"$body" | head -1 | cut -d'"' -f4)"
  # The endpoint shows no details, so ask the database what the scheduled-jobs check reads.
  missing="$(sudo -n -u postgres psql -d lifeplatform -tAc \
    "SELECT string_agg(item, ', ') FROM ops.platform_readiness() WHERE NOT present" 2>/dev/null)"
  echo "backend health ${status:-not answering}${missing:+ (missing: $missing)}"
}

check_site() {
  local k="" r bad=() code
  [ -n "${CADDY_GLOBAL_OPTIONS:-}" ] && k="-k"
  r="--resolve $APP_DOMAIN:443:127.0.0.1 --resolve $AUTH_DOMAIN:443:127.0.0.1"
  # shellcheck disable=SC2086
  curl -s -m 15 $k $r "https://$APP_DOMAIN/" | grep -q '<div id="root"' || bad+=("console")
  # shellcheck disable=SC2086
  code="$(curl -s -m 15 $k $r -o /dev/null -w '%{http_code}' "https://$AUTH_DOMAIN/if/flow/lifeplatform-sign-in/")"
  [ "$code" = 200 ] || bad+=("sign-in page ($code)")
  [ ${#bad[@]} = 0 ] || echo "site not answering: ${bad[*]}"
}

check_disk() {
  local pct
  pct="$(df --output=pcent / | tail -1 | tr -dc '0-9')"
  [ "${pct:-0}" -lt 85 ] || echo "disk ${pct}% full"
}

check_backup() {
  local newest=0 d t problems=()
  for d in "$PLATFORM_HOME"/backups/*/; do
    [ -f "$d.complete" ] || continue
    t="$(stat -c %Y "$d.complete")"; [ "$t" -gt "$newest" ] && newest="$t"
  done
  [ $(( NOW_REAL - newest )) -lt 108000 ] || problems+=("no complete backup in 30h")
  if [ -n "${BACKUP_S3_ENDPOINT:-}" ]; then
    t="$(stat -c %Y "$PLATFORM_HOME/backups/.offsite-ok" 2>/dev/null || echo 0)"
    [ $(( NOW_REAL - t )) -lt 108000 ] || problems+=("no off-server copy in 30h")
  fi
  [ ${#problems[@]} = 0 ] || (IFS=,; echo "backups: ${problems[*]}" | sed 's/,/, /g')
}

check_certificates() {
  local d end left bad=()
  for d in "$APP_DOMAIN" "$AUTH_DOMAIN"; do
    end="$(echo | openssl s_client -connect 127.0.0.1:443 -servername "$d" 2>/dev/null \
           | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    if [ -z "$end" ]; then bad+=("$d unreadable"); continue; fi
    left=$(( ( $(date -d "$end" +%s) - NOW_REAL ) / 86400 ))
    [ "$left" -ge 14 ] || bad+=("$d ends $(date -d "$end" '+%d %b')")
  done
  [ ${#bad[@]} = 0 ] || echo "certificates: ${bad[*]}"
}

# NextSMS, as the backend's SmsGatewayAdapter speaks it. Not subject to SMS_GATEWAY_ALLOWED_RECIPIENTS: these are the
# operator's own numbers. With SMS off, the text goes to the journal only.
watch_send() {
  local text="$1" phone msisdn reply ok=0
  if [ "${SMS_GATEWAY_LIVE:-false}" != true ]; then
    echo "watch: SMS is off (SMS_GATEWAY_LIVE), not sent: $text"; return 0
  fi
  for phone in ${ALERT_PHONES//,/ }; do
    msisdn="$(tr -dc '0-9' <<<"$phone")"
    case "$msisdn" in 0*) msisdn="255${msisdn#0}";; esac
    reply="$(curl -s -m 20 -X POST "$SMS_GATEWAY_BASE_URL/api/sms/v1/text/single" \
      -H "Authorization: $SMS_GATEWAY_AUTHORIZATION" -H 'Content-Type: application/json' -H 'Accept: application/json' \
      -d "$(printf '{"from":"%s","to":"%s","text":"%s","reference":"%s"}' "$SMS_GATEWAY_SENDER_NAME" "$msisdn" \
            "$(sed 's/["\\]/\\&/g' <<<"$text")" "$(cat /proc/sys/kernel/random/uuid)")")"
    if grep -qE '"groupName" *: *"(PENDING|DELIVERED)"' <<<"$reply"; then
      echo "watch: texted $msisdn: $text"
    else
      echo "watch: NextSMS did not take the text for $msisdn: ${reply:-no answer}" >&2; ok=1
    fi
  done
  return "$ok"
}

case "${1:-}" in
  --check)
    rc=0
    for key in $WATCH_KEYS; do
      line="$("check_$key" 2>&1 | head -1)"
      if [ -z "$line" ]; then printf 'ok    %s\n' "$(watch_label "$key")"; else printf 'FAIL  %s\n' "$line"; rc=1; fi
    done
    exit "$rc" ;;
  --test-text)
    [ "${SMS_GATEWAY_LIVE:-false}" = true ] || { echo "SMS_GATEWAY_LIVE is not true in .env: nothing can be texted" >&2; exit 1; }
    [ -n "${ALERT_PHONES:-}" ] || { echo "ALERT_PHONES is empty in .env" >&2; exit 1; }
    watch_send "Nlolo $APP_DOMAIN watcher: test message" ;;
  "")
    [ -n "${ALERT_PHONES:-}" ] || { echo "watch: ALERT_PHONES is empty in .env: nobody to text" >&2; exit 1; }
    watch_pass ;;
  *) echo "Usage: ./watch.sh [--check | --test-text]" >&2; exit 2 ;;
esac
```

- [ ] **Step 3: Syntax check, and the rules test still passes**

Run: `bash -n deploy/server/watch.sh && bash -n deploy/server/backup.sh && bash deploy/test/watch-test.sh`
Expected: no syntax output, then `WATCH TEST PASSED`.

- [ ] **Step 4: Commit**

```bash
git add deploy/server/watch.sh
git commit -m "feat(deploy): the watcher's six checks, its NextSMS sender and its commands" -- deploy/server/watch.sh deploy/server/backup.sh
```

---

### Task 3: The timer, the release and the smoke check

**Files:**
- Create: `deploy/server/systemd/platform-watch.service`, `deploy/server/systemd/platform-watch.timer`
- Modify: `deploy/server/remote-release.sh` (top; `install_units`; release and rollback ends)
- Modify: `deploy/server/smoke.sh` (before `echo "Smoke check passed."`)
- Modify: `deploy/server/.env.example`, `deploy/bootstrap-server.sh` (the "Edit .env" message)

- [ ] **Step 1: The units**

`platform-watch.service`:
```ini
# The server watches itself (2026-11-23, deploy/server/watch.sh): six checks, texted to ALERT_PHONES. One run per
# timer tick, as the deploy user, whose sudo reads the database's readiness when the backend is unhealthy.
[Unit]
Description=Platform watcher: checks the server and texts ALERT_PHONES
After=network-online.target

[Service]
Type=oneshot
User=deploy
WorkingDirectory=/home/deploy/platform
ExecStart=/home/deploy/platform/watch.sh
TimeoutStartSec=4min
```
`platform-watch.timer`:
```ini
# Every 5 minutes (2026-11-23). Not Persistent: a run missed while the machine was off is not worth making up.
[Unit]
Description=Run the platform watcher every 5 minutes

[Timer]
OnBootSec=5min
OnUnitActiveSec=5min
AccuracySec=30s

[Install]
WantedBy=timers.target
```

- [ ] **Step 2: remote-release.sh**

After `. ./platform-lib.sh` (top of the file) add:
```bash

# The watcher stays quiet while a release restarts things (watch-rules.sh). Removed when the release or rollback
# succeeds; a crashed one's pause lapses after two hours.
mkdir -p "$PLATFORM_HOME/watch"
date +%s > "$PLATFORM_HOME/watch/paused"
```
In `install_units`, after the `*.service` line add (a release from before the watcher has no timer to copy, and a
rollback to it must not stop on that):
```bash
  for timer in "$src"/systemd/*.timer; do [ -e "$timer" ] && sudo install -m 0644 "$timer" /etc/systemd/system/; done
```
In the release section, after `[ -d "$INCOMING" ] || ...` add:
```bash
[ -n "${ALERT_PHONES:-}" ] || { echo "Set ALERT_PHONES in .env: the watcher texts those numbers when something breaks" >&2; exit 1; }
```
Before each of the two `./smoke.sh` calls (rollback and release) add:
```bash
sudo systemctl enable --now platform-watch.timer
```
After each `echo "$VERSION" > RELEASED` add:
```bash
rm -f "$PLATFORM_HOME/watch/paused"
```
After the release's final `echo "Released $VERSION."` add:
```bash
echo "== the watcher's checks now (./watch.sh --check; ./watch.sh --test-text sends a test text)"
./watch.sh --check || true
```

- [ ] **Step 3: smoke.sh**

Before `echo "Smoke check passed."`:
```bash
# The watcher (2026-11-23): without it, a broken server stays broken until someone looks.
systemctl is-active --quiet platform-watch.timer || fail "the watcher's timer (platform-watch.timer) is not active"
[ -n "${ALERT_PHONES:-}" ] || fail "ALERT_PHONES is empty in .env: the watcher has nobody to text"
```
And in its header list: `the watcher's timer runs,`.

- [ ] **Step 4: .env.example and bootstrap message**

`.env.example`, after `SUPPORT_PHONE=` block:
```bash
# Who the watcher texts when something on this server breaks, clears or stays broken (2026-11-23, watch.sh): one or
# two on-call mobiles, +255..., space- or comma-separated. Required: a release refuses to run without it.
ALERT_PHONES=
```
`bootstrap-server.sh`'s "Edit ... .env" message: add `ALERT_PHONES` to the list of settings to fill.

- [ ] **Step 5: Checks**

Run: `bash -n deploy/server/remote-release.sh && bash -n deploy/server/smoke.sh && bash -n deploy/bootstrap-server.sh && echo ok`
Expected: `ok`. Also `grep -n "platform-watch\|watch/paused\|ALERT_PHONES" deploy/server/remote-release.sh` shows the pause, the refusal, two enables, two removals and the --check print.

- [ ] **Step 6: Commit**

```bash
git add deploy/server/systemd/platform-watch.service deploy/server/systemd/platform-watch.timer
git commit -m "feat(deploy): the watcher runs every 5 minutes; releases pause it and require ALERT_PHONES" -- deploy/server/systemd/platform-watch.service deploy/server/systemd/platform-watch.timer deploy/server/remote-release.sh deploy/server/smoke.sh deploy/server/.env.example deploy/bootstrap-server.sh
```

---

### Task 4: The docs

**Files:**
- Modify: `deploy/DEPLOYMENT-GUIDE.md` (Step 3 table; Step 8 UptimeRobot item; Part 4 "Checking on it"; the release stages table)
- Modify: `deploy/README.md` (section 7)

- [ ] **Step 1: Guide**

Step 3 table, after `SUPPORT_PHONE`: `` | `ALERT_PHONES` | One or two on-call mobiles, `+255...`, space-separated | Required; the watcher texts them when something breaks | ``.

Replace the Step 8 item `- [ ] **An uptime monitor** (UptimeRobot, free) on both addresses, alerting someone by SMS or phone.` with:
```
- [ ] **The watcher texts you:** `ssh deploy@SERVER_IP 'cd platform && ./watch.sh --test-text'` — every number in
      `ALERT_PHONES` should receive "watcher: test message". Then `./watch.sh --check` should print six `ok` lines.
      From now on the server checks itself every 5 minutes and texts when a service stops, the backend is unhealthy,
      the site does not answer, the disk passes 85%, a backup is missing or a certificate is about to expire; again
      every 6 hours while it lasts; and when it clears. Every Monday at 08:00 it texts that it is still watching —
      **if that text stops coming, the watcher or the server is down.** It cannot text if the whole server is off.
```

Part 4 "Checking on it": after the `status.sh` block add:
```
`./watch.sh --check` runs the watcher's six checks once and prints each. The watcher's own log:
`sudo journalctl -u platform-watch -n 50`.
```

Release stages table, `smoke check` row: append `; then the watcher's checks are printed`.

- [ ] **Step 2: README**

Section 7's block gains:
```bash
./watch.sh --check                         # the watcher's six checks, once
./watch.sh --test-text                     # text ALERT_PHONES a test message
```
and a sentence below it: `platform-watch.timer runs ./watch.sh every 5 minutes and texts ALERT_PHONES (watch-rules.sh holds the rules; deploy/test/watch-test.sh proves them).`

- [ ] **Step 3: Commit**

```bash
git commit -m "docs(deploy): the watcher, ALERT_PHONES and the test text" -- deploy/DEPLOYMENT-GUIDE.md deploy/README.md
```

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
texts() { if [ -f "$OUTBOX" ]; then wc -l < "$OUTBOX" | tr -d ' '; else echo 0; fi; }
last() { tail -1 "$OUTBOX" 2>/dev/null; }
# Tuesday 2026-11-24 10:00 EAT, so the Monday text stays out of the way of the other cases.
START=1795514400
MONDAY_0759=1796025540   # Monday 2026-11-30 07:59 EAT
MONDAY_0800=1796025600   # Monday 2026-11-30 08:00 EAT
NOW=$START
run() { WATCH_NOW=$NOW watch_pass; }
at() { NOW=$((NOW + $1)); }   # move the clock on by seconds

reset() { rm -rf "$WATCH_DIR" "$OUTBOX" "$T"/problem-* "$T/send-fails"; mkdir -p "$WATCH_DIR"; NOW=$START; }

echo "-- seen once: nothing; seen twice: one text"
reset; broken disk "disk 91% full"
run; [ "$(texts)" = 0 ] || fail "a problem seen once was texted"
at 300; run; [ "$(texts)" = 1 ] || fail "a problem seen twice sent $(texts) texts, expected 1"
[ "$(last)" = "Nlolo app.test PROBLEM: disk 91% full." ] || fail "problem text: $(last)"

echo "-- unchanged: silent until 6 hours, then one reminder"
at 300; run; [ "$(texts)" = 1 ] || fail "an unchanged problem was texted again at once"
at 21000; run; [ "$(texts)" = 1 ] || fail "reminded before 6 hours"
at 600; run; [ "$(texts)" = 2 ] || fail "no reminder after 6 hours"
[ "$(last)" = "Nlolo app.test STILL: disk 91% full (since Tue 10:00)." ] || fail "reminder text: $(last)"
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

echo "-- a failed clearance is retried too"
fixed disk; touch "$T/send-fails"; at 300; run; [ "$(texts)" = 1 ] || fail "a failed clearance reached the outbox"
rm -f "$T/send-fails"; at 300; run; [ "$(texts)" = 2 ] || fail "the failed clearance was not retried"
case "$(last)" in "Nlolo app.test OK again: disk space"*) ;; *) fail "retried clearance: $(last)";; esac

echo "-- Monday 08:00 EAT: one weekly text, not again that week"
reset
NOW=$MONDAY_0759; run; [ "$(texts)" = 0 ] || fail "weekly text before 08:00"
NOW=$MONDAY_0800; run; [ "$(texts)" = 1 ] || fail "no weekly text at 08:00"
[ "$(last)" = "Nlolo app.test watcher: all 2 checks OK." ] || fail "weekly text: $(last)"
at 300; run; at 7200; run; [ "$(texts)" = 1 ] || fail "weekly text sent twice"
broken disk "disk 91% full"; NOW=$((MONDAY_0800 + 604800 - 600)); run; at 300; run; at 300; run   # next Mon 07:50-08:00
grep -q "watcher: 1 open: disk." "$OUTBOX" || fail "weekly text with an open problem: $(cat "$OUTBOX")"

[ "$FAILED" = 0 ] || { echo "WATCH TEST FAILED" >&2; exit 1; }
echo "WATCH TEST PASSED"

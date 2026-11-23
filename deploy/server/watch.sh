#!/usr/bin/env bash
# The server watches itself and texts (2026-11-23, backend/docs/superpowers/specs/2026-11-23-server-watch-design.md).
# Run every 5 minutes by platform-watch.timer as the deploy user. Six checks here; the texting rules are watch-rules.sh.
#
#   ./watch.sh               one run: check, then text ALERT_PHONES about what started, lasts or cleared
#   ./watch.sh --check       run the six checks once and print each; no text, no state. Exit 1 if any fails
#   ./watch.sh --test-text   text ALERT_PHONES a test message
#
# If the whole server or its network is down, nothing here can text -- the accepted cost of texting from the server
# itself (the user, 2026-11-23). The Monday 08:00 text is how that silence shows.
set -uo pipefail
cd "$(dirname "$0")" || exit 1
. ./platform-lib.sh
. ./watch-rules.sh
WATCH_DIR="$PLATFORM_HOME/watch"
NOW_REAL="$(date +%s)"
DAY_AND_A_QUARTER=108000   # 30 hours: a nightly backup that is late, not merely later than usual

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
  # The endpoint shows no details (show-details: never), so ask the database what the scheduled-jobs check reads.
  missing="$(sudo -n -u postgres psql -d lifeplatform -tAc \
    "SELECT string_agg(item, ', ') FROM ops.platform_readiness() WHERE NOT present" 2>/dev/null)"
  echo "backend health ${status:-not answering}${missing:+ (missing: $missing)}"
}

check_site() {
  local k="" bad=() code
  [ -n "${CADDY_GLOBAL_OPTIONS:-}" ] && k="-k"
  local r=(--resolve "$APP_DOMAIN:443:127.0.0.1" --resolve "$AUTH_DOMAIN:443:127.0.0.1")
  # shellcheck disable=SC2086
  curl -s -m 15 $k "${r[@]}" "https://$APP_DOMAIN/" | grep -q '<div id="root"' || bad+=("console")
  # shellcheck disable=SC2086
  code="$(curl -s -m 15 $k "${r[@]}" -o /dev/null -w '%{http_code}' "https://$AUTH_DOMAIN/if/flow/lifeplatform-sign-in/")"
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
    t="$(stat -c %Y "$d.complete")"
    [ "$t" -gt "$newest" ] && newest="$t"
  done
  [ $(( NOW_REAL - newest )) -lt "$DAY_AND_A_QUARTER" ] || problems+=("no complete backup in 30h")
  if [ -n "${BACKUP_S3_ENDPOINT:-}" ]; then
    t="$(stat -c %Y "$PLATFORM_HOME/backups/.offsite-ok" 2>/dev/null || echo 0)"
    [ $(( NOW_REAL - t )) -lt "$DAY_AND_A_QUARTER" ] || problems+=("no off-server copy in 30h")
  fi
  [ ${#problems[@]} = 0 ] || echo "backups: $(watch_join "${problems[@]}")"
}

check_certificates() {
  local d end left bad=()
  # A rehearsal (CADDY_GLOBAL_OPTIONS set, e.g. local_certs) signs with Caddy's own CA, whose certificates last hours,
  # not months: there is no renewal to watch.
  [ -z "${CADDY_GLOBAL_OPTIONS:-}" ] || return 0
  for d in "$APP_DOMAIN" "$AUTH_DOMAIN"; do
    end="$(echo | openssl s_client -connect 127.0.0.1:443 -servername "$d" 2>/dev/null \
           | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    if [ -z "$end" ]; then bad+=("$d unreadable"); continue; fi
    left=$(( ( $(date -d "$end" +%s) - NOW_REAL ) / 86400 ))
    [ "$left" -ge 14 ] || bad+=("$d ends $(date -d "$end" '+%d %b')")
  done
  [ ${#bad[@]} = 0 ] || echo "certificates: $(watch_join "${bad[@]}")"
}

# NextSMS, spoken as the backend's SmsGatewayAdapter speaks it: no plus on the number, the sender name in "from", and
# PENDING or DELIVERED meaning it was taken. Not subject to SMS_GATEWAY_ALLOWED_RECIPIENTS: these are the operator's own
# numbers. With SMS off, the text goes to the journal only.
watch_send() {
  local text="$1" phone msisdn reply rc=0 escaped
  if [ "${SMS_GATEWAY_LIVE:-false}" != true ]; then
    echo "watch: SMS is off (SMS_GATEWAY_LIVE), not sent: $text"
    return 0
  fi
  escaped="$(sed 's/["\\]/\\&/g' <<<"$text")"
  for phone in ${ALERT_PHONES//,/ }; do
    msisdn="$(tr -dc '0-9' <<<"$phone")"
    case "$msisdn" in 0*) msisdn="255${msisdn#0}" ;; esac
    reply="$(curl -s -m 20 -X POST "$SMS_GATEWAY_BASE_URL/api/sms/v1/text/single" \
      -H "Authorization: $SMS_GATEWAY_AUTHORIZATION" -H 'Content-Type: application/json' -H 'Accept: application/json' \
      -d "{\"from\":\"$SMS_GATEWAY_SENDER_NAME\",\"to\":\"$msisdn\",\"text\":\"$escaped\",\"reference\":\"$(cat /proc/sys/kernel/random/uuid)\"}")"
    if grep -qE '"groupName" *: *"(PENDING|DELIVERED)"' <<<"$reply"; then
      echo "watch: texted $msisdn: $text"
    else
      echo "watch: NextSMS did not take the text for $msisdn: ${reply:-no answer}" >&2
      rc=1
    fi
  done
  return "$rc"
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

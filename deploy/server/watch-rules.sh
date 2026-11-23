# shellcheck shell=bash
# The watcher's texting rules (2026-11-23, backend/docs/superpowers/specs/2026-11-23-server-watch-design.md). Sourced by
# watch.sh, which supplies the six real checks and the NextSMS sender, and by deploy/test/watch-test.sh, which supplies
# stand-ins. Each check_<key> prints nothing when fine and one line naming the problem otherwise; watch_send <text>
# returns 0 when the text went.
#
# Per check, a state file WATCH_DIR/<key>: line 1 "SEEN SINCE TEXTED" (runs in a row it has failed, capped at 2; when it
# started; when it was last texted, 0 for never), line 2 its problem. A problem is texted on its second run in a row,
# again every 6 hours, and once when it clears if it was ever texted. One text per run. A change a text reports is
# written only once the text went, so a failed send is simply made again by the next run.

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

watch_paused() {   # watch_paused <now>: true while a release's pause is younger than two hours
  local since
  [ -f "$WATCH_DIR/paused" ] || return 1
  since="$(cat "$WATCH_DIR/paused" 2>/dev/null)"
  [[ "$since" =~ ^[0-9]+$ ]] && [ $(( $1 - since )) -lt "$WATCH_PAUSE_MAX" ]
}

watch_join() { local IFS=';'; printf '%s' "$*" | sed 's/;/; /g'; }   # watch_join a b c -> "a; b; c"

watch_pass() {
  local now="${WATCH_NOW:-$(date +%s)}" key line seen since texted
  local new=() still=() cleared=() commit=()
  mkdir -p "$WATCH_DIR"
  watch_paused "$now" && return 0
  for key in $WATCH_KEYS; do
    line="$("check_$key" 2>&1 | head -1)"
    seen=0; since=0; texted=0
    [ -f "$WATCH_DIR/$key" ] && read -r seen since texted < "$WATCH_DIR/$key"
    if [ -n "$line" ]; then
      if [ "$seen" = 0 ]; then
        since="$now"; seen=1
      else
        seen=2
        if [ "$texted" = 0 ]; then
          new+=("$line"); commit+=("$key $since $now")
        elif [ $(( now - texted )) -ge "$WATCH_REMIND" ]; then
          still+=("$line (since $(TZ=$WATCH_TZ date -d "@$since" '+%a %H:%M'))"); commit+=("$key $since $now")
        fi
      fi
      printf '%s %s %s\n%s\n' "$seen" "$since" "$texted" "$line" > "$WATCH_DIR/$key"
    elif [ "$texted" != 0 ]; then
      cleared+=("$(watch_label "$key") (was down $(( (now - since) / 60 )) min)"); commit+=("$key clear")
    else
      rm -f "$WATCH_DIR/$key"
    fi
  done

  if [ $(( ${#new[@]} + ${#still[@]} + ${#cleared[@]} )) -gt 0 ]; then
    local text="Nlolo $APP_DOMAIN"
    [ ${#new[@]} -gt 0 ] && text="$text PROBLEM: $(watch_join "${new[@]}")."
    [ ${#still[@]} -gt 0 ] && text="$text STILL: $(watch_join "${still[@]}")."
    [ ${#cleared[@]} -gt 0 ] && text="$text OK again: $(watch_join "${cleared[@]}")."
    if watch_send "$text"; then
      local c k from at
      for c in "${commit[@]}"; do
        read -r k from at <<<"$c"
        if [ "$from" = clear ]; then rm -f "$WATCH_DIR/$k"; else sed -i "1s/.*/2 $from $at/" "$WATCH_DIR/$k"; fi
      done
    else
      echo "watch: the text did not go; the next run tries again: $text" >&2
    fi
  fi
  watch_weekly "$now"
}

watch_weekly() {   # watch_weekly <now>: Monday, from 08:00 in Tanzania, once a week
  local now="$1" week key seen open=() count text
  [ "$(TZ=$WATCH_TZ date -d "@$now" +%u)" = 1 ] || return 0
  [ "$(TZ=$WATCH_TZ date -d "@$now" +%H)" -ge 8 ] || return 0
  week="$(TZ=$WATCH_TZ date -d "@$now" +%G-W%V)"
  [ "$(cat "$WATCH_DIR/weekly" 2>/dev/null)" = "$week" ] && return 0
  for key in $WATCH_KEYS; do
    [ -f "$WATCH_DIR/$key" ] || continue
    read -r seen _ < "$WATCH_DIR/$key"
    [ "$seen" = 2 ] && open+=("$key")
  done
  count=$(wc -w <<<"$WATCH_KEYS" | tr -d ' ')
  if [ ${#open[@]} = 0 ]; then
    text="Nlolo $APP_DOMAIN watcher: all $count checks OK."
  else
    text="Nlolo $APP_DOMAIN watcher: ${#open[@]} open: $(watch_join "${open[@]}" | sed 's/;/,/g')."
  fi
  watch_send "$text" && echo "$week" > "$WATCH_DIR/weekly"
}

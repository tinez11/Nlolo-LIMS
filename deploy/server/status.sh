#!/usr/bin/env bash
# One look at the server (2026-10-13; native since 2026-11-20, ticket 10 of .scratch/authentik-cutover): what is
# deployed, whether each service is running, how much disk is left, how old the last backup is, and what the backend
# says about itself -- including the scheduled-jobs and partition checks, which are the two things that fail silently
# for weeks.
set -uo pipefail
cd "$(dirname "$0")" || exit 1
. ./platform-lib.sh

echo "== version: $(cat RELEASED 2>/dev/null || echo 'none recorded')  (Authentik $(readlink "$AUTHENTIK_OPT/current" | xargs -r basename))"
echo
for unit in "$PG_SERVICE" seaweedfs platform-mock-mobile-money authentik-server authentik-worker platform-backend caddy; do
  printf '%-30s %s\n' "$unit" "$(systemctl is-active "$unit" 2>/dev/null)"
done
echo
echo "== disk"
df -h / | tail -1
echo
last=$(ls -1d backups/*/ 2>/dev/null | sort | tail -1)
if [ -n "$last" ]; then
  age=$(( ( $(date +%s) - $(stat -c %Y "$last") ) / 3600 ))
  echo "== last backup: $last (${age}h ago)"
  [ "$age" -gt 30 ] && echo "   WARNING: older than a day -- check the nightly cron job"
else
  echo "== last backup: NONE"
fi
echo
echo "== backend health"
curl -sf "http://127.0.0.1:$BACKEND_MANAGEMENT_PORT/actuator/health" || echo "(backend not answering)"
echo
echo "== a service's log:  sudo journalctl -u <service> -n 100   (add -f to follow)"

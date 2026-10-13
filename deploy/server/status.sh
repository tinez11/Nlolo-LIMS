#!/usr/bin/env bash
# One look at the server (2026-10-13): what is deployed, what is running, how much disk is left, how old the last
# backup is, and what the backend says about itself -- including the scheduled-jobs and partition checks, which are
# the two things that fail silently for weeks.
set -uo pipefail
cd "$(dirname "$0")"

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"

echo "== version: $(cat RELEASED 2>/dev/null || echo 'none recorded')"
echo
$DC ps --format 'table {{.Service}}\t{{.Status}}'
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
$DC exec -T backend wget -qO- http://localhost:9090/actuator/health 2>/dev/null || echo "(backend not answering)"
echo

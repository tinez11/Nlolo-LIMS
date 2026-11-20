# shellcheck shell=bash
# Shared by the server scripts (2026-11-20, ticket 10 of .scratch/authentik-cutover: the platform runs natively under
# systemd). Sourced, never run. Loads .env and versions.env, and names every place on the server once.
#
# The scripts run as the deploy user, who has sudo (bootstrap-server.sh): starting services and reading the databases
# needs root, as running containers did.

PLATFORM_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a; . "$PLATFORM_HOME/.env"; . "$PLATFORM_HOME/versions.env"; set +a

# Releases: /opt/platform/releases/<version>, the one in service linked as /opt/platform/current.
PLATFORM_OPT=/opt/platform
TOOLS=$PLATFORM_OPT/tools
# Authentik, built from source once per version (install-authentik.sh); the one in service linked as current.
AUTHENTIK_OPT=/opt/authentik
AUTHENTIK_STATE=/var/lib/authentik
# Each service's settings, rendered from .env by remote-release.sh, readable by root and that service only.
ETC=/etc/platform

PG_SERVICE="postgresql@${POSTGRES_MAJOR}-main"
# Ports, all on loopback; only Caddy listens on the network.
BACKEND_PORT=8080
BACKEND_MANAGEMENT_PORT=9090
AUTHENTIK_SERVER_PORT=9000
AUTHENTIK_WORKER_PORT=9001
MOCK_MONEY_PORT=8089
SEAWEED_S3_PORT=8333
SEAWEED_MASTER_PORT=9333

psql_() { sudo -u postgres psql -v ON_ERROR_STOP=1 "$@"; }

# Authentik's own management command (python -m manage ...), as the authentik user, with the server's settings.
# Values for the command go in as VAR=value before --, so no name or phone ever becomes part of code:
#   ak_manage LP_USERNAME=asha -- shell -c '...'
ak_manage() {
  local assignments=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do assignments+=("$1"); shift; done
  [ "${1:-}" = "--" ] && shift
  sudo -u authentik env "${assignments[@]}" bash -c '
    set -a; . /etc/platform/authentik.env; . /etc/platform/authentik-server.env; set +a
    cd /opt/authentik/current && exec .venv/bin/python -m manage "$@"' _ "$@"
}

# Authentik's own health check for one of its two services (server or worker), with that service's ports.
ak_healthy() {
  sudo -u authentik bash -c "
    set -a; . /etc/platform/authentik.env; . /etc/platform/authentik-$1.env; set +a
    cd /opt/authentik/current && exec ./bin/authentik healthcheck" > /dev/null 2>&1
}

wait_for() {   # wait_for <what> <command...>: up to three minutes
  local what="$1" i=0; shift
  until "$@"; do
    i=$((i + 1))
    if [ "$i" -gt 90 ]; then
      echo "$what did not become ready in three minutes" >&2
      return 1
    fi
    sleep 2
  done
}

backend_ready() {
  curl -sf "http://127.0.0.1:$BACKEND_MANAGEMENT_PORT/actuator/health/readiness" | grep -q '"UP"'
}

# One look at a unit's last lines when it fails, so the release log says why.
unit_log() { sudo journalctl -u "$1" -n 60 --no-pager >&2 || true; }

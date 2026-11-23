#!/usr/bin/env bash
# Caddy with the rate-limit module (2026-11-23, backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md).
# The packaged Caddy has no rate limiting, so this builds one with xcaddy at the versions versions.env names.
#
#   ./install-caddy.sh --build-to <dir>   build only, into <dir>/caddy (the local rehearsal uses this; needs Go)
#   ./install-caddy.sh                    on the server: build into /opt/caddy/<versions> once, then make it the
#                                         system's caddy
#
# The apt package stays installed for its systemd unit, caddy user and directories. Its binary is moved aside with
# dpkg-divert and /usr/bin/caddy becomes an alternative pointing at our build -- Caddy's documented way to run a custom
# binary on Debian. Without the divert, an unattended apt upgrade would put back a Caddy with no rate limiting.
set -euo pipefail
cd "$(dirname "$0")"
# The pins come from the caller when it has them -- a rollback passes the release's own, which may be older than this
# folder's versions.env -- and from versions.env otherwise.
PINS=CADDY_VERSION,CADDY_RATELIMIT_VERSION,XCADDY_VERSION,CADDY_GO_VERSION
if [ -z "${CADDY_VERSION:-}" ]; then set -a; . ./versions.env; set +a; fi

MODULE=github.com/mholt/caddy-ratelimit

build() {   # build <dir>
  local out="$1" work
  work="$(mktemp -d)"
  export GOTOOLCHAIN="go$CADDY_GO_VERSION" GOPATH="${GOPATH:-$work/gopath}"
  go install "github.com/caddyserver/xcaddy/cmd/xcaddy@v$XCADDY_VERSION"
  (cd "$work" && "$GOPATH/bin/xcaddy" build "v$CADDY_VERSION" --with "$MODULE@$CADDY_RATELIMIT_VERSION" \
     --output "$work/caddy")
  "$work/caddy" list-modules | grep -qx 'http.handlers.rate_limit' \
    || { echo "The built Caddy has no http.handlers.rate_limit" >&2; exit 1; }
  mkdir -p "$out"
  install -m 0755 "$work/caddy" "$out/caddy"
  # Go's module cache is read-only; make it removable first.
  chmod -R u+w "$work"; rm -rf "$work"
  echo "Caddy $CADDY_VERSION with $MODULE@$CADDY_RATELIMIT_VERSION built in $out"
}

if [ "${1:-}" = "--build-to" ]; then
  build "${2:?Usage: ./install-caddy.sh --build-to <dir>}"
  exit 0
fi

[ "$(id -u)" = 0 ] || exec sudo --preserve-env="$PINS" bash "$0" "$@"
CADDY_OPT=/opt/caddy
DIR="$CADDY_OPT/$CADDY_VERSION-$CADDY_RATELIMIT_VERSION"
if [ ! -x "$DIR/caddy" ]; then
  # Go's module and build caches live with the builds, not in root's home.
  export GOPATH="$CADDY_OPT/toolchain/gopath" GOCACHE="$CADDY_OPT/toolchain/cache"
  build "$DIR"
fi

if ! dpkg-divert --list /usr/bin/caddy | grep -q caddy.default; then
  dpkg-divert --divert /usr/bin/caddy.default --rename /usr/bin/caddy
  update-alternatives --install /usr/bin/caddy caddy /usr/bin/caddy.default 10
fi
update-alternatives --install /usr/bin/caddy caddy "$DIR/caddy" 50
update-alternatives --set caddy "$DIR/caddy"
caddy list-modules | grep -qx 'http.handlers.rate_limit' \
  || { echo "/usr/bin/caddy is not the build with rate limiting" >&2; exit 1; }

# Two builds are kept: the one in service and the one before, for a rollback.
for old in $(ls -1d "$CADDY_OPT"/[0-9]*/ 2>/dev/null | sort -V | head -n -2); do
  update-alternatives --remove caddy "${old%/}/caddy" || true
  rm -rf "$old"
done
echo "caddy: $(caddy version | head -1) with rate limiting"

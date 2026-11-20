#!/usr/bin/env bash
# Builds Authentik from source on this server (2026-11-20, ticket 10 of .scratch/authentik-cutover). The user chose a
# native install with no Docker; upstream ships Authentik only as a container image, so this follows that image's own
# recipe -- lifecycle/container/Dockerfile at the same tag -- step for step:
#
#   1. the web UI, with Node and pnpm                         -> web/dist
#   2. the server, a Rust binary (aws-lc's FIPS build needs cmake, clang and Go)   -> bin/authentik
#   3. the Python environment, with uv and the pinned Python  -> .venv
#
# Into /opt/authentik/<version>, ONCE per version: a release with an unchanged AUTHENTIK_VERSION skips straight to the
# end. The first build takes 30-60 minutes and a few GB of disk. The version only goes into service when every step
# succeeded (the .built marker); remote-release.sh then points /opt/authentik/current at it.
#
# The tools live under /opt/authentik/toolchain, apart from the system's, at the versions versions.env names.
#
# Usage: ./install-authentik.sh            (the AUTHENTIK_VERSION in versions.env)
#        ./install-authentik.sh <version>
set -euo pipefail
cd "$(dirname "$0")"
# As root, so everything it installs is root's and only readable by the authentik user that runs it.
[ "$(id -u)" = 0 ] || exec sudo bash "$0" "$@"
. ./platform-lib.sh

VERSION="${1:-$AUTHENTIK_VERSION}"
PREFIX="$AUTHENTIK_OPT/$VERSION"
if [ -f "$PREFIX/.built" ]; then
  echo "Authentik $VERSION is already built in $PREFIX"
  exit 0
fi

TOOLCHAIN="$AUTHENTIK_OPT/toolchain"
NODE_DIR="$TOOLCHAIN/node-$AUTHENTIK_NODE_VERSION"
UV_DIR="$TOOLCHAIN/uv-$AUTHENTIK_UV_VERSION"
BUILD="$AUTHENTIK_OPT/.build-$VERSION"

echo "== Authentik $VERSION: building from source (30-60 minutes the first time)"
mkdir -p "$TOOLCHAIN"

echo "-- tools"
if [ ! -x "$NODE_DIR/bin/node" ]; then
  curl -fsSL "https://nodejs.org/dist/v$AUTHENTIK_NODE_VERSION/node-v$AUTHENTIK_NODE_VERSION-linux-x64.tar.xz" \
    | tar xJ -C "$TOOLCHAIN"
  mv "$TOOLCHAIN/node-v$AUTHENTIK_NODE_VERSION-linux-x64" "$NODE_DIR"
fi
export PATH="$NODE_DIR/bin:$PATH"
[ "$(pnpm --version 2>/dev/null || true)" = "$AUTHENTIK_PNPM_VERSION" ] \
  || npm install -g --silent "pnpm@$AUTHENTIK_PNPM_VERSION"

if [ ! -x "$UV_DIR/uv" ]; then
  mkdir -p "$UV_DIR"
  curl -fsSL "https://github.com/astral-sh/uv/releases/download/$AUTHENTIK_UV_VERSION/uv-x86_64-unknown-linux-gnu.tar.gz" \
    | tar xz -C "$UV_DIR" --strip-components=1
fi
export PATH="$UV_DIR:$PATH"

export RUSTUP_HOME="$TOOLCHAIN/rustup" CARGO_HOME="$TOOLCHAIN/cargo"
export PATH="$CARGO_HOME/bin:$PATH"
if [ ! -x "$CARGO_HOME/bin/rustup" ]; then
  curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none --no-modify-path
fi

echo "-- source"
rm -rf "$BUILD"
git clone --quiet --depth 1 --branch "version/$VERSION" https://github.com/goauthentik/authentik.git "$BUILD"
cd "$BUILD"

echo "-- 1/3 the web UI"
(
  export NODE_ENV=production
  node ./scripts/node/lint-runtime.ts ./web
  cd web
  pnpm install --frozen-lockfile
  pnpm run build
  pnpm run build:sfe
)

echo "-- 2/3 the server binary"
(
  # The tag's own rust-toolchain.toml names the toolchain; rustup installs it on first use.
  rustup show active-toolchain > /dev/null 2>&1 || rustup install
  # See https://github.com/aws/aws-lc-rs/issues/569, as upstream does.
  export AWS_LC_FIPS_SYS_CC=clang
  cargo build --package authentik --no-default-features --features core --locked --release
  mkdir -p bin
  cp target/release/authentik bin/authentik
)

echo "-- 3/3 the Python environment"
(
  export UV_PYTHON_INSTALL_DIR="$TOOLCHAIN/python" UV_PROJECT_ENVIRONMENT="$BUILD/.venv" \
         UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_NATIVE_TLS=1 \
         UV_NO_BINARY_PACKAGE="cryptography lxml python-kadmin-rs xmlsec"
  uv python install "$AUTHENTIK_PYTHON_VERSION"
  uv sync --locked --no-install-project --no-dev --python "$AUTHENTIK_PYTHON_VERSION"
)

# Only what runs is kept: the build's caches and the Rust target directory are several GB.
rm -rf target web/node_modules node_modules website .git
touch .built

cd /
rm -rf "$PREFIX"
# The virtualenv names the Python it was built with by path ($TOOLCHAIN/python), so that must stay where uv put it.
mv "$BUILD" "$PREFIX"
chmod -R go+rX "$PREFIX" "$TOOLCHAIN/python"
echo "Authentik $VERSION built in $PREFIX"

# Two versions are kept: the one in service and the one before, for a rollback.
ls -1d "$AUTHENTIK_OPT"/20*/ 2>/dev/null | sort -V | head -n -2 | xargs -r rm -rf

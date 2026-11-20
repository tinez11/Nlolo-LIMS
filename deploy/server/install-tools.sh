#!/usr/bin/env bash
# Puts the pinned third-party programs in place (2026-11-20, ticket 10 of .scratch/authentik-cutover): Flyway (the
# migrations), WireMock (the mobile-money mock), SeaweedFS (the documents) and rclone (off-server backups), at the
# versions versions.env names. Each is skipped when that version is already there, so every release runs this and a
# version change in versions.env is all an upgrade takes.
set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || exec sudo bash "$0" "$@"
. ./platform-lib.sh

mkdir -p "$TOOLS"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$TOOLS/flyway-$FLYWAY_VERSION/flyway" ]; then
  echo "-- Flyway $FLYWAY_VERSION"
  curl -fsSL "https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/$FLYWAY_VERSION/flyway-commandline-$FLYWAY_VERSION-linux-x64.tar.gz" \
    | tar xz -C "$TOOLS"
fi
ln -sfn "$TOOLS/flyway-$FLYWAY_VERSION/flyway" /usr/local/bin/flyway

if [ ! -f "$TOOLS/wiremock-standalone-$WIREMOCK_VERSION.jar" ]; then
  echo "-- WireMock $WIREMOCK_VERSION"
  curl -fsSL -o "$TOOLS/wiremock-standalone-$WIREMOCK_VERSION.jar" \
    "https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/$WIREMOCK_VERSION/wiremock-standalone-$WIREMOCK_VERSION.jar"
fi
ln -sfn "$TOOLS/wiremock-standalone-$WIREMOCK_VERSION.jar" "$TOOLS/wiremock-standalone.jar"

if [ "$(/usr/local/bin/weed version 2>/dev/null | grep -o "$SEAWEEDFS_VERSION" | head -1)" != "$SEAWEEDFS_VERSION" ]; then
  echo "-- SeaweedFS $SEAWEEDFS_VERSION"
  curl -fsSL "https://github.com/seaweedfs/seaweedfs/releases/download/$SEAWEEDFS_VERSION/linux_amd64.tar.gz" \
    | tar xz -C "$tmp"
  install -m 0755 "$tmp/weed" /usr/local/bin/weed
fi

if [ "$(rclone version 2>/dev/null | head -1)" != "rclone v$RCLONE_VERSION" ]; then
  echo "-- rclone $RCLONE_VERSION"
  curl -fsSL -o "$tmp/rclone.zip" "https://downloads.rclone.org/v$RCLONE_VERSION/rclone-v$RCLONE_VERSION-linux-amd64.zip"
  unzip -q -o "$tmp/rclone.zip" -d "$tmp"
  install -m 0755 "$tmp/rclone-v$RCLONE_VERSION-linux-amd64/rclone" /usr/local/bin/rclone
fi
echo "tools: Flyway $FLYWAY_VERSION, WireMock $WIREMOCK_VERSION, SeaweedFS $SEAWEEDFS_VERSION, rclone $RCLONE_VERSION"

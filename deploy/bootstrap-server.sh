#!/usr/bin/env bash
# One-time setup of a fresh Debian 13 (trixie) server (2026-10-13, deployment pilot §8; native under systemd since
# 2026-11-20, ticket 10 of .scratch/authentik-cutover). Run once, as root:
#
#   scp deploy/bootstrap-server.sh deploy/server/.env.example root@SERVER:
#   ssh root@SERVER bash bootstrap-server.sh
#
# Leaves a server that can receive releases: the system packages (Postgres, Java, Caddy, and the libraries Authentik is
# built against), a user for each service, a deploy user with key-only SSH and sudo, a firewall open on 22/80/443 only,
# a filled .env with generated secrets, and the nightly backup in cron. Everything versioned beyond the system's own
# packages -- Authentik, Flyway, SeaweedFS, WireMock, rclone -- arrives with the first release (versions.env).
#
# Debian 13 because Authentik, built here from source, is built upstream on it and nowhere else.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
. /etc/os-release
[ "${VERSION_CODENAME:-}" = trixie ] || echo "WARNING: this is $PRETTY_NAME, not Debian 13 (trixie); Authentik's build is only known to work there" >&2

export DEBIAN_FRONTEND=noninteractive
POSTGRES_MAJOR=16

echo "== package sources"
apt-get update
apt-get install -y ca-certificates curl gnupg debian-keyring debian-archive-keyring apt-transport-https
install -m 0755 -d /etc/apt/keyrings
# PostgreSQL 16 with pg_partman and pg_cron, from the PostgreSQL project's own repository (Debian 13 ships 17; the
# platform is built and tested on 16).
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor --yes -o /etc/apt/keyrings/postgresql.gpg
echo "deb [signed-by=/etc/apt/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt trixie-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list
# Caddy, from its own repository.
curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/caddy.gpg
echo "deb [signed-by=/etc/apt/keyrings/caddy.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main" \
  > /etc/apt/sources.list.d/caddy.list
apt-get update

echo "== packages"
apt-get install -y --no-install-recommends \
  ufw unattended-upgrades openssl sudo git unzip xz-utils jq \
  "postgresql-$POSTGRES_MAJOR" "postgresql-$POSTGRES_MAJOR-partman" "postgresql-$POSTGRES_MAJOR-cron" \
  openjdk-21-jre-headless caddy \
  `# Authentik's build, as its upstream Dockerfile lists it` \
  build-essential pkg-config libffi-dev cmake clang golang \
  libxslt-dev zlib1g-dev libpq-dev krb5-multidev libkrb5-dev heimdal-multidev libclang-dev libltdl-dev \
  `# and what it needs at run time` \
  libpq5 libmaxminddb0 libkadm5clnt-mit12 libkadm5clnt7t64-heimdal libltdl7 libxslt1.1
dpkg-reconfigure -f noninteractive unattended-upgrades
# Postgres and Caddy start when their releases configure them, not before.
systemctl disable --now caddy || true

echo "== users"
for user in platform authentik seaweedfs platform-mock; do
  id "$user" > /dev/null 2>&1 || adduser --system --group --no-create-home --home /nonexistent "$user"
done
id deploy > /dev/null 2>&1 || adduser --disabled-password --gecos "" deploy
# The deploy user runs releases, which start services and read the databases: root's power, as membership of the
# docker group was before. Its login is a key only.
echo "deploy ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/deploy && chmod 440 /etc/sudoers.d/deploy
mkdir -p /home/deploy/.ssh
cp /root/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' \
  /etc/ssh/sshd_config
systemctl reload ssh

echo "== directories"
install -d -m 0755 /opt/platform /opt/platform/releases /opt/platform/tools /opt/authentik
install -d -m 0750 -o root -g root /etc/platform
install -d -m 0750 -o authentik -g authentik /var/lib/authentik /var/lib/authentik/data /var/lib/authentik/certs
install -d -m 0750 -o seaweedfs -g seaweedfs /var/lib/seaweedfs

echo "== firewall"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "== swap"
# Authentik's first build needs room: its Rust and web builds each want several GB at their peak.
if [ ! -f /swapfile ]; then
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== platform directory"
P=/home/deploy/platform
mkdir -p "$P/backups"
if [ ! -f "$P/.env" ]; then
  cp /root/.env.example "$P/.env"
  secret() { openssl rand -base64 36 | tr -d '/+=' | cut -c1-32; }
  for key in POSTGRES_PASSWORD APP_DB_PASSWORD AUTHENTIK_DB_PASSWORD AUTHENTIK_BOOTSTRAP_PASSWORD \
             AUTHENTIK_API_TOKEN SIGN_IN_CODE_WEBHOOK_SECRET PASSWORD_CHANGED_WEBHOOK_SECRET \
             OBJECT_STORE_SECRET_KEY MOBILE_MONEY_CALLBACK_HMAC_SECRET \
             BACKUP_CRYPT_PASSWORD; do
    sed -i "s|^$key=.*|$key=$(secret)|" "$P/.env"
  done
  # Longer than the rest: Authentik signs sessions with its key and asks for at least 50 characters, and the visit
  # secret needs at least 32, which the backend checks.
  long_secret() { openssl rand -base64 90 | tr -d '/+=\n' | cut -c1-64; }
  for key in AUTHENTIK_SECRET_KEY PUBLIC_VISIT_SECRET; do
    sed -i "s|^$key=.*|$key=$(long_secret)|" "$P/.env"
  done
  sed -i "s|^PLATFORM_TENANT_ID=.*|PLATFORM_TENANT_ID=$(cat /proc/sys/kernel/random/uuid)|" "$P/.env"
  chmod 600 "$P/.env"
  echo "   .env created with generated secrets"
else
  echo "   .env already exists, left alone"
fi
chown -R deploy:deploy "$P"

echo "== nightly backup"
( crontab -u deploy -l 2>/dev/null | grep -v backup.sh
  echo "30 2 * * * cd $P && ./backup.sh nightly >> backups/cron.log 2>&1" ) | crontab -u deploy -

cat <<'NEXT'

Done. Next, in order (DEPLOYMENT-GUIDE.md, Part 3, has the detail):
  1. Edit /home/deploy/platform/.env -- APP_DOMAIN, AUTH_DOMAIN, ACME_EMAIL, the SMS settings,
     SUPPORT_PHONE, FIRST_ADMIN_PHONE, ALERT_PHONES, TURNSTILE_SECRET, and the BACKUP_S3_* settings.
  2. Point DNS at this server: an A record for each of APP_DOMAIN and AUTH_DOMAIN.
     Caddy fetches the certificates itself, and cannot until those records resolve.
  3. From your machine:  deploy/release.sh v1.0.0
     The first release builds Authentik here (30-60 minutes), then prints the first staff
     login's one-time password once. Change it at first sign-in.
  4. Copy AUTHENTIK_BOOTSTRAP_PASSWORD and BACKUP_CRYPT_PASSWORD from .env somewhere safe,
     off this server.
NEXT
